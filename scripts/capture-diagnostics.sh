#!/bin/bash
# Record what is needed to diagnose a picture problem on a device connected over adb.
# See docs/diagnosing-video.md for the full procedure.
#
#   scripts/capture-diagnostics.sh start          begin recording logcat (do this before connecting the sender)
#   scripts/capture-diagnostics.sh snap <label>   mark the log, save a screenshot and the compositor state
#   scripts/capture-diagnostics.sh mark <text>    mark the log only
#   scripts/capture-diagnostics.sh stop           stop recording, write the filtered log and the archive
#
# Set ANDROID_SERIAL when more than one device is attached.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
base="$repo_dir/dist/diagnostics"
current="$base/.current"
package=io.github.pgodlews.wormhole

die() { echo "error: $*" >&2; exit 1; }

capture_dir() {
  [ -f "$current" ] || die "no capture in progress; run '$0 start' first"
  cat "$current"
}

recording() {
  [ -f "$1/logcat.pid" ] && kill -0 "$(cat "$1/logcat.pid")" 2>/dev/null
}

# adb shell joins its arguments into one remote command line, so keep marker text to harmless characters.
mark() {
  local out="$1" text
  shift
  text="$(printf '%s' "$*" | tr -c 'A-Za-z0-9 _.,:#=-' '_')"
  adb shell "log -t WormholeMark '$text'" || echo "warning: could not write the marker to the device log" >&2
  printf '%s %s\n' "$(date '+%m-%d %H:%M:%S')" "$text" >> "$out/marks.txt"
}

command -v adb >/dev/null || die "adb is not on PATH"

case "${1:-}" in
  start)
    if [ -f "$current" ] && recording "$(cat "$current")"; then
      die "a capture is already running in $(cat "$current"); run '$0 stop' first"
    fi
    adb get-state >/dev/null 2>&1 || die "no device reachable over adb (check 'adb devices', or set ANDROID_SERIAL)"
    out="$base/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$out"
    echo "$out" > "$current"
    {
      for prop in ro.product.manufacturer ro.product.model ro.product.device \
                  ro.build.version.release ro.build.version.sdk ro.build.display.id; do
        echo "$prop=$(adb shell getprop "$prop" | tr -d '\r')"
      done
      echo "--- package"
      adb shell dumpsys package "$package" | grep -E "versionName|versionCode|lastUpdateTime" || echo "$package is not installed"
      echo "--- display"
      adb shell dumpsys display | grep -iE "hdr|colorMode|mBaseDisplayInfo" || true
    } > "$out/device.txt" 2>&1 || true
    # A larger buffer keeps earlier lines; the existing buffer is recorded too, so nothing before 'start' is lost.
    adb logcat -G 16M >/dev/null 2>&1 || true
    nohup adb logcat -v threadtime > "$out/logcat.txt" 2>&1 &
    echo $! > "$out/logcat.pid"
    mark "$out" "capture started"
    echo "Recording to $out"
    echo "Now start mirroring from the sender, then use '$0 snap <label>' while a problem is on screen."
    ;;
  mark)
    out="$(capture_dir)"
    [ $# -ge 2 ] || die "usage: $0 mark <text>"
    shift
    mark "$out" "$*"
    echo "marked: $*"
    ;;
  snap)
    out="$(capture_dir)"
    [ $# -ge 2 ] || die "usage: $0 snap <label>   (for example: grey, normal, phone-stopped)"
    label="$(printf '%s' "$2" | tr -c 'A-Za-z0-9_.-' '_')"
    n=$(( $(find "$out" -name '*.png' | wc -l) + 1 ))
    name="$(printf '%02d-%s' "$n" "$label")"
    mark "$out" "snap $name"
    adb exec-out screencap -p > "$out/$name.png" || echo "warning: screenshot failed" >&2
    adb shell dumpsys SurfaceFlinger > "$out/$name-surfaceflinger.txt" 2>&1 || echo "warning: SurfaceFlinger dump failed" >&2
    echo "saved $out/$name.png"
    ;;
  stop)
    out="$(capture_dir)"
    if recording "$out"; then
      mark "$out" "capture stopped"
      sleep 1
      kill "$(cat "$out/logcat.pid")" 2>/dev/null || true
    fi
    rm -f "$out/logcat.pid" "$current"
    # The app's own lines, the core's, the markers, and crashes. logcat.txt keeps everything else.
    grep -aE "[VDIWEF] (Wormhole|WormholeServer|WormholeMark|uxplay) *:|AndroidRuntime|\[diag\]" "$out/logcat.txt" \
      > "$out/wormhole.txt" || true
    diag_lines="$(grep -acF "[diag]" "$out/wormhole.txt" || true)"
    tar -czf "$out.tar.gz" -C "$base" "$(basename "$out")"
    echo "Filtered log: $out/wormhole.txt ($(wc -l < "$out/wormhole.txt" | tr -d ' ') lines, $diag_lines diagnostic)"
    echo "Archive:      $out.tar.gz"
    if [ "$diag_lines" = 0 ]; then
      echo "warning: no [diag] lines. Is the diagnostics build installed, and did mirroring start during the capture?" >&2
    else
      echo "Streams seen:"
      grep -aF "stream-config" "$out/wormhole.txt" | grep -aF "summary:" | sed 's/.*summary: /  /' | sort | uniq -c
    fi
    ;;
  *)
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
