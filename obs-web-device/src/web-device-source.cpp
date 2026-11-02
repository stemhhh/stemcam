#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <bcrypt.h>
#include <obs-module.h>
#include <util/platform.h>

#include "cloud-client.hpp"
#include "jpeg-decode.hpp"
#include "net-server.hpp"
#include "web-device-source.hpp"

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char *kDefaultWs = "wss://nexocam-api.nexocam.workers.dev/ws";
constexpr const char *kDefaultSite = "https://nexocam.pages.dev";

std::string format_text(const char *key, ...)
{
	const char *fmt = obs_module_text(key);
	char buf[768];
	va_list ap;
	va_start(ap, key);
	vsnprintf(buf, sizeof(buf), fmt ? fmt : "", ap);
	va_end(ap);
	return buf;
}

void copy_utf8(const std::string &text)
{
	if (text.empty())
		return;
	int n = MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, nullptr, 0);
	if (n <= 0)
		return;
	HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, (SIZE_T)n * sizeof(wchar_t));
	if (!mem)
		return;
	auto *dst = (wchar_t *)GlobalLock(mem);
	MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, dst, n);
	GlobalUnlock(mem);
	if (!OpenClipboard(nullptr)) {
		GlobalFree(mem);
		return;
	}
	EmptyClipboard();
	if (!SetClipboardData(CF_UNICODETEXT, mem))
		GlobalFree(mem);
	CloseClipboard();
}

