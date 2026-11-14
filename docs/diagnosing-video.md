# Diagnosing picture problems

The receiver logs what the sender's video stream says about itself (size, bit depth, colour range, primaries,
transfer function) and how the Portal's decoder interprets it. Together with a screenshot taken while a problem is
on screen, that is enough to tell whether a wrong-looking picture comes from the sender, the decoder or the display.

Every line added for this purpose carries the marker `[diag]`. They are few (a handful per session and per stream
change), so they stay on in normal builds.

## Capturing a session

You need the device on USB with `adb devices` listing it, and the sender on the same Wi-Fi.

```bash
./scripts/build.sh
adb install -r dist/wormhole-display.apk      # see "Install fails" below if this is refused
adb shell am start -n io.github.pgodlews.wormhole/.MainActivity

./scripts/capture-diagnostics.sh start        # 1. before connecting the sender
#                                               2. start Screen Mirroring on the iPhone, reproduce the problem
./scripts/capture-diagnostics.sh snap grey    # 3. while a bad picture is on screen
./scripts/capture-diagnostics.sh snap normal  #    and while a good one is, for comparison
./scripts/capture-diagnostics.sh stop         # 4. writes dist/diagnostics/<timestamp>.tar.gz
```

Start the capture **before** the sender connects: the session-start lines and the first stream description are
logged once, when mirroring begins.

`snap <label>` writes a marker into the device log, saves a screenshot and saves the compositor state
(`dumpsys SurfaceFlinger`). `mark <text>` writes only the marker; use it to note anything that has no picture, for
example `mark low-power-mode-on`. Labels are free text.

`stop` prints the distinct streams it saw and leaves these in `dist/diagnostics/<timestamp>/`:

| File | Contents |
|---|---|
| `wormhole.txt` | The app's log lines, the AirPlay core's, the markers and any crash. Small; send this first. |
| `logcat.txt` | The complete device log, including the media and display services. |
| `NN-<label>.png` | Screenshot for each `snap`. |
| `NN-<label>-surfaceflinger.txt` | Compositor state for each `snap`; shows the dataspace of the video layer. |
| `marks.txt` | The markers with host timestamps. |
| `device.txt` | Model, OS, installed app version, display HDR properties. |

Send the `.tar.gz`. If only text can be passed on, send `wormhole.txt` and say what each screenshot looks like.

### Grey or washed-out videos, or the phone stops showing the video

1. `start`, then mirror the iPhone and open the app that shows the problem (for example TikTok).
2. Take `snap normal` on two or three videos that look right.
3. Take `snap grey` on at least three videos that look wrong, while each is still on screen.
4. When the phone stops showing a video and it plays only on the Portal, take `snap phone-stopped`. Say whether
   those videos are also the grey ones.
5. Compare each `grey` screenshot with the panel itself. If the screenshot looks right and only the panel looks
   grey, say so and photograph the panel: the problem is then in display composition, after the screenshot is taken.
6. Optional comparisons, each as its own `mark`:
   - Toggle **H.265** on the dashboard (this restarts the receiver, so reconnect) and repeat steps 2–3.
   - Turn on Low Power Mode on the iPhone and check whether grey videos still occur.
7. `stop`.

### Without the script

```bash
adb logcat -G 16M
adb logcat -v threadtime > logcat.txt &                       # record
adb shell "log -t WormholeMark 'grey video 1'"                 # marker
adb exec-out screencap -p > grey-1.png                         # screenshot
adb shell dumpsys SurfaceFlinger > grey-1-surfaceflinger.txt   # compositor state
kill %1                                                        # stop recording
grep -E "[VDIWEF] (Wormhole|WormholeServer|WormholeMark|uxplay) *:|AndroidRuntime|\[diag\]" logcat.txt > wormhole.txt
```

To watch live: `adb logcat -v time Wormhole:V WormholeServer:V WormholeMark:V uxplay:V '*:S'`.

## Reading the lines

| Line | Logged | Meaning |
|---|---|---|
| `[diag] session-start` | once per mirroring session | App version, device, sender name, advertised size, H.265 setting and decoder. |
| `[diag] display` | once per session | HDR types the panel reports (`2` HDR10, `3` HLG), wide colour gamut, display mode. |
| `[diag] decoder-available` | once per session, per decoder | Every H.264/HEVC decoder with its profiles. For HEVC, `2` is Main10 and `4096` is Main10 HDR10. |
| `[diag] sender-size` | with every parameter packet | Size the sender announces: `source` is what it is showing, `encoded` is the video size. |
| `[diag] stream-config #N <codec> first\|changed\|repeated` | with every parameter packet | The NAL units in the packet. `changed` means new parameter sets without a reconnect. |
| `[diag] stream-config #N summary:` | when the sets are new | Parsed SPS: profile, level, chroma, bit depth, size, then the VUI colour description, ending in `=> SDR`, `=> HDR`, `=> WIDE-GAMUT` or `=> 10-BIT`. |
| `[diag] stream-config #N sps=` / `pps=` / `vps=` / `sei=` | when the sets are new | The raw units in hex, so anything the summary does not cover can be decoded later. |
| `[diag] decoder-output` | when the decoder's output format changes | Size and the colour standard, range and transfer the decoder reports to the display. |
| `[diag] sender-video paused` / `resumed` | when signalled | The sender suspended or resumed the stream (for example a locked phone). |
| `WormholeMark` | `snap` / `mark` | Where in the log a screenshot or note belongs. |

`decoder started:` (which decoder was chosen), `decoder format:` (the full `MediaFormat`) and the five-second
video diagnostics report were already logged and are in `wormhole.txt` too.

### What the combinations mean

| Stream summary at the bad moment | Screenshot | Conclusion |
|---|---|---|
| `=> HDR`, `=> WIDE-GAMUT` or `=> 10-BIT` | wrong | The sender is sending HDR or wide-gamut video and it is shown without tone mapping. Fixable in the receiver. |
| `=> SDR`, same as for good pictures | wrong | The picture is already wrong inside the stream; the sender produced it. |
| `=> SDR` | right, but the panel is wrong | Decoder output or display composition. Compare `decoder-output` and the layer's dataspace in the SurfaceFlinger dump between a good and a bad snap. |
| `range=full` but `decoder-output` says `color-range=2(limited)` (or the reverse) | either | Range mismatch between the stream and the decoder. |
| `changed` with a new `size=` and a new `sender-size` when the phone stops showing the video | – | The sender switched this screen from mirroring the phone to showing the video alone. |

## Install fails

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed app was signed with a different key (a release APK from
GitHub, or a debug build from another machine). `adb uninstall io.github.pgodlews.wormhole` first. That resets the
receiver's identity and the "Display over other apps" permission, so grant it again from the dashboard's *Auto-open
on connect* row and pick the Portal again on the sender.
