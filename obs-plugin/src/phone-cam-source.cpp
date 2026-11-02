#include "phone-cam-source.hpp"
#include "decoder.hpp"
#include "protocol-io.hpp"
#include "usb-aoa.hpp"

#include <obs-module.h>
#include <util/platform.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <random>
#include <string>
#include <vector>

struct phone_cam_data {
	obs_source_t *source = nullptr;
	std::unique_ptr<UsbAoaSession> usb;
	std::unique_ptr<VideoDecoder> decoder;

	std::mutex frame_mutex;
	DecodedFrame frame;
	gs_texture_t *texture = nullptr;
	uint32_t tex_w = 0;
	uint32_t tex_h = 0;

	std::mutex audio_mutex;
	std::deque<std::vector<uint8_t>> audio_chunks; // PCM s16le mono/stereo interleaved
	uint32_t audio_sample_rate = 48000;
	uint32_t audio_channels = 1;

	std::atomic<bool> active{false};
	std::atomic<uint32_t> width{1920};
	std::atomic<uint32_t> height{1080};
	std::string status = "Disconnected";
	std::mutex status_mutex;
	uint32_t session_id = 0;
	uint16_t msg_id = 1;

	int preset = OPCM_PRESET_NATIVE_MAX;
	int bitrate_kbps = 50000;
	bool auto_start = true;
	bool torch = false;
	bool enable_audio = true;

	uint64_t last_frame_ns = 0;
	int decode_fail_streak = 0;
};

static void set_status(phone_cam_data *ctx, const std::string &s)
{
	std::lock_guard<std::mutex> lock(ctx->status_mutex);
	ctx->status = s;
}

static std::string get_status(phone_cam_data *ctx)
{
	std::lock_guard<std::mutex> lock(ctx->status_mutex);
	return ctx->status;
}

static const char *phone_cam_get_name(void *)
{
	return obs_module_text("PhoneCam.Name");
}

static void send_control(phone_cam_data *ctx,
			 const std::vector<std::pair<uint16_t, std::vector<uint8_t>>> &tlvs)
{
	if (!ctx->usb || !ctx->usb->connected())
		return;
	auto payload = opcm_build_control(ctx->msg_id++, tlvs);
	ctx->usb->send_packet(OPCM_PKT_CONTROL, payload.data(), payload.size());
}

static void send_start_control(phone_cam_data *ctx)
{
	std::vector<std::pair<uint16_t, std::vector<uint8_t>>> tlvs;
	tlvs.push_back({OPCM_CTRL_PRESET, opcm_u8((uint8_t)ctx->preset)});
	tlvs.push_back({OPCM_CTRL_BITRATE_KBPS, opcm_u32((uint32_t)ctx->bitrate_kbps)});
	tlvs.push_back({OPCM_CTRL_TORCH, opcm_u8(ctx->torch ? 1 : 0)});
	tlvs.push_back({OPCM_CTRL_START_STREAM, {}});
	send_control(ctx, tlvs);
	set_status(ctx, "Streaming requested…");
}

