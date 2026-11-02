#pragma once
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#include <winusb.h>
#endif

class UsbAoaSession {
public:
	using PacketHandler = std::function<void(uint16_t type, uint32_t flags, int64_t pts_us,
						 uint16_t codec, const uint8_t *payload,
						 size_t payload_size)>;

	UsbAoaSession();
	~UsbAoaSession();

	bool start(PacketHandler handler);
	void stop();
	bool connected() const;
	bool send_packet(uint16_t type, const uint8_t *payload, size_t size, uint32_t flags = 0,
			 int64_t pts_us = 0, uint16_t codec = 0);
	std::string status() const;

private:
	bool open_accessory_device();
	bool try_switch_android_to_accessory();
	bool claim_winusb(const std::wstring &path);
	void reader_loop();
	void monitor_loop();
	bool write_raw(const uint8_t *data, size_t size);
	bool read_raw(uint8_t *data, size_t size);
	void set_status(const std::string &s);

#ifdef _WIN32
	HANDLE device_handle_ = INVALID_HANDLE_VALUE;
	WINUSB_INTERFACE_HANDLE winusb_handle_ = nullptr;
	UCHAR bulk_in_ = 0;
	UCHAR bulk_out_ = 0;
#endif
	PacketHandler handler_;
	std::thread reader_;
	std::thread monitor_;
	std::mutex io_mutex_;
	mutable std::mutex status_mutex_;
	bool running_ = false;
	bool connected_ = false;
	uint32_t tx_seq_ = 0;
	std::string status_ = "Disconnected — open the phone app and plug USB";
	int switch_attempts_ = 0;
};
