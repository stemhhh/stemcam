cmake_minimum_required(VERSION 3.16)
project(obs-phone-cam VERSION 1.0.0)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OBS_SOURCE_DIR "" CACHE PATH "Path to obs-studio source root")
set(OBS_BUILD_DIR "" CACHE PATH "Path to obs-studio build root")
set(FFMPEG_ROOT "" CACHE PATH "Path to shared FFmpeg (include/, lib/, bin/)")

if(NOT OBS_SOURCE_DIR)
  message(WARNING "OBS_SOURCE_DIR not set. Configure with -DOBS_SOURCE_DIR=...")
endif()

add_library(obs-phone-cam MODULE
  src/plugin-main.cpp
  src/phone-cam-source.cpp
  src/usb-aoa.cpp
  src/protocol-io.cpp
  src/decoder.cpp
)

target_include_directories(obs-phone-cam PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/src
  ${CMAKE_CURRENT_SOURCE_DIR}/../shared
  ${OBS_SOURCE_DIR}/libobs
  ${OBS_BUILD_DIR}/config
)

if(TARGET libobs)
  target_link_libraries(obs-phone-cam PRIVATE libobs)
elseif(OBS_BUILD_DIR)
  target_link_directories(obs-phone-cam PRIVATE
    ${OBS_BUILD_DIR}/libobs/Release
    ${OBS_BUILD_DIR}/libobs
  )
  target_link_libraries(obs-phone-cam PRIVATE obs)
endif()

if(WIN32)
  target_link_libraries(obs-phone-cam PRIVATE setupapi winusb)
  target_compile_definitions(obs-phone-cam PRIVATE UNICODE _UNICODE)
endif()

# FFmpeg is mandatory for distribution builds.
if(FFMPEG_ROOT)
  set(FFMPEG_INCLUDE_DIRS "${FFMPEG_ROOT}/include" CACHE PATH "" FORCE)
  set(FFMPEG_LIBRARY_DIRS "${FFMPEG_ROOT}/lib" CACHE PATH "" FORCE)
endif()

if(NOT FFMPEG_INCLUDE_DIRS AND DEFINED ENV{FFMPEG_ROOT})
  set(FFMPEG_INCLUDE_DIRS "$ENV{FFMPEG_ROOT}/include")
  set(FFMPEG_LIBRARY_DIRS "$ENV{FFMPEG_ROOT}/lib")
endif()

find_path(AVCODEC_INCLUDE_DIR libavcodec/avcodec.h
  HINTS ${FFMPEG_INCLUDE_DIRS} ${FFMPEG_ROOT}/include)
find_library(AVCODEC_LIBRARY avcodec HINTS ${FFMPEG_LIBRARY_DIRS} ${FFMPEG_ROOT}/lib)
find_library(AVUTIL_LIBRARY avutil HINTS ${FFMPEG_LIBRARY_DIRS} ${FFMPEG_ROOT}/lib)
find_library(SWSCALE_LIBRARY swscale HINTS ${FFMPEG_LIBRARY_DIRS} ${FFMPEG_ROOT}/lib)

if(NOT AVCODEC_INCLUDE_DIR OR NOT AVCODEC_LIBRARY OR NOT AVUTIL_LIBRARY OR NOT SWSCALE_LIBRARY)
  message(FATAL_ERROR
    "FFmpeg is required (avcodec/avutil/swscale). Set -DFFMPEG_ROOT=... to a shared FFmpeg tree.")
endif()

target_include_directories(obs-phone-cam PRIVATE ${AVCODEC_INCLUDE_DIR})
target_link_directories(obs-phone-cam PRIVATE ${FFMPEG_LIBRARY_DIRS} ${FFMPEG_ROOT}/lib)
target_link_libraries(obs-phone-cam PRIVATE ${AVCODEC_LIBRARY} ${AVUTIL_LIBRARY} ${SWSCALE_LIBRARY})
target_compile_definitions(obs-phone-cam PRIVATE OPCM_HAVE_FFMPEG=1)

set_target_properties(obs-phone-cam PROPERTIES
  PREFIX ""
  OUTPUT_NAME "obs-phone-cam"
)

install(TARGETS obs-phone-cam LIBRARY DESTINATION obs-plugins/64bit)
install(DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/data/ DESTINATION data/obs-plugins/obs-phone-cam)
