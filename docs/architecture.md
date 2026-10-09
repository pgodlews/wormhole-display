# Wormhole Display — architecture

Goal: an Android device appears in the macOS/iOS **Screen Mirroring** picker and
accepts a native mirroring or extended-display session, with audio, and no
companion app on the sender.

## Approach

A port of the mirroring core of [UxPlay](https://github.com/FDH2/UxPlay) (C, `lib/`,
vendored at `app/src/main/cpp/uxplay/`) to Android, replacing its GStreamer
renderers with `MediaCodec` + `SurfaceView` for video and `AudioTrack` for audio:

- **Protocol core** (`raop*.c`, `httpd.c`, `llhttp/`) — RTSP/HTTP control channel.
- **Crypto** (`playfair/`, `pairing.c`, `srp.c`, `crypto.c`) — pairing handshake and
  stream decryption; uses a static OpenSSL `libcrypto` for arm64.
- **Discovery** — UxPlay's bundled internal mDNS responder (`lib/mdnsd/`). Android's
  `NsdManager` cannot publish TXT records before API 31 (Portal devices run API
  28–29), and the `/info` handler asserts on the C `dnssd` object anyway, so
  advertisement stays native.
- **JNI shim** (`cpp/wormhole_jni.c`) — server lifecycle and core callbacks.
  Decrypted Annex-B H.264/HEVC is copied to `VideoFrameQueue` → `VideoRenderer`
  (hardware `MediaCodec` on a `Surface`); decrypted AAC-ELD is copied to
  `AudioRenderer`.
- **Now-playing callbacks** — the core hands over the metadata an audio sender
  attaches with `SET_PARAMETER`: DMAP track info, cover art and `progress:` RTP
  timestamps. The shim parses DMAP (`cpp/dmap.c`) and forwards
  `NativeBridge.Listener.onNowPlaying`, `onCoverArt` and `onProgress`. They run on the
  audio RTP thread, only between `onAudioRunning(true)` and `onAudioRunning(false)`,
  and are default no-ops on the listener. Track text is decoded leniently through
  `String(bytes, "UTF-8")`, since senders' bytes need not be the modified UTF-8 that
  `NewStringUTF` requires. `WormholeServer` keeps a `NowPlaying` state (position is
  extrapolated while audio flows and frozen on a flush) and decodes the art off the
  RTP thread; `MainActivity` shows it full screen for audio-only sessions.
- **App layer** (Kotlin) — `WormholeService` (foreground service, multicast and
  wake locks, boot autostart), `WormholeServer` (process-wide owner of the native
  server, renderers and UI state), `MainActivity` (Compose dashboard and video
  surface), and `ScreenOrientation` (portrait/landscape/auto management with sensor
  handling).

Changes to the vendored core are listed in [uxplay-patches.md](uxplay-patches.md).

## System overview

A sender finds the receiver over Bonjour, then streams through the native core. The
JNI shim is the only code that touches both sides.

```mermaid
flowchart LR
    subgraph Sender["Mac, iPhone or iPad"]
        PICK["Screen Mirroring picker<br/>or AirPlay audio"]
    end

    subgraph Portal["Portal (Android 9 to 10)"]
        subgraph Native["Native core (C)"]
            MDNS["mdnsd<br/>_airplay._tcp, _raop._tcp"]
            CORE["UxPlay core<br/>RTSP, FairPlay, decryption"]
            SHIM["wormhole_jni.c and dmap.c<br/>JNI shim"]
        end
        subgraph Kotlin["App layer (Kotlin)"]
            SRV["WormholeServer<br/>session state and callbacks"]
            VID["VideoFrameQueue and VideoRenderer<br/>MediaCodec to SurfaceView"]
            AUD["AudioRenderer<br/>AAC-ELD or PCM to AudioTrack"]
            NP["NowPlaying<br/>track, art, position"]
            UI["MainActivity<br/>dashboard, mirror view, now playing"]
        end
    end

    PICK -- "Bonjour browse" --> MDNS
    PICK -- "RTSP 7000, mirror TCP 7100, UDP 7011 7001 7101" --> CORE
    CORE -- "H.264 or H.265 frames" --> SHIM
    CORE -- "audio frames" --> SHIM
    CORE -- "DMAP, cover art, progress" --> SHIM
    SHIM -- "NativeBridge.Listener" --> SRV
    SRV --> VID
    SRV --> AUD
    SRV --> NP
    VID --> UI
    NP --> UI
    SRV --> UI
```

