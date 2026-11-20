#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Refreshes the ad/tracker filter lists shipped in the APKs (shared/src/main/assets/filters/*.txt, plain text:
# the APK build decompresses .gz assets and drops the suffix, which broke 0.2.2-0.3.0; zip deflates them anyway).
# The apps also update them weekly on Wi-Fi from the same URLs (lists.txt; shared/.../web/AdBlock.kt).
# Licences: EasyList + EasyPrivacy GPLv3 / CC BY-SA 3.0; uBlock Origin filters GPLv3 (see THIRD_PARTY.md). Lists
# marked "download" in lists.txt (Peter Lowe's: no stated licence) are never shipped; the apps fetch them.
# Attribution: Settings > Ad blocking (tablet), the waiting screen (TV), README.
set -euo pipefail
cd "$(dirname "$0")/../shared/src/main/assets/filters"
while IFS='|' read -r name url licence mode; do
    [ -z "$name" ] || [ "${name#\#}" != "$name" ] && continue
    if [ "$mode" = download ]; then rm -f "$name.txt"; echo "$name: not shipped (fetched by the apps)"; continue; fi
    tmp=$(mktemp)
    curl -sSfL --max-time 120 -o "$tmp" "$url"
    lines=$(wc -l < "$tmp")
    if [ "$lines" -lt 1000 ] || ! head -c 2000 "$tmp" | grep -q '^\[Adblock\|^!'; then echo "$name: doesn't look like a filter list ($lines lines), kept the old one" >&2; rm -f "$tmp"; continue; fi
    chmod 644 "$tmp" && mv "$tmp" "$name.txt"
    echo "$name: $lines lines, $(du -k "$name.txt" | cut -f1) KB ($licence)"
done < lists.txt