static void on_usb_packet(phone_cam_data *ctx, uint16_t type, uint32_t flags, int64_t pts_us,
			  uint16_t codec, const uint8_t *payload, size_t size)
{
	switch (type) {
	case OPCM_PKT_HELLO: {
		auto ack = opcm_build_hello_ack(ctx->session_id, OPCM_CODEC_HEVC);
		ctx->usb->send_packet(OPCM_PKT_HELLO_ACK, ack.data(), ack.size());
		auto hello = opcm_build_hello_pc(ctx->session_id, "OBS");
		ctx->usb->send_packet(OPCM_PKT_HELLO, hello.data(), hello.size());
		set_status(ctx, "Handshake");
		if (ctx->auto_start)
			send_start_control(ctx);
		break;
	}
	case OPCM_PKT_HELLO_ACK:
		set_status(ctx, "Connected");
		if (ctx->auto_start)
			send_start_control(ctx);
		break;
	case OPCM_PKT_STATS:
		if (size >= sizeof(OpcmStatsPayload)) {
			OpcmStatsPayload stats{};
			memcpy(&stats, payload, sizeof(stats));
			if (stats.width && stats.height) {
				ctx->width = stats.width;
				ctx->height = stats.height;
			}
			char buf[160];
			snprintf(buf, sizeof(buf),
				 "Streaming %ux%u @ %.1f fps · %u kbps · drops %u", stats.width,
				 stats.height, stats.fps_x100 / 100.f, stats.bitrate_kbps_avg,
				 stats.dropped_frames);
			set_status(ctx, buf);
		}
		break;
	case OPCM_PKT_VIDEO: {
		bool key = (flags & OPCM_FLAG_KEYFRAME) != 0;
		if (!ctx->decoder->configure(codec, ctx->width.load(), ctx->height.load())) {
			set_status(ctx, "Error — decoder configure failed (need FFmpeg DLLs)");
			break;
		}
		if (ctx->decoder->decode(payload, size, key, pts_us)) {
			DecodedFrame f;
			if (ctx->decoder->take_frame(f)) {
				std::lock_guard<std::mutex> lock(ctx->frame_mutex);
				/* Keep only newest frame (drop old = lower latency). */
				ctx->frame = std::move(f);
				ctx->width = ctx->frame.width;
				ctx->height = ctx->frame.height;
				ctx->last_frame_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
							     std::chrono::steady_clock::now().time_since_epoch())
							 .count();
				ctx->decode_fail_streak = 0;
			}
		} else {
			ctx->decode_fail_streak++;
			if (ctx->decode_fail_streak == 30) {
				set_status(ctx, "Waiting for keyframe — press Request keyframe");
				send_control(ctx, {{OPCM_CTRL_REQUEST_KEYFRAME, {}}});
			}
		}
		break;
	}
	case OPCM_PKT_AUDIO: {
		if (!ctx->enable_audio || size < 8)
			break;
		/* payload: u32 sample_rate, u16 channels, u16 reserved, then s16le PCM */
		uint32_t rate = 0;
		uint16_t ch = 0;
		memcpy(&rate, payload, 4);
		memcpy(&ch, payload + 4, 2);
		if (rate)
			ctx->audio_sample_rate = rate;
		if (ch)
			ctx->audio_channels = ch;
		std::vector<uint8_t> pcm(payload + 8, payload + size);
		std::lock_guard<std::mutex> lock(ctx->audio_mutex);
		ctx->audio_chunks.push_back(std::move(pcm));
		while (ctx->audio_chunks.size() > 32)
			ctx->audio_chunks.pop_front();
		break;
	}
	case OPCM_PKT_GOODBYE:
		set_status(ctx, "Peer closed");
		break;
	default:
		break;
	}
}

static void phone_cam_update(void *data, obs_data_t *settings)
{
	auto *ctx = (phone_cam_data *)data;
	ctx->preset = (int)obs_data_get_int(settings, "preset");
	ctx->bitrate_kbps = (int)obs_data_get_int(settings, "bitrate_kbps");
	ctx->auto_start = obs_data_get_bool(settings, "auto_start");
	ctx->torch = obs_data_get_bool(settings, "torch");
	ctx->enable_audio = obs_data_get_bool(settings, "enable_audio");
	if (ctx->usb && ctx->usb->connected()) {
		send_control(ctx, {{OPCM_CTRL_PRESET, opcm_u8((uint8_t)ctx->preset)},
				   {OPCM_CTRL_BITRATE_KBPS, opcm_u32((uint32_t)ctx->bitrate_kbps)},
				   {OPCM_CTRL_TORCH, opcm_u8(ctx->torch ? 1 : 0)}});
		if (ctx->auto_start)
			send_start_control(ctx);
	}
}

