#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build, test and package debug APKs into dist/ (+ .sha256).
#   tools/build.sh            tablet app: lint + unit/Robolectric tests (+ :shared, :link) -> dist/tab-cast-<v>.apk
#   tools/build.sh --tv       Tab Cast TV (the browser on the TV)                          -> dist/tab-cast-tv-<v>.apk
#   tools/build.sh --pair     both (what a handoff ships: the tablet and the TV app belong together)
#   tools/build.sh --probe    the 0.2 mirroring probe                                      -> dist/tab-cast-probe-<v>.apk
#   --replay                  also replay jstest/out recordings through the catch code (network)
# Always runs the host tests of the shared C code first (make -C native test).
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/env.sh
mods=()
for a in "$@"; do
    case "$a" in
        --replay) export TABCAST_REPLAY=1 ;;
        --probe) mods+=(probe) ;;
        --tv) mods+=(tv) ;;
        --pair) mods+=(app tv) ;;
        *) echo "unknown option $a" >&2; exit 2 ;;
    esac
done
[ ${#mods[@]} -gt 0 ] || mods=(app)
make -s -C native test
tasks=()
for m in "${mods[@]}"; do tasks+=(":$m:lintDebug" ":$m:testDebugUnitTest" ":$m:assembleDebug"); done
case " ${mods[*]} " in *" app "*|*" tv "*) tasks+=(":shared:lintDebug" ":shared:testDebugUnitTest" ":link:test") ;; esac
./gradlew --no-daemon -q "${tasks[@]}"
mkdir -p dist
for m in "${mods[@]}"; do
    ver=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$m/build.gradle.kts")
    case $m in app) name=tab-cast ;; tv) name=tab-cast-tv ;; probe) name=tab-cast-probe ;; esac
    cp "$m/build/outputs/apk/debug/$m-debug.apk" "dist/$name-$ver.apk"
    (cd dist && sha256sum "$name-$ver.apk" > "$name-$ver.apk.sha256")
    echo "built dist/$name-$ver.apk ($(du -h "dist/$name-$ver.apk" | cut -f1))"
    cat "dist/$name-$ver.apk.sha256"
done
