#pragma once

#include <cstdint>
#include <string>
#include <vector>

/* Video and audio arriving from a browser tab. Callbacks run on the
   connection thread and must return before the source is destroyed. */
class WebDeviceSink {
public:
	virtual ~WebDeviceSink() = default;
	virtual void on_link(bool up, const std::string &peer_name) = 0;
	virtual void on_jpeg(const uint8_t *data, size_t size, uint32_t width, uint32_t height) = 0;
	virtual void on_pcm(const int16_t *interleaved, uint32_t frames, uint32_t sample_rate,
			    uint32_t channels) = 0;
};

struct WebDeviceInfo {
	bool running = false;
	bool http_ok = false;
	bool https_ok = false;
	bool localhost_only = false;
	int http_port = 0;
	int https_port = 0;
	std::string error;
	std::vector<std::string> ips;
};

std::string web_device_normalize_room(const std::string &in);
std::string web_device_make_room();

void web_device_server_set_root(const std::string &utf8_directory);
void web_device_server_ensure(int http_port, bool localhost_only);
void web_device_server_stop();
WebDeviceInfo web_device_server_info();
void web_device_server_register(const std::string &room, const std::string &password, WebDeviceSink *sink);
void web_device_server_unregister(const std::string &room, WebDeviceSink *sink);
