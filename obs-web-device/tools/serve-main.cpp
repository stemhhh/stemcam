#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <winhttp.h>
#include <wincodec.h>

#include "jpeg-decode.hpp"
#include "net-server.hpp"

#include <atomic>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

namespace {

struct Sink : WebDeviceSink {
	std::atomic<int> links{0};
	std::atomic<bool> jpeg_ok{false};
	std::atomic<bool> pcm_ok{false};
	std::string name;
	std::mutex mu;

	void on_link(bool up, const std::string &peer_name) override
	{
		if (up)
			links.fetch_add(1);
		std::lock_guard<std::mutex> lock(mu);
		if (up && !peer_name.empty())
			name = peer_name;
	}
	void on_jpeg(const uint8_t *data, size_t size, uint32_t, uint32_t) override
	{
		std::vector<uint8_t> bgra;
		uint32_t w = 0, h = 0;
		if (jpeg_to_bgra(data, size, bgra, w, h) && w > 0 && h > 0)
			jpeg_ok.store(true);
	}
	void on_pcm(const int16_t *, uint32_t frames, uint32_t rate, uint32_t channels) override
	{
		if (frames > 0 && rate >= 8000 && channels >= 1)
			pcm_ok.store(true);
	}
};

void check(bool ok, const char *name, int &failed)
{
	printf("CHECK %s %s\n", name, ok ? "PASS" : "FAIL");
	if (!ok)
		++failed;
}

bool send_all(SOCKET sock, const void *data, int n)
{
	const char *p = (const char *)data;
	int off = 0;
	while (off < n) {
		int r = send(sock, p + off, n - off, 0);
		if (r <= 0)
			return false;
		off += r;
	}
	return true;
}

bool recv_headers(SOCKET sock, std::string &out)
{
	out.clear();
	while (out.size() < 8192) {
		char b = 0;
		int r = recv(sock, &b, 1, 0);
		if (r <= 0)
			return false;
		out.push_back(b);
		if (out.size() >= 4 && out.compare(out.size() - 4, 4, "\r\n\r\n") == 0)
			return true;
	}
	return false;
}

bool http_get(int port, const char *path, std::string &status_line, std::string &body_start)
{
	SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
	if (sock == INVALID_SOCKET)
		return false;
	DWORD timeout = 3000;
	setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout));
	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons((u_short)port);
	inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
	if (connect(sock, (sockaddr *)&addr, sizeof(addr)) != 0) {
		closesocket(sock);
		return false;
	}
	std::string req = std::string("GET ") + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
	bool ok = send_all(sock, req.data(), (int)req.size());
	std::string headers;
	ok = ok && recv_headers(sock, headers);
	body_start.clear();
	char buf[512];
	int r = recv(sock, buf, sizeof(buf), 0);
	if (r > 0)
		body_start.assign(buf, buf + r);
	closesocket(sock);
	auto end = headers.find("\r\n");
	status_line = end == std::string::npos ? headers : headers.substr(0, end);
	return ok;
}

