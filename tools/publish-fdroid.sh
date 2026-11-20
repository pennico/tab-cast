#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Publish Tab Cast TV (release-signed) to the F-Droid repo pennico/tab-cast-fdroid (GitHub Pages:
# https://pennico.github.io/tab-cast-fdroid/repo). See README "Updates through F-Droid".
#
#   tools/publish-fdroid.sh             build + test the pair, sign the TV app, update the index, commit + push, check
#                                       it is served
#   tools/publish-fdroid.sh --dry-run   the same without push (repo left in build/fdroid-checkout for a look)
#
# Only Tab Cast TV goes to that repo. The tablet app (it bundles youtubedl-android) is published with its source on
# GitHub Releases instead.
#
# Keys: never in git. They come from a secret store, rendered into $TABCAST_KEYDIR:
#   tab-cast-release.p12.b64   app signing key (Tab Cast TV; PKCS12, alias tab-cast)
#   fdroid-repo.p12.b64        F-Droid index signing key (PKCS12, alias fdroid-repo)
#   release.env                TABCAST_RELEASE_STORE_PASSWORD=…, FDROID_REPO_STORE_PASSWORD=…
# They are decoded into a private temp folder that is removed on exit. The F-Droid config (with the index key
# password) is written there too, so nothing secret ever lands in the public repo's checkout.
# fdroidserver: $FDROID (default: `fdroid` on the PATH). Commit author of the repo: $FDROID_GIT_NAME and
# $FDROID_GIT_EMAIL. tools/env.sh may set all of these from a local, git-ignored private/env.sh.
#
# What goes to the public repo: the Tab Cast TV APK, the index, metadata/ (names and descriptions) and the icon. No
# source, no host names or addresses, no logs. The last 3 versions of each app stay in repo/, older ones go to
# archive/ (fdroid archive_older).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
DRY=0
for a in "$@"; do case "$a" in --dry-run) DRY=1 ;; *) echo "unknown option $a" >&2; exit 2 ;; esac; done

. tools/env.sh
KEYDIR=${TABCAST_KEYDIR:?set TABCAST_KEYDIR to the folder with the release key files (see the top of this script)}
FDROID=${FDROID:-$(command -v fdroid || echo fdroid)}
REPO_SLUG=pennico/tab-cast-fdroid
REPO_URL=https://pennico.github.io/tab-cast-fdroid/repo
CHECKOUT=$ROOT/build/fdroid-checkout
for f in tab-cast-release.p12.b64 fdroid-repo.p12.b64 release.env; do
    [ -s "$KEYDIR/$f" ] || { echo "missing $KEYDIR/$f (release keys not rendered yet?)" >&2; exit 1; }
done
[ -x "$FDROID" ] || { echo "fdroidserver not found at $FDROID" >&2; exit 1; }

tmp=$(mktemp -d); chmod 700 "$tmp"
trap 'rm -rf "$tmp"' EXIT
base64 -d "$KEYDIR/tab-cast-release.p12.b64" > "$tmp/app.p12"
base64 -d "$KEYDIR/fdroid-repo.p12.b64" > "$tmp/repo.p12"
# release.env: two NAME=value lines, read without sourcing (no shell evaluation of the values).
val() { sed -n "s/^$1=//p" "$KEYDIR/release.env" | head -n1; }
export TABCAST_RELEASE_STORE="$tmp/app.p12"
TABCAST_RELEASE_STORE_PASSWORD=$(val TABCAST_RELEASE_STORE_PASSWORD); export TABCAST_RELEASE_STORE_PASSWORD
export TABCAST_RELEASE_KEY_ALIAS=tab-cast
REPO_PW=$(val FDROID_REPO_STORE_PASSWORD)
[ -n "$TABCAST_RELEASE_STORE_PASSWORD" ] && [ -n "$REPO_PW" ] || { echo "release.env lacks a password line" >&2; exit 1; }

echo "== build + tests (debug pair: lint, unit, Robolectric) =="
tools/build.sh --pair
echo "== release build of Tab Cast TV =="
./gradlew --no-daemon -q :tv:assembleRelease
APKSIGNER=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n1)
rm -rf dist/release; mkdir -p dist/release
for m in tv; do       # never "app": see the note at the top
    ver=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$m/build.gradle.kts")
    code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$m/build.gradle.kts")
    case $m in app) id=dk.pennico.tabcast ;; tv) id=dk.pennico.tabcast.tv ;; esac
    src="$m/build/outputs/apk/release/$m-release.apk"
    [ -f "$src" ] || { echo "no signed $src (unsigned build?)" >&2; exit 1; }
    "$APKSIGNER" verify "$src" >/dev/null
    out="dist/release/${id}_${code}.apk"     # F-Droid's file naming
    cp "$src" "$out"
    echo "$out ($ver): $("$APKSIGNER" verify --print-certs "$src" | sed -n 's/.*SHA-256 digest: //p' | head -n1)"
done

(cd dist/release && sha256sum dk.pennico.tabcast.tv_*.apk > SHA256SUMS)

