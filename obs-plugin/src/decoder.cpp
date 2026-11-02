#include "decoder.hpp"
#include "protocol.h"
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

struct VideoDecoder::Impl {
	uint16_t codec = OPCM_CODEC_NONE;
	uint32_t width = 0;
	uint32_t height = 0;
	DecodedFrame latest;
	AVCodecContext *ctx = nullptr;
	SwsContext *sws = nullptr;
	AVFrame *frame = nullptr;
	AVPacket *pkt = nullptr;
	bool waiting_key = true;
};

VideoDecoder::VideoDecoder() { impl_ = new Impl(); }

VideoDecoder::~VideoDecoder()
{
	reset();
	delete impl_;
	impl_ = nullptr;
}

void VideoDecoder::reset()
{
	std::lock_guard<std::mutex> lock(mutex_);
	if (impl_->sws) {
		sws_freeContext(impl_->sws);
		impl_->sws = nullptr;
	}
	if (impl_->frame)
		av_frame_free(&impl_->frame);
	if (impl_->pkt)
		av_packet_free(&impl_->pkt);
	if (impl_->ctx)
		avcodec_free_context(&impl_->ctx);
	impl_->latest = {};
	impl_->codec = OPCM_CODEC_NONE;
	impl_->width = 0;
	impl_->height = 0;
	impl_->waiting_key = true;
}

bool VideoDecoder::configure(uint16_t codec_id, uint32_t width_hint, uint32_t height_hint)
{
	std::lock_guard<std::mutex> lock(mutex_);
	if (impl_->codec == codec_id && impl_->ctx)
		return true;

	if (impl_->ctx) {
		avcodec_free_context(&impl_->ctx);
		impl_->ctx = nullptr;
	}
	if (impl_->frame) {
		av_frame_free(&impl_->frame);
		impl_->frame = nullptr;
	}
	if (impl_->pkt) {
		av_packet_free(&impl_->pkt);
		impl_->pkt = nullptr;
	}

	AVCodecID id = AV_CODEC_ID_NONE;
	if (codec_id == OPCM_CODEC_HEVC)
		id = AV_CODEC_ID_HEVC;
	else if (codec_id == OPCM_CODEC_H264)
		id = AV_CODEC_ID_H264;
	else if (codec_id == OPCM_CODEC_MJPEG)
		id = AV_CODEC_ID_MJPEG;
	else
		return false;

	const AVCodec *codec = avcodec_find_decoder(id);
	if (!codec)
		return false;

	impl_->ctx = avcodec_alloc_context3(codec);
	if (!impl_->ctx)
		return false;

	impl_->ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
	impl_->ctx->thread_count = 2;
	if (width_hint && height_hint) {
		impl_->ctx->width = (int)width_hint;
		impl_->ctx->height = (int)height_hint;
	}

	if (avcodec_open2(impl_->ctx, codec, nullptr) < 0) {
		avcodec_free_context(&impl_->ctx);
		return false;
	}

	impl_->frame = av_frame_alloc();
	impl_->pkt = av_packet_alloc();
	impl_->codec = codec_id;
	impl_->waiting_key = true;
	return impl_->frame && impl_->pkt;
}

bool VideoDecoder::decode(const uint8_t *data, size_t size, bool keyframe, int64_t pts_us)
{
	std::lock_guard<std::mutex> lock(mutex_);
	if (!impl_->ctx || !impl_->pkt || !impl_->frame || !data || size == 0)
		return false;

	if (impl_->waiting_key && !keyframe)
		return false;
	if (keyframe)
		impl_->waiting_key = false;

	av_packet_unref(impl_->pkt);
	if (av_new_packet(impl_->pkt, (int)size) < 0)
		return false;
	memcpy(impl_->pkt->data, data, size);
	impl_->pkt->pts = pts_us;
	if (keyframe)
		impl_->pkt->flags |= AV_PKT_FLAG_KEY;

	int send = avcodec_send_packet(impl_->ctx, impl_->pkt);
	if (send == AVERROR_INVALIDDATA) {
		impl_->waiting_key = true;
		return false;
	}
	if (send < 0)
		return false;

	bool got_any = false;
	while (true) {
		int got = avcodec_receive_frame(impl_->ctx, impl_->frame);
		if (got == AVERROR(EAGAIN) || got == AVERROR_EOF)
			break;
		if (got < 0) {
			impl_->waiting_key = true;
			return false;
		}

		int w = impl_->frame->width;
		int h = impl_->frame->height;
		impl_->sws = sws_getCachedContext(impl_->sws, w, h,
						  (AVPixelFormat)impl_->frame->format, w, h,
						  AV_PIX_FMT_BGRA, SWS_FAST_BILINEAR, nullptr,
						  nullptr, nullptr);
		if (!impl_->sws)
			return false;

		DecodedFrame f;
		f.width = (uint32_t)w;
		f.height = (uint32_t)h;
		f.pts_us = pts_us;
		f.bgra.resize((size_t)w * (size_t)h * 4);
		uint8_t *dst[4] = {f.bgra.data(), nullptr, nullptr, nullptr};
		int dst_stride[4] = {w * 4, 0, 0, 0};
		sws_scale(impl_->sws, impl_->frame->data, impl_->frame->linesize, 0, h, dst,
			  dst_stride);
		f.valid = true;
		impl_->latest = std::move(f);
		impl_->width = (uint32_t)w;
		impl_->height = (uint32_t)h;
		got_any = true;
	}
	return got_any;
}

bool VideoDecoder::take_frame(DecodedFrame &out)
{
	std::lock_guard<std::mutex> lock(mutex_);
	if (!impl_->latest.valid)
		return false;
	out = std::move(impl_->latest);
	impl_->latest.valid = false;
	return true;
}
