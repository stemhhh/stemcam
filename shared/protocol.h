#pragma once
/* OBS Phone Cam USB protocol v1 — shared C header (OBS plugin + reference). */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OPCM_MAGIC           0x4F50434Du
#define OPCM_VERSION         0x0001u
#define OPCM_HEADER_SIZE     32u
#define OPCM_MAX_PAYLOAD     (4u * 1024u * 1024u)

enum OpcmPacketType {
	OPCM_PKT_HELLO         = 1,
	OPCM_PKT_HELLO_ACK     = 2,
	OPCM_PKT_VIDEO         = 3,
	OPCM_PKT_CONTROL       = 4,
	OPCM_PKT_CONTROL_ACK   = 5,
	OPCM_PKT_STATS         = 6,
	OPCM_PKT_GOODBYE       = 7,
	OPCM_PKT_PING          = 8,
	OPCM_PKT_PONG          = 9,
	OPCM_PKT_AUDIO         = 10,
};

enum OpcmCodecId {
	OPCM_CODEC_NONE  = 0,
	OPCM_CODEC_H264  = 1,
	OPCM_CODEC_HEVC  = 2,
	OPCM_CODEC_MJPEG = 3,
};

enum OpcmVideoFlags {
	OPCM_FLAG_KEYFRAME = 1u << 0,
	OPCM_FLAG_CONFIG   = 1u << 1,
	OPCM_FLAG_EOS      = 1u << 2,
};

enum OpcmRole {
	OPCM_ROLE_PHONE = 1,
	OPCM_ROLE_PC    = 2,
};

enum OpcmCapability {
	OPCM_CAP_HEVC            = 1u << 0,
	OPCM_CAP_H264            = 1u << 1,
	OPCM_CAP_MJPEG           = 1u << 2,
	OPCM_CAP_MANUAL_CONTROLS = 1u << 3,
};

enum OpcmControlKey {
	OPCM_CTRL_PRESET            = 1,
	OPCM_CTRL_WIDTH             = 2,
	OPCM_CTRL_HEIGHT            = 3,
	OPCM_CTRL_FPS_X100          = 4,
	OPCM_CTRL_BITRATE_KBPS      = 5,
	OPCM_CTRL_CODEC             = 6,
	OPCM_CTRL_KEYFRAME_INTERVAL = 7,
	OPCM_CTRL_EXPOSURE_NS       = 8,
	OPCM_CTRL_ISO               = 9,
	OPCM_CTRL_WB_MODE           = 10,
	OPCM_CTRL_WB_TEMP_K         = 11,
	OPCM_CTRL_FOCUS_MODE        = 12,
	OPCM_CTRL_FOCUS_DISTANCE    = 13,
	OPCM_CTRL_ZOOM_RATIO_X100   = 14,
	OPCM_CTRL_TORCH             = 15,
	OPCM_CTRL_STABILIZATION     = 16,
	OPCM_CTRL_REQUEST_KEYFRAME  = 17,
	OPCM_CTRL_CAMERA_FACING     = 18,
	OPCM_CTRL_START_STREAM      = 19,
	OPCM_CTRL_STOP_STREAM       = 20,
};

enum OpcmPreset {
	OPCM_PRESET_NATIVE_MAX  = 0,
	OPCM_PRESET_HIGH        = 1,
	OPCM_PRESET_BALANCED    = 2,
	OPCM_PRESET_LOW_LATENCY = 3,
};

#pragma pack(push, 1)
typedef struct OpcmHeader {
	uint32_t magic;
	uint16_t version;
	uint16_t packet_type;
	uint32_t flags;
	uint32_t sequence;
	int64_t  pts_us;
	uint32_t payload_size;
	uint16_t codec;
	uint16_t reserved;
} OpcmHeader;

typedef struct OpcmHelloPayload {
	uint16_t role;
	uint16_t capabilities;
	uint32_t max_width;
	uint32_t max_height;
	uint32_t max_fps_x100;
	uint32_t max_bitrate_kbps;
	uint32_t usb_version;
	uint8_t  device_name[24];
	uint32_t session_id;
} OpcmHelloPayload;

typedef struct OpcmHelloAckPayload {
	uint32_t accepted;
	uint32_t session_id;
	uint32_t preferred_codec;
	uint32_t reserved;
} OpcmHelloAckPayload;

typedef struct OpcmStatsPayload {
	uint32_t width;
	uint32_t height;
	uint32_t fps_x100;
	uint32_t bitrate_kbps_avg;
	uint32_t frames_sent;
	uint32_t dropped_frames;
	uint64_t pts_us;
	uint32_t encode_latency_ms;
	uint32_t thermal_hint;
} OpcmStatsPayload;
#pragma pack(pop)

#define OPCM_AOA_MANUFACTURER "OBSPhoneCam"
#define OPCM_AOA_MODEL        "PhoneCamUSB"
#define OPCM_AOA_DESCRIPTION  "OBS Phone Cam USB"
#define OPCM_AOA_VERSION      "1.0"
#define OPCM_AOA_URI          "https://github.com/obsphonecam"
#define OPCM_AOA_SERIAL       "OPCM001"

#ifdef __cplusplus
}
#endif
