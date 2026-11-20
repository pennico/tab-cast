#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Run catch.mjs with the unpacked Chromium libraries. usage: ./run.sh <name> <url> [seconds]
set -euo pipefail
cd "$(dirname "$0")"
L="$PWD/.libs"
export LD_LIBRARY_PATH="$L/usr/lib/x86_64-linux-gnu:$L/lib/x86_64-linux-gnu" FONTCONFIG_FILE="$L/fonts.conf"
exec node catch.mjs "$@"
