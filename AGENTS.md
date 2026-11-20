# AGENTS.md — tab-cast

Read README.md first (what it does, how, limits, build/test). This file is how to work on it. Maintainers may keep
local, git-ignored notes and settings in `private/` (e.g. `private/env.sh`, sourced by `tools/env.sh`).

## Rules
- Kotlin, not C, for the apps: Android UI/WebView/media-session APIs have no practical C API (see README). Pure
  per-pixel computation is the exception: `native/` (pure C, host-tested with `make -C native test`, NEON on ARM; JNI
  glue next to the module).
- No secrets in files. Keystores never go into git: the shared debug key comes from `TABCAST_DEBUG_KEYSTORE`, the
  release and F-Droid index keys from `TABCAST_KEYDIR` (`tools/publish-fdroid.sh` decodes them into a temp folder).
  Never regenerate the release keys: installed apps are signed with them.
- No personal data, host names, local addresses or device names in the tree. `scripts/scan-private.sh` must pass
  before every push (it reads local patterns from `private/patterns.txt` or `$PRIVATE_PATTERNS`).
- The F-Droid repo (`pennico/tab-cast-fdroid`) holds only Tab Cast TV APKs + the index; user-visible strings in the
  TV app end up there too.
- `jstest/out/` (recordings with signed URLs) and yt-dlp JSON dumps stay out of git.
- Keep Tab Cast general: no site rules, header/token tricks or relay tweaks aimed at particular unofficial sites.
  Test on legitimate sources (archive.org, PeerTube, hls.js/Mux samples, TED, YouTube, Dailymotion, news sites).

## Before a release
0. TV release = `tools/publish-fdroid.sh` once the build below is green (it runs it again); add
   `tools/fdroid/whatsnew/tv-<versionCode>.txt` first (plain words, public). The TV updates itself from that repo
   ("Update to x.y.z").
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts` and `tv/build.gradle.kts` (they move together).
2. `tools/build.sh --pair` (lint 0 issues, all tests green). Optionally re-record and `--replay`.
3. `scripts/scan-private.sh`.
4. Install on a tablet: `adb install --no-streaming -r dist/tab-cast-<v>.apk` (streamed installs fail on some
   devices: "splice failed: EINVAL"). Same signing key every build, so `-r` keeps settings.

## Where to look when something fails on the tablet
- App log: Settings → Show log → Share; or `adb pull /sdcard/Android/data/dk.pennico.tabcast/files/tabcast.log`;
  or `adb logcat -s TabCast`. Lines: `probe …` (what each candidate is), `cast to …` (URL, relay or not),
  `TV playback error`, `yt-dlp …`, `scan found …`.
- Catching wrong/nothing on a site: record it with `jstest/run.sh <name> <url>` and replay (README). The page script
  is `shared/src/main/assets/inject.js`; ranking is `core/Catch.kt` (`score()`, `ranked()`).
- TV can't play it: try long-press → "Cast through the tablet (relay)". If relay works and direct doesn't, the
  site needs something the TV request lacks; a site rule (`headers`, `relay: true`) can say so.
- yt-dlp errors: update yt-dlp (Settings), check the same URL with a desktop yt-dlp; for sites that need
  impersonation (curl_cffi) yt-dlp on Android can't work → resolver `browser`.

## Ad blocking lists
`tools/fetch-filters.sh` refreshes the lists shipped in the APKs (`shared/src/main/assets/filters/*.txt`, plain text:
the APK build strips a `.gz` suffix from assets, which broke 0.2.2–0.3.0; `PackagedListsTest` loads them from the
built APK). Lists marked `download` in `lists.txt` (Peter Lowe's) are never shipped; the apps fetch them. Licences:
THIRD_PARTY.md. Run it before a release that should ship newer lists; `RealListsTest` must still pass.

## Modules
`app` (tablet), `tv` (Tab Cast TV, the browser on the TV), `shared` (catching code + assets both use), `link`
(tablet <-> TV connection, pure JVM), `probe` (mirroring probe), `native` (C). A release ships the **pair**
(`tools/build.sh --pair`): tablet and TV versions move together. The link protocol must stay compatible one version
back (the TV may update before the tablet): new message types are ignored by the old side, new kv keys are optional
(e.g. 0.3.3's CMD `id` / `ACK`, HELLO_ACK `acks`).

## Films (0.4.0)
- Cache and server are pure JVM in `link/` (`FilmCache`, `FileServer`): change them with `FilmCacheTest` running
  (`./gradlew :link:test`). The TV side is `tv/TvFilms.kt` + `FilmSource.kt` + the film part of `TvActivity`.
- On the TV look for `film "…": N MB, M MB already on the TV, F MB free, may use B MB`, `film: waiting for the
  tablet`, `film: player error #n`; on the tablet `file: casting`, `file: stopped serving`, `wake + Wi-Fi lock on|off`.
- Optional tablet OS settings are adb-level and reversible; document each with its undo line (e.g.
  MANAGE_EXTERNAL_STORAGE for sidecar subtitles).

## Mirroring (0.5.0)
- Sender `app/.../mirror/` (+ `app/src/main/jni/`), receiver `tv/.../TvMirror.kt`. The tablet app builds native code
  (ndk-build); keep `keepDebugSymbols += "**/*.zip.so"` (youtubedl's zip files named .so must not be stripped).
