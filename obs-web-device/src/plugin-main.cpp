#include <obs-module.h>

#include "net-server.hpp"
#include "web-device-source.hpp"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("nexocam", "en-US")

MODULE_EXPORT const char *obs_module_description(void)
{
	return "NexoCam — use any camera or microphone from a browser in OBS.";
}

bool obs_module_load(void)
{
	register_web_device_source();
	blog(LOG_INFO, "[nexocam] loaded");
	return true;
}

void obs_module_unload(void)
{
	web_device_server_stop();
	blog(LOG_INFO, "[nexocam] unloaded");
}