echo "== F-Droid repo =="
git_gh() { git -c credential.helper= -c credential.helper='!gh auth git-credential' "$@"; }
if [ -d "$CHECKOUT/.git" ]; then git_gh -C "$CHECKOUT" pull -q --ff-only
else rm -rf "$CHECKOUT"; git_gh clone -q "https://github.com/$REPO_SLUG.git" "$CHECKOUT"; fi
work="$tmp/fdroid"; mkdir -p "$work"
for d in repo archive metadata; do mkdir -p "$CHECKOUT/$d"; cp -a "$CHECKOUT/$d" "$work/"; done
rm -f "$work/metadata/dk.pennico.tabcast.yml"
cp tools/fdroid/metadata/dk.pennico.tabcast.tv.yml "$work/metadata/"
# 0.5.1: "What's new" per version (shown by Tab Cast TV's own updater): tools/fdroid/whatsnew/tv-<versionCode>.txt.
cl="$work/metadata/dk.pennico.tabcast.tv/en-US/changelogs"
for f in tools/fdroid/whatsnew/tv-*.txt; do [ -e "$f" ] || continue; mkdir -p "$cl"; c=${f##*/tv-}; cp "$f" "$cl/$c"; done
cp tools/fdroid/icon.png "$work/tab-cast.png"   # repo_icon is relative to the fdroid work folder
cp dist/release/dk.pennico.tabcast.tv_*.apk "$work/repo/"
# Only this release's two APKs are new; older ones are already in repo/ or archive/ from the checkout.
cat > "$work/config.yml" <<CFG
repo_url: $REPO_URL
repo_name: Tab Cast
repo_icon: tab-cast.png
repo_description: >-
  Tab Cast TV, the browser on the TV for Tab Cast. Source: https://github.com/pennico/tab-cast
archive_older: 3
archive_url: ${REPO_URL%/repo}/archive
archive_name: Tab Cast archive
archive_description: Older Tab Cast versions.
archive_icon: tab-cast.png
keystore: $tmp/repo.p12
repo_keyalias: fdroid-repo
keystorepass: $REPO_PW
keypass: $REPO_PW
keydname: CN=Tab Cast F-Droid repo
make_current_version_link: false
CFG
chmod 600 "$work/config.yml"
(cd "$work" && "$FDROID" update --pretty --delete-unknown 2>&1 | grep -v "^WARNING: .*No 'lastUpdated'" ) || { echo "fdroid update failed" >&2; exit 1; }
FPR=$(cd "$work" && python3 - <<'PY'
import json,glob
d=json.load(open('repo/index-v1.json'))
print(d['repo'].get('fingerprint',''))
PY
)
[ -n "$FPR" ] || FPR=$("$JAVA_HOME/bin/keytool" -list -v -storetype PKCS12 -keystore "$tmp/repo.p12" -storepass "$REPO_PW" -alias fdroid-repo 2>/dev/null | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
# status/ is fdroid's run report (tool paths, command line): not for the public repo.
rm -rf "$work/repo/status" "$work/archive/status"
if grep -rlE '/(workspace|tmp|home|Users)/' "$work/repo" "$work/archive" --include='*.json' --include='*.xml' --include='*.html' | grep -q .; then
    echo "local paths in the generated index: refusing to publish" >&2; exit 1
fi
rm -rf "$CHECKOUT/repo" "$CHECKOUT/archive" "$CHECKOUT/metadata"
cp -a "$work/repo" "$work/archive" "$work/metadata" "$CHECKOUT/"
# Never let key material or the config reach the public repo.
if ls "$CHECKOUT"/repo/dk.pennico.tabcast_*.apk "$CHECKOUT"/archive/dk.pennico.tabcast_*.apk >/dev/null 2>&1; then
    echo "the tablet app (GPL-3.0 youtubedl-android inside) is in the repo: refusing to publish" >&2; exit 1
fi
if find "$CHECKOUT" -path "$CHECKOUT/.git" -prune -o \( -name '*.p12' -o -name '*.jks' -o -name '*.keystore' -o -name 'config.yml' -o -name '*.b64' \) -print | grep -q .; then
    echo "key material in the checkout: refusing to commit" >&2; exit 1
fi
cp tools/fdroid/README-repo.md "$CHECKOUT/README.md"
git -C "$CHECKOUT" add -A
if git -C "$CHECKOUT" diff --cached --quiet; then echo "repo unchanged"; else
    vers=$(ls dist/release/ | sed 's/\.apk$//' | tr '\n' ' ')
    git -C "$CHECKOUT" -c user.name="${FDROID_GIT_NAME:-Tab Cast publisher}" -c user.email="${FDROID_GIT_EMAIL:-noreply@invalid}" commit -q -m "Tab Cast TV release: $vers"
fi
echo
echo "F-Droid repo address: $REPO_URL"
echo "Fingerprint (SHA-256): $FPR"
echo "Add link: $REPO_URL?fingerprint=$FPR"
if [ $DRY = 1 ]; then echo "dry run: not pushed; see $CHECKOUT"; exit 0; fi
git_gh -C "$CHECKOUT" push -q origin HEAD:main
echo "== waiting for GitHub Pages =="
want=$(sha256sum "$CHECKOUT/repo/index-v1.jar" | cut -d' ' -f1)
for i in $(seq 1 40); do
    got=$(curl -fsSL "$REPO_URL/index-v1.jar" 2>/dev/null | sha256sum | cut -d' ' -f1 || true)
    [ "$got" = "$want" ] && { echo "served: $REPO_URL/index-v1.jar (after ~$((i * 15)) s)"; exit 0; }
    sleep 15
done
echo "GitHub Pages hasn't served the new index after 10 min; check the Pages build: gh api repos/$REPO_SLUG/pages/builds/latest" >&2
exit 1
