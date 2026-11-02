#pragma once

#include "net-server.hpp"

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

/* Outbound WSS to the NexoCam Cloudflare Worker. Plugin is the server role. */
class CloudBridge {
public:
	CloudBridge();
	~CloudBridge();

	void start(const std::string &ws_url, const std::string &room, const std::string &password,
		   WebDeviceSink *sink);
	void stop();
	bool connected() const { return linked_.load(); }
	std::string status() const;

private:
	void run();
	void set_status(const std::string &s);

	mutable std::mutex mu_;
	std::string status_;
	std::string ws_url_;
	std::string room_;
	std::string password_;
	WebDeviceSink *sink_ = nullptr;
	std::atomic<bool> stop_{true};
	std::atomic<bool> linked_{false};
	std::thread thr_;
};
