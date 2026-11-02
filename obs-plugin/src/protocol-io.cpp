#include "protocol-io.hpp"
#include <cstring>

static void put_u16(std::vector<uint8_t> &o, uint16_t v)
{
	o.push_back((uint8_t)(v & 0xff));
	o.push_back((uint8_t)((v >> 8) & 0xff));
}

static void put_u32(std::vector<uint8_t> &o, uint32_t v)
{
	o.push_back((uint8_t)(v & 0xff));
	o.push_back((uint8_t)((v >> 8) & 0xff));
	o.push_back((uint8_t)((v >> 16) & 0xff));
	o.push_back((uint8_t)((v >> 24) & 0xff));
}

static void put_i64(std::vector<uint8_t> &o, int64_t v)
{
	for (int i = 0; i < 8; i++)
		o.push_back((uint8_t)((v >> (8 * i)) & 0xff));
}

bool opcm_write_header(std::vector<uint8_t> &out, uint16_t type, uint32_t flags,
		       uint32_t sequence, int64_t pts_us, uint32_t payload_size,
		       uint16_t codec)
{
	out.clear();
	out.reserve(OPCM_HEADER_SIZE + payload_size);
	put_u32(out, OPCM_MAGIC);
	put_u16(out, OPCM_VERSION);
	put_u16(out, type);
	put_u32(out, flags);
	put_u32(out, sequence);
	put_i64(out, pts_us);
	put_u32(out, payload_size);
	put_u16(out, codec);
	put_u16(out, 0);
	return true;
}

bool opcm_parse_header(const uint8_t *data, size_t len, OpcmHeader &hdr)
{
	if (!data || len < OPCM_HEADER_SIZE)
		return false;
	std::memcpy(&hdr, data, sizeof(OpcmHeader));
	if (hdr.magic != OPCM_MAGIC || hdr.version != OPCM_VERSION)
		return false;
	if (hdr.payload_size > OPCM_MAX_PAYLOAD)
		return false;
	return true;
}

std::vector<uint8_t> opcm_u8(uint8_t v) { return {v}; }
std::vector<uint8_t> opcm_u16(uint16_t v)
{
	std::vector<uint8_t> o;
	put_u16(o, v);
	return o;
}
std::vector<uint8_t> opcm_u32(uint32_t v)
{
	std::vector<uint8_t> o;
	put_u32(o, v);
	return o;
}

std::vector<uint8_t> opcm_build_hello_pc(uint32_t session_id, const char *name)
{
	std::vector<uint8_t> p;
	put_u16(p, OPCM_ROLE_PC);
	put_u16(p, OPCM_CAP_HEVC | OPCM_CAP_H264 | OPCM_CAP_MANUAL_CONTROLS);
	put_u32(p, 3840);
	put_u32(p, 2160);
	put_u32(p, 6000);
	put_u32(p, 80000);
	put_u32(p, 0x0300);
	uint8_t name_field[24] = {};
	if (name) {
		size_t n = strlen(name);
		if (n > 23)
			n = 23;
		memcpy(name_field, name, n);
	}
	p.insert(p.end(), name_field, name_field + 24);
	put_u32(p, session_id);
	return p;
}

std::vector<uint8_t> opcm_build_hello_ack(uint32_t session_id, uint32_t preferred_codec)
{
	std::vector<uint8_t> p;
	put_u32(p, 1);
	put_u32(p, session_id);
	put_u32(p, preferred_codec);
	put_u32(p, 0);
	return p;
}

std::vector<uint8_t> opcm_build_control(
	uint16_t msg_id, const std::vector<std::pair<uint16_t, std::vector<uint8_t>>> &tlvs)
{
	std::vector<uint8_t> p;
	put_u16(p, msg_id);
	put_u16(p, (uint16_t)tlvs.size());
	for (auto &t : tlvs) {
		put_u16(p, t.first);
		put_u16(p, (uint16_t)t.second.size());
		p.insert(p.end(), t.second.begin(), t.second.end());
	}
	return p;
}