bool https_get(int port, DWORD &status, std::string &body, DWORD &err)
{
	status = 0;
	err = 0;
	body.clear();
	HINTERNET ses = WinHttpOpen(L"obs-web-device-test", WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME,
				    WINHTTP_NO_PROXY_BYPASS, 0);
	if (!ses) {
		err = GetLastError();
		return false;
	}
	WinHttpSetTimeouts(ses, 3000, 3000, 3000, 3000);
	HINTERNET con = WinHttpConnect(ses, L"127.0.0.1", (INTERNET_PORT)port, 0);
	if (!con) {
		err = GetLastError();
		WinHttpCloseHandle(ses);
		return false;
	}
	HINTERNET req = WinHttpOpenRequest(con, L"GET", L"/", nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
					   WINHTTP_FLAG_SECURE);
	if (!req) {
		err = GetLastError();
		WinHttpCloseHandle(con);
		WinHttpCloseHandle(ses);
		return false;
	}
	DWORD flags = SECURITY_FLAG_IGNORE_UNKNOWN_CA | SECURITY_FLAG_IGNORE_CERT_DATE_INVALID |
		      SECURITY_FLAG_IGNORE_CERT_CN_INVALID | SECURITY_FLAG_IGNORE_CERT_WRONG_USAGE;
	WinHttpSetOption(req, WINHTTP_OPTION_SECURITY_FLAGS, &flags, sizeof(flags));
	BOOL sent = WinHttpSendRequest(req, WINHTTP_NO_ADDITIONAL_HEADERS, 0, nullptr, 0, 0, 0);
	if (sent)
		sent = WinHttpReceiveResponse(req, nullptr);
	if (!sent) {
		err = GetLastError();
		WinHttpCloseHandle(req);
		WinHttpCloseHandle(con);
		WinHttpCloseHandle(ses);
		return false;
	}
	DWORD slen = sizeof(status);
	WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX,
			    &status, &slen, WINHTTP_NO_HEADER_INDEX);
	for (;;) {
		DWORD avail = 0;
		if (!WinHttpQueryDataAvailable(req, &avail) || avail == 0)
			break;
		std::vector<char> buf(avail);
		DWORD read = 0;
		if (!WinHttpReadData(req, buf.data(), avail, &read) || read == 0)
			break;
		body.append(buf.data(), buf.data() + read);
		if (body.size() > 4096)
			break;
	}
	WinHttpCloseHandle(req);
	WinHttpCloseHandle(con);
	WinHttpCloseHandle(ses);
	return true;
}

bool encode_jpeg(std::vector<uint8_t> &out)
{
	out.clear();
	HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	bool com = (hr == S_OK || hr == S_FALSE);
	IWICImagingFactory *factory = nullptr;
	IStream *stream = nullptr;
	IWICBitmapEncoder *encoder = nullptr;
	IWICBitmapFrameEncode *frame = nullptr;
	IPropertyBag2 *props = nullptr;
	hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory));
	if (FAILED(hr))
		goto done;
	hr = CreateStreamOnHGlobal(nullptr, TRUE, &stream);
	if (FAILED(hr))
		goto done;
	hr = factory->CreateEncoder(GUID_ContainerFormatJpeg, nullptr, &encoder);
	if (FAILED(hr))
		goto done;
	hr = encoder->Initialize(stream, WICBitmapEncoderNoCache);
	if (FAILED(hr))
		goto done;
	hr = encoder->CreateNewFrame(&frame, &props);
	if (FAILED(hr))
		goto done;
	if (props) {
		PROPBAG2 opt{};
		opt.pstrName = const_cast<LPOLESTR>(L"ImageQuality");
		VARIANT val;
		VariantInit(&val);
		val.vt = VT_R4;
		val.fltVal = 0.9f;
		props->Write(1, &opt, &val);
		VariantClear(&val);
	}
	hr = frame->Initialize(props);
	if (FAILED(hr))
		goto done;
	hr = frame->SetSize(2, 2);
	if (FAILED(hr))
		goto done;
	{
		WICPixelFormatGUID fmt = GUID_WICPixelFormat24bppBGR;
		hr = frame->SetPixelFormat(&fmt);
		if (FAILED(hr))
			goto done;
		BYTE pixels[16] = {40, 180, 40, 40, 180, 40, 0, 0, 40, 180, 40, 40, 180, 40, 0, 0};
		UINT stride = 8;
		if (fmt == GUID_WICPixelFormat32bppBGRA || fmt == GUID_WICPixelFormat32bppBGR) {
			stride = 8;
		} else if (fmt != GUID_WICPixelFormat24bppBGR) {
			hr = E_FAIL;
			goto done;
		}
		hr = frame->WritePixels(2, stride, sizeof(pixels), pixels);
		if (FAILED(hr))
			goto done;
	}
	hr = frame->Commit();
	if (FAILED(hr))
		goto done;
	hr = encoder->Commit();
	if (FAILED(hr))
		goto done;
	{
		STATSTG stat{};
		if (FAILED(stream->Stat(&stat, STATFLAG_NONAME)))
			goto done;
		LARGE_INTEGER zero{};
		stream->Seek(zero, STREAM_SEEK_SET, nullptr);
		ULONG size = stat.cbSize.LowPart;
		if (size < 4 || size > 1024 * 1024)
			goto done;
		out.resize(size);
		ULONG read = 0;
		hr = stream->Read(out.data(), size, &read);
		if (FAILED(hr) || read == 0)
			out.clear();
		else
			out.resize(read);
	}
