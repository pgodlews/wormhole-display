# AGENTS.md — guide for AI agents working on Wormhole Display

Read this before touching anything. It encodes hard-won facts about the AirPlay
protocol, this Android port, and the target devices. Skipping it will cost you
hours.

## What this is

An AirPlay-compatible **mirroring and extended display receiver** for Android,
developed and tested on Meta Portal+ (Gen 2), Portal Go, Portal TV and
Portal Mini (Android 9–10 / API 28–29, arm64, Wi-Fi only). A Mac, iPhone or
iPad sees the device in its native Screen Mirroring picker and streams video and
lifting (RTSP, FairPlay, decryption, mDNS) is a vendored copy of UxPlay's C core
at `app/src/main/cpp/uxplay/`; the app layer is Kotlin + MediaCodec/AudioTrack.

## Repo layout

```
app/src/main/cpp/uxplay/lib/              vendored UxPlay core (DO NOT casually edit — see below)
app/src/main/cpp/uxplay/lib/llhttp/       bundled HTTP parser (MIT)
app/src/main/cpp/uxplay/lib/playfair/     FairPlay handshake crypto
app/src/main/cpp/uxplay/lib/mdnsd/        embedded mDNS responder (the Bonjour advertiser)
app/src/main/cpp/wormhole_jni.c           JNI shim: server lifecycle + all raop callbacks
app/src/main/cpp/deps/                    committed prebuilt static libs (openssl, plist)
app/src/main/java/io/github/pgodlews/wormhole/
    MainActivity.kt                       Compose dashboard + video surface
    WormholeService.kt                    foreground service: keeps the receiver discoverable, boot autostart
    WormholeServer.kt                     process-wide owner of the native server, renderers and UI state
    NativeBridge.kt                       JNI declarations
    ScreenOrientation.kt                  portrait/landscape/auto orientation modes and model helpers
    VideoFrameQueue.kt, VideoRenderer.kt  compressed-frame queue → MediaCodec → Surface (H.264/HEVC)
    AudioRenderer.kt                      AAC-ELD decode → AudioTrack
    WormholeIdentity.kt                   persisted deviceid, service name, Wi-Fi multicast lock
scripts/build.sh                          build APK → dist/wormhole-display.apk
scripts/build-deps.sh                     rebuild the native deps (rarely needed)
scripts/test-native.sh                    host regression tests for the vendored mirror loop
docs/architecture.md                      design overview
docs/uxplay-patches.md                    inventory of vendored-code changes
```

