#!/bin/sh
# Installs a built Mac app into ~/Applications as the only copy macOS knows about (`make install`).
#
#   tools/mac-install.sh build/Quire.app            copy to ~/Applications, then forget every other copy
#   tools/mac-install.sh --forget build/Quire.app   only forget the other copies (after a test run launched one)
#
# Build copies (build/, build-dev/, a test clone) carry the same bundle id. If Launch Services keeps them
# registered, Spotlight and "Open With" list the app two or three times and a default handler can land on a build
# folder. So: copy to ~/Applications, unregister every other copy with that id, register the installed one.
# File-type defaults are stored by bundle id and survive the update.
# macOS's "Siri AI" agent keeps its own list of apps it has seen and registers them again within ~10 s
# (seen 2026-11-23), so after unregistering it is restarted (launchd starts it again at once) to drop that list.
# Generic on purpose: other Mac apps copy this file unchanged (owner rule 2026-11-23, every app gets `make install`).
set -eu

FORGET_ONLY=0
if [ "${1:-}" = --forget ]; then FORGET_ONLY=1; shift; fi
SRC=${1:?usage: mac-install.sh [--forget] path/to/App.app}
[ -d "$SRC/Contents" ] || { echo "mac-install: no app at $SRC (run make app first)" >&2; exit 1; }
LSR=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
PB=/usr/libexec/PlistBuddy
NAME=$(basename "$SRC")
DEST="$HOME/Applications/$NAME"
ID=$($PB -c 'Print :CFBundleIdentifier' "$SRC/Contents/Info.plist")
EXE=$($PB -c 'Print :CFBundleExecutable' "$SRC/Contents/Info.plist")
VER=$($PB -c 'Print :CFBundleShortVersionString' "$SRC/Contents/Info.plist" 2>/dev/null || echo '?')

# Unregister every registered path with this bundle id except the installed one; prints how many.
forget_others() {
	others=$("$LSR" -dump 2>/dev/null | awk -v id="$ID" -v dest="$DEST" '
		/^path:/ { p = $0; sub(/^path: */, "", p); sub(/ \(0x[0-9a-f]+\)$/, "", p) }
		/^identifier:/ { i = $2; if (i == id && p != dest && !(p in seen)) { seen[p] = 1; print p } }')
	n=0
	if [ -n "$others" ]; then
		while IFS= read -r p; do
			"$LSR" -u "$p" 2>/dev/null || true
			n=$((n + 1))
		done <<EOF
$others
EOF
		pkill -x "Siri AI" 2>/dev/null || true
	fi
	echo "$n"
}

if [ "$FORGET_ONLY" = 1 ]; then
	n=$(forget_others)
	echo "mac-install: $n build cop$( [ "$n" = 1 ] && echo y || echo ies) of $ID removed from Launch Services"
	exit 0
fi

if pgrep -f "$DEST/Contents/MacOS/$EXE" >/dev/null 2>&1; then
	echo "mac-install: $NAME is open; quit it, then run make install again" >&2
	exit 1
fi

mkdir -p "$HOME/Applications"
rm -rf "$DEST.new"
ditto "$SRC" "$DEST.new"
rm -rf "$DEST"
mv "$DEST.new" "$DEST"
# ditto keeps the build folder's old date, and macOS caches icons by path + date: without a new date a changed
# icon stays old in Finder, Spotlight and the Dock (seen 2026-11-23).
touch "$DEST" "$DEST/Contents" "$DEST/Contents/Info.plist"

n=$(forget_others)
"$LSR" -f "$DEST"

echo "mac-install: $NAME $VER -> $DEST ($ID); $n other registered cop$( [ "$n" = 1 ] && echo y || echo ies) removed from Launch Services"