done:
	if (props)
		props->Release();
	if (frame)
		frame->Release();
	if (encoder)
		encoder->Release();
	if (stream)
		stream->Release();
	if (factory)
		factory->Release();
	if (com)
		CoUninitialize();
	return !out.empty() && out[0] == 0xFF && out[1] == 0xD8;
}

bool send_ws(SOCKET sock, uint8_t opcode, const uint8_t *data, size_t len)
{
	std::vector<uint8_t> frame;
	frame.push_back((uint8_t)(0x80 | opcode));
	uint8_t mask[4] = {0x11, 0x22, 0x33, 0x44};
	if (len < 126) {
		frame.push_back((uint8_t)(0x80 | len));
	} else {
		frame.push_back((uint8_t)(0x80 | 126));
		frame.push_back((uint8_t)((len >> 8) & 0xff));
		frame.push_back((uint8_t)(len & 0xff));
	}
	frame.insert(frame.end(), mask, mask + 4);
	for (size_t i = 0; i < len; ++i)
		frame.push_back((uint8_t)(data[i] ^ mask[i & 3]));
	return send_all(sock, frame.data(), (int)frame.size());
}

int run_selftest(const std::string &root, int port)
{
	if (!root.empty())
		web_device_server_set_root(root);
	Sink sink;
	web_device_server_register("TEST", "secret", &sink);
	web_device_server_ensure(port, true);
	WebDeviceInfo info = web_device_server_info();
	printf("server http=%d https=%d err=%s\n", info.http_ok ? 1 : 0, info.https_ok ? 1 : 0, info.error.c_str());
	Sleep(150);
	int failed = 0;
	check(info.http_ok, "http-listen", failed);
	check(info.https_ok, "https-listen", failed);

	std::string status, chunk;
	bool page = http_get(info.http_port, "/", status, chunk);
	check(page && status.find("200") != std::string::npos && chunk.find("NexoCam") != std::string::npos,
	      "http-page", failed);

	std::string cfg_status, cfg;
	bool config = http_get(info.http_port, "/config.js", cfg_status, cfg);
	check(config && cfg.find("httpPort") != std::string::npos, "config-js", failed);

	std::string cer_status, cer;
	bool cert = http_get(info.http_port, "/obs-web-device.cer", cer_status, cer);
	check(cert && !cer.empty() && (unsigned char)cer[0] == 0x30, "certificate", failed);

	if (info.https_ok) {
		DWORD https_status = 0, err = 0;
		std::string body;
		bool tls = https_get(info.https_port, https_status, body, err);
		if (!tls)
			printf("https error %lu\n", err);
		check(tls && https_status == 200 && body.find("NexoCam") != std::string::npos, "https-page", failed);
	}

	SOCKET ws = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
	DWORD timeout = 3000;
	setsockopt(ws, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout));
	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons((u_short)info.http_port);
	inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
	bool ws_ok = connect(ws, (sockaddr *)&addr, sizeof(addr)) == 0;
	const char *upgrade = "GET /ws?room=TEST HTTP/1.1\r\n"
			      "Host: 127.0.0.1\r\n"
			      "Upgrade: websocket\r\n"
			      "Connection: Upgrade\r\n"
			      "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
			      "Sec-WebSocket-Version: 13\r\n\r\n";
	std::string hs;
	ws_ok = ws_ok && send_all(ws, upgrade, (int)strlen(upgrade)) && recv_headers(ws, hs);
	check(ws_ok && hs.find("101") != std::string::npos && hs.find("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=") != std::string::npos,
	      "websocket", failed);

	const char *auth =
		"{\"type\":\"auth\",\"role\":\"client\",\"room\":\"TEST\",\"password\":\"secret\"}";
	ws_ok = ws_ok && send_ws(ws, 0x1, (const uint8_t *)auth, strlen(auth));
	const char *hello = "{\"type\":\"hello\",\"name\":\"selftest\"}";
	ws_ok = ws_ok && send_ws(ws, 0x1, (const uint8_t *)hello, strlen(hello));

	std::vector<uint8_t> jpeg;
	bool encoded = encode_jpeg(jpeg);
	check(encoded, "jpeg-encode", failed);
	if (encoded) {
		std::vector<uint8_t> msg(5 + jpeg.size());
		msg[0] = 1;
		msg[1] = 2;
		msg[2] = 0;
		msg[3] = 2;
		msg[4] = 0;
		memcpy(msg.data() + 5, jpeg.data(), jpeg.size());
		ws_ok = ws_ok && send_ws(ws, 0x2, msg.data(), msg.size());
	}
	int16_t pcm[48];
	for (int i = 0; i < 48; ++i)
		pcm[i] = (int16_t)(i * 200);
	uint8_t audio[9 + sizeof(pcm)];
	memset(audio, 0, sizeof(audio));
	audio[0] = 2;
	audio[1] = 0x80;
	audio[2] = 0xBB;
	audio[3] = 0;
	audio[4] = 0;
	audio[5] = 1;
	audio[6] = 0;
	audio[7] = 48;
	audio[8] = 0;
	memcpy(audio + 9, pcm, sizeof(pcm));
	ws_ok = ws_ok && send_ws(ws, 0x2, audio, sizeof(audio));

	for (int i = 0; i < 40 && (!sink.jpeg_ok.load() || !sink.pcm_ok.load() || sink.links.load() == 0); ++i)
		Sleep(50);
	check(sink.links.load() > 0, "link", failed);
	check(sink.jpeg_ok.load(), "jpeg-decode", failed);
	check(sink.pcm_ok.load(), "pcm", failed);
	{
		std::lock_guard<std::mutex> lock(sink.mu);
		check(sink.name == "selftest", "hello-name", failed);
	}
	closesocket(ws);
	web_device_server_stop();
	printf("%s\n", failed ? "SELFTEST FAIL" : "SELFTEST PASS");
	return failed ? 1 : 0;
}

} /* namespace */