static void *phone_cam_create(obs_data_t *settings, obs_source_t *source)
{
	auto *ctx = new phone_cam_data();
	ctx->source = source;
	ctx->decoder = std::make_unique<VideoDecoder>();
	ctx->usb = std::make_unique<UsbAoaSession>();
	std::random_device rd;
	ctx->session_id = rd();

	phone_cam_update(ctx, settings);
	set_status(ctx, "Looking for phone…");

	ctx->usb->start([ctx](uint16_t type, uint32_t flags, int64_t pts_us, uint16_t codec,
			      const uint8_t *payload, size_t size) {
		on_usb_packet(ctx, type, flags, pts_us, codec, payload, size);
	});
	ctx->active = true;
	return ctx;
}

static void phone_cam_destroy(void *data)
{
	auto *ctx = (phone_cam_data *)data;
	ctx->active = false;
	if (ctx->usb) {
		ctx->usb->send_packet(OPCM_PKT_GOODBYE, nullptr, 0);
		ctx->usb->stop();
	}
	obs_enter_graphics();
	if (ctx->texture) {
		gs_texture_destroy(ctx->texture);
		ctx->texture = nullptr;
	}
	obs_leave_graphics();
	delete ctx;
}

static uint32_t phone_cam_width(void *data)
{
	return ((phone_cam_data *)data)->width.load();
}

static uint32_t phone_cam_height(void *data)
{
	return ((phone_cam_data *)data)->height.load();
}

static void phone_cam_video_tick(void *data, float)
{
	auto *ctx = (phone_cam_data *)data;
	if (!ctx->usb)
		return;
	if (!ctx->usb->connected()) {
		set_status(ctx, ctx->usb->status());
	}

	/* Push buffered audio into OBS */
	if (ctx->enable_audio) {
		std::deque<std::vector<uint8_t>> local;
		{
			std::lock_guard<std::mutex> lock(ctx->audio_mutex);
			local.swap(ctx->audio_chunks);
		}
		for (auto &chunk : local) {
			if (chunk.empty())
				continue;
			obs_source_audio audio = {};
			audio.speakers = ctx->audio_channels >= 2 ? SPEAKERS_STEREO : SPEAKERS_MONO;
			audio.format = AUDIO_FORMAT_16BIT;
			audio.samples_per_sec = ctx->audio_sample_rate;
			audio.frames = (uint32_t)(chunk.size() / (2 * ctx->audio_channels));
			audio.timestamp = os_gettime_ns();
			const uint8_t *data_ptr = chunk.data();
			audio.data[0] = data_ptr;
			obs_source_output_audio(ctx->source, &audio);
		}
	}
}

static void phone_cam_video_render(void *data, gs_effect_t *)
{
	auto *ctx = (phone_cam_data *)data;

	DecodedFrame local;
	{
		std::lock_guard<std::mutex> lock(ctx->frame_mutex);
		if (!ctx->frame.valid)
			return;
		local = std::move(ctx->frame);
		ctx->frame.valid = false;
	}

	obs_enter_graphics();
	if (!ctx->texture || ctx->tex_w != local.width || ctx->tex_h != local.height) {
		if (ctx->texture)
			gs_texture_destroy(ctx->texture);
		ctx->texture =
			gs_texture_create(local.width, local.height, GS_BGRA, 1, nullptr, GS_DYNAMIC);
		ctx->tex_w = local.width;
		ctx->tex_h = local.height;
	}
	if (ctx->texture && !local.bgra.empty()) {
		uint8_t *ptr;
		uint32_t linesize;
		if (gs_texture_map(ctx->texture, &ptr, &linesize)) {
			for (uint32_t y = 0; y < local.height; ++y) {
				memcpy(ptr + y * linesize, local.bgra.data() + y * local.width * 4,
				       local.width * 4);
			}
			gs_texture_unmap(ctx->texture);
		}
	}

	gs_effect_t *effect = obs_get_base_effect(OBS_EFFECT_DEFAULT);
	gs_technique_t *tech = gs_effect_get_technique(effect, "Draw");
	gs_technique_begin(tech);
	gs_technique_begin_pass(tech, 0);
	gs_effect_set_texture(gs_effect_get_param_by_name(effect, "image"), ctx->texture);
	gs_draw_sprite(ctx->texture, 0, local.width, local.height);
	gs_technique_end_pass(tech);
	gs_technique_end(tech);
	obs_leave_graphics();
}

