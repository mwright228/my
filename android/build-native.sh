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
    echo "[!] ERROR: ANDROID_NDK_HOME not found in environment." >&2
    echo "[!] Please set ANDROID_NDK_HOME or install NDK via Android Studio SDK Manager." >&2
    echo "[!] Example: export ANDROID_NDK_HOME=\$HOME/Library/Android/sdk/ndk/27.1.12297006" >&2
    echo "[!] Without this, libmubxcore.so is never built and the app installs" >&2
    echo "[!] fine but every VPN connection attempt fails with 'Native VPN core" >&2
    echo "[!] is unavailable' — there is no separate broken feature to chase," >&2
    echo "[!] the native core itself was never compiled." >&2
    echo "[!] To intentionally skip this (UI-only work), set MUBX_SKIP_NATIVE_BUILD=1." >&2
    if [[ "${MUBX_SKIP_NATIVE_BUILD:-0}" == "1" ]]; then
        echo "[!] MUBX_SKIP_NATIVE_BUILD=1 set — continuing without a native core." >&2
        exit 0
    fi
    exit 1
fi

if ! command -v go >/dev/null 2>&1; then
    echo "[!] ERROR: 'go' is not on PATH — Go 1.22+ is required to cross-compile" >&2
    echo "[!] libmubxcore.so from src/tbrutal/bridge/cmd/libmubxcore." >&2
    echo "[!] Install Go, or set MUBX_SKIP_NATIVE_BUILD=1 to skip (UI-only work)." >&2
    if [[ "${MUBX_SKIP_NATIVE_BUILD:-0}" == "1" ]]; then
        echo "[!] MUBX_SKIP_NATIVE_BUILD=1 set — continuing without a native core." >&2
        exit 0
    fi
    exit 1
fi

echo "[*] Using NDK at: $NDK_PATH"

HOST_TAG="linux-x86_64"
if [[ "$(uname -s)" == "Linux" ]]; then
    HOST_TAG="linux-x86_64"
elif [[ "$(uname -s)" == "Darwin" ]]; then
    HOST_TAG="darwin-x86_64"
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
    -tags "with_quic,with_utls,with_reality,with_shadowsocks,with_gvisor" \
    -ldflags="-s -w -extldflags '-Wl,-z,max-page-size=16384'" \
    -o "$JNI_LIBS_DIR/arm64-v8a/libmubxcore.so" \
    "$WORKSPACE_ROOT/src/tbrutal/bridge/cmd/libmubxcore"

echo "[✓] Successfully built $JNI_LIBS_DIR/arm64-v8a/libmubxcore.so"

# 3. Compile for x86_64 (Emulator support)
echo "[*] Building libmubxcore.so for x86_64 (Emulator)..."
mkdir -p "$JNI_LIBS_DIR/x86_64"

CC="$TOOLCHAIN/bin/x86_64-linux-android${API_LEVEL}-clang" \
CGO_ENABLED=1 \
GOOS=android \
GOARCH=amd64 \
go build -buildmode=c-shared \
    -tags "with_quic,with_utls,with_reality,with_shadowsocks,with_gvisor" \
    -ldflags="-s -w -extldflags '-Wl,-z,max-page-size=16384'" \
    -o "$JNI_LIBS_DIR/x86_64/libmubxcore.so" \
    "$WORKSPACE_ROOT/src/tbrutal/bridge/cmd/libmubxcore"

echo "[✓] Successfully built $JNI_LIBS_DIR/x86_64/libmubxcore.so"
echo "=========================================================="
echo "    Build Complete: JNI libraries packaged into android/"
echo "=========================================================="