int main(int argc, char **argv)
{
	std::string root;
	int port = 18787;
	bool selftest = false;
	bool lan = false;
	std::string room = "TEST";
	for (int i = 1; i < argc; ++i) {
		std::string arg = argv[i];
		if (arg == "--root" && i + 1 < argc)
			root = argv[++i];
		else if (arg == "--port" && i + 1 < argc)
			port = atoi(argv[++i]);
		else if (arg == "--room" && i + 1 < argc)
			room = argv[++i];
		else if (arg == "--selftest")
			selftest = true;
		else if (arg == "--lan")
			lan = true;
	}

	WSADATA wsa{};
	WSAStartup(MAKEWORD(2, 2), &wsa);
	if (selftest) {
		int code = run_selftest(root, port);
		WSACleanup();
		return code;
	}

	if (!root.empty())
		web_device_server_set_root(root);
	Sink sink;
	web_device_server_register(room, "secret", &sink);
	web_device_server_ensure(port, !lan);
	WebDeviceInfo info = web_device_server_info();
	printf("http=%d https=%d port=%d err=%s\n", info.http_ok ? 1 : 0, info.https_ok ? 1 : 0, info.http_port,
	       info.error.c_str());
	printf("room %s — press Ctrl+C to stop\n", room.c_str());
	for (;;)
		Sleep(1000);
}
