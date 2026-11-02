#include "usb-aoa.hpp"
#include "protocol-io.hpp"

#ifdef _WIN32
#include <setupapi.h>
#include <winusb.h>
#include <initguid.h>
#include <usbiodef.h>
#ifndef USB_ENDPOINT_DIRECTION_IN
#define USB_ENDPOINT_DIRECTION_IN(addr) (((addr)&0x80) != 0)
#endif
#pragma comment(lib, "setupapi.lib")
#pragma comment(lib, "winusb.lib")
#endif

#include <algorithm>
#include <chrono>
#include <cstring>

// Google AOA accessory IDs after switch
static const uint16_t AOA_VID = 0x18D1;
static const uint16_t AOA_PID_ACC = 0x2D00;
static const uint16_t AOA_PID_ACC_ADB = 0x2D01;

UsbAoaSession::UsbAoaSession() = default;

UsbAoaSession::~UsbAoaSession() { stop(); }

bool UsbAoaSession::connected() const { return connected_; }

std::string UsbAoaSession::status() const
{
	std::lock_guard<std::mutex> lock(status_mutex_);
	return status_;
}

void UsbAoaSession::set_status(const std::string &s)
{
	std::lock_guard<std::mutex> lock(status_mutex_);
	status_ = s;
}

void UsbAoaSession::stop()
{
	running_ = false;
	if (reader_.joinable())
		reader_.join();
	if (monitor_.joinable())
		monitor_.join();

#ifdef _WIN32
	std::lock_guard<std::mutex> lock(io_mutex_);
	if (winusb_handle_) {
		WinUsb_Free(winusb_handle_);
		winusb_handle_ = nullptr;
	}
	if (device_handle_ != INVALID_HANDLE_VALUE) {
		CloseHandle(device_handle_);
		device_handle_ = INVALID_HANDLE_VALUE;
	}
#endif
	connected_ = false;
	set_status("Disconnected — open the phone app and plug USB");
}

bool UsbAoaSession::start(PacketHandler handler)
{
	stop();
	handler_ = std::move(handler);
	running_ = true;
	monitor_ = std::thread([this] { monitor_loop(); });
	return true;
}

bool UsbAoaSession::send_packet(uint16_t type, const uint8_t *payload, size_t size, uint32_t flags,
				int64_t pts_us, uint16_t codec)
{
	if (!connected_)
		return false;
	std::vector<uint8_t> packet;
	opcm_write_header(packet, type, flags, tx_seq_++, pts_us, (uint32_t)size, codec);
	if (payload && size)
		packet.insert(packet.end(), payload, payload + size);
	return write_raw(packet.data(), packet.size());
}

void UsbAoaSession::monitor_loop()
{
	while (running_) {
		if (!connected_) {
			set_status("Looking for Android USB / switching to accessory…");
			bool switched = try_switch_android_to_accessory();
			if (switched)
				switch_attempts_ = 0;
			else
				++switch_attempts_;

			if (open_accessory_device()) {
				connected_ = true;
				set_status("USB connected — negotiating…");
				if (reader_.joinable())
					reader_.join();
				reader_ = std::thread([this] { reader_loop(); });
			} else if (switch_attempts_ >= 8) {
				set_status(
					"No accessory yet — install WinUSB driver for 18D1:2D00, open the app, use a data cable");
			}
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(750));
	}
}

void UsbAoaSession::reader_loop()
{
	std::vector<uint8_t> header(OPCM_HEADER_SIZE);
	while (running_ && connected_) {
		if (!read_raw(header.data(), header.size())) {
			connected_ = false;
			set_status("Reconnecting… USB read failed");
			break;
		}
		OpcmHeader hdr{};
		if (!opcm_parse_header(header.data(), header.size(), hdr))
			continue;
		std::vector<uint8_t> payload(hdr.payload_size);
		if (hdr.payload_size > 0) {
			if (!read_raw(payload.data(), payload.size())) {
				connected_ = false;
				set_status("Reconnecting… USB payload read failed");
				break;
			}
		}
		if (hdr.packet_type == OPCM_PKT_PING) {
			send_packet(OPCM_PKT_PONG, payload.data(), payload.size());
			continue;
		}
		if (handler_) {
			handler_(hdr.packet_type, hdr.flags, hdr.pts_us, hdr.codec,
				 payload.data(), payload.size());
		}
	}

#ifdef _WIN32
	{
		std::lock_guard<std::mutex> lock(io_mutex_);
		if (winusb_handle_) {
			WinUsb_Free(winusb_handle_);
			winusb_handle_ = nullptr;
		}
		if (device_handle_ != INVALID_HANDLE_VALUE) {
			CloseHandle(device_handle_);
			device_handle_ = INVALID_HANDLE_VALUE;
		}
	}
#endif
	connected_ = false;
}

