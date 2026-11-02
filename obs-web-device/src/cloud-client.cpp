#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <winhttp.h>

#include "cloud-client.hpp"

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#ifndef WEB_DEVICE_STANDALONE
#include <obs-module.h>
#else
#define LOG_INFO 3
#define LOG_WARNING 2
#define LOG_ERROR 1
static void blog(int, const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vprintf(fmt, ap);
	printf("\n");
	va_end(ap);
}
#endif

namespace {

std::wstring utf8_wide(const std::string &s)
{
	if (s.empty())
		return {};
	int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
	if (n <= 1)
		return {};
	std::wstring w((size_t)n - 1, L'\0');
	MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, w.data(), n);
	return w;
}

struct UrlParts {
	bool https = true;
	std::wstring host;
	INTERNET_PORT port = 443;
	std::wstring path;
};

bool parse_ws_url(const std::string &url, UrlParts &out)
{
	out = {};
	std::string u = url;
	if (u.rfind("wss://", 0) == 0) {
		out.https = true;
		u = u.substr(6);
		out.port = 443;
	} else if (u.rfind("ws://", 0) == 0) {
		out.https = false;
		u = u.substr(5);
		out.port = 80;
	} else {
		return false;
	}
	auto slash = u.find('/');
	std::string hostport = slash == std::string::npos ? u : u.substr(0, slash);
	std::string path = slash == std::string::npos ? "/" : u.substr(slash);
	auto colon = hostport.find(':');
	if (colon != std::string::npos) {
		out.host = utf8_wide(hostport.substr(0, colon));
		out.port = (INTERNET_PORT)atoi(hostport.c_str() + colon + 1);
	} else {
		out.host = utf8_wide(hostport);
	}
	out.path = utf8_wide(path);
	return !out.host.empty() && !out.path.empty();
}

uint16_t rd_u16(const uint8_t *p)
{
	return (uint16_t)(p[0] | (p[1] << 8));
}

uint32_t rd_u32(const uint8_t *p)
{
	return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

std::string json_string_field(const std::string &json, const char *key)
{
	std::string pattern = std::string("\"") + key + "\"";
	auto p = json.find(pattern);
	if (p == std::string::npos)
		return {};
	p = json.find(':', p + pattern.size());
	if (p == std::string::npos)
		return {};
	p = json.find('"', p + 1);
	if (p == std::string::npos)
		return {};
	std::string out;
	for (size_t i = p + 1; i < json.size() && out.size() < 120; ++i) {
		unsigned char c = (unsigned char)json[i];
		if (c == '\\' && i + 1 < json.size()) {
			out.push_back(json[++i]);
			continue;
		}
		if (c == '"')
			break;
		if (c >= 32)
			out.push_back((char)c);
	}
	return out;
}

bool json_bool_field(const std::string &json, const char *key, bool fallback)
{
	std::string pattern = std::string("\"") + key + "\"";
	auto p = json.find(pattern);
	if (p == std::string::npos)
		return fallback;
	p = json.find(':', p + pattern.size());
	if (p == std::string::npos)
		return fallback;
	auto t = json.find_first_not_of(" \t", p + 1);
	if (t == std::string::npos)
		return fallback;
	if (json.compare(t, 4, "true") == 0)
		return true;
	if (json.compare(t, 5, "false") == 0)
		return false;
	return fallback;
}

void handle_binary(WebDeviceSink *sink, const uint8_t *msg, size_t size)
{
	if (!sink || !msg || size == 0)
		return;
	if (msg[0] == 1) {
		if (size < 5)
			return;
		uint32_t w = rd_u16(msg + 1);
		uint32_t h = rd_u16(msg + 3);
		sink->on_jpeg(msg + 5, size - 5, w, h);
		return;
	}
	if (msg[0] == 2) {
		if (size < 9)
			return;
		uint32_t rate = rd_u32(msg + 1);
		uint32_t channels = rd_u16(msg + 5);
		uint32_t frames = rd_u16(msg + 7);
		if (channels < 1 || channels > 2 || frames == 0 || frames > 48000)
			return;
		if (rate < 8000 || rate > 192000)
			return;
		size_t nbytes = (size_t)frames * channels * 2u;
		if (size < 9 + nbytes)
			return;
		sink->on_pcm(reinterpret_cast<const int16_t *>(msg + 9), frames, rate, channels);
	}
}

} /* namespace */

CloudBridge::CloudBridge() = default;

CloudBridge::~CloudBridge()
{
	stop();
}

void CloudBridge::set_status(const std::string &s)
{
	std::lock_guard<std::mutex> lock(mu_);
	status_ = s;
}

std::string CloudBridge::status() const
{
	std::lock_guard<std::mutex> lock(mu_);
	return status_;
}

void CloudBridge::stop()
{
	stop_.store(true);
	if (thr_.joinable())
		thr_.join();
	linked_.store(false);
}

void CloudBridge::start(const std::string &ws_url, const std::string &room, const std::string &password,
			WebDeviceSink *sink)
{
	stop();
	ws_url_ = ws_url;
	room_ = room;
	password_ = password;
	sink_ = sink;
	stop_.store(false);
	linked_.store(false);
	set_status("Connecting to NexoCam…");
	thr_ = std::thread([this] { run(); });
}

