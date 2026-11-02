#pragma once
#include <cstdint>
#include <mutex>
#include <vector>

struct DecodedFrame {
	std::vector<uint8_t> bgra;
	uint32_t width = 0;
	uint32_t height = 0;
	int64_t pts_us = 0;
	bool valid = false;
};

class VideoDecoder {
public:
	VideoDecoder();
	~VideoDecoder();

	bool configure(uint16_t codec_id, uint32_t width_hint, uint32_t height_hint);
	bool decode(const uint8_t *data, size_t size, bool keyframe, int64_t pts_us);
	bool take_frame(DecodedFrame &out);
	void reset();

private:
	struct Impl;
	Impl *impl_ = nullptr;
	std::mutex mutex_;
};