bool UsbAoaSession::write_raw(const uint8_t *data, size_t size)
{
#ifdef _WIN32
	std::lock_guard<std::mutex> lock(io_mutex_);
	if (!winusb_handle_ || !bulk_out_)
		return false;
	ULONG written = 0;
	size_t off = 0;
	while (off < size) {
		ULONG chunk = (ULONG)std::min<size_t>(size - off, 16384);
		if (!WinUsb_WritePipe(winusb_handle_, bulk_out_, (PUCHAR)(data + off), chunk,
				      &written, nullptr))
			return false;
		off += written;
	}
	return true;
#else
	(void)data;
	(void)size;
	return false;
#endif
}

bool UsbAoaSession::read_raw(uint8_t *data, size_t size)
{
#ifdef _WIN32
	std::lock_guard<std::mutex> lock(io_mutex_);
	if (!winusb_handle_ || !bulk_in_)
		return false;
	size_t off = 0;
	while (off < size) {
		ULONG read = 0;
		ULONG chunk = (ULONG)std::min<size_t>(size - off, 16384);
		if (!WinUsb_ReadPipe(winusb_handle_, bulk_in_, data + off, chunk, &read, nullptr))
			return false;
		if (read == 0)
			return false;
		off += read;
	}
	return true;
#else
	(void)data;
	(void)size;
	return false;
#endif
}

