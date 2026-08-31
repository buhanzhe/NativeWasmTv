#! /usr/bin/env bash

# NativeWasmTv's small ijkplayer/FFmpeg feature profile.
# Copy this file to ijkplayer/config/module.sh before compiling FFmpeg 3.4 from
# bilibili/ijkplayer k0.8.8 (Bilibili/FFmpeg 2902e33f6e59). The upstream lite
# profile remains the baseline so
# the custom libraries only add the protocols and decoder needed by the app.

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$MODULE_DIR/module-lite.sh"

# MPEG-1 Layer II audio is common in cameras, IPTV gateways and MPEG-TS feeds.
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=mp2"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-parser=mpegaudio"

# RTSP needs the SDP/RTP demux path. Both TCP interleaving and UDP transport are
# retained; the application chooses the transport through the rtsp_transport
# format option and defaults to TCP.
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=rtsp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=sdp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=rtp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-protocol=rtp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-protocol=tcp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-protocol=udp"
