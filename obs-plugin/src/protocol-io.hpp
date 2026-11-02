#pragma once
#include "protocol.h"
#include <cstdint>
#include <string>
#include <vector>

struct OpcmPacket {
	uint16_t type = 0;
	uint32_t flags = 0;
	uint32_t sequence = 0;
	int64_t pts_us = 0;
	uint16_t codec = 0;
	std::vector<uint8_t> payload;
};

bool opcm_write_header(std::vector<uint8_t> &out, uint16_t type, uint32_t flags,
		       uint32_t sequence, int64_t pts_us, uint32_t payload_size,
		       uint16_t codec);

bool opcm_parse_header(const uint8_t *data, size_t len, OpcmHeader &hdr);

std::vector<uint8_t> opcm_build_hello_pc(uint32_t session_id, const char *name);
std::vector<uint8_t> opcm_build_hello_ack(uint32_t session_id, uint32_t preferred_codec);
std::vector<uint8_t> opcm_build_control(uint16_t msg_id,
					const std::vector<std::pair<uint16_t, std::vector<uint8_t>>> &tlvs);

std::vector<uint8_t> opcm_u8(uint8_t v);
std::vector<uint8_t> opcm_u16(uint16_t v);
std::vector<uint8_t> opcm_u32(uint32_t v);