#ifdef _WIN32
static bool get_device_path_by_vid_pid(uint16_t vid, uint16_t pid, std::wstring &out_path)
{
	HDEVINFO info = SetupDiGetClassDevs(&GUID_DEVINTERFACE_USB_DEVICE, nullptr, nullptr,
					    DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
	if (info == INVALID_HANDLE_VALUE)
		return false;

	SP_DEVICE_INTERFACE_DATA ifdata{};
	ifdata.cbSize = sizeof(ifdata);
	wchar_t needle[64];
	swprintf(needle, 64, L"vid_%04x&pid_%04x", vid, pid);

	for (DWORD i = 0; SetupDiEnumDeviceInterfaces(info, nullptr, &GUID_DEVINTERFACE_USB_DEVICE, i,
						      &ifdata);
	     ++i) {
		DWORD needed = 0;
		SetupDiGetDeviceInterfaceDetailW(info, &ifdata, nullptr, 0, &needed, nullptr);
		std::vector<uint8_t> buf(needed);
		auto *detail = (SP_DEVICE_INTERFACE_DETAIL_DATA_W *)buf.data();
		detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
		if (!SetupDiGetDeviceInterfaceDetailW(info, &ifdata, detail, needed, nullptr, nullptr))
			continue;
		std::wstring path = detail->DevicePath;
		std::wstring lower = path;
		for (auto &c : lower)
			c = (wchar_t)towlower(c);
		if (lower.find(needle) != std::wstring::npos) {
			out_path = path;
			SetupDiDestroyDeviceInfoList(info);
			return true;
		}
	}
	SetupDiDestroyDeviceInfoList(info);
	return false;
}

static bool aoa_control(WINUSB_INTERFACE_HANDLE h, uint8_t request, uint16_t value,
			const void *data, uint16_t len)
{
	WINUSB_SETUP_PACKET pkt{};
	pkt.RequestType = 0x40; // host to device, vendor, device
	pkt.Request = request;
	pkt.Value = value;
	pkt.Index = 0;
	pkt.Length = len;
	ULONG transferred = 0;
	return WinUsb_ControlTransfer(h, pkt, (PUCHAR)data, len, &transferred, nullptr) == TRUE;
}

bool UsbAoaSession::claim_winusb(const std::wstring &path)
{
	std::lock_guard<std::mutex> lock(io_mutex_);
	if (winusb_handle_) {
		WinUsb_Free(winusb_handle_);
		winusb_handle_ = nullptr;
	}
	if (device_handle_ != INVALID_HANDLE_VALUE) {
		CloseHandle(device_handle_);
		device_handle_ = INVALID_HANDLE_VALUE;
	}

	device_handle_ = CreateFileW(path.c_str(), GENERIC_WRITE | GENERIC_READ,
				     FILE_SHARE_WRITE | FILE_SHARE_READ, nullptr, OPEN_EXISTING,
				     FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED, nullptr);
	if (device_handle_ == INVALID_HANDLE_VALUE)
		return false;

	if (!WinUsb_Initialize(device_handle_, &winusb_handle_)) {
		CloseHandle(device_handle_);
		device_handle_ = INVALID_HANDLE_VALUE;
		return false;
	}

	USB_INTERFACE_DESCRIPTOR iface{};
	if (!WinUsb_QueryInterfaceSettings(winusb_handle_, 0, &iface))
		return false;

	bulk_in_ = 0;
	bulk_out_ = 0;
	for (UCHAR e = 0; e < iface.bNumEndpoints; ++e) {
		WINUSB_PIPE_INFORMATION pipe{};
		if (!WinUsb_QueryPipe(winusb_handle_, 0, e, &pipe))
			continue;
		if (pipe.PipeType != UsbdPipeTypeBulk)
			continue;
		if (USB_ENDPOINT_DIRECTION_IN(pipe.PipeId))
			bulk_in_ = pipe.PipeId;
		else
			bulk_out_ = pipe.PipeId;
	}
	if (!bulk_in_ || !bulk_out_)
		return false;

	ULONG timeout_ms = 3000;
	WinUsb_SetPipePolicy(winusb_handle_, bulk_in_, PIPE_TRANSFER_TIMEOUT, sizeof(timeout_ms),
			     &timeout_ms);
	WinUsb_SetPipePolicy(winusb_handle_, bulk_out_, PIPE_TRANSFER_TIMEOUT, sizeof(timeout_ms),
			     &timeout_ms);
	return true;
}

bool UsbAoaSession::open_accessory_device()
{
	std::wstring path;
	if (!get_device_path_by_vid_pid(AOA_VID, AOA_PID_ACC, path) &&
	    !get_device_path_by_vid_pid(AOA_VID, AOA_PID_ACC_ADB, path))
		return false;
	return claim_winusb(path);
}

bool UsbAoaSession::try_switch_android_to_accessory()
{
	/* Scan common Android vendor devices already in accessory protocol wait state
	   by attempting AOA handshake on any USB device that accepts vendor requests.
	   Practical approach: look for Google AOA supporting devices via VID list +
	   already-open WinUSB interfaces. For v1 we try accessory PIDs first; if a
	   device exposes WinUSB and answers GET PROTOCOL, switch it. */

	HDEVINFO info = SetupDiGetClassDevs(&GUID_DEVINTERFACE_USB_DEVICE, nullptr, nullptr,
					    DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
	if (info == INVALID_HANDLE_VALUE)
		return false;

	SP_DEVICE_INTERFACE_DATA ifdata{};
	ifdata.cbSize = sizeof(ifdata);
	bool switched = false;

	for (DWORD i = 0; SetupDiEnumDeviceInterfaces(info, nullptr, &GUID_DEVINTERFACE_USB_DEVICE, i,
						      &ifdata);
	     ++i) {
		DWORD needed = 0;
		SetupDiGetDeviceInterfaceDetailW(info, &ifdata, nullptr, 0, &needed, nullptr);
		std::vector<uint8_t> buf(needed);
		auto *detail = (SP_DEVICE_INTERFACE_DETAIL_DATA_W *)buf.data();
		detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
		if (!SetupDiGetDeviceInterfaceDetailW(info, &ifdata, detail, needed, nullptr, nullptr))
			continue;

		std::wstring path = detail->DevicePath;
		std::wstring lower = path;
		for (auto &c : lower)
			c = (wchar_t)towlower(c);
		// Skip already-accessory devices
		if (lower.find(L"vid_18d1&pid_2d00") != std::wstring::npos ||
		    lower.find(L"vid_18d1&pid_2d01") != std::wstring::npos)
			continue;

		HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE | GENERIC_READ,
				       FILE_SHARE_WRITE | FILE_SHARE_READ, nullptr, OPEN_EXISTING,
				       FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED, nullptr);
		if (h == INVALID_HANDLE_VALUE)
			continue;
		WINUSB_INTERFACE_HANDLE wh = nullptr;
		if (!WinUsb_Initialize(h, &wh)) {
			CloseHandle(h);
			continue;
		}

		uint16_t protocol = 0;
		WINUSB_SETUP_PACKET pkt{};
		pkt.RequestType = 0xC0;
		pkt.Request = 51; // ACCESSORY_GET_PROTOCOL
		pkt.Length = sizeof(protocol);
		ULONG transferred = 0;
		BOOL ok = WinUsb_ControlTransfer(wh, pkt, (PUCHAR)&protocol, sizeof(protocol),
						 &transferred, nullptr);
		if (ok && protocol >= 1) {
			auto send_str = [&](uint16_t index, const char *s) {
				aoa_control(wh, 52, index, s, (uint16_t)(strlen(s) + 1));
			};
			send_str(0, OPCM_AOA_MANUFACTURER);
			send_str(1, OPCM_AOA_MODEL);
			send_str(2, OPCM_AOA_DESCRIPTION);
			send_str(3, OPCM_AOA_VERSION);
			send_str(4, OPCM_AOA_URI);
			send_str(5, OPCM_AOA_SERIAL);
			aoa_control(wh, 53, 0, nullptr, 0); // ACCESSORY_START
			switched = true;
		}

		WinUsb_Free(wh);
		CloseHandle(h);
		if (switched)
			break;
	}
	SetupDiDestroyDeviceInfoList(info);
	if (switched)
		std::this_thread::sleep_for(std::chrono::milliseconds(1200));
	return switched;
}
#else
bool UsbAoaSession::open_accessory_device() { return false; }
bool UsbAoaSession::try_switch_android_to_accessory() { return false; }
bool UsbAoaSession::claim_winusb(const std::wstring &) { return false; }
#endif
