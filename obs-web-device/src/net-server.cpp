#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <mstcpip.h>
#include <windows.h>
#define SECURITY_WIN32
#include <security.h>
#include <schannel.h>
#include <wincrypt.h>
#include <iphlpapi.h>
#include <bcrypt.h>

#ifndef SP_PROT_TLS1_2_SERVER
#define SP_PROT_TLS1_2_SERVER 0x00000400
#endif

#pragma warning(disable : 4996)

#include "net-server.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#ifndef WEB_DEVICE_STANDALONE
#include <obs-module.h>
#else
#define LOG_INFO 3
#define LOG_WARNING 2
#define LOG_ERROR 1
#endif

namespace {

void wd_log(int level, const char *fmt, ...)
{
	char buf[1400];
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(buf, sizeof(buf), fmt, ap);
	va_end(ap);
#ifdef WEB_DEVICE_STANDALONE
	(void)level;
	printf("[obs-web-device] %s\n", buf);
	fflush(stdout);
#else
	blog(level, "[obs-web-device] %s", buf);
#endif
}

std::wstring utf8_to_wide(const std::string &s)
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

std::string read_file_w(const std::wstring &path)
{
	FILE *f = nullptr;
	if (_wfopen_s(&f, path.c_str(), L"rb") != 0 || !f)
		return {};
	std::string data;
	char tmp[8192];
	size_t n = 0;
	while ((n = fread(tmp, 1, sizeof(tmp), f)) > 0)
		data.append(tmp, n);
	fclose(f);
	return data;
}

std::wstring dll_directory()
{
	HMODULE mod = nullptr;
	if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
				reinterpret_cast<LPCWSTR>(&dll_directory), &mod))
		return {};
	wchar_t path[MAX_PATH * 4];
	DWORD n = GetModuleFileNameW(mod, path, (DWORD)(sizeof(path) / sizeof(path[0])));
	if (!n)
		return {};
	std::wstring s(path, n);
	auto slash = s.find_last_of(L"\\/");
	if (slash == std::wstring::npos)
		return {};
	return s.substr(0, slash);
}

std::string lower_copy(std::string s)
{
	for (char &c : s) {
		if (c >= 'A' && c <= 'Z')
			c = (char)(c - 'A' + 'a');
	}
	return s;
}

std::string trim_copy(const std::string &s)
{
	size_t a = 0;
	while (a < s.size() && (s[a] == ' ' || s[a] == '\t'))
		++a;
	size_t b = s.size();
	while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r'))
		--b;
	return s.substr(a, b - a);
}

/* RFC6455 example: key dGhlIHNhbXBsZSBub25jZQ== -> s3pPLMBiTxaQ9kYGzzhZRbK+xOo= */
struct Sha1 {
	uint32_t h[5] = {0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};
	uint8_t block[64]{};
	uint64_t bits = 0;
	size_t n = 0;

	static uint32_t rol(uint32_t v, int s) { return (v << s) | (v >> (32 - s)); }

	void process(const uint8_t *p)
	{
		uint32_t w[80];
		for (int i = 0; i < 16; ++i) {
			w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
			       ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
		}
		for (int i = 16; i < 80; ++i)
			w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
		uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
		for (int i = 0; i < 80; ++i) {
			uint32_t f, k;
			if (i < 20) {
				f = (b & c) | (~b & d);
				k = 0x5A827999u;
			} else if (i < 40) {
				f = b ^ c ^ d;
				k = 0x6ED9EBA1u;
			} else if (i < 60) {
				f = (b & c) | (b & d) | (c & d);
				k = 0x8F1BBCDCu;
			} else {
				f = b ^ c ^ d;
				k = 0xCA62C1D6u;
			}
			uint32_t t = rol(a, 5) + f + e + k + w[i];
			e = d;
			d = c;
			c = rol(b, 30);
			b = a;
			a = t;
		}
		h[0] += a;
		h[1] += b;
		h[2] += c;
		h[3] += d;
		h[4] += e;
	}

	void update(const uint8_t *data, size_t len)
	{
		bits += (uint64_t)len * 8ull;
		while (len) {
			size_t take = 64 - n;
			if (take > len)
				take = len;
			memcpy(block + n, data, take);
			n += take;
			data += take;
			len -= take;
			if (n == 64) {
				process(block);
				n = 0;
			}
		}
	}

	void update(const std::string &s) { update(reinterpret_cast<const uint8_t *>(s.data()), s.size()); }

	void final(uint8_t out[20])
	{
		block[n++] = 0x80;
		if (n > 56) {
			while (n < 64)
				block[n++] = 0;
			process(block);
			n = 0;
		}
		while (n < 56)
			block[n++] = 0;
		for (int i = 7; i >= 0; --i)
			block[n++] = (uint8_t)((bits >> (i * 8)) & 0xff);
		process(block);
		for (int i = 0; i < 5; ++i) {
			out[i * 4] = (uint8_t)(h[i] >> 24);
			out[i * 4 + 1] = (uint8_t)(h[i] >> 16);
			out[i * 4 + 2] = (uint8_t)(h[i] >> 8);
			out[i * 4 + 3] = (uint8_t)h[i];
		}
	}
};

std::string b64_encode(const uint8_t *data, size_t n)
{
	static const char *t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	std::string o;
	o.reserve(((n + 2) / 3) * 4);
	for (size_t i = 0; i < n; i += 3) {
		uint32_t v = (uint32_t)data[i] << 16;
		if (i + 1 < n)
			v |= (uint32_t)data[i + 1] << 8;
		if (i + 2 < n)
			v |= (uint32_t)data[i + 2];
		o.push_back(t[(v >> 18) & 63]);
		o.push_back(t[(v >> 12) & 63]);
		o.push_back(i + 1 < n ? t[(v >> 6) & 63] : '=');
		o.push_back(i + 2 < n ? t[v & 63] : '=');
	}
	return o;
}

