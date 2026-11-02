cmake_minimum_required(VERSION 3.16)
project(obs-web-device VERSION 1.0.0)
# Product name: NexoCam

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OBS_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../deps/obs-studio" CACHE PATH "Path to obs-studio source root")
set(OBS_BUILD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../deps/obs-build-stub" CACHE PATH "Path to obs-studio build root")

add_library(obs-web-device MODULE
  src/plugin-main.cpp
  src/web-device-source.cpp
  src/net-server.cpp
  src/cloud-client.cpp
  src/jpeg-decode.cpp
)

target_include_directories(obs-web-device PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/src
  ${OBS_SOURCE_DIR}/libobs
  ${OBS_BUILD_DIR}/config
)

if(TARGET libobs)
  target_link_libraries(obs-web-device PRIVATE libobs)
elseif(OBS_BUILD_DIR)
  target_link_directories(obs-web-device PRIVATE
    ${OBS_BUILD_DIR}/libobs/Release
    ${OBS_BUILD_DIR}/libobs
  )
  target_link_libraries(obs-web-device PRIVATE obs)
endif()

if(WIN32)
  target_compile_definitions(obs-web-device PRIVATE UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)
  target_link_libraries(obs-web-device PRIVATE ws2_32 crypt32 secur32 bcrypt iphlpapi windowscodecs ole32 winhttp)
endif()

set_target_properties(obs-web-device PROPERTIES
  PREFIX ""
  OUTPUT_NAME "nexocam"
)

add_custom_command(TARGET obs-web-device POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_directory
    ${CMAKE_CURRENT_SOURCE_DIR}/web
    $<TARGET_FILE_DIR:obs-web-device>/web
)

add_executable(obs-web-device-serve
  tools/serve-main.cpp
  src/net-server.cpp
  src/jpeg-decode.cpp
)
target_include_directories(obs-web-device-serve PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_definitions(obs-web-device-serve PRIVATE WEB_DEVICE_STANDALONE UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)
if(WIN32)
  target_link_libraries(obs-web-device-serve PRIVATE ws2_32 crypt32 secur32 bcrypt iphlpapi windowscodecs ole32 winhttp)
endif()

install(TARGETS obs-web-device LIBRARY DESTINATION obs-plugins/64bit)
install(DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/data/ DESTINATION data/obs-plugins/nexocam)
install(DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/web/ DESTINATION data/obs-plugins/nexocam/web)