std::string make_password()
{
	static const char *alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	unsigned char bytes[8] = {};
	if (BCryptGenRandom(nullptr, bytes, sizeof(bytes), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
		DWORD t = GetTickCount();
		for (int i = 0; i < 8; ++i)
			bytes[i] = (unsigned char)((t >> (i * 3)) ^ (0xA5 + i));
	}
	std::string s(8, 'A');
	for (int i = 0; i < 8; ++i)
		s[(size_t)i] = alphabet[bytes[i] % 32];
	return s;
}

struct WebDeviceSource : WebDeviceSink {
	obs_source_t *source = nullptr;
	std::mutex mu;
	std::atomic<bool> alive{true};
	std::string room;
	std::string password;
	std::string ws_url;
	std::string site_url;
	int port = 8787;
	bool enable_lan = true;
	bool enable_cloud = true;
	std::atomic<bool> linked{false};
	std::string peer;
	std::string status;
	std::string connect_url;
	std::string local_url;
	std::unique_ptr<CloudBridge> cloud;
	std::atomic<uint32_t> width{0};
	std::atomic<uint32_t> height{0};
	uint32_t shown_w = 0;
	uint32_t shown_h = 0;
	std::atomic<int> bad_jpeg{0};

	void set_status(const std::string &text)
	{
		std::lock_guard<std::mutex> lock(mu);
		status = text;
	}

	std::string status_copy()
	{
		std::lock_guard<std::mutex> lock(mu);
		return status;
	}

	void refresh_urls()
	{
		connect_url = site_url;
		if (!connect_url.empty() && connect_url.back() == '/')
			connect_url.pop_back();
		connect_url += "/app/?room=" + room;

		WebDeviceInfo info = web_device_server_info();
		int http_port = info.http_port ? info.http_port : port;
		local_url = "http://127.0.0.1:" + std::to_string(http_port) + "/?room=" + room;
	}

	void restart_cloud()
	{
		if (!cloud)
			cloud = std::make_unique<CloudBridge>();
		cloud->stop();
		if (enable_cloud && !ws_url.empty() && !room.empty() && !password.empty())
			cloud->start(ws_url, room, password, this);
	}

	void apply(obs_data_t *settings)
	{
		int next_port = (int)obs_data_get_int(settings, "port");
		if (next_port < 1024 || next_port > 65534)
			next_port = 8787;

		std::string next_room = web_device_normalize_room(obs_data_get_string(settings, "room"));
		if (next_room.size() < 4) {
			next_room = web_device_make_room();
			obs_data_set_string(settings, "room", next_room.c_str());
		} else if (next_room != obs_data_get_string(settings, "room")) {
			obs_data_set_string(settings, "room", next_room.c_str());
		}

		std::string next_password = obs_data_get_string(settings, "password");
		if (next_password.empty()) {
			next_password = make_password();
			obs_data_set_string(settings, "password", next_password.c_str());
		}

		std::string next_ws = obs_data_get_string(settings, "ws_url");
		if (next_ws.empty()) {
			next_ws = kDefaultWs;
			obs_data_set_string(settings, "ws_url", next_ws.c_str());
		}
		std::string next_site = obs_data_get_string(settings, "site_url");
		if (next_site.empty()) {
			next_site = kDefaultSite;
			obs_data_set_string(settings, "site_url", next_site.c_str());
		}

		enable_lan = obs_data_get_bool(settings, "enable_lan");
		enable_cloud = obs_data_get_bool(settings, "enable_cloud");

		bool room_changed = next_room != room;
		bool pass_changed = next_password != password;
		bool ws_changed = next_ws != ws_url;

		if (room_changed && !room.empty())
			web_device_server_unregister(room, this);

		room = next_room;
		password = next_password;
		ws_url = next_ws;
		site_url = next_site;
		port = next_port;

		if (enable_lan) {
			web_device_server_register(room, password, this);
			web_device_server_ensure(port, false);
		} else if (!room.empty()) {
			web_device_server_unregister(room, this);
		}

		if (enable_cloud && (room_changed || pass_changed || ws_changed || !cloud || !cloud->connected()))
			restart_cloud();
		else if (!enable_cloud && cloud)
			cloud->stop();

		refresh_urls();

		if (!linked.load()) {
			if (enable_cloud)
				set_status(format_text("WebDevice.StatusWaiting", room.c_str()));
			else if (enable_lan) {
				WebDeviceInfo info = web_device_server_info();
				if (!info.http_ok)
					set_status(format_text("WebDevice.StatusHttpFail", port));
				else
					set_status(format_text("WebDevice.StatusWaiting", room.c_str()));
			} else {
				set_status(obs_module_text("WebDevice.StatusOffline"));
			}
		}
	}

	void on_link(bool up, const std::string &peer_name) override
	{
		if (!alive.load())
			return;
		linked.store(up);
		if (up) {
			std::string name = peer_name.empty() ? room : peer_name;
			{
				std::lock_guard<std::mutex> lock(mu);
				peer = name;
			}
			set_status(format_text("WebDevice.StatusConnected", name.c_str()));
		} else {
			shown_w = 0;
			shown_h = 0;
			set_status(format_text("WebDevice.StatusWaiting", room.c_str()));
		}
		if (alive.load() && source)
			obs_source_update_properties(source);
	}

	void on_jpeg(const uint8_t *data, size_t size, uint32_t, uint32_t) override
	{
		if (!alive.load() || !source)
			return;
		std::vector<uint8_t> bgra;
		uint32_t w = 0;
		uint32_t h = 0;
		if (!jpeg_to_bgra(data, size, bgra, w, h)) {
			if (bad_jpeg.fetch_add(1) < 3)
				blog(LOG_WARNING, "[nexocam] JPEG frame rejected");
			return;
		}
		width.store(w);
		height.store(h);

		obs_source_frame frame = {};
		frame.data[0] = bgra.data();
		frame.linesize[0] = w * 4;
		frame.width = w;
		frame.height = h;
		frame.format = VIDEO_FORMAT_BGRA;
		frame.full_range = true;
		frame.timestamp = os_gettime_ns();
		obs_source_output_video(source, &frame);

		bool changed = false;
		{
			std::lock_guard<std::mutex> lock(mu);
			if (w != shown_w || h != shown_h) {
				shown_w = w;
				shown_h = h;
				changed = true;
			}
		}
		if (changed && alive.load()) {
			std::string name;
			{
				std::lock_guard<std::mutex> lock(mu);
				name = peer.empty() ? room : peer;
			}
			set_status(format_text("WebDevice.StatusLive", name.c_str(), w, h));
			if (alive.load())
				obs_source_update_properties(source);
		}
	}

	void on_pcm(const int16_t *interleaved, uint32_t frames, uint32_t sample_rate, uint32_t channels) override
	{
		if (!alive.load() || !source || !interleaved)
			return;
		if (channels < 1 || channels > 2 || frames == 0)
			return;
		obs_source_audio audio = {};
		audio.data[0] = reinterpret_cast<const uint8_t *>(interleaved);
		audio.frames = frames;
		audio.speakers = channels >= 2 ? SPEAKERS_STEREO : SPEAKERS_MONO;
		audio.format = AUDIO_FORMAT_16BIT;
		audio.samples_per_sec = sample_rate;
		audio.timestamp = os_gettime_ns();
		obs_source_output_audio(source, &audio);
	}
};

WebDeviceSource *cast_source(void *data)
{
	return static_cast<WebDeviceSource *>(data);
}

const char *web_device_get_name(void *)
{
	return obs_module_text("WebDevice.Name");
}

void *web_device_create(obs_data_t *settings, obs_source_t *source)
{
	auto *ctx = new WebDeviceSource();
	ctx->source = source;
	obs_source_set_async_unbuffered(source, true);
	obs_source_set_async_decoupled(source, true);
	ctx->apply(settings);
	return ctx;
}

void web_device_destroy(void *data)
{
	auto *ctx = cast_source(data);
	ctx->alive.store(false);
	if (ctx->cloud)
		ctx->cloud->stop();
	if (!ctx->room.empty())
		web_device_server_unregister(ctx->room, ctx);
	delete ctx;
}

void web_device_update(void *data, obs_data_t *settings)
{
	cast_source(data)->apply(settings);
}

uint32_t web_device_width(void *data)
{
	return cast_source(data)->width.load();
}

uint32_t web_device_height(void *data)
{
	return cast_source(data)->height.load();
}

bool on_copy_link(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = cast_source(data);
	copy_utf8(ctx->connect_url);
	ctx->set_status(obs_module_text("WebDevice.Copied"));
	return true;
}

bool on_copy_password(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = cast_source(data);
	copy_utf8(ctx->password);
	ctx->set_status(obs_module_text("WebDevice.PasswordCopied"));
	return true;
}

bool on_new_room(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = cast_source(data);
	obs_data_t *settings = obs_source_get_settings(ctx->source);
	obs_data_set_string(settings, "room", web_device_make_room().c_str());
	obs_data_set_string(settings, "password", make_password().c_str());
	ctx->apply(settings);
	obs_data_release(settings);
	return true;
}

bool on_open_connect(obs_properties_t *, obs_property_t *, void *)
{
	return false;
}

obs_properties_t *web_device_properties(void *data)
{
	auto *ctx = cast_source(data);
	obs_properties_t *props = obs_properties_create();
	obs_properties_set_param(props, data, nullptr);

	obs_property_t *help =
		obs_properties_add_text(props, "help", obs_module_text("WebDevice.How"), OBS_TEXT_INFO);
	obs_property_text_set_info_word_wrap(help, true);

	obs_properties_add_text(props, "room", obs_module_text("WebDevice.Room"), OBS_TEXT_DEFAULT);
	obs_properties_add_text(props, "password", obs_module_text("WebDevice.Password"), OBS_TEXT_DEFAULT);
	obs_properties_add_button(props, "copy_password", obs_module_text("WebDevice.CopyPassword"),
				  on_copy_password);

	obs_property_t *connect =
		obs_properties_add_text(props, "connect_url", obs_module_text("WebDevice.ConnectUrl"), OBS_TEXT_INFO);
	obs_property_text_set_info_word_wrap(connect, true);
	obs_properties_add_button(props, "copy_connect", obs_module_text("WebDevice.Copy"), on_copy_link);
	obs_property_t *open_btn =
		obs_properties_add_button(props, "open_connect", obs_module_text("WebDevice.Open"), on_open_connect);
	obs_property_button_set_type(open_btn, OBS_BUTTON_URL);

	obs_properties_add_bool(props, "enable_cloud", obs_module_text("WebDevice.EnableCloud"));
	obs_properties_add_text(props, "ws_url", obs_module_text("WebDevice.WsUrl"), OBS_TEXT_DEFAULT);
	obs_properties_add_text(props, "site_url", obs_module_text("WebDevice.SiteUrl"), OBS_TEXT_DEFAULT);

	obs_properties_add_bool(props, "enable_lan", obs_module_text("WebDevice.EnableLan"));
	obs_properties_add_int(props, "port", obs_module_text("WebDevice.Port"), 1024, 65534, 1);
	obs_properties_add_text(props, "url_local", obs_module_text("WebDevice.UrlLocal"), OBS_TEXT_INFO);

	obs_properties_add_button(props, "new_room", obs_module_text("WebDevice.NewRoom"), on_new_room);
	obs_property_t *status =
		obs_properties_add_text(props, "status_ro", obs_module_text("WebDevice.Status"), OBS_TEXT_INFO);
	obs_property_text_set_info_word_wrap(status, true);

	if (ctx) {
		ctx->refresh_urls();
		obs_data_t *settings = obs_source_get_settings(ctx->source);
		obs_data_set_string(settings, "help", obs_module_text("WebDevice.Help"));
		obs_data_set_string(settings, "connect_url", ctx->connect_url.c_str());
		obs_data_set_string(settings, "url_local", ctx->local_url.c_str());
		obs_data_set_string(settings, "status_ro", ctx->status_copy().c_str());
		if (!ctx->connect_url.empty())
			obs_property_button_set_url(open_btn, const_cast<char *>(ctx->connect_url.c_str()));
		obs_data_release(settings);
	}
	return props;
}

void web_device_defaults(obs_data_t *settings)
{
	obs_data_set_default_int(settings, "port", 8787);
	obs_data_set_default_string(settings, "room", "");
	obs_data_set_default_string(settings, "password", "");
	obs_data_set_default_string(settings, "ws_url", kDefaultWs);
	obs_data_set_default_string(settings, "site_url", kDefaultSite);
	obs_data_set_default_bool(settings, "enable_cloud", true);
	obs_data_set_default_bool(settings, "enable_lan", true);
}

} /* namespace */

void register_web_device_source()
{
	obs_source_info info = {};
	info.id = "nexocam_source";
	info.type = OBS_SOURCE_TYPE_INPUT;
	info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE;
	info.get_name = web_device_get_name;
	info.create = web_device_create;
	info.destroy = web_device_destroy;
	info.update = web_device_update;
	info.get_width = web_device_width;
	info.get_height = web_device_height;
	info.get_properties = web_device_properties;
	info.get_defaults = web_device_defaults;
	info.icon_type = OBS_ICON_TYPE_CAMERA;
	obs_register_source(&info);
}