bool websocket_self_test()
{
	Sha1 sha;
	sha.update("dGhlIHNhbXBsZSBub25jZQ==258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
	uint8_t dig[20];
	sha.final(dig);
	return b64_encode(dig, 20) == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
}

std::string ws_accept_key(const std::string &client_key)
{
	Sha1 sha;
	sha.update(client_key);
	sha.update("258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
	uint8_t dig[20];
	sha.final(dig);
	return b64_encode(dig, 20);
}

bool send_all(SOCKET sock, const void *data, int n)
{
	const char *p = (const char *)data;
	int off = 0;
	while (off < n) {
		int r = ::send(sock, p + off, n - off, 0);
		if (r <= 0)
			return false;
		off += r;
	}
	return true;
}

class ByteStream {
public:
	virtual ~ByteStream() = default;
	virtual int read(uint8_t *dst, int n) = 0;
	virtual int write(const uint8_t *src, int n) = 0;
};

class PlainStream : public ByteStream {
public:
	explicit PlainStream(SOCKET sock) : sock(sock) {}
	int read(uint8_t *dst, int n) override
	{
		int r = ::recv(sock, (char *)dst, n, 0);
		if (r == 0)
			return 0;
		if (r < 0)
			return -1;
		return r;
	}
	int write(const uint8_t *src, int n) override { return send_all(sock, src, n) ? n : -1; }
	SOCKET sock;
};

class TlsConn : public ByteStream {
public:
	TlsConn(SOCKET sock, CredHandle *cred) : sock(sock), cred(cred) {}
	~TlsConn() override
	{
		if (have_ctx) {
			DeleteSecurityContext(&ctx);
			have_ctx = false;
		}
	}

	bool handshake()
	{
		std::vector<uint8_t> buf(32 * 1024);
		int used = 0;
		SECURITY_STATUS ss = SEC_I_CONTINUE_NEEDED;
		while (true) {
			if (used == 0 || ss == SEC_E_INCOMPLETE_MESSAGE) {
				if ((int)buf.size() - used < 4096)
					buf.resize(buf.size() * 2);
				int r = ::recv(sock, (char *)buf.data() + used, (int)buf.size() - used, 0);
				if (r <= 0)
					return false;
				used += r;
			}

			SecBuffer inb[2];
			inb[0].BufferType = SECBUFFER_TOKEN;
			inb[0].pvBuffer = buf.data();
			inb[0].cbBuffer = (ULONG)used;
			inb[1].BufferType = SECBUFFER_EMPTY;
			inb[1].pvBuffer = nullptr;
			inb[1].cbBuffer = 0;
			SecBufferDesc in_desc{SECBUFFER_VERSION, 2, inb};

			SecBuffer outb[1];
			outb[0].BufferType = SECBUFFER_TOKEN;
			outb[0].pvBuffer = nullptr;
			outb[0].cbBuffer = 0;
			SecBufferDesc out_desc{SECBUFFER_VERSION, 1, outb};

			DWORD flags = ASC_REQ_SEQUENCE_DETECT | ASC_REQ_REPLAY_DETECT | ASC_REQ_CONFIDENTIALITY |
				      ASC_REQ_EXTENDED_ERROR | ASC_REQ_ALLOCATE_MEMORY | ASC_REQ_STREAM;
			DWORD out_flags = 0;
			bool had = have_ctx;
			ss = AcceptSecurityContext(cred, had ? &ctx : nullptr, &in_desc, flags, SECURITY_NATIVE_DREP,
						   &ctx, &out_desc, &out_flags, nullptr);
			if (ss == SEC_E_OK || ss == SEC_I_CONTINUE_NEEDED || ss == SEC_I_COMPLETE_NEEDED ||
			    ss == SEC_I_COMPLETE_AND_CONTINUE || (ss == SEC_E_INCOMPLETE_MESSAGE && had))
				have_ctx = true;

			if (ss == SEC_I_COMPLETE_NEEDED || ss == SEC_I_COMPLETE_AND_CONTINUE) {
				CompleteAuthToken(&ctx, &out_desc);
				ss = (ss == SEC_I_COMPLETE_NEEDED) ? SEC_E_OK : SEC_I_CONTINUE_NEEDED;
			}

			if (outb[0].cbBuffer && outb[0].pvBuffer) {
				bool sent = send_all(sock, outb[0].pvBuffer, (int)outb[0].cbBuffer);
				FreeContextBuffer(outb[0].pvBuffer);
				if (!sent)
					return false;
			}

			if (ss == SEC_E_INCOMPLETE_MESSAGE)
				continue;

			if (ss != SEC_E_OK && ss != SEC_I_CONTINUE_NEEDED)
				return false;

			if (inb[1].BufferType == SECBUFFER_EXTRA && inb[1].cbBuffer > 0 && inb[1].pvBuffer) {
				int extra = (int)inb[1].cbBuffer;
				if (extra > (int)buf.size())
					return false;
				memmove(buf.data(), inb[1].pvBuffer, (size_t)extra);
				used = extra;
			} else {
				used = 0;
			}
			if (ss == SEC_E_OK)
				break;
		}

		if (QueryContextAttributes(&ctx, SECPKG_ATTR_STREAM_SIZES, &sizes) != SEC_E_OK)
			return false;
		if (sizes.cbMaximumMessage == 0)
			return false;
		if (used > 0)
			enc.assign(buf.begin(), buf.begin() + used);
		return true;
	}

	int read(uint8_t *dst, int n) override
	{
		if (n <= 0)
			return 0;
		if (plain_off >= plain.size()) {
			if (!pull_plain())
				return closed_clean ? 0 : -1;
		}
		int have = (int)(plain.size() - plain_off);
		int take = n < have ? n : have;
		memcpy(dst, plain.data() + plain_off, (size_t)take);
		plain_off += (size_t)take;
		return take;
	}

	int write(const uint8_t *src, int n) override
	{
		int off = 0;
		while (off < n) {
			int chunk = n - off;
			if ((unsigned)chunk > sizes.cbMaximumMessage)
				chunk = (int)sizes.cbMaximumMessage;
			if (!write_record(src + off, chunk))
				return -1;
			off += chunk;
		}
		return n;
	}

private:
	bool read_socket()
	{
		uint8_t tmp[8192];
		int r = ::recv(sock, (char *)tmp, (int)sizeof(tmp), 0);
		if (r == 0) {
			closed_clean = true;
			return false;
		}
		if (r < 0)
			return false;
		enc.insert(enc.end(), tmp, tmp + r);
		return true;
	}

	bool pull_plain()
	{
		plain.clear();
		plain_off = 0;
		while (plain.empty()) {
			if (enc.size() > 256u * 1024u)
				return false;
			if (enc.empty()) {
				if (!read_socket())
					return false;
				continue;
			}
			SecBuffer bufs[4];
			for (int i = 0; i < 4; ++i) {
				bufs[i].BufferType = SECBUFFER_EMPTY;
				bufs[i].cbBuffer = 0;
				bufs[i].pvBuffer = nullptr;
			}
			bufs[0].BufferType = SECBUFFER_DATA;
			bufs[0].cbBuffer = (ULONG)enc.size();
			bufs[0].pvBuffer = enc.data();
			SecBufferDesc desc{SECBUFFER_VERSION, 4, bufs};
			SECURITY_STATUS st = DecryptMessage(&ctx, &desc, 0, nullptr);
			if (st == SEC_E_INCOMPLETE_MESSAGE) {
				if (!read_socket())
					return false;
				continue;
			}
			if (st == SEC_I_CONTEXT_EXPIRED) {
				closed_clean = true;
				return false;
			}
			if (st != SEC_E_OK)
				return false;

			std::vector<uint8_t> data;
			std::vector<uint8_t> extra;
			for (int i = 0; i < 4; ++i) {
				if (!bufs[i].pvBuffer || !bufs[i].cbBuffer)
					continue;
				auto *p = (uint8_t *)bufs[i].pvBuffer;
				if (bufs[i].BufferType == SECBUFFER_DATA)
					data.insert(data.end(), p, p + bufs[i].cbBuffer);
				else if (bufs[i].BufferType == SECBUFFER_EXTRA)
					extra.assign(p, p + bufs[i].cbBuffer);
			}
			enc.swap(extra);
			if (data.empty())
				continue;
			plain.swap(data);
			plain_off = 0;
		}
		return true;
	}

	bool write_record(const uint8_t *src, int n)
	{
		std::vector<uint8_t> buf((size_t)sizes.cbHeader + (size_t)n + (size_t)sizes.cbTrailer);
		memcpy(buf.data() + sizes.cbHeader, src, (size_t)n);
		SecBuffer bufs[4];
		bufs[0].BufferType = SECBUFFER_STREAM_HEADER;
		bufs[0].cbBuffer = sizes.cbHeader;
		bufs[0].pvBuffer = buf.data();
		bufs[1].BufferType = SECBUFFER_DATA;
		bufs[1].cbBuffer = (ULONG)n;
		bufs[1].pvBuffer = buf.data() + sizes.cbHeader;
		bufs[2].BufferType = SECBUFFER_STREAM_TRAILER;
		bufs[2].cbBuffer = sizes.cbTrailer;
		bufs[2].pvBuffer = buf.data() + sizes.cbHeader + n;
		bufs[3].BufferType = SECBUFFER_EMPTY;
		bufs[3].cbBuffer = 0;
		bufs[3].pvBuffer = nullptr;
		SecBufferDesc desc{SECBUFFER_VERSION, 4, bufs};
		if (EncryptMessage(&ctx, 0, &desc, 0) != SEC_E_OK)
			return false;
		if (bufs[0].cbBuffer && !send_all(sock, bufs[0].pvBuffer, (int)bufs[0].cbBuffer))
			return false;
		if (bufs[1].cbBuffer && !send_all(sock, bufs[1].pvBuffer, (int)bufs[1].cbBuffer))
			return false;
		if (bufs[2].cbBuffer && !send_all(sock, bufs[2].pvBuffer, (int)bufs[2].cbBuffer))
			return false;
		return true;
	}

	SOCKET sock;
	CredHandle *cred = nullptr;
	CtxtHandle ctx{};
	bool have_ctx = false;
	SecPkgContext_StreamSizes sizes{};
	std::vector<uint8_t> enc;
	std::vector<uint8_t> plain;
	size_t plain_off = 0;
	bool closed_clean = false;
};

bool read_exact(ByteStream &s, uint8_t *dst, int n)
{
	int off = 0;
	while (off < n) {
		int r = s.read(dst + off, n - off);
		if (r <= 0)
			return false;
		off += r;
	}
	return true;
}

bool read_headers(ByteStream &s, std::string &out)
{
	out.clear();
	while (out.size() < 16384) {
		uint8_t b = 0;
		int r = s.read(&b, 1);
		if (r <= 0)
			return false;
		out.push_back((char)b);
		if (out.size() >= 4 && out.compare(out.size() - 4, 4, "\r\n\r\n") == 0)
			return true;
	}
	return false;
}

struct HttpRequest {
	std::string method;
	std::string path;
	std::string query;
	std::map<std::string, std::string> headers;
};

bool parse_request(const std::string &raw, HttpRequest &req)
{
	auto line_end = raw.find("\r\n");
	if (line_end == std::string::npos)
		return false;
	std::string line = raw.substr(0, line_end);
	size_t sp1 = line.find(' ');
	size_t sp2 = line.find(' ', sp1 == std::string::npos ? 0 : sp1 + 1);
	if (sp1 == std::string::npos || sp2 == std::string::npos)
		return false;
	req.method = line.substr(0, sp1);
	std::string target = line.substr(sp1 + 1, sp2 - sp1 - 1);
	auto q = target.find('?');
	if (q == std::string::npos) {
		req.path = target;
	} else {
		req.path = target.substr(0, q);
		req.query = target.substr(q + 1);
	}
	size_t pos = line_end + 2;
	while (pos < raw.size()) {
		auto next = raw.find("\r\n", pos);
		if (next == std::string::npos || next == pos)
			break;
		std::string row = raw.substr(pos, next - pos);
		auto colon = row.find(':');
		if (colon != std::string::npos) {
			req.headers[lower_copy(trim_copy(row.substr(0, colon)))] = trim_copy(row.substr(colon + 1));
		}
		pos = next + 2;
	}
	return true;
}

std::string header_get(const HttpRequest &req, const char *name)
{
	auto it = req.headers.find(name);
	if (it == req.headers.end())
		return {};
	return it->second;
}

bool header_has_token(const std::string &value, const char *token)
{
	std::string v = lower_copy(value);
	std::string t = token;
	return v.find(t) != std::string::npos;
}

std::string query_param(const std::string &query, const std::string &key)
{
	size_t i = 0;
	while (i < query.size()) {
		size_t amp = query.find('&', i);
		if (amp == std::string::npos)
			amp = query.size();
		std::string part = query.substr(i, amp - i);
		auto eq = part.find('=');
		std::string k = eq == std::string::npos ? part : part.substr(0, eq);
		std::string v = eq == std::string::npos ? std::string() : part.substr(eq + 1);
		if (k == key)
			return v;
		i = amp + 1;
	}
	return {};
}

bool write_bytes(ByteStream &s, const std::string &bytes)
{
	const uint8_t *p = reinterpret_cast<const uint8_t *>(bytes.data());
	size_t off = 0;
	while (off < bytes.size()) {
		size_t chunk = bytes.size() - off;
		if (chunk > 16384)
			chunk = 16384;
		int w = s.write(p + off, (int)chunk);
		if (w <= 0)
			return false;
		off += (size_t)w;
	}
	return true;
}

bool http_response(ByteStream &s, int code, const char *reason, const char *content_type, const std::string &body,
		   const char *extra_headers)
{
	std::string hdr = "HTTP/1.1 " + std::to_string(code) + " " + reason + "\r\n";
	if (content_type && content_type[0])
		hdr += "Content-Type: " + std::string(content_type) + "\r\n";
	hdr += "Content-Length: " + std::to_string(body.size()) + "\r\n";
	hdr += "Cache-Control: no-store\r\n";
	hdr += "Connection: close\r\n";
	if (extra_headers && extra_headers[0])
		hdr += extra_headers;
	hdr += "\r\n";
	return write_bytes(s, hdr) && write_bytes(s, body);
}

bool ws_send(ByteStream &s, uint8_t opcode, const uint8_t *data, size_t len)
{
	uint8_t hdr[10];
	size_t hlen = 2;
	hdr[0] = (uint8_t)(0x80 | opcode);
	if (len < 126) {
		hdr[1] = (uint8_t)len;
	} else if (len <= 65535) {
		hdr[1] = 126;
		hdr[2] = (uint8_t)((len >> 8) & 0xff);
		hdr[3] = (uint8_t)(len & 0xff);
		hlen = 4;
	} else {
		hdr[1] = 127;
		uint64_t L = (uint64_t)len;
		for (int i = 0; i < 8; ++i)
			hdr[2 + i] = (uint8_t)((L >> ((7 - i) * 8)) & 0xff);
		hlen = 10;
	}
	if (s.write(hdr, (int)hlen) != (int)hlen)
		return false;
	size_t off = 0;
	while (off < len) {
		size_t chunk = len - off;
		if (chunk > 16384)
			chunk = 16384;
		int w = s.write(data + off, (int)chunk);
		if (w <= 0)
			return false;
		off += (size_t)w;
	}
	return true;
}

enum class WsRead { Ok, Closed, Error };

WsRead ws_read(ByteStream &s, uint8_t &opcode, std::vector<uint8_t> &payload)
{
	std::vector<uint8_t> frag;
	uint8_t frag_op = 0;
	bool in_frag = false;
	while (true) {
		uint8_t hdr[2];
		if (!read_exact(s, hdr, 2))
			return WsRead::Closed;
		bool fin = (hdr[0] & 0x80) != 0;
		uint8_t op = (uint8_t)(hdr[0] & 0x0f);
		bool masked = (hdr[1] & 0x80) != 0;
		uint64_t len = (uint64_t)(hdr[1] & 0x7f);
		if (!masked)
			return WsRead::Error;
		if (len == 126) {
			uint8_t ext[2];
			if (!read_exact(s, ext, 2))
				return WsRead::Closed;
			len = ((uint64_t)ext[0] << 8) | ext[1];
		} else if (len == 127) {
			uint8_t ext[8];
			if (!read_exact(s, ext, 8))
				return WsRead::Closed;
			len = 0;
			for (int i = 0; i < 8; ++i)
				len = (len << 8) | ext[i];
		}
		if (len > 8ull * 1024ull * 1024ull)
			return WsRead::Error;
		uint8_t mask[4];
		if (!read_exact(s, mask, 4))
			return WsRead::Closed;
		std::vector<uint8_t> data((size_t)len);
		if (len && !read_exact(s, data.data(), (int)len))
			return WsRead::Closed;
		for (size_t i = 0; i < data.size(); ++i)
			data[i] = (uint8_t)(data[i] ^ mask[i & 3]);

		if (op == 0x8)
			return WsRead::Closed;
		if (op == 0x9) {
			if (!ws_send(s, 0xA, data.data(), data.size()))
				return WsRead::Error;
			continue;
		}
		if (op == 0xA)
			continue;
		if (op == 0x0) {
			if (!in_frag)
				return WsRead::Error;
			frag.insert(frag.end(), data.begin(), data.end());
			if (frag.size() > 8u * 1024u * 1024u)
				return WsRead::Error;
			if (fin) {
				opcode = frag_op;
				payload.swap(frag);
				return WsRead::Ok;
			}
			continue;
		}
		if (op == 0x1 || op == 0x2) {
			if (!fin) {
				in_frag = true;
				frag_op = op;
				frag.swap(data);
				continue;
			}
			opcode = op;
			payload.swap(data);
			return WsRead::Ok;
		}
	}
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
	for (size_t i = p + 1; i < json.size() && out.size() < 80; ++i) {
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

struct Slot {
	std::mutex mu;
	WebDeviceSink *sink = nullptr;
	std::string password;
	uint64_t active = 0;
};

struct Client {
	std::mutex mu;
	SOCKET sock = INVALID_SOCKET;
	std::thread thr;
	std::atomic<bool> done{false};
};

void tune_socket(SOCKET sock)
{
	/* accept() inherits the listening socket's non-blocking mode. Client
	   threads use blocking reads so a partial handshake is not a disconnect. */
	u_long blocking = 0;
	ioctlsocket(sock, FIONBIO, &blocking);
	BOOL nodelay = 1;
	setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (char *)&nodelay, sizeof(nodelay));
	BOOL ka = 1;
	setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, (char *)&ka, sizeof(ka));
	tcp_keepalive vals{};
	vals.onoff = 1;
	vals.keepalivetime = 15000;
	vals.keepaliveinterval = 3000;
	DWORD bytes = 0;
	WSAIoctl(sock, SIO_KEEPALIVE_VALS, &vals, sizeof(vals), nullptr, 0, &bytes, nullptr, nullptr);
	DWORD snd = 10000;
	setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (char *)&snd, sizeof(snd));
}

bool ip_is_safe(const std::string &ip)
{
	if (ip.empty() || ip.size() > 15)
		return false;
	for (char c : ip) {
		if (!((c >= '0' && c <= '9') || c == '.'))
			return false;
	}
	return true;
}

bool looks_virtual(const std::wstring &name)
{
	std::string n;
	for (wchar_t c : name) {
		if (c < 128) {
			char ch = (char)c;
			if (ch >= 'A' && ch <= 'Z')
				ch = (char)(ch - 'A' + 'a');
			n.push_back(ch);
		}
	}
	static const char *bad[] = {"virtual", "vethernet", "vmware", "hyper-v", "vbox", "loopback",
				    "bluetooth", "wsl", "tunnel", "tap-windows", "npcap", "zerotier"};
	for (const char *b : bad) {
		if (n.find(b) != std::string::npos)
			return true;
	}
	return false;
}

bool looks_wifi(const std::wstring &name)
{
	std::string n;
	for (wchar_t c : name) {
		if (c < 128) {
			char ch = (char)c;
			if (ch >= 'A' && ch <= 'Z')
				ch = (char)(ch - 'A' + 'a');
			n.push_back(ch);
		}
	}
	return n.find("wi-fi") != std::string::npos || n.find("wifi") != std::string::npos ||
	       n.find("wireless") != std::string::npos || n.find("wlan") != std::string::npos;
}

struct AdapterIp {
	std::string ip;
	bool wifi = false;
	bool virt = false;
};

std::vector<std::string> lan_ips()
{
	ULONG sz = 16 * 1024;
	std::vector<BYTE> mem(sz);
	auto *addrs = reinterpret_cast<IP_ADAPTER_ADDRESSES *>(mem.data());
	ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
	ULONG rc = GetAdaptersAddresses(AF_INET, flags, nullptr, addrs, &sz);
	if (rc == ERROR_BUFFER_OVERFLOW) {
		mem.resize(sz);
		addrs = reinterpret_cast<IP_ADAPTER_ADDRESSES *>(mem.data());
		rc = GetAdaptersAddresses(AF_INET, flags, nullptr, addrs, &sz);
	}
	std::vector<AdapterIp> found;
	if (rc != NO_ERROR)
		return {};
	for (auto *a = addrs; a; a = a->Next) {
		if (a->OperStatus != IfOperStatusUp)
			continue;
		bool virt = looks_virtual(a->FriendlyName ? a->FriendlyName : L"");
		bool wifi = looks_wifi(a->FriendlyName ? a->FriendlyName : L"");
		for (auto *u = a->FirstUnicastAddress; u; u = u->Next) {
			if (!u->Address.lpSockaddr || u->Address.lpSockaddr->sa_family != AF_INET)
				continue;
			char ip[INET_ADDRSTRLEN] = {};
			auto *in = reinterpret_cast<sockaddr_in *>(u->Address.lpSockaddr);
			if (!inet_ntop(AF_INET, &in->sin_addr, ip, sizeof(ip)))
				continue;
			if (strcmp(ip, "127.0.0.1") == 0)
				continue;
			if (strncmp(ip, "169.254.", 8) == 0)
				continue;
			found.push_back({ip, wifi, virt});
		}
	}
	std::stable_sort(found.begin(), found.end(), [](const AdapterIp &a, const AdapterIp &b) {
		if (a.virt != b.virt)
			return !a.virt && b.virt;
		if (a.wifi != b.wifi)
			return a.wifi && !b.wifi;
		return false;
	});
	std::vector<std::string> ips;
	for (const auto &f : found) {
		if (std::find(ips.begin(), ips.end(), f.ip) == ips.end())
			ips.push_back(f.ip);
		if (ips.size() == 4)
			break;
	}
	return ips;
}

void append_ip(std::vector<std::array<uint8_t, 4>> &store, const std::string &ip)
{
	unsigned a = 0, b = 0, c = 0, d = 0;
	if (sscanf_s(ip.c_str(), "%u.%u.%u.%u", &a, &b, &c, &d) != 4)
		return;
	if (a > 255 || b > 255 || c > 255 || d > 255)
		return;
	store.push_back({(uint8_t)a, (uint8_t)b, (uint8_t)c, (uint8_t)d});
}

PCCERT_CONTEXT make_server_cert(const std::vector<std::string> &ips, std::vector<uint8_t> &der)
{
	der.clear();
	const wchar_t *container = L"OBSWebDeviceKey";
	HCRYPTPROV prov = 0;
	if (!CryptAcquireContextW(&prov, container, nullptr, PROV_RSA_AES, CRYPT_NEWKEYSET)) {
		if (GetLastError() != (DWORD)NTE_EXISTS ||
		    !CryptAcquireContextW(&prov, container, nullptr, PROV_RSA_AES, 0)) {
			wd_log(LOG_ERROR, "certificate key container failed (%lu)", GetLastError());
			return nullptr;
		}
	}
	HCRYPTKEY key = 0;
	if (!CryptGetUserKey(prov, AT_KEYEXCHANGE, &key)) {
		DWORD key_flags = (2048u << 16) | CRYPT_EXPORTABLE;
		if (!CryptGenKey(prov, AT_KEYEXCHANGE, key_flags, &key) &&
		    !CryptGenKey(prov, AT_KEYEXCHANGE, CRYPT_EXPORTABLE, &key)) {
			wd_log(LOG_ERROR, "CryptGenKey failed (%lu)", GetLastError());
			CryptReleaseContext(prov, 0);
			return nullptr;
		}
	}

	BYTE name_buf[256];
	DWORD name_len = sizeof(name_buf);
	if (!CertStrToNameW(X509_ASN_ENCODING, L"CN=OBS Web Device", CERT_X500_NAME_STR, nullptr, name_buf, &name_len,
			    nullptr)) {
		CryptDestroyKey(key);
		CryptReleaseContext(prov, 0);
		return nullptr;
	}
	CERT_NAME_BLOB subject{};
	subject.cbData = name_len;
	subject.pbData = name_buf;

	CRYPT_KEY_PROV_INFO kpi{};
	kpi.pwszContainerName = const_cast<wchar_t *>(container);
	kpi.dwProvType = PROV_RSA_AES;
	kpi.dwKeySpec = AT_KEYEXCHANGE;

	CRYPT_ALGORITHM_IDENTIFIER sig{};
	sig.pszObjId = const_cast<char *>(szOID_RSA_SHA256RSA);

	std::vector<std::wstring> dns;
	dns.push_back(L"localhost");
	wchar_t host[256];
	DWORD host_len = 256;
	if (GetComputerNameW(host, &host_len) && host[0])
		dns.push_back(host);

	std::vector<std::array<uint8_t, 4>> ip_bytes;
	append_ip(ip_bytes, "127.0.0.1");
	for (const auto &ip : ips)
		append_ip(ip_bytes, ip);

	std::vector<CERT_ALT_NAME_ENTRY> alts;
	for (auto &name : dns) {
		CERT_ALT_NAME_ENTRY e{};
		e.dwAltNameChoice = CERT_ALT_NAME_DNS_NAME;
		e.pwszDNSName = name.data();
		alts.push_back(e);
	}
	for (auto &ip : ip_bytes) {
		CERT_ALT_NAME_ENTRY e{};
		e.dwAltNameChoice = CERT_ALT_NAME_IP_ADDRESS;
		e.IPAddress.cbData = 4;
		e.IPAddress.pbData = ip.data();
		alts.push_back(e);
	}
	CERT_ALT_NAME_INFO alt_info{};
	alt_info.cAltEntry = (DWORD)alts.size();
	alt_info.rgAltEntry = alts.data();

	BYTE key_usage_byte = (BYTE)(CERT_DIGITAL_SIGNATURE_KEY_USAGE | CERT_KEY_ENCIPHERMENT_KEY_USAGE);
	CRYPT_BIT_BLOB key_usage{};
	key_usage.cbData = 1;
	key_usage.pbData = &key_usage_byte;
	key_usage.cUnusedBits = 0;

	LPSTR eku_oid = const_cast<char *>(szOID_PKIX_KP_SERVER_AUTH);
	CERT_ENHKEY_USAGE eku{};
	eku.cUsageIdentifier = 1;
	eku.rgpszUsageIdentifier = &eku_oid;

	BYTE *san_enc = nullptr;
	BYTE *ku_enc = nullptr;
	BYTE *eku_enc = nullptr;
	DWORD san_len = 0, ku_len = 0, eku_len = 0;
	CryptEncodeObjectEx(X509_ASN_ENCODING, X509_ALTERNATE_NAME, &alt_info, CRYPT_ENCODE_ALLOC_FLAG, nullptr,
			    &san_enc, &san_len);
	CryptEncodeObjectEx(X509_ASN_ENCODING, X509_KEY_USAGE, &key_usage, CRYPT_ENCODE_ALLOC_FLAG, nullptr, &ku_enc,
			    &ku_len);
	CryptEncodeObjectEx(X509_ASN_ENCODING, X509_ENHANCED_KEY_USAGE, &eku, CRYPT_ENCODE_ALLOC_FLAG, nullptr,
			    &eku_enc, &eku_len);

	CERT_EXTENSION exts_store[3];
	DWORD ext_count = 0;
	if (ku_enc && ku_len) {
		exts_store[ext_count].pszObjId = const_cast<char *>(szOID_KEY_USAGE);
		exts_store[ext_count].fCritical = TRUE;
		exts_store[ext_count].Value.pbData = ku_enc;
		exts_store[ext_count].Value.cbData = ku_len;
		++ext_count;
	}
	if (eku_enc && eku_len) {
		exts_store[ext_count].pszObjId = const_cast<char *>(szOID_ENHANCED_KEY_USAGE);
		exts_store[ext_count].fCritical = FALSE;
		exts_store[ext_count].Value.pbData = eku_enc;
		exts_store[ext_count].Value.cbData = eku_len;
		++ext_count;
	}
	if (san_enc && san_len) {
		exts_store[ext_count].pszObjId = const_cast<char *>(szOID_SUBJECT_ALT_NAME2);
		exts_store[ext_count].fCritical = FALSE;
		exts_store[ext_count].Value.pbData = san_enc;
		exts_store[ext_count].Value.cbData = san_len;
		++ext_count;
	}
	CERT_EXTENSIONS extensions{};
	extensions.cExtension = ext_count;
	extensions.rgExtension = exts_store;

	SYSTEMTIME start{};
	GetSystemTime(&start);
	FILETIME ft{};
	SystemTimeToFileTime(&start, &ft);
	ULARGE_INTEGER ul;
	ul.LowPart = ft.dwLowDateTime;
	ul.HighPart = ft.dwHighDateTime;
	ul.QuadPart -= 24ull * 60ull * 60ull * 10000000ull;
	ft.dwLowDateTime = ul.LowPart;
	ft.dwHighDateTime = ul.HighPart;
	FileTimeToSystemTime(&ft, &start);
	SYSTEMTIME end = start;
	end.wYear = (WORD)(end.wYear + 5);

	PCCERT_CONTEXT cert = CertCreateSelfSignCertificate(0, &subject, 0, &kpi, &sig, &start, &end,
							    ext_count ? &extensions : nullptr);
	if (!cert)
		wd_log(LOG_ERROR, "CertCreateSelfSignCertificate failed (%lu)", GetLastError());
	else
		der.assign(cert->pbCertEncoded, cert->pbCertEncoded + cert->cbCertEncoded);

	if (san_enc)
		LocalFree(san_enc);
	if (ku_enc)
		LocalFree(ku_enc);
	if (eku_enc)
		LocalFree(eku_enc);
	CryptDestroyKey(key);
	CryptReleaseContext(prov, 0);
	return cert;
}

const char *kFallbackHtml =
	"<!doctype html><meta charset=utf-8><title>OBS Web Device</title>"
	"<body style=font-family:sans-serif;background:#121417;color:#eee;padding:24px>"
	"<h1>OBS Web Device</h1><p>The web app files were not found next to the plugin. "
	"Copy data/obs-plugins/obs-web-device into the OBS data folder and restart OBS.</p>";

struct Server {
	std::mutex mu;
	std::string web_root;
	WebDeviceInfo info;
	SOCKET http_listen = INVALID_SOCKET;
	SOCKET https_listen = INVALID_SOCKET;
	std::thread accept_thread;
	std::atomic<bool> stop{false};
	std::mutex clients_mu;
	std::vector<std::unique_ptr<Client>> clients;
	std::mutex rooms_mu;
	std::map<std::string, std::shared_ptr<Slot>> rooms;
	CredHandle cred{};
	bool have_cred = false;
	PCCERT_CONTEXT cert = nullptr;
	std::vector<uint8_t> cert_der;
	std::unordered_map<std::string, std::string> files;
	bool wsa = false;
	std::atomic<int> tls_fail_logs{0};

	std::string load_asset(const std::string &name)
	{
		if (name.find('/') != std::string::npos || name.find('\\') != std::string::npos ||
		    name.find("..") != std::string::npos)
			return {};
		if (!web_root.empty()) {
			auto data = read_file_w(utf8_to_wide(web_root) + L"\\" + utf8_to_wide(name));
			if (!data.empty())
				return data;
		}
#ifndef WEB_DEVICE_STANDALONE
		std::string rel = "web/" + name;
		char *p = obs_module_file(rel.c_str());
		if (p) {
			auto data = read_file_w(utf8_to_wide(p));
			bfree(p);
			if (!data.empty())
				return data;
		}
#endif
		std::wstring dir = dll_directory();
		if (!dir.empty()) {
			auto data = read_file_w(dir + L"\\web\\" + utf8_to_wide(name));
			if (!data.empty())
				return data;
		}
		return {};
	}

	void load_files()
	{
		files.clear();
		const char *names[] = {"index.html", "app.js", "app.css", "pcm-worklet.js"};
		for (const char *name : names) {
			auto data = load_asset(name);
			if (!data.empty())
				files[name] = std::move(data);
		}
		if (!files.count("index.html"))
			wd_log(LOG_WARNING, "web/index.html was not found; serving a short fallback page");
	}

	void free_cred()
	{
		if (have_cred) {
			FreeCredentialsHandle(&cred);
			have_cred = false;
		}
		if (cert) {
			CertFreeCertificateContext(cert);
			cert = nullptr;
		}
		cert_der.clear();
	}

	void close_client(Client *c)
	{
		std::lock_guard<std::mutex> lock(c->mu);
		if (c->sock != INVALID_SOCKET) {
			::shutdown(c->sock, SD_BOTH);
			closesocket(c->sock);
			c->sock = INVALID_SOCKET;
		}
	}

	void close_listen()
	{
		if (http_listen != INVALID_SOCKET) {
			closesocket(http_listen);
			http_listen = INVALID_SOCKET;
		}
		if (https_listen != INVALID_SOCKET) {
			closesocket(https_listen);
			https_listen = INVALID_SOCKET;
		}
	}

	void stop_listeners()
	{
		stop.store(true);
		close_listen();
		if (accept_thread.joinable())
			accept_thread.join();
		std::vector<std::thread> joinable;
		{
			std::lock_guard<std::mutex> lock(clients_mu);
			for (auto &c : clients) {
				close_client(c.get());
				if (c->thr.joinable())
					joinable.push_back(std::move(c->thr));
			}
		}
		for (auto &t : joinable)
			t.join();
		{
			std::lock_guard<std::mutex> lock(clients_mu);
			clients.clear();
		}
		free_cred();
	}

	SOCKET open_listen(int port, bool loopback, std::string &err)
	{
		SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
		if (s == INVALID_SOCKET) {
			err = "socket() failed";
			return INVALID_SOCKET;
		}
		BOOL opt = 1;
		setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (char *)&opt, sizeof(opt));
		sockaddr_in addr{};
		addr.sin_family = AF_INET;
		addr.sin_port = htons((u_short)port);
		addr.sin_addr.s_addr = htonl(loopback ? INADDR_LOOPBACK : INADDR_ANY);
		if (bind(s, (sockaddr *)&addr, sizeof(addr)) != 0) {
			err = "bind " + std::to_string(port) + " failed (" + std::to_string(WSAGetLastError()) + ")";
			closesocket(s);
			return INVALID_SOCKET;
		}
		if (listen(s, 16) != 0) {
			err = "listen failed";
			closesocket(s);
			return INVALID_SOCKET;
		}
		u_long nb = 1;
		ioctlsocket(s, FIONBIO, &nb);
		return s;
	}

	void setup_https(const std::vector<std::string> &ips)
	{
		cert = make_server_cert(ips, cert_der);
		if (!cert)
			return;
		DWORD cb = 0;
		if (!CertGetCertificateContextProperty(cert, CERT_KEY_PROV_INFO_PROP_ID, nullptr, &cb)) {
			wd_log(LOG_ERROR, "server certificate has no private key");
			CertFreeCertificateContext(cert);
			cert = nullptr;
			cert_der.clear();
			return;
		}
		SCHANNEL_CRED cred_data{};
		cred_data.dwVersion = SCHANNEL_CRED_VERSION;
		cred_data.cCreds = 1;
		cred_data.paCred = &cert;
		cred_data.grbitEnabledProtocols = SP_PROT_TLS1_2_SERVER;
		cred_data.dwFlags = SCH_CRED_NO_DEFAULT_CREDS | SCH_USE_STRONG_CRYPTO;
		wchar_t package_name[] = L"Microsoft Unified Security Protocol Provider";
		SECURITY_STATUS ss = AcquireCredentialsHandleW(nullptr, package_name, SECPKG_CRED_INBOUND, nullptr,
							       &cred_data, nullptr, nullptr, &cred, nullptr);
		if (ss != SEC_E_OK) {
			wd_log(LOG_ERROR, "AcquireCredentialsHandle failed (0x%08lx)", (unsigned long)ss);
			CertFreeCertificateContext(cert);
			cert = nullptr;
			cert_der.clear();
			return;
		}
		have_cred = true;
	}

	std::string config_js()
	{
		std::string s = "window.OBS_WEB_DEVICE={httpPort:";
		s += std::to_string(info.http_port);
		s += ",httpsPort:";
		s += std::to_string(info.https_port);
		s += ",httpsOk:";
		s += info.https_ok ? "true" : "false";
		s += ",ips:[";
		bool first = true;
		for (const auto &ip : info.ips) {
			if (!ip_is_safe(ip))
				continue;
			if (!first)
				s += ",";
			first = false;
			s += "\"";
			s += ip;
			s += "\"";
		}
		s += "]};";
		return s;
	}

	void post_link(const std::shared_ptr<Slot> &slot, uint64_t id, bool up, const std::string &name)
	{
		std::lock_guard<std::mutex> lock(slot->mu);
		if (!slot->sink || slot->active != id)
			return;
		slot->sink->on_link(up, name);
	}

	void post_jpeg(const std::shared_ptr<Slot> &slot, uint64_t id, const uint8_t *data, size_t size, uint32_t w,
		       uint32_t h)
	{
		std::lock_guard<std::mutex> lock(slot->mu);
		if (!slot->sink || slot->active != id)
			return;
		slot->sink->on_jpeg(data, size, w, h);
	}

	void post_pcm(const std::shared_ptr<Slot> &slot, uint64_t id, const int16_t *pcm, uint32_t frames, uint32_t rate,
		      uint32_t channels)
	{
		std::lock_guard<std::mutex> lock(slot->mu);
		if (!slot->sink || slot->active != id)
			return;
		slot->sink->on_pcm(pcm, frames, rate, channels);
	}

	void handle_binary(const std::shared_ptr<Slot> &slot, uint64_t id, const std::vector<uint8_t> &msg)
	{
		if (msg.empty())
			return;
		if (msg[0] == 1) {
			if (msg.size() < 5 || msg.size() > 8u * 1024u * 1024u)
				return;
			uint32_t w = rd_u16(msg.data() + 1);
			uint32_t h = rd_u16(msg.data() + 3);
			post_jpeg(slot, id, msg.data() + 5, msg.size() - 5, w, h);
			return;
		}
		if (msg[0] == 2) {
			if (msg.size() < 9)
				return;
			uint32_t rate = rd_u32(msg.data() + 1);
			uint32_t channels = rd_u16(msg.data() + 5);
			uint32_t frames = rd_u16(msg.data() + 7);
			if (channels < 1 || channels > 2 || frames == 0 || frames > 48000)
				return;
			if (rate < 8000 || rate > 192000)
				return;
			size_t nbytes = (size_t)frames * channels * 2u;
			if (msg.size() < 9 + nbytes)
				return;
			post_pcm(slot, id, reinterpret_cast<const int16_t *>(msg.data() + 9), frames, rate, channels);
		}
	}

	void serve_websocket(ByteStream &stream, const HttpRequest &req, const std::string &peer)
	{
		std::string key = header_get(req, "sec-websocket-key");
		if (key.empty() || !header_has_token(header_get(req, "upgrade"), "websocket") ||
		    !header_has_token(header_get(req, "connection"), "upgrade")) {
			http_response(stream, 400, "Bad Request", "text/plain; charset=utf-8", "Expected WebSocket\n",
				      nullptr);
			return;
		}
		std::string room = web_device_normalize_room(query_param(req.query, "room"));
		auto slot = [&]() -> std::shared_ptr<Slot> {
			std::lock_guard<std::mutex> lock(rooms_mu);
			auto it = rooms.find(room);
			if (it == rooms.end())
				return {};
			return it->second;
		}();

		std::string accept = ws_accept_key(key);
		std::string res = "HTTP/1.1 101 Switching Protocols\r\n"
				  "Upgrade: websocket\r\n"
				  "Connection: Upgrade\r\n"
				  "Sec-WebSocket-Accept: " +
				  accept + "\r\n\r\n";
		if (!write_bytes(stream, res))
			return;

		if (!slot) {
			std::string err = "{\"type\":\"error\",\"error\":\"unknown_room\"}";
			ws_send(stream, 0x1, reinterpret_cast<const uint8_t *>(err.data()), err.size());
			ws_send(stream, 0x8, nullptr, 0);
			return;
		}

		/* Require auth before any media (same contract as Cloudflare Worker). */
		bool authed = false;
		std::string expected_password;
		{
			std::lock_guard<std::mutex> lock(slot->mu);
			expected_password = slot->password;
		}
		{
			uint8_t opcode = 0;
			std::vector<uint8_t> payload;
			WsRead rr = ws_read(stream, opcode, payload);
			if (rr != WsRead::Ok || opcode != 0x1) {
				std::string err = "{\"type\":\"error\",\"error\":\"auth_required\"}";
				ws_send(stream, 0x1, reinterpret_cast<const uint8_t *>(err.data()), err.size());
				ws_send(stream, 0x8, nullptr, 0);
				return;
			}
			std::string text(payload.begin(), payload.end());
			if (text.size() > 4096)
				text.resize(4096);
			std::string type = json_string_field(text, "type");
			std::string pass = json_string_field(text, "password");
			std::string auth_room = web_device_normalize_room(json_string_field(text, "room"));
			if (type != "auth" || auth_room != room || pass != expected_password || pass.empty()) {
				std::string err = "{\"type\":\"error\",\"error\":\"bad_password\"}";
				ws_send(stream, 0x1, reinterpret_cast<const uint8_t *>(err.data()), err.size());
				ws_send(stream, 0x8, nullptr, 0);
				return;
			}
			authed = true;
		}
		if (!authed)
			return;

		uint64_t id = 0;
		{
			std::lock_guard<std::mutex> lock(slot->mu);
			slot->active += 1;
			id = slot->active;
		}
		wd_log(LOG_INFO, "browser connected room %s from %s", room.c_str(), peer.c_str());
		post_link(slot, id, true, peer);
		std::string welcome = std::string("{\"type\":\"welcome\",\"room\":\"") + room + "\"}";
		if (!ws_send(stream, 0x1, reinterpret_cast<const uint8_t *>(welcome.data()), welcome.size())) {
			post_link(slot, id, false, "");
			return;
		}

		while (!stop.load()) {
			uint8_t opcode = 0;
			std::vector<uint8_t> payload;
			WsRead rr = ws_read(stream, opcode, payload);
			if (rr != WsRead::Ok)
				break;
			{
				std::lock_guard<std::mutex> lock(slot->mu);
				if (slot->active != id)
					break;
			}
			if (opcode == 0x1) {
				std::string text(payload.begin(), payload.end());
				if (text.size() > 4096)
					text.resize(4096);
				std::string name = json_string_field(text, "name");
				if (!name.empty())
					post_link(slot, id, true, name);
			} else if (opcode == 0x2) {
				handle_binary(slot, id, payload);
			}
		}
		post_link(slot, id, false, "");
		wd_log(LOG_INFO, "browser disconnected room %s", room.c_str());
	}

	void serve_http(ByteStream &stream, const std::string &peer)
	{
		std::string raw;
		if (!read_headers(stream, raw))
			return;
		HttpRequest req;
		if (!parse_request(raw, req)) {
			http_response(stream, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request\n", nullptr);
			return;
		}
		if (req.method != "GET") {
			http_response(stream, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Use GET\n",
				      nullptr);
			return;
		}
		if (req.path == "/ws") {
			serve_websocket(stream, req, peer);
			return;
		}
		std::string route = req.path;
		if (!route.empty() && route[0] == '/')
			route.erase(0, 1);
		if (route.empty())
			route = "index.html";
		if (route == "favicon.ico") {
			http_response(stream, 204, "No Content", nullptr, "", nullptr);
			return;
		}
		if (route == "config.js") {
			std::string body;
			{
				std::lock_guard<std::mutex> lock(mu);
				body = config_js();
			}
			http_response(stream, 200, "OK", "text/javascript; charset=utf-8", body, nullptr);
			return;
		}
		if (route == "obs-web-device.cer") {
			std::string body;
			{
				std::lock_guard<std::mutex> lock(mu);
				body.assign(reinterpret_cast<const char *>(cert_der.data()), cert_der.size());
			}
			if (body.empty()) {
				http_response(stream, 404, "Not Found", "text/plain; charset=utf-8", "No certificate\n",
					      nullptr);
				return;
			}
			http_response(stream, 200, "OK", "application/pkix-cert", body,
				      "Content-Disposition: attachment; filename=\"obs-web-device.cer\"\r\n");
			return;
		}
		if (route.find("..") != std::string::npos || route.find('/') != std::string::npos ||
		    route.find('\\') != std::string::npos) {
			http_response(stream, 404, "Not Found", "text/plain; charset=utf-8", "Not found\n", nullptr);
			return;
		}
		std::string body;
		{
			std::lock_guard<std::mutex> lock(mu);
			auto it = files.find(route);
			if (it != files.end())
				body = it->second;
		}
		if (route == "index.html" && body.empty())
			body = kFallbackHtml;
		if (body.empty()) {
			http_response(stream, 404, "Not Found", "text/plain; charset=utf-8", "Not found\n", nullptr);
			return;
		}
		const char *mime = "application/octet-stream";
		if (route == "index.html")
			mime = "text/html; charset=utf-8";
		else if (route.size() >= 3 && route.compare(route.size() - 3, 3, ".js") == 0)
			mime = "text/javascript; charset=utf-8";
		else if (route.size() >= 4 && route.compare(route.size() - 4, 4, ".css") == 0)
			mime = "text/css; charset=utf-8";
		http_response(stream, 200, "OK", mime, body, nullptr);
	}

	void client_proc(Client *client, bool use_tls, std::string peer)
	{
		SOCKET sock = INVALID_SOCKET;
		{
			std::lock_guard<std::mutex> lock(client->mu);
			sock = client->sock;
		}
		if (sock == INVALID_SOCKET) {
			client->done.store(true);
			return;
		}
		try {
			if (!use_tls) {
				PlainStream plain(sock);
				serve_http(plain, peer);
			} else if (have_cred) {
				TlsConn tls(sock, &cred);
				if (!tls.handshake()) {
					if (tls_fail_logs.fetch_add(1) < 5)
						wd_log(LOG_WARNING, "TLS handshake failed from %s", peer.c_str());
				} else {
					serve_http(tls, peer);
				}
			}
		} catch (const std::exception &ex) {
			wd_log(LOG_WARNING, "client error: %s", ex.what());
		} catch (...) {
			wd_log(LOG_WARNING, "client error");
		}
		close_client(client);
		client->done.store(true);
	}

	void reap_clients()
	{
		std::vector<std::unique_ptr<Client>> finished;
		{
			std::lock_guard<std::mutex> lock(clients_mu);
			for (auto it = clients.begin(); it != clients.end();) {
				if ((*it)->done.load()) {
					finished.push_back(std::move(*it));
					it = clients.erase(it);
				} else {
					++it;
				}
			}
		}
		for (auto &c : finished) {
			if (c && c->thr.joinable())
				c->thr.join();
		}
	}

	void accept_one(SOCKET listen_sock, bool use_tls)
	{
		reap_clients();
		sockaddr_in peer{};
		int len = sizeof(peer);
		SOCKET sock = accept(listen_sock, (sockaddr *)&peer, &len);
		if (sock == INVALID_SOCKET)
			return;
		{
			std::lock_guard<std::mutex> lock(clients_mu);
			size_t active = 0;
			for (const auto &c : clients) {
				if (!c->done.load())
					++active;
			}
			if (active >= 8 || stop.load()) {
				closesocket(sock);
				return;
			}
		}
		tune_socket(sock);
		char ip[INET_ADDRSTRLEN] = {};
		inet_ntop(AF_INET, &peer.sin_addr, ip, sizeof(ip));
		auto client = std::make_unique<Client>();
		client->sock = sock;
		Client *raw = client.get();
		{
			std::lock_guard<std::mutex> lock(clients_mu);
			clients.push_back(std::move(client));
			raw->thr = std::thread([this, raw, use_tls, peer_ip = std::string(ip)] {
				client_proc(raw, use_tls, peer_ip);
			});
		}
	}

	void accept_loop()
	{
		while (!stop.load()) {
			fd_set fds;
			FD_ZERO(&fds);
			SOCKET maxfd = 0;
			if (http_listen != INVALID_SOCKET) {
				FD_SET(http_listen, &fds);
				maxfd = http_listen;
			}
			if (https_listen != INVALID_SOCKET) {
				FD_SET(https_listen, &fds);
				if (https_listen > maxfd)
					maxfd = https_listen;
			}
			if (maxfd == 0)
				break;
			timeval tv{};
			tv.tv_sec = 0;
			tv.tv_usec = 250000;
			int sel = select((int)maxfd + 1, &fds, nullptr, nullptr, &tv);
			if (sel <= 0)
				continue;
			if (http_listen != INVALID_SOCKET && FD_ISSET(http_listen, &fds))
				accept_one(http_listen, false);
			if (https_listen != INVALID_SOCKET && FD_ISSET(https_listen, &fds))
				accept_one(https_listen, true);
		}
	}

	void ensure(int http_port, bool localhost_only)
	{
		if (http_port < 1024)
			http_port = 1024;
		if (http_port > 65534)
			http_port = 65534;
		{
			std::lock_guard<std::mutex> lock(mu);
			if (info.running && info.http_port == http_port && info.localhost_only == localhost_only &&
			    info.http_ok)
				return;
		}
		stop_listeners();
		stop.store(false);

		if (!wsa) {
			WSADATA wsa_data{};
			if (WSAStartup(MAKEWORD(2, 2), &wsa_data) != 0) {
				std::lock_guard<std::mutex> lock(mu);
				info = {};
				info.error = "WSAStartup failed";
				return;
			}
			wsa = true;
		}
		if (!websocket_self_test()) {
			wd_log(LOG_ERROR, "websocket accept self-test failed");
			std::lock_guard<std::mutex> lock(mu);
			info = {};
			info.error = "websocket self-test failed";
			return;
		}

		load_files();
		auto ips = lan_ips();
		setup_https(ips);

		std::string http_err;
		std::string https_err;
		SOCKET http = open_listen(http_port, localhost_only, http_err);
		SOCKET https = INVALID_SOCKET;
		if (have_cred)
			https = open_listen(http_port + 1, localhost_only, https_err);

		{
			std::lock_guard<std::mutex> lock(mu);
			info = {};
			info.localhost_only = localhost_only;
			info.http_port = http_port;
			info.https_port = http_port + 1;
			info.ips = ips;
			info.http_ok = http != INVALID_SOCKET;
			info.https_ok = https != INVALID_SOCKET;
			info.running = info.http_ok || info.https_ok;
			if (!info.http_ok)
				info.error = http_err;
			else if (!info.https_ok)
				info.error = https_err.empty() ? "HTTPS unavailable" : https_err;
			http_listen = http;
			https_listen = https;
		}
		if (http == INVALID_SOCKET && https == INVALID_SOCKET) {
			wd_log(LOG_ERROR, "listen failed: %s", http_err.c_str());
			free_cred();
			return;
		}
		wd_log(LOG_INFO, "listening http:%d (%s) https:%d (%s)", http_port, http != INVALID_SOCKET ? "up" : "down",
		       http_port + 1, https != INVALID_SOCKET ? "up" : "down");
		accept_thread = std::thread([this] { accept_loop(); });
	}

	void stop_all()
	{
		stop_listeners();
		{
			std::lock_guard<std::mutex> lock(rooms_mu);
			rooms.clear();
		}
		if (wsa) {
			WSACleanup();
			wsa = false;
		}
		std::lock_guard<std::mutex> lock(mu);
		info.running = false;
		info.http_ok = false;
		info.https_ok = false;
	}
};

Server &server()
{
	static Server instance;
	return instance;
}

} /* namespace */

