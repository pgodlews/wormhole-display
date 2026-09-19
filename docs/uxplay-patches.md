# UxPlay vendor patch inventory

`app/src/main/cpp/uxplay/lib/` is a vendored copy of UxPlay's C core. Keep
downstream changes narrowly scoped and listed here so upgrades remain diffable.
Patched code is marked "Android port" in the source (the public-key getter is
marked "Android JNI addition").

| File | Change |
|---|---|
| `raop.c` / `raop.h` | `raop_get_pk_str` public-key getter; partial-initialization shutdown guard (no `httpd_stop(NULL)`). |
| `raop_rtp_mirror.c` | H.264/HEVC parameter-set bounds checks; clear previous prepend state before replacing configuration; validate AVCC length/header availability before reads; allocation cap; EOF/allocation/streaming-report cleanup; worker-join ownership (the mirror worker no longer stops/joins itself). |
| `httpd.c` | Join a finished but unjoined worker before freeing it. |
| `byteutils.c` | Alignment-safe (`memcpy`) packet field loads/stores. |
| `raop.c` / `raop.h` | Legacy RAOP ("AirTunes") audio for RSA senders (iTunes, older macOS, some Android AirPlay apps; NOT modern macOS Music — see note): `ANNOUNCE` dispatch, `Apple-Challenge`→`Apple-Response` (RSA), an `rsakey` field, and an `audio_running` callback. |
| `raop_handlers.h` | `raop_handler_announce` (SDP parse, RSA-OAEP AES key, ALAC fmtp) and a legacy Transport-header branch of `raop_handler_setup`. |
| `raop_rtp.c` | Fire the new `audio_running` callback on audio-thread start/exit. |
| `raop_buffer.c` | Allow a NULL AES key (unencrypted legacy session). |
| `dnssdint.h` / `mdnsd/dnssd_mdnsd.c` | Advertise `et=0,1` + `ek=1` (RSA) for legacy audio clients. |
| `global.h` / `mdnsd/dnssd_mdnsd.c` | Advertise an AirPort-Express `am=` on the RAOP record (audio auth selection) while `_airplay` keeps `AppleTV3,2`. |
| `rsakey.c` / `rsakey.h` (new) | AirPort Express RSA key: Apple-Challenge signing (PKCS#1 v1.5) and rsaaeskey decryption (RSA-OAEP), plus padding-tolerant base64. |
| `mdnsd/dnssd_mdnsd.c` | Build a unique mDNS hostname from `hwaddr` instead of `gethostname()`, which returns "localhost" on Android and caused LAN collisions and misrouted connections across multiple receivers. |

These are deliberate correctness changes. No upstream-version claim is made;
reconcile each with upstream during the next controlled vendor update. The UxPlay
license is preserved and the `dns_sd` (Bonjour/Avahi) backend stays excluded.

### Note on macOS Music vs iOS audio

iOS/iPadOS Apple Music and other iOS audio work: they use FairPlay v3 fp-setup
(the same handshake as mirroring, version byte `0x03`), which the vendored
`playfair` implements. The decrypted stream is ALAC, decoded by the in-tree
`app/src/main/cpp/alac/` decoder (bit-exact against `afconvert`).

Modern **macOS Music** (tested 26.6) does **not** work: it drives AirPlay-1
audio through its legacy "ITPlayer" path and always sends FairPlay SAP **v2.5**
fp-setup (version byte `0x02`), regardless of the advertised `et`/`ek`, the `am`
model, or the `_airplay` FairPlay feature bits. playfair only answers v3, so
`SendFPSAP` fails on the sender (`-15102`). No open-source FairPlay v2.5
implementation exists, so this cannot be fixed from the receiver side. The RSA
`ANNOUNCE` path above is the correct fallback for the clients that will use it,
but macOS Music will not.

## Upgrading UxPlay

1. Re-vendor `lib/` (keep `mdnsd/`, exclude `dns_sd/`).
2. Re-apply `raop_get_pk_str`, then check each change above against upstream.
3. Run the regression checks:

```sh
./gradlew :app:testDebugUnitTest
GUARD_MALLOC=1 ./scripts/test-native.sh
./scripts/build.sh
```

The native tests (`tests/native/`) drive the real `raop_rtp_mirror.c` loop over
loopback sockets with UBSan in fail-fast mode; `GUARD_MALLOC=1` adds macOS strict
Guard Malloc. Crypto, NTP and plist are stubbed, so they cover parser and lifetime
paths, not live sender compatibility. On a host with a working ASan runtime,
`SANITIZERS=address,undefined ./scripts/test-native.sh` enables ASan as well.
