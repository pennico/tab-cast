#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Runs the Tab Cast mirroring probe (0.2 step 1) on the Tab S2 and collects the results. Mac side, tablet on USB with
# USB debugging on, screen unlocked, tablet held in LANDSCAPE. The TV is not needed. Takes about 3 minutes.
#
#   sh run-probe.sh <probe.apk> [results dir] [steps]
#
# steps: "all" (default) or a comma list of info,page,copy,convert,encode,pipeline,vd,vdenc,projection
# What it changes on the tablet: installs dk.pennico.tabcast.probe, grants it RECORD_AUDIO (used only to capture the
# probe page's own sound), wakes the screen, and taps "Start" in the screen-capture dialog. Nothing else.
# Remove afterwards with: adb uninstall dk.pennico.tabcast.probe
set -eu
APK=${1:?usage: sh run-probe.sh <tab-cast-probe-*.apk> [results dir] [steps]}
OUT=${2:-probe-results}
STEPS=${3:-all}
PKG=dk.pennico.tabcast.probe
DIR=/sdcard/Android/data/$PKG/files

[ -f "$APK.sha256" ] && (cd "$(dirname "$APK")" && shasum -a 256 -c "$(basename "$APK").sha256")
adb get-state >/dev/null
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
adb install --no-streaming -r "$APK"          # streamed install fails on this tablet (splice EINVAL)
adb shell pm grant $PKG android.permission.RECORD_AUDIO
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

adb shell top -b -d 2 -n 150 -m 15 > "$OUT/top.txt" 2>&1 &
TOP=$!
trap 'kill $TOP 2>/dev/null || true' EXIT
adb shell am start -n $PKG/.ProbeActivity --es tests "$STEPS" >/dev/null

run=""; seen=0; tries=0; consent=0; i=0
while [ $i -lt 480 ]; do                      # 480 x 1 s = 8 min at most
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
                if tap_button 'start( now)?'; then consent=1; echo ">> tapped Start in the capture dialog"
                elif [ $tries -eq 15 ]; then echo ">> couldn't find the Start button: please tap it on the tablet"; fi
            fi ;;
    esac
done

[ -n "$run" ] || { echo "the probe never started (no $DIR/LAST)"; exit 1; }
adb pull "$DIR/$run" "$OUT/" >/dev/null
adb logcat -d > "$OUT/$run/logcat.txt"
kill $TOP 2>/dev/null || true
mv "$OUT/top.txt" "$OUT/$run/top.txt" 2>/dev/null || true
echo
echo "== results in $OUT/$run"
grep -E ' (info|page|copy|convert|encode|pipeline|vd|mirror|audio capture|projection)[: (]' "$OUT/$run/probe.log" | cut -c14- || true
echo "gralloc/import errors in logcat: $(grep -c -E 'invalid gralloc handle|importBuffer' "$OUT/$run/logcat.txt" || true)"
case "$(cat "$OUT/$run/probe.log")" in *"PROBE DONE"*) echo "probe finished" ;; *) echo "probe did NOT finish (see probe.log / logcat.txt)" ;; esac