std::string web_device_normalize_room(const std::string &in)
{
	std::string out;
	for (unsigned char c : in) {
		if (c >= 'a' && c <= 'z')
			c = (unsigned char)(c - 'a' + 'A');
		if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
			out.push_back((char)c);
			if (out.size() == 12)
				break;
		}
	}
	return out;
}

std::string web_device_make_room()
{
	static const char *alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	unsigned char bytes[4] = {};
	if (BCryptGenRandom(nullptr, bytes, sizeof(bytes), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
		bytes[0] = (unsigned char)(GetTickCount() & 0xff);
		bytes[1] = (unsigned char)((GetTickCount() >> 8) & 0xff);
		bytes[2] = 0x5a;
		bytes[3] = 0xa5;
	}
	std::string s(4, 'A');
	for (int i = 0; i < 4; ++i)
		s[(size_t)i] = alphabet[bytes[i] & 31];
	return s;
}

void web_device_server_set_root(const std::string &utf8_directory)
{
	std::lock_guard<std::mutex> lock(server().mu);
	server().web_root = utf8_directory;
}

void web_device_server_ensure(int http_port, bool localhost_only)
{
	server().ensure(http_port, localhost_only);
}

void web_device_server_stop()
{
	server().stop_all();
}

WebDeviceInfo web_device_server_info()
{
	std::lock_guard<std::mutex> lock(server().mu);
	return server().info;
}

void web_device_server_register(const std::string &room, const std::string &password, WebDeviceSink *sink)
{
	auto slot = std::make_shared<Slot>();
	slot->sink = sink;
	slot->password = password;
	std::shared_ptr<Slot> old;
	{
		std::lock_guard<std::mutex> lock(server().rooms_mu);
		auto it = server().rooms.find(room);
		if (it != server().rooms.end())
			old = it->second;
		server().rooms[room] = slot;
	}
	if (old) {
		std::lock_guard<std::mutex> lock(old->mu);
		old->sink = nullptr;
	}
}

void web_device_server_unregister(const std::string &room, WebDeviceSink *sink)
{
	std::shared_ptr<Slot> slot;
	{
		std::lock_guard<std::mutex> lock(server().rooms_mu);
		auto it = server().rooms.find(room);
		if (it == server().rooms.end())
			return;
		slot = it->second;
		{
			std::lock_guard<std::mutex> slot_lock(slot->mu);
			if (slot->sink != sink)
				return;
		}
		server().rooms.erase(it);
	}
	std::lock_guard<std::mutex> lock(slot->mu);
	if (slot->sink == sink)
		slot->sink = nullptr;
}
