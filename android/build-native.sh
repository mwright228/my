#!/usr/bin/env bash
# ==============================================================================
# MUB-X VPN: Android Native Shared Library Build Script
# Targets: ARM64-v8a (Android 15 API 35 with 16KB memory page alignment)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

echo "=========================================================="
echo "    MUB-X Core: Android 15 Native Cross-Compiler"
echo "=========================================================="

# 1. Locate Android NDK
NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_PATH" ]]; then
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
        NDK_PATH=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 | sort -V | tail -n 1)
    fi
fi

if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
    echo "[!] WARNING: ANDROID_NDK_HOME not found in environment."
    echo "[!] Please set ANDROID_NDK_HOME or install NDK via Android Studio SDK Manager."
    echo "[!] Architecture scaffolding is complete and ready."
    echo "[!] Example: export ANDROID_NDK_HOME=\$HOME/Library/Android/sdk/ndk/27.1.12297006"
    exit 0
fi

echo "[*] Using NDK at: $NDK_PATH"

HOST_TAG="darwin-x86_64"
if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]]; then
    HOST_TAG="darwin-x86_64" # Clang toolchain directory in modern NDK
fi
if [[ ! -d "$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG" ]]; then
    HOST_TAG=$(ls "$NDK_PATH/toolchains/llvm/prebuilt" | head -n 1)
fi

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG"
API_LEVEL=35 # Android 15

# 2. Compile for ARM64-v8a (Android 15 Flagship & 16KB Page-Size Aligned)
echo "[*] Building libmubxcore.so for arm64-v8a (16KB Page Aligned)..."
mkdir -p "$JNI_LIBS_DIR/arm64-v8a"

CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang" \
CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm64 \
go build -buildmode=c-shared \
    -ldflags="-s -w -extldflags '-Wl,-z,max-page-size=16384'" \
    -o "$JNI_LIBS_DIR/arm64-v8a/libmubxcore.so" \
    "$WORKSPACE_ROOT/src/tbrutal/bridge"

echo "[✓] Successfully built $JNI_LIBS_DIR/arm64-v8a/libmubxcore.so"

# 3. Compile for x86_64 (Emulator support)
echo "[*] Building libmubxcore.so for x86_64 (Emulator)..."
mkdir -p "$JNI_LIBS_DIR/x86_64"

CC="$TOOLCHAIN/bin/x86_64-linux-android${API_LEVEL}-clang" \
CGO_ENABLED=1 \
GOOS=android \
GOARCH=amd64 \
go build -buildmode=c-shared \
    -ldflags="-s -w -extldflags '-Wl,-z,max-page-size=16384'" \
    -o "$JNI_LIBS_DIR/x86_64/libmubxcore.so" \
    "$WORKSPACE_ROOT/src/tbrutal/bridge"

echo "[✓] Successfully built $JNI_LIBS_DIR/x86_64/libmubxcore.so"
echo "=========================================================="
echo "    Build Complete: JNI libraries packaged into android/"
echo "=========================================================="