static obs_properties_t *phone_cam_properties(void *data)
{
	auto *ctx = (phone_cam_data *)data;
	obs_properties_t *props = obs_properties_create();

	obs_property_t *preset =
		obs_properties_add_list(props, "preset", obs_module_text("PhoneCam.Preset"),
					OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(preset, "Native Max", OPCM_PRESET_NATIVE_MAX);
	obs_property_list_add_int(preset, "High", OPCM_PRESET_HIGH);
	obs_property_list_add_int(preset, "Balanced", OPCM_PRESET_BALANCED);
	obs_property_list_add_int(preset, "Low Latency", OPCM_PRESET_LOW_LATENCY);

	obs_properties_add_int(props, "bitrate_kbps", obs_module_text("PhoneCam.Bitrate"), 1000,
			       80000, 500);
	obs_properties_add_bool(props, "auto_start", obs_module_text("PhoneCam.AutoStart"));
	obs_properties_add_bool(props, "torch", obs_module_text("PhoneCam.Torch"));
	obs_properties_add_bool(props, "enable_audio", obs_module_text("PhoneCam.Audio"));

	obs_properties_add_button(
		props, "request_keyframe", obs_module_text("PhoneCam.RequestKeyframe"),
		[](obs_properties_t *, obs_property_t *, void *d) -> bool {
			auto *c = (phone_cam_data *)d;
			send_control(c, {{OPCM_CTRL_REQUEST_KEYFRAME, {}}});
			set_status(c, "Keyframe requested");
			return true;
		});

	obs_properties_add_button(
		props, "reconnect", obs_module_text("PhoneCam.Reconnect"),
		[](obs_properties_t *, obs_property_t *, void *d) -> bool {
			auto *c = (phone_cam_data *)d;
			if (!c->usb)
				return false;
			c->usb->stop();
			set_status(c, "Reconnecting…");
			c->decoder->reset();
			c->usb->start([c](uint16_t type, uint32_t flags, int64_t pts_us, uint16_t codec,
					  const uint8_t *payload, size_t size) {
				on_usb_packet(c, type, flags, pts_us, codec, payload, size);
			});
			return true;
		});

	obs_properties_add_text(props, "status_ro", obs_module_text("PhoneCam.Status"),
				OBS_TEXT_INFO);
	if (ctx) {
		obs_data_t *s = obs_source_get_settings(ctx->source);
		obs_data_set_string(s, "status_ro", get_status(ctx).c_str());
		obs_data_release(s);
	}
	obs_properties_set_param(props, data, nullptr);
	return props;
}

static void phone_cam_defaults(obs_data_t *settings)
{
	obs_data_set_default_int(settings, "preset", OPCM_PRESET_NATIVE_MAX);
	obs_data_set_default_int(settings, "bitrate_kbps", 50000);
	obs_data_set_default_bool(settings, "auto_start", true);
	obs_data_set_default_bool(settings, "torch", false);
	obs_data_set_default_bool(settings, "enable_audio", true);
}

void register_phone_cam_source()
{
	obs_source_info info = {};
	info.id = "phone_cam_usb";
	info.type = OBS_SOURCE_TYPE_INPUT;
	info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_CUSTOM_DRAW |
			    OBS_SOURCE_DO_NOT_DUPLICATE;
	info.get_name = phone_cam_get_name;
	info.create = phone_cam_create;
	info.destroy = phone_cam_destroy;
	info.update = phone_cam_update;
	info.video_tick = phone_cam_video_tick;
	info.video_render = phone_cam_video_render;
	info.get_width = phone_cam_width;
	info.get_height = phone_cam_height;
	info.get_properties = phone_cam_properties;
	info.get_defaults = phone_cam_defaults;
	info.icon_type = OBS_ICON_TYPE_CAMERA;
	obs_register_source(&info);
}
