#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Probe 2 (Tab Cast 0.4/0.5, phase 0) on the Tab S2. Mac side, tablet on USB with USB
# debugging on, screen unlocked, LANDSCAPE, on Wi-Fi with internet. Takes about 10 minutes. Makes sound.
#
#   sh run-probe2.sh <tab-cast-probe-0.2.0-probe2.apk> [results dir] [steps] [long seconds]
#
# steps (default): info,page,vp8,projection,audiothers,mirrorenc,long
# What it changes on the tablet, and puts back at the end (also on Ctrl-C):
#   - installs/updates dk.pennico.tabcast.probe, grants it RECORD_AUDIO (capture of played sound only, never the mic)
#   - appops for the probe: PROJECT_MEDIA allow (does it skip the capture dialog?), SYSTEM_ALERT_WINDOW allow (to open
#     VLC/Brave/Tab Cast from the background), WRITE_SETTINGS allow (backlight to minimum) -> reset at the end
#   - screen-off timeout set to 30 s for the long test -> the old value is restored at the end
#   - VLC, Brave and Tab Cast each open one archive.org test video (a media "pause" key after each)
set -eu
APK=${1:?usage: sh run-probe2.sh <tab-cast-probe-*.apk> [results dir] [steps] [long seconds]}
OUT=${2:-probe-2}
STEPS=${3:-info,page,vp8,projection,audiothers,mirrorenc,long}
LONG=${4:-240}
PKG=dk.pennico.tabcast.probe
DIR=/sdcard/Android/data/$PKG/files

[ -f "$APK.sha256" ] && (cd "$(dirname "$APK")" && shasum -a 256 -c "$(basename "$APK").sha256")
adb get-state >/dev/null
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "apps: VLC $(adb shell pm list packages org.videolan.vlc | grep -c vlc || true), Brave $(adb shell pm list packages com.brave.browser | grep -c brave || true), Tab Cast $(adb shell pm list packages dk.pennico.tabcast | grep -c 'tabcast$' || true)"
adb install --no-streaming -r "$APK"          # streamed install fails on this tablet (splice EINVAL)
adb shell pm grant $PKG android.permission.RECORD_AUDIO
OLD_TIMEOUT=$(adb shell settings get system screen_off_timeout | tr -d '\r')
echo "screen-off timeout was $OLD_TIMEOUT ms; set to 30000 for the test"
restore() {
    adb shell settings put system screen_off_timeout "$OLD_TIMEOUT" || true
    adb shell appops reset $PKG || true
    kill "${TOP:-0}" 2>/dev/null || true
    echo "restored: screen-off timeout $OLD_TIMEOUT ms, the probe's appops reset"
}
trap restore EXIT INT TERM
adb shell appops set $PKG PROJECT_MEDIA allow
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell appops set $PKG WRITE_SETTINGS allow
adb shell settings put system screen_off_timeout 30000
adb shell input keyevent KEYCODE_WAKEUP
adb shell am force-stop $PKG
adb shell rm -f "$DIR/LAST"
adb logcat -c
mkdir -p "$OUT"

# Taps the dialog button whose text matches the regex $1 (uiautomator); returns 1 if there is none.
tap_button() {
    adb shell uiautomator dump /sdcard/tabcast-ui.xml >/dev/null 2>&1 || return 1
    xy=$(adb exec-out cat /sdcard/tabcast-ui.xml | python3 -c '
import re, sys
xml = sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", n)
    b = re.search(r" bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if t and b and re.fullmatch(sys.argv[1], t.group(1).strip(), re.I):
        a = [int(v) for v in b.groups()]
        print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
        break
' "$1")
    [ -n "$xy" ] || return 1
    # shellcheck disable=SC2086
    adb shell input tap $xy
}

adb shell top -b -d 5 -n 200 -m 12 > "$OUT/top.txt" 2>&1 &
TOP=$!
adb shell am start -n $PKG/.ProbeActivity --es tests "$STEPS" --es long "$LONG" >/dev/null

run=""; seen=0; tries=0; consent=0; dialog="not seen"; i=0
while [ $i -lt 1200 ]; do                     # 1200 x 1 s = 20 min at most
    sleep 1; i=$((i + 1))
    [ -n "$run" ] || run=$(adb shell cat "$DIR/LAST" 2>/dev/null | tr -d '\r\n' || true)
    [ -n "$run" ] || continue
    log=$(adb shell cat "$DIR/$run/probe.log" 2>/dev/null | tr -d '\r' || true)
    n=$(printf '%s\n' "$log" | wc -l | tr -d ' ')
    if [ "$n" -gt "$seen" ]; then printf '%s\n' "$log" | tail -n $((n - seen)) | cut -c1-220; seen=$n; fi
    case "$log" in *"PROBE DONE"*) break ;; esac
    case "$log" in
        *"PROBE consent-wanted"*)
            case "$log" in *"MediaProjection granted"*|*"consent refused"*|*"no consent answer"*) consent=2 ;; esac
            if [ $consent -eq 0 ] && [ $tries -lt 15 ]; then
                tries=$((tries + 1)); sleep 1
                if tap_button 'start( now)?'; then consent=1; dialog="SHOWN (tapped Start)"; echo ">> the capture dialog was shown: tapped Start"
                elif [ $tries -eq 15 ]; then echo ">> couldn't find the Start button: please tap it on the tablet if a dialog shows"; fi
            fi ;;
    esac
done

[ -n "$run" ] || { echo "the probe never started (no $DIR/LAST)"; exit 1; }
echo "capture dialog: $dialog (with appops PROJECT_MEDIA allow)" | tee "$OUT/dialog.txt"
adb pull "$DIR/$run" "$OUT/" >/dev/null
mv "$OUT/dialog.txt" "$OUT/$run/dialog.txt"
adb logcat -d > "$OUT/$run/logcat.txt"
kill $TOP 2>/dev/null || true
mv "$OUT/top.txt" "$OUT/$run/top.txt" 2>/dev/null || true
echo
echo "== results in $OUT/$run"
grep -E ' (info|page|encode|vp8|mirror|audio|projection|long)[: (]' "$OUT/$run/probe.log" | cut -c14- || true
case "$(cat "$OUT/$run/probe.log")" in *"PROBE DONE"*) echo "probe finished" ;; *) echo "probe did NOT finish (see probe.log / logcat.txt)" ;; esac
