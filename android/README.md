# MUB-X Android Client

Jetpack Compose VPN client (`id.my.mub`) that talks to `libmubxcore.so` — the
actual protocol/tunnel engine — over JNI. The Kotlin/Compose layer is pure
UI + service plumbing; all VPN protocol logic lives in Go under
`../src/tbrutal/bridge` and is cross-compiled into a native shared library.

## Before your first build

`libmubxcore.so` is **not** produced by Gradle/AGP — it's built separately
and dropped into `app/src/main/jniLibs/<abi>/`. Skipping this step doesn't
break the build; it produces an app that installs and looks completely
normal, except every "Connect" attempt fails with *"Native VPN core is
unavailable"*, because `System.loadLibrary("mubxcore")` has nothing to load.
That's the most common cause of "the app builds but nothing works."

As of this change, `./gradlew` runs `build-native.sh` automatically as part
of `preBuild`, and it now fails the build loudly (instead of silently
succeeding) if the prerequisites below are missing — so a broken native
core surfaces as a build error, not a confusing runtime failure.

**Requirements:**
- Android NDK (set `ANDROID_NDK_HOME`, or install it via Android Studio's
  SDK Manager — it'll be auto-detected under `$ANDROID_HOME/ndk`)
- Go 1.22+ on `PATH`, with `CGO_ENABLED=1` support for the host toolchain
- Network access to `proxy.golang.org` (or a configured `GOPROXY`) the first
  time, to fetch `sagernet/sing-box`, `sagernet/sing-tun`, and friends

If you only want to work on the UI and don't need a working VPN connection,
set `MUBX_SKIP_NATIVE_BUILD=1` to skip the native build without failing.

## Building

```bash
export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/27.1.12297006  # adjust
./gradlew assembleDebug
```

This runs `build-native.sh` first (unless skipped), which cross-compiles
`libmubxcore.so` for `arm64-v8a` and `x86_64` into
`app/src/main/jniLibs/`, then builds the APK as usual.
