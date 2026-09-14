#!/usr/bin/env bash
# ==============================================================================
# MUB-X VPN: Android Native Shared Library Build Script
# Targets: ARM64-v8a and x86_64 (Android API 35, 16KB page-size aligned)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_PATH" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK_PATH=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d -print | sort -V | tail -n 1)
fi
if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
    echo "[!] Android NDK is required to build libmubxcore.so." >&2
    echo "    Set ANDROID_NDK_HOME/ANDROID_NDK_ROOT or install the NDK." >&2
    exit 1
fi

HOST_TAG="linux-x86_64"
case "$(uname -s)" in
    Linux) HOST_TAG="linux-x86_64" ;;
    Darwin) HOST_TAG="darwin-x86_64" ;;
esac
if [[ ! -d "$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG" ]]; then
    HOST_TAG="$(find "$NDK_PATH/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | head -n 1 || true)"
fi
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG"
[[ -d "$TOOLCHAIN" ]] || { echo "[!] NDK LLVM toolchain not found under $NDK_PATH." >&2; exit 1; }
API_LEVEL=35
TAGS="with_quic,with_utls,with_reality,with_shadowsocks,with_gvisor"

cd "$WORKSPACE_ROOT"
go mod download all

build_one() {
    local abi="$1" goarch="$2" compiler="$3"
    local out="$JNI_LIBS_DIR/$abi/libmubxcore.so"
    local cc="$TOOLCHAIN/bin/${compiler}${API_LEVEL}-clang"
    [[ -x "$cc" ]] || { echo "[!] Android compiler missing: $cc" >&2; return 1; }
    mkdir -p "$JNI_LIBS_DIR/$abi"
    CC="$cc" CGO_ENABLED=1 GOOS=android GOARCH="$goarch" \
      go build -buildmode=c-shared \
        -tags "$TAGS" \
        -ldflags="-s -w -checklinkname=0 -extldflags '-Wl,-z,max-page-size=16384'" \
        -o "$out" \
        "$WORKSPACE_ROOT/src/tbrutal/bridge/cmd/libmubxcore"
    [[ -s "$out" ]] || { echo "[!] Native library was not produced: $out" >&2; return 1; }
}

build_one arm64-v8a arm64 aarch64-linux-android
build_one x86_64 amd64 x86_64-linux-android

command -v file >/dev/null 2>&1 && {
    file "$JNI_LIBS_DIR/arm64-v8a/libmubxcore.so" | grep -q 'ELF 64-bit.*ARM aarch64' || { echo "[!] arm64 JNI artifact has the wrong architecture." >&2; exit 1; }
    file "$JNI_LIBS_DIR/x86_64/libmubxcore.so" | grep -q 'ELF 64-bit.*x86-64' || { echo "[!] x86_64 JNI artifact has the wrong architecture." >&2; exit 1; }
}

echo "[✓] Android native libraries built and architecture-verified."