## Server startup

The order matters. `/info` reads TXT records from the `dnssd` object, so registration
must finish before the HTTP listener starts. A failure at any step unwinds what was
built so far, and `nativeStart` returns a negative code.

```mermaid
flowchart TD
    A["WormholeServer.startServer()<br/>multicast lock held"] --> B["nativeSetListener(listener)"]
    B --> C["nativeStart(keyFile, deviceId, name, width, height, fps, hevc)"]
    C --> D["raop_init2<br/>ed25519 key and device ID"]
    D --> E["raop_set_plist<br/>width, height, refreshRate, maxFPS"]
    E --> F["dnssd_init<br/>feature bit 42 when HEVC is on"]
    F --> G["dnssd_register_raop and dnssd_register_airplay<br/>TXT records are built here"]
    G --> H["raop_start_httpd on port 7000"]
    H --> I["Receiver advertised, /info answers"]
    G -. "any failure: unregister, destroy, return negative" .-> X["Startup aborted"]
    H -. "port not 7000 or bind failed" .-> X
```

## Connection flow

Both streams start during `SETUP`, not `RECORD`. The client-connected callback fires
first, then the mirror stream and the audio stream each report when they start, and
`TEARDOWN` reports them stopped. Metadata arrives on `SET_PARAMETER` while the session
runs.

```mermaid
sequenceDiagram
    autonumber
    participant S as Sender
    participant M as mdnsd
    participant C as UxPlay core
    participant J as JNI shim
    participant K as WormholeServer

    S->>M: Browse _airplay._tcp and _raop._tcp
    M-->>S: Service name, pk and TXT records
    S->>C: GET /info (RTSP with CSeq)
    C-->>S: 200 OK, binary plist with displays and features
    S->>C: pair-verify (ed25519, transient)
    S->>C: POST /fp-setup, twice (FairPlay)
    S->>C: SETUP (mirror stream)
    C->>J: report_client_request(name)
    J->>K: onClientConnected(name)
    C->>J: mirror_video_running(true)
    J->>K: onMirrorRunning(true)
    S->>C: SETUP (audio stream)
    C->>J: audio_running(true, ct)
    J->>K: onAudioRunning(true, ct)
    loop While streaming
        C->>J: video_process (decrypted Annex-B)
        J->>K: onVideoFrame
        C->>J: audio_process (PCM or AAC-ELD)
        J->>K: onAudioFrame
    end
    S->>C: SET_PARAMETER (DMAP, cover art, progress)
    C->>J: audio_set_metadata, audio_set_coverart, audio_set_progress
    J->>K: onNowPlaying, onCoverArt, onProgress
    S->>C: TEARDOWN
    C->>J: mirror_video_running(false) and audio_running(false)
    J->>K: onMirrorRunning(false), onAudioRunning(false)
```

## Session states

`WormholeServer` derives the UI state from these callbacks. Audio that arrives during a
mirroring session does not change the state, because mirroring already owns the screen.
Back, Home, orientation changes and stream errors restart the native receiver, which
resets the state to Idle.

```mermaid
stateDiagram-v2
    [*] --> Idle: receiver started
    Idle --> Mirroring: onMirrorRunning(true)
    Idle --> AudioOnly: onAudioRunning(true) while not mirroring
    Mirroring --> AudioOnly: mirror ends, audio continues
    AudioOnly --> Mirroring: onMirrorRunning(true)
    Mirroring --> Idle: onMirrorRunning(false)
    AudioOnly --> Idle: onAudioRunning(false)
    Mirroring --> Idle: Back, Home, orientation change or stream error (restartServer)
    AudioOnly --> Idle: Back or Home (restartServer)
    Idle --> [*]: stopServer()
```