- Logs: tablet `mirror: encoder …`, `mirror: started`, `mirror: 30s, N fps …`, `mirror: stopped (why)`; TV `mirror:
  decoder …`, `mirror: ended (why): {decoded, dropped, soundDelayMs}`. "This didn't work" reports land in the tablet's
  `files/reports/`.
- Probe 2 on the reference tablet: OMX.Exynos.VP8.Encoder works (960x720, 30 fps, ~15 ms, 2.3 Mbit/s; it refuses
  the "full" settings, the minimal ones work), so no libvpx. c2.android.vp8.encoder crashed the probe's C converter:
  the C code writes only into an own buffer (MirrorEncoder `staging`), never codec memory. `appops PROJECT_MEDIA
  allow` skips Android's capture dialog on that ROM. One VirtualDisplay per projection (Android 14): rotation =
  `resize` + new ImageReader surface.
- 0.5.2: MirrorEncoder is async (MediaCodec.Callback on the mirror thread), never throws after its constructor and
  reports a lost codec once (`onLost`); MirrorService makes a new one (`MirrorRecovery`: same level, then hardware →
  software 640x480 15 fps after 3 losses a minute, then stop with `BUSY` on the tablet and the TV via STATE `why`).
  Look for `mirror: encoder lost: … : <why>; codec record: …` and `mirror: encoder back: …` in the tablet log.

## TV updates (0.5.1)
- `tv/TvUpdates.kt` (check daily, install on request), `tv/SelfUpdate.kt` (download + PackageInstaller),
  `link/RepoIndex.kt` (pinned index signature; `RepoIndexTest` uses a copy of the real index in `link/src/test/resources`).
  Never change the pinned fingerprint or the repo URL casually: every installed TV checks them.
- TV log: `update: repo has x (code)`, `update: installing …`, `update: install status N`.
- Robolectric tests set `TvUpdates.autoCheck = false` (no network).
- If Robolectric fails with "unable to create native thread", run Gradle with `--max-workers=1`.

## Reference devices
Tablet Samsung Galaxy Tab S2 (SM-T715), LineageOS 21 (Android 14), armeabi-v7a only, 2 GB RAM, no Google apps. TV
Chromecast HD (Android TV 14) with FCast Receiver and Tab Cast TV. Ports: FCast 46899, Cast 8009, Tab Cast TV link
46898, web remote 46897, relay 8642 on the tablet, film server on a random port. Mirroring probe 1: every H.264
encoder an app can use fails on that tablet (Codec2 gralloc import); only OMX.Exynos.VP8.Encoder is a hardware
encoder; MediaProjection → ImageReader 39 fps.