void CloudBridge::run()
{
	UrlParts parts;
	if (!parse_ws_url(ws_url_, parts)) {
		set_status("Invalid Worker URL");
		blog(LOG_WARNING, "[nexocam] bad ws url");
		return;
	}

	std::wstring path = parts.path;
	if (path.find(L'?') == std::wstring::npos)
		path += L"?room=" + utf8_wide(room_) + L"&role=plugin";
	else
		path += L"&room=" + utf8_wide(room_) + L"&role=plugin";

	while (!stop_.load()) {
		linked_.store(false);
		HINTERNET session = WinHttpOpen(L"NexoCam/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
						WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
		if (!session) {
			set_status("WinHTTP open failed");
			Sleep(2000);
			continue;
		}
		WinHttpSetTimeouts(session, 10000, 10000, 15000, 15000);
		HINTERNET connect = WinHttpConnect(session, parts.host.c_str(), parts.port, 0);
		if (!connect) {
			WinHttpCloseHandle(session);
			set_status("Connect failed");
			Sleep(2000);
			continue;
		}

		DWORD flags = parts.https ? WINHTTP_FLAG_SECURE : 0;
		HINTERNET request = WinHttpOpenRequest(connect, L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER,
						       WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
		if (!request) {
			WinHttpCloseHandle(connect);
			WinHttpCloseHandle(session);
			Sleep(2000);
			continue;
		}

		if (!WinHttpSetOption(request, WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET, nullptr, 0)) {
			WinHttpCloseHandle(request);
			WinHttpCloseHandle(connect);
			WinHttpCloseHandle(session);
			set_status("WebSocket upgrade unsupported");
			Sleep(3000);
			continue;
		}

		if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, nullptr, 0, 0, 0) ||
		    !WinHttpReceiveResponse(request, nullptr)) {
			WinHttpCloseHandle(request);
			WinHttpCloseHandle(connect);
			WinHttpCloseHandle(session);
			set_status("Worker unreachable — retrying");
			Sleep(2500);
			continue;
		}

		HINTERNET ws = WinHttpWebSocketCompleteUpgrade(request, 0);
		WinHttpCloseHandle(request);
		request = nullptr;
		if (!ws) {
			WinHttpCloseHandle(connect);
			WinHttpCloseHandle(session);
			set_status("WebSocket upgrade failed");
			Sleep(2500);
			continue;
		}

		std::string auth = std::string("{\"type\":\"auth\",\"role\":\"plugin\",\"room\":\"") + room_ +
				   "\",\"password\":\"";
		for (char c : password_) {
			if (c == '\\' || c == '"')
				auth.push_back('\\');
			auth.push_back(c);
		}
		auth += "\"}";

		if (WinHttpWebSocketSend(ws, WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE, (PVOID)auth.data(),
					 (DWORD)auth.size()) != NO_ERROR) {
			WinHttpWebSocketClose(ws, WINHTTP_WEB_SOCKET_SUCCESS_CLOSE_STATUS, nullptr, 0);
			WinHttpCloseHandle(ws);
			WinHttpCloseHandle(connect);
			WinHttpCloseHandle(session);
			Sleep(2000);
			continue;
		}

		set_status("Waiting for device…");
		bool welcomed = false;
		std::vector<uint8_t> buf(256 * 1024);

		while (!stop_.load()) {
			DWORD read = 0;
			WINHTTP_WEB_SOCKET_BUFFER_TYPE type;
			DWORD err = WinHttpWebSocketReceive(ws, buf.data(), (DWORD)buf.size(), &read, &type);
			if (err != NO_ERROR)
				break;
			if (type == WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE)
				break;

			if (type == WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE ||
			    type == WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE) {
				std::string text((char *)buf.data(), (char *)buf.data() + read);
				std::string msg_type = json_string_field(text, "type");
				if (msg_type == "welcome") {
					welcomed = true;
					set_status("Cloud connected — waiting for device");
				} else if (msg_type == "error") {
					set_status("Cloud auth failed");
					blog(LOG_WARNING, "[nexocam] cloud auth error");
					break;
				} else if (msg_type == "peer") {
					bool up = json_bool_field(text, "linked", false);
					std::string name = json_string_field(text, "name");
					linked_.store(up);
					if (sink_)
						sink_->on_link(up, name);
					if (up)
						set_status(name.empty() ? "Device connected" : name);
					else
						set_status("Waiting for device…");
				}
				continue;
			}

			if (type == WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE ||
			    type == WINHTTP_WEB_SOCKET_BINARY_FRAGMENT_BUFFER_TYPE) {
				if (welcomed && sink_)
					handle_binary(sink_, buf.data(), read);
			}
		}

		if (linked_.load() && sink_)
			sink_->on_link(false, "");
		linked_.store(false);
		WinHttpWebSocketClose(ws, WINHTTP_WEB_SOCKET_SUCCESS_CLOSE_STATUS, nullptr, 0);
		WinHttpCloseHandle(ws);
		WinHttpCloseHandle(connect);
		WinHttpCloseHandle(session);
		if (!stop_.load()) {
			set_status("Reconnecting to NexoCam…");
			Sleep(1500);
		}
	}
}
