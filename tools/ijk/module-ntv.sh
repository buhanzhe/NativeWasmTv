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

# Dolby bitstreams must remain decodable on speakers/legacy devices when there
# is no compatible HDMI output. Keep the demuxers too (raw HLS .ac3/.ec3).
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=ac3 --enable-decoder=eac3"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=truehd --enable-decoder=mlp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-parser=ac3 --enable-parser=mlp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=ac3 --enable-demuxer=eac3"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=truehd --enable-demuxer=mlp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=matroska"

# IJK still opens an AVCodecContext before selecting MediaCodec, so MPEG-2
# needs its decoder compiled even when hardware decoding is preferred.
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=mpeg2video --enable-parser=mpegvideo"

# Text subtitles exposed by the media controller must actually be decodable.
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=movtext --enable-decoder=ass --enable-decoder=ssa --enable-decoder=subrip --enable-decoder=srt --enable-decoder=webvtt"

# RTSP needs the SDP/RTP demux path. Both TCP interleaving and UDP transport are
# retained; the application chooses the transport through the rtsp_transport
# format option and defaults to TCP.
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=rtsp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=sdp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-demuxer=rtp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-protocol=rtp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-protocol=tcp"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-protocol=udp"
