#include <obs-module.h>
#include "phone-cam-source.hpp"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("obs-phone-cam", "en-US")

MODULE_EXPORT const char *obs_module_description(void)
{
	return "High-quality Android phone camera over USB (AOA + OPCM protocol).";
}

bool obs_module_load(void)
{
	register_phone_cam_source();
	blog(LOG_INFO, "[obs-phone-cam] loaded");
	return true;
}

void obs_module_unload(void)
{
	blog(LOG_INFO, "[obs-phone-cam] unloaded");
}
