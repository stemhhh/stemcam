#include "jpeg-decode.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <wincodec.h>

#include <cstdint>

namespace {

struct Com {
	IWICImagingFactory *factory = nullptr;
	IWICStream *stream = nullptr;
	IWICBitmapDecoder *decoder = nullptr;
	IWICBitmapFrameDecode *frame = nullptr;
	IWICFormatConverter *conv = nullptr;
	~Com()
	{
		if (conv)
			conv->Release();
		if (frame)
			frame->Release();
		if (decoder)
			decoder->Release();
		if (stream)
			stream->Release();
		if (factory)
			factory->Release();
	}
};

void ensure_com()
{
	thread_local bool ready = false;
	if (ready)
		return;
	HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	if (hr == S_OK || hr == S_FALSE || hr == RPC_E_CHANGED_MODE)
		ready = true;
}

} /* namespace */

bool jpeg_to_bgra(const uint8_t *jpeg, size_t size, std::vector<uint8_t> &bgra, uint32_t &width,
		  uint32_t &height)
{
	width = 0;
	height = 0;
	bgra.clear();
	if (!jpeg || size < 4 || size > 8u * 1024u * 1024u)
		return false;
	if (jpeg[0] != 0xFF || jpeg[1] != 0xD8)
		return false;

	ensure_com();

	Com com;
	HRESULT hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
				      IID_PPV_ARGS(&com.factory));
	if (FAILED(hr) || !com.factory)
		return false;
	if (FAILED(com.factory->CreateStream(&com.stream)) || !com.stream)
		return false;
	if (FAILED(com.stream->InitializeFromMemory(const_cast<BYTE *>(jpeg), (DWORD)size)))
		return false;
	hr = com.factory->CreateDecoderFromStream(com.stream, nullptr, WICDecodeMetadataCacheOnLoad,
						  &com.decoder);
	if (FAILED(hr) || !com.decoder)
		return false;
	if (FAILED(com.decoder->GetFrame(0, &com.frame)) || !com.frame)
		return false;

	UINT w = 0, h = 0;
	if (FAILED(com.frame->GetSize(&w, &h)))
		return false;
	if (w == 0 || h == 0 || w > 3840 || h > 2160)
		return false;

	if (FAILED(com.factory->CreateFormatConverter(&com.conv)) || !com.conv)
		return false;
	hr = com.conv->Initialize(com.frame, GUID_WICPixelFormat32bppBGRA, WICBitmapDitherTypeNone, nullptr,
				  0.0, WICBitmapPaletteTypeCustom);
	if (FAILED(hr))
		return false;

	const uint64_t bytes = (uint64_t)w * (uint64_t)h * 4ull;
	bgra.resize((size_t)bytes);
	hr = com.conv->CopyPixels(nullptr, w * 4, (UINT)bgra.size(), bgra.data());
	if (FAILED(hr)) {
		bgra.clear();
		return false;
	}
	width = w;
	height = h;
	return true;
}
