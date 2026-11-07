#!/bin/zsh
# Builds libtvtownmp3.so from LAME 3.100 for the TV. Usage: build.sh <path to the unpacked lame-3.100>
set -e
L=$1; HERE=${0:A:h}; OUT=$HERE/../../app/src/main/jniLibs
BIN=$(echo $HOME/Library/Android/sdk/ndk/*/toolchains/llvm/prebuilt/darwin-x86_64/bin)
SRC=($L/libmp3lame/*.c $HERE/mp3jni.c)
for pair in armeabi-v7a:armv7a-linux-androideabi28 arm64-v8a:aarch64-linux-android28; do
  abi=${pair%%:*}; cc=$BIN/${pair##*:}-clang
  mkdir -p $OUT/$abi
  $cc -shared -fPIC -O2 -DNDEBUG -DSTDC_HEADERS -DHAVE_STDINT_H -Dieee754_float32_t=float -DTAKEHIRO_IEEE754_HACK=0 \
      -I$L/include -I$L/libmp3lame -w -fvisibility=default -Wl,-z,max-page-size=16384 -Wl,--gc-sections -ffunction-sections -fdata-sections \
      -o $OUT/$abi/libtvtownmp3.so $SRC -lm
  $BIN/llvm-strip --strip-unneeded $OUT/$abi/libtvtownmp3.so
done
ls -la $OUT/*/libtvtownmp3.so