## Audio path

Mirroring and iOS audio-only send AAC-ELD (`ct` 8), which the Kotlin renderer decodes.
Music and iTunes over legacy RAOP send ALAC (`ct` 2). The shim decodes that to PCM
before the JNI hop, because the Portal has no ALAC `MediaCodec` decoder.

```mermaid
flowchart LR
    RTP["Audio RTP thread<br/>core decrypts the frame"] --> CT{"Negotiated ct"}
    CT -- "ct 8, AAC-ELD<br/>mirroring and iOS audio" --> JNI["cb_audio_process<br/>copies the frame"]
    CT -- "ct 2, ALAC<br/>Music and iTunes over RAOP" --> DEC["alac_decoder<br/>decoded in-process"]
    DEC --> PCM["16-bit stereo PCM<br/>tagged ct 0"]
    PCM --> JNI
    JNI --> AR["AudioRenderer<br/>AAC-ELD decoded here, PCM sent straight to AudioTrack"]
```

## Now-playing data

The shim parses or forwards the track info, cover art and progress that the sender
attaches. `NowPlaying` keeps the position moving between sender updates: it advances
while audio is flowing and freezes on a flush (pause or seek). Art is decoded off the
RTP thread and dropped if its session has already ended.

```mermaid
flowchart TD
    SP["SET_PARAMETER from sender"] --> KIND{"Content"}
    KIND -- "application/x-dmap-tagged" --> DMAP["dmap_parse<br/>title, artist, album, year"]
    KIND -- "image/jpeg or image/png" --> ART["Image bytes copied"]
    KIND -- "progress start/curr/end" --> PROG["RTP timestamps at 44.1 kHz"]
    DMAP --> CB1["onNowPlaying<br/>UTF-8 decoded leniently"]
    ART --> CB2["onCoverArt"]
    PROG --> CB3["onProgress<br/>seconds"]
    CB1 --> NP["NowPlaying state"]
    CB3 --> NP
    CB2 --> DEC["Decoded off the RTP thread<br/>at most 1024 px"]
    DEC --> BMP["Cover art bitmap"]
    NP --> SCREEN["Now-playing screen"]
    BMP --> SCREEN
    FLOW["Audio flowing"] -. "position advances" .-> NP
    FLUSH["Audio flush: pause or seek"] -. "position freezes" .-> NP
    END["Audio session ends"] -. "state and art cleared" .-> NP
```

## Protocol knobs

Senders change the protocol periodically; the moving knobs are `model`, `srcvers`,
and the `features` bitmask built by the vendored `dnssd` from `lib/dnssdint.h`
(currently `AppleTV3,2` / `220.68` / `0x5A7FFEE6,0x0`, UxPlay's defaults; bit 42
is added when experimental HEVC is enabled). Display geometry is advertised from
real panel metrics via `raop_set_plist` in `wormhole_jni.c` — senders stream at
whatever size is advertised. When screen orientation changes (either via manual toggle
or physical device rotation on supported models such as Portal Mini),
active mirroring clients are disconnected and the receiver restarts with transposed
width/height values so connecting senders pick up the updated geometry in `/info`.

## Licensing

UxPlay is GPLv3 (its `lib/` mirroring core is LGPLv2.1+; bundled `llhttp` is MIT,
`mdnsd` is BSD-like). This repository is therefore GPLv3 as a whole (see
[LICENSE](../LICENSE) and [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)).

## Known limitations

- Wi-Fi only; sender and receiver must be on the same LAN (no AWDL).
- No PIN or password admission: any device on the LAN can connect.
- The sender chooses mirroring vs extended display and the codec; advertising
  HEVC support does not force HEVC.
