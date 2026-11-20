#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Browser checks without root: Playwright in jstest/node_modules, Chromium's system libraries unpacked into
# jstest/.libs (git-ignored). Both survive a container restart, unlike /tmp. Run once; safe to run again.
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules/playwright ] || npm install --silent --no-audit --no-fund
ls "$HOME/.cache/ms-playwright" 2>/dev/null | grep -q '^chromium' || npx playwright install chromium
L="$PWD/.libs"
if [ ! -f "$L/fonts.conf" ]; then
  T="$(mktemp -d)"; O="-o Dir::State=$T/state -o Dir::Cache=$T/cache -o Debug::NoLocking=1 -o Dir::State::status=/var/lib/dpkg/status"
  mkdir -p "$T/state/lists/partial" "$T/cache/archives/partial" "$L"
  apt-get $O update -qq
  apt-get $O install -d -y -qq libx11-6 libxcomposite1 libxdamage1 libxext6 libxfixes3 libxrandr2 libasound2 libatk1.0-0 \
    libatk-bridge2.0-0 libatspi2.0-0 libcairo2 libcups2 libdbus-1-3 libgbm1 libglib2.0-0 libnspr4 libnss3 libpango-1.0-0 \
    libxcb1 libxkbcommon0 fonts-liberation >/dev/null
  for f in "$T"/cache/archives/*.deb; do dpkg-deb -x "$f" "$L"; done
  rm -rf "$T"
  # system-ui resolves to Liberation Sans, so screenshots show a proportional font as on a real device
  cat > "$L/fonts.conf" <<XML
<?xml version="1.0"?><fontconfig><dir>$L/usr/share/fonts</dir>
<alias binding="same"><family>system-ui</family><prefer><family>Liberation Sans</family></prefer></alias>
<alias binding="same"><family>sans-serif</family><prefer><family>Liberation Sans</family></prefer></alias></fontconfig>
XML
fi
echo "jstest ready (libs in $L)"
