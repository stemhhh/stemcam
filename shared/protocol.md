# OBS Phone Cam — USB Protocol v1

Binary framing over USB bulk endpoints (AOA accessory mode preferred; CDC/bulk fallback).

Endianness: **little-endian** for all multi-byte integers.

## Magic / Version

| Field   | Value        |
|---------|--------------|
| Magic   | `0x4F50434D` (`OPCM`) |
| Version | `0x0001`     |

## Packet layout

Every packet starts with a fixed **32-byte header**, followed by `payload_size` bytes.

```
Offset  Size  Type     Name
0       4     u32      magic          // 0x4F50434D
4       2     u16      version        // 0x0001
6       2     u16      packet_type    // see PacketType
8       4     u32      flags          // bitfield
12      4     u32      sequence       // monotonic per direction
16      8     i64      pts_us         // presentation timestamp (µs), 0 if N/A
24      4     u32      payload_size   // bytes after header
28      2     u16      codec          // CodecId (meaningful for VIDEO)
30      2     u16      reserved       // 0
32      N     bytes    payload
```

Maximum payload: **4 MiB** (reject larger; reconnect).

## PacketType

| Id   | Name           | Direction        | Payload |
|------|----------------|------------------|---------|
| 1    | HELLO          | both             | HelloPayload |
| 2    | HELLO_ACK      | both             | HelloAckPayload |
| 3    | VIDEO          | phone → PC       | encoded access unit |
| 4    | CONTROL        | PC ↔ phone       | ControlMessage (TLV) |
| 5    | CONTROL_ACK    | phone ↔ PC       | ControlAck |
| 6    | STATS          | phone → PC       | StatsPayload |
| 7    | GOODBYE        | both             | optional reason UTF-8 |
| 8    | PING           | both             | 8-byte opaque |
| 9    | PONG           | both             | echo opaque |
| 10   | AUDIO          | phone → PC       | AudioPayload + PCM s16le |

## CodecId

| Id | Codec |
|----|-------|
| 0  | NONE |
| 1  | H264 (AVC Annex-B AU) |
| 2  | HEVC (HEVC Annex-B AU) |
| 3  | MJPEG (JPEG SOI…EOI) |

## Flags (VIDEO)

| Bit | Name        | Meaning |
|-----|-------------|---------|
| 0   | KEYFRAME    | IDR / key frame |
| 1   | CONFIG      | Contains SPS/PPS or VPS/SPS/PPS |
| 2   | EOS         | End of stream |

## HelloPayload (48 bytes)

```
u16  role              // 1=phone, 2=pc
u16  capabilities      // bit0 HEVC, bit1 H264, bit2 MJPEG, bit3 manual controls
u32  max_width
u32  max_height
u32  max_fps_x100      // e.g. 6000 = 60.00 fps
u32  max_bitrate_kbps
u32  usb_version       // 0x0200 / 0x0300 hint
u8   device_name[24]   // UTF-8 NUL-padded
u32  session_id        // random
```

## HelloAckPayload (16 bytes)

```
u32  accepted          // 1 ok, 0 reject
u32  session_id        // echo
u32  preferred_codec   // CodecId
u32  reserved
```

## ControlMessage (TLV stream)

```
u16  msg_id            // correlator
u16  count             // number of TLVs
repeated:
  u16  key
  u16  value_len
  u8   value[value_len]
```

### Control keys

| Key | Name              | Value type        | Notes |
|-----|-------------------|-------------------|-------|
| 1   | PRESET            | u8                | 0 NativeMax, 1 High, 2 Balanced, 3 LowLatency |
| 2   | WIDTH             | u32               | capture width |
| 3   | HEIGHT            | u32               | capture height |
| 4   | FPS_X100          | u32               | e.g. 3000 |
| 5   | BITRATE_KBPS      | u32               | video bitrate |
| 6   | CODEC             | u16               | CodecId |
| 7   | KEYFRAME_INTERVAL | u32               | frames between IDR |
| 8   | EXPOSURE_NS       | i64               | sensor exposure; 0 = auto |
| 9   | ISO               | i32               | sensitivity; 0 = auto |
| 10  | WB_MODE           | u8                | 0 auto, 1 manual, 2 daylight, 3 cloudy, 4 tungsten, 5 fluorescent |
| 11  | WB_TEMP_K         | u32               | Kelvin when manual |
| 12  | FOCUS_MODE        | u8                | 0 auto continuous, 1 auto once, 2 manual, 3 infinity |
| 13  | FOCUS_DISTANCE    | f32               | diopters when manual |
| 14  | ZOOM_RATIO_X100   | u32               | 100 = 1.0x |
| 15  | TORCH             | u8                | 0 off, 1 on |
| 16  | STABILIZATION     | u8                | 0 off, 1 on if supported |
| 17  | REQUEST_KEYFRAME  | empty             | force IDR |
| 18  | CAMERA_FACING     | u8                | 0 back, 1 front |
| 19  | START_STREAM      | empty             | begin encode+send |
| 20  | STOP_STREAM       | empty             | stop encode |

## ControlAck

```
u16  msg_id
u16  status            // 0 ok, 1 unsupported, 2 invalid, 3 busy
u32  applied_flags     // bitmask of keys successfully applied
```

## AudioPayload header (8 bytes) + PCM

```
u32  sample_rate       // e.g. 48000
u16  channels          // 1 or 2
u16  reserved          // 0
u8   pcm_s16le[...]    // interleaved little-endian samples
```

## StatsPayload (40 bytes)

```
u32  width
u32  height
u32  fps_x100
u32  bitrate_kbps_avg
u32  frames_sent
u32  dropped_frames
u64  pts_us
u32  encode_latency_ms
u32  thermal_hint      // 0 none … 3 severe (best effort)
```

## Handshake sequence

1. Phone opens accessory/bulk; both sides may send `HELLO`.
2. Peer replies `HELLO_ACK` with `accepted=1` and `session_id`.
3. PC (or phone UI) sends `CONTROL` with capture params + `START_STREAM`.
4. Phone sends continuous `VIDEO` packets; optional `STATS` every ~1s.
5. `PING`/`PONG` every 2s; miss 3 → reconnect.
6. `GOODBYE` or USB detach ends session; both sides reset sequence to 0.

## USB identity (AOA)

- Manufacturer: `OBSPhoneCam`
- Model: `PhoneCamUSB`
- Version: `1.0`
- Serial: device-specific

When AOA is unavailable, fall back to USB host bulk using the same framing over an app-owned connection (adb reverse / custom host driver is out of v1 scope; AOA is the primary path).

## Annex-B note

VIDEO payloads are **Annex-B** (start codes `00 00 00 01`). Config NALUs may be prepended on KEYFRAME+CONFIG packets.
