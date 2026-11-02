#pragma once

#include <cstdint>
#include <vector>

/* Decode a JPEG buffer into tightly packed BGRA8. Returns false on
   truncated or oversized images (limit 3840x2160). */
bool jpeg_to_bgra(const uint8_t *jpeg, size_t size, std::vector<uint8_t> &bgra, uint32_t &width,
		  uint32_t &height);
