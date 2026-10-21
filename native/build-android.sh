#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/workspace/android-sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.3.13750724}"
case "$(uname -s)" in Linux) HOST=linux-x86_64;; Darwin) HOST=darwin-x86_64;; *) echo 'Run this script in Linux/macOS; set NDK toolchains manually on Windows.' >&2; exit 1;; esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
[ -x "$BIN/aarch64-linux-android26-clang" ] || { echo "NDK compiler not found under $BIN" >&2; exit 1; }
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$BIN/x86_64-linux-android26-clang"
export CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0
for pair in 'aarch64-linux-android arm64-v8a' 'x86_64-linux-android x86_64'; do
 read -r target abi <<< "$pair"
 echo "Building $abi ($target)"
 cargo build --target "$target"
 mkdir -p "../app/src/main/jniLibs/$abi"
 "$BIN/llvm-strip" --strip-unneeded "target/$target/debug/libfreeoffice.so" -o "../app/src/main/jniLibs/$abi/libfreeoffice.so"
 echo "Ready: ../app/src/main/jniLibs/$abi/libfreeoffice.so"
done