Vendored UxPlay changes are listed in `docs/uxplay-patches.md` and marked
"Android port" in the source (the `raop_get_pk_str` getter is marked "Android
JNI addition"). Keep that inventory current and avoid unrelated vendor changes
so future upgrades remain diffable.

## Environment

- JDK 17 and the Android SDK. `scripts/build.sh` sets `JAVA_HOME` and
  `ANDROID_HOME` defaults, so plain `java` does not need to be on PATH.
- Native deps are **committed prebuilt** (`app/src/main/cpp/deps/`). You only
  need the NDK if you edit C code: AGP will use `.tools/ndk-r27d` if present
  (see `app/build.gradle.kts`) or any SDK-installed NDK. Pinned version:
  27.3.13750724. If you change the NDK version, update `ndkVersion` there.

## Build, deploy, run

```bash
./scripts/build.sh
adb install -r dist/wormhole-display.apk
adb shell am start -n io.github.pgodlews.wormhole/.MainActivity
```

Portal devices are reachable over ADB via USB-C. Device and sender must share a
Wi-Fi LAN (mDNS does not cross the USB cable; Portal has no ethernet).

## How a connection works (protocol flow)

1. Native `mdnsd` advertises `_airplay._tcp` and `_raop._tcp` (both → port 7000)
   with TXT records including `pk=<hex ed25519 pubkey>`, `model=AppleTV3,2`,
   `srcvers=220.68`, `features=0x5A7FFEE6,0x0`. This is what populates the
   sender's picker.
2. Client connects to TCP 7000, does `GET /info` (RTSP-style, **with CSeq**),
   then ed25519 `pair-verify` (transient — no PIN with our feature bits).
3. `POST /fp-setup` × 2 — FairPlay SAP handshake (in `playfair/`).
4. `SETUP` negotiates streams: video over TCP 7100 ("mirror data"), timing/control
   UDP 7011/7001/7101. Then `RECORD` starts the stream.
5. Video arrives AES-128-CTR encrypted; the core decrypts and calls
   `video_process` with **Annex-B H.264/HEVC** (parameter sets prepended after each
   parameter packet). The JNI shim copies it into a jbyteArray → `VideoFrameQueue`
   → `VideoRenderer` → MediaCodec.
6. Audio (AAC-ELD, 44.1 kHz stereo) is decrypted by the core and delivered to
   `audio_process`; the shim copies each frame to `AudioRenderer`.

## Verification recipes

Device seen by the sender (run on the Mac):

```bash
dns-sd -B _airplay._tcp local.        # must list the service name, e.g. "Wormhole Plus"
dns-sd -B _raop._tcp local.           # must list "<DEVICEID>@<service name>"
```

Server alive (run on the Mac; substitute the device's LAN IP):

```bash
printf 'GET /info RTSP/1.0\r\nCSeq: 0\r\nUser-Agent: AirPlay/320.20\r\n\r\n' \
  | nc -w 5 <device-ip> 7000 | head -c 200
# expect: "RTSP/1.0 200 OK" + application/x-apple-binary-plist body
```

Decode the plist body with `plistlib` to check advertised `displays[0]` width/
height/maxFPS. A 409 means another client is already connected.

Logs on the device:

```bash
adb logcat | grep -E "uxplay|Wormhole|AndroidRuntime|libc"
```

## Troubleshooting — every one of these was hit for real

| Symptom | Cause | Fix / notes |
|---|---|---|
| Plain `curl http://…:7000/info` hangs, empty reply | The core drops requests **without a CSeq header** unless HLS support is on | Probe with the RTSP+CSeq recipe above, not curl |
| Crash: `assertion "raop->dnssd" failed` on first /info | `/info` reads TXT records from the C dnssd object | `dnssd_init` + `dnssd_register_*` must complete before HTTP starts (done in `nativeStart`) |
| Picker shows the device, connect fails instantly | Advertised `pk` ≠ the server's actual ed25519 key | Always advertise `raop_get_pk_str()` output; never a random/persisted key |
| Mac mirrors at 1920×1080, looks wrong on the panel | UxPlay defaults advertise 1920×1080@30; **macOS mirrors at whatever you advertise** | `raop_set_plist(raop,"width"/"height"/"refreshRate"/"maxFPS", …)` — done from real display metrics in `nativeStart` |
| App not in Portal's app list | Missing launcher icon — Portal OS hides icon-less apps | Keep `android:icon="@mipmap/ic_launcher"` in the manifest |
| Re-pairing a running app via `am start … --es` silently does nothing | Standard-launchMode activities don't get `onNewIntent` when already on top | `android:launchMode="singleTop"` (already set) |
| Stream is live (decoders started) but the app stays behind the launcher | Android 10 blocks background activity starts; logcat shows `Background activity start … isBgStartWhitelisted: false` | Needs "Display over other apps" (`SYSTEM_ALERT_WINDOW` appop). The dashboard's *Auto-open on connect* row links to it. A new `applicationId` starts without the grant |
| mDNS advertising works on desktop Java but not on device | Android drops multicast without a `WifiManager.MulticastLock`; also needs `CHANGE_WIFI_MULTICAST_STATE` | `WormholeIdentity` holds the multicast lock — it must be held while mdnsd runs |
| Why not `NsdManager` for Bonjour? | It cannot publish TXT records before API 31; Portal is API 28–29 | Native `mdnsd` is used instead (and is required for /info anyway) |
| Native link errors: `dnssd_private_init`, `dnssd_get_*_txt` undefined | Those symbols live in a dnssd *backend*; you must compile exactly one (`mdnsd/`) | `${UX}/mdnsd/*.c` is in the CMake glob |
| Stale native builds after adding/removing .c files | CMake `file(GLOB)` result is cached | The glob uses `CONFIGURE_DEPENDS`; if it still looks stale, `rm -rf app/build/intermediates/cxx app/.cxx` then rebuild |
| Multiple receivers on one LAN collide / connections misrouted | `gethostname()` returns "localhost" on Android | mDNS hostname is derived from `hwaddr` (vendor patch in `mdnsd/dnssd_mdnsd.c`) |
| Frames must not be kept past the callback | The core `free()`s `video_decode_struct.data` on return | Always copy in the JNI layer (already done) |
| JNI "thread has no JNIEnv" crashes | Callbacks fire on the core's RTP threads | `env_for_thread()` attaches on demand (already done) |
| `UnsatisfiedLinkError` / `NoClassDefFoundError` at startup after a rename | JNI symbol names and `FindClass` strings encode the Kotlin package | See "Package or class renames" below |

## When modifying the code

- **Advertised device parameters** (model, srcvers, features, ports): TXT records
  are built by the vendored `dnssd` from `lib/dnssdint.h` constants; geometry comes
  from `raop_set_plist` in `wormhole_jni.c`. If a sender update breaks
  compatibility, these strings/bitmasks are the knobs — compare against current
  UxPlay upstream. They are protocol values: never rebrand them.
- **Ports**: RTSP 7000, mirror data TCP 7100, UDP 7011/7001/7101 — set in
  `nativeStart`. The mDNS registration must match.
- **Audio**: `cb_audio_process` in `wormhole_jni.c` delivers decrypted audio to
  `AudioRenderer`. Mirroring and iOS audio-only are AAC-ELD (`ct=8`, spf 480);
  legacy RAOP audio (iTunes / older macOS / some Android senders, via `ANNOUNCE`)
  is ALAC (`ct=2`, spf 352), decoded in-process by `app/src/main/cpp/alac/` to
  PCM (`ct=0`) before the JNI hop. The negotiated codec/lifetime reach Kotlin via
  the `audio_running(bool, ct)` callback. Dropping audio frames is protocol-safe
  (the core handles resends).
- **Legacy RAOP audio (RSA)**: `rsakey.c` holds the AirPort Express key; `raop.c`
  answers `Apple-Challenge` and dispatches `ANNOUNCE`; `raop_handlers.h` parses
  the SDP (RSA-OAEP AES key, ALAC fmtp) and the Transport-header SETUP. The RAOP
  record advertises `et=0,1 ek=1 am=AirPort4,107`. **Modern macOS Music does not
  use this path** — it sends FairPlay SAP v2.5 (`fp-setup` version `0x02`), which
  playfair cannot answer and no open code implements; see `docs/uxplay-patches.md`.
  Do not spend time trying to make macOS Music audio work from the receiver.
- **H.265**: optional experimental dashboard setting (off by default). Bit 42 is
  advertised only when `VideoDecoderSupport` finds a hardware HEVC decoder for
  the advertised size/rate. JNI carries the codec on each frame; `VideoFrameQueue`
  handles HEVC VPS/SPS/PPS and random access, and `VideoRenderer` selects that
  decoder. H.264 stays available. A renderer failure in an HEVC session suppresses
  HEVC advertisement until the setting is toggled or the process restarts.
  The sender still chooses the codec: on Portal+ over Wi-Fi a Mac selected AVC at
  2160×1440 despite bit 42, while an iPad and an iPhone negotiated HEVC. For a real
  codec A/B comparison keep resolution, content, frame-rate limit and network
  identical, and confirm `negotiated video codec` in logcat on both runs.
- **Screen orientation & rotation**:
  `ScreenOrientation` exposes `LANDSCAPE`, `PORTRAIT`, and `AUTO`. Hardware
  accelerometer rotation (`AUTO`) is enabled only on hardware with rotatable
  physical stands (Portal+ Gen 1 with swivel arm, Portal Mini 8" with dual-side feet)
  via `WormholeIdentity.supportsAutoOrientation()`. Fixed-orientation devices
  (Portal TV on HDMI, Portal Go with wedge stand, Portal+ Gen 2 with tilt-only stand)
  have `AUTO` mode hidden/disabled and default to `LANDSCAPE`, though manual
  `PORTRAIT` / `LANDSCAPE` overrides remain available on all devices. Because
  macOS/iOS negotiate resolution at session setup and do not dynamically renegotiate
  stream geometry mid-session, switching orientation disconnects any active mirroring
  client (`disconnectClient("orientation_changed")`) and restarts the native receiver
  to re-advertise transposed dimensions in RTSP `/info`.
- **Package or class renames**: the JNI functions in `wormhole_jni.c`
  (`Java_io_github_pgodlews_wormhole_NativeBridge_*`) and the
  `FindClass("io/github/pgodlews/wormhole/NativeBridge$Listener")` string encode
  the Kotlin package and class names. The build does not catch a mismatch; it
  fails at runtime. The native library name `wormhole` must match
  `System.loadLibrary` in `NativeBridge.kt` and the CMake target in
  `app/build.gradle.kts`.
- **UxPlay upgrades**: follow `docs/uxplay-patches.md` — re-vendor `lib/`,
  re-apply each listed change, and keep the `dns_sd/` backend excluded.

## Rebuilding the native deps (only to upgrade OpenSSL/libplist)

```bash
# needs NDK r27d at .tools/ndk-r27d
./scripts/build-deps.sh
```

Gotchas already encoded in the script: OpenSSL 3.0 wants `ANDROID_NDK_ROOT` (not
`HOME`) and rejects `no-apps`; libplist 2.6.0's release tarball is autotools-only;
its `make install` fails on ranlib PATH lookup *after* installing the archive —
the script runs `llvm-ranlib` manually. It also strips debug info from
`libplist-2.0.a` so local build paths (`/Users/...`) never end up in the committed
archive; check with `strings -a <archive> | grep /Users/` before committing
rebuilt deps. Update `THIRD_PARTY_NOTICES.md` if versions change.

## Releases (GitHub Actions)

`.github/workflows/release.yml` runs on the public GitHub repo (Gitea keeps using
`.gitea/workflows/`, which takes precedence there):

- **Pull requests:** unit tests + debug APK, uploaded as a workflow artifact.
- **Push to `main`:** signed release APK, republished as the rolling `latest` pre-release.
- **Tag `vX.Y.Z`:** signed release APK published as a GitHub Release with
  `wormhole-display.apk` and its `.sha256`.

Versions come from `scripts/version.sh` (newest reachable `vX.Y.Z` tag): a tagged
commit builds as `X.Y.Z` with versionCode `X*1000000 + Y*10000 + Z*100`; a `main`
build N commits later is `X.Y.Z-N-g<sha>` with `+N` (capped at 99), so the next tag
always upgrades it. Minor and patch must stay ≤ 99. Local builds default to
`0.1.0-dev` / versionCode 1.

Lint's `ExpiredTargetSdkVersion` (a Play Store policy check) is downgraded to a
warning in `app/build.gradle.kts`; otherwise `lintVitalRelease` fails every release
build. `targetSdk = 29` is intentional: Portal runs API 28–29, APKs are sideloaded,
and raising it would bring newer background-start and foreground-service rules.

Release signing reads `WORMHOLE_KEYSTORE_PATH`, `WORMHOLE_KEYSTORE_PASSWORD`,
`WORMHOLE_KEY_ALIAS` and `WORMHOLE_KEY_PASSWORD`. CI fills them from repository
secrets (`WORMHOLE_KEYSTORE_BASE64` plus the other three). Without a keystore,
`assembleRelease` produces an unsigned APK, and pushes to `main`/tags fail rather
than publish unsigned builds. Never commit keystores (`*.jks`/`*.keystore` are
gitignored). Release-signed and debug-signed APKs can't replace each other on a
device: uninstall first when switching (this resets the receiver identity and the
"Display over other apps" grant).

## Hard limitations (don't promise otherwise)

- Wi-Fi only, same LAN, no AWDL/p2p.
- The sender decides mirroring vs extended display, resolution within the
  advertised modes, and codec; the receiver advertises capabilities and renders
  what it is sent.
- No PIN or password: any device on the LAN can connect. This is intentional.
- macOS/iOS updates can and do break third-party receivers; treat
  `model`/`srcvers`/`features` as moving targets.
- **GPLv3**: everything here links UxPlay. Never copy code from this repo into
  non-GPL-compatible projects, and keep `LICENSE` and `THIRD_PARTY_NOTICES.md`
  intact.
