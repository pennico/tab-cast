# Tab Cast

Cast the video of any web page from an Android tablet to the TV, **without Google Play Services** on the tablet.
Two apps that work together:

- **Tab Cast** (tablet/phone, `dk.pennico.tabcast`): a small browser that finds the stream a page plays and sends it
  to an FCast receiver or a Chromecast; also casts files from the tablet, and mirrors the whole screen as a fallback.
- **Tab Cast TV** (Android TV, `dk.pennico.tabcast.tv`): a browser on the TV that the tablet (or a phone's web
  remote) opens pages in; it plays their video full screen with Media3, keeps films from the tablet in a rolling
  cache, shows the mirrored screen, and updates itself from its F-Droid repo.

Open a page in Tab Cast, start the video, tap **Cast**. Or, in any browser, **Share → Cast to TV**. Videos on the
tablet: **Cast a file**, or Share → Cast to TV from VLC, Files, Gallery. Anything else: **Mirror screen**.

Developed on a Samsung Galaxy Tab S2 (LineageOS 21, Android 14, 32-bit ARM, no Google apps) and a Chromecast HD
(Android TV 14) with FUTO's FCast Receiver. Licence: GPL-3.0-or-later (see `LICENSE` and `THIRD_PARTY.md`).

## Limits (stated, not fought)

- **No DRM.** Widevine/PlayReady/FairPlay streams (Netflix, Prime Video and other subscription services) are
  encrypted for the device that plays them; no casting app can hand them to the TV. Use the TV's own apps. Tab Cast
  marks such streams "DRM (won't play)".
- **No Google Play Services.** Tab Cast talks to receivers directly (FCast protocol, and the Cast protocol's Default
  Media Receiver through the FCast SDK); it doesn't use the Google Cast SDK, so no Google-only receiver apps.
- Players that don't load a manifest or file at all (YouTube's own player, Vimeo's web player): there yt-dlp is the
  route (built-in site rules send these pages to yt-dlp).
- URLs signed for one client fingerprint, live WebRTC players, P2P-only players: rare; the Cast list shows them
  failing ("HTTP 403"), and the relay (long-press → "Cast through the tablet") is the next thing to try.
- The tablet APK is built for **armeabi-v7a** only (the reference tablet); see "Build" to change `abiFilters`.

## Install

**Tab Cast TV** (on the TV): add the F-Droid repo **https://pennico.github.io/tab-cast-fdroid/repo** in an F-Droid
client (F-Droid, Droid-ify) and install Tab Cast TV from there, or install the APK by USB:

```sh
adb install --no-streaming -r tab-cast-tv-<version>.apk
```

From 0.5.1 Tab Cast TV updates itself from that repo (see below). One-time setup on the TV for that: "Install unknown
apps" for Tab Cast TV (the TV offers the settings page; by USB:
`adb shell appops set dk.pennico.tabcast.tv REQUEST_INSTALL_PACKAGES allow`).

**Tab Cast** (on the tablet): the APK from the GitHub release, by USB (or open it on the tablet):

```sh
adb install --no-streaming -r tab-cast-<version>.apk
```

(`--no-streaming`: streamed installs fail on some tablets with "splice failed: EINVAL".) Check the `.sha256` next to
each APK. **Pairing:** the first time the tablet opens a page on the TV, Tab Cast TV shows a 6-digit code; type it
on the tablet once.

The TV, the tablet and any phone using the web remote must be on the same Wi-Fi.

## How it works

1. **Catching (any site, no list).** Tab Cast is a small browser on the system WebView. A script injected at document
   start into every frame (`shared/src/main/assets/inject.js`) and the WebView's request hook
   (`shouldInterceptRequest`) watch what the page actually plays:
   - HLS (`.m3u8`) and DASH (`.mpd`) manifests, found by URL, by `Content-Type`, or by sniffing fetch/XHR responses.
     This finds the stream behind players that play `blob:` URLs through MediaSource (hls.js, Shaka, dash.js, video.js…),
     also inside cross-origin iframes (embedded players);
   - `<video>`/`<source>` files (MP4, WebM…), and which video is really **playing** (duration, size);
   - `og:video` / JSON-LD metadata and embedded player URLs.

   The Cast list ranks what was caught (`core/Catch.kt`): the master playlist of the playing video first, then DASH,
   then files; ad hosts, segments, dead links (it test-fetches the top few, `core/Http.kt` Prober) and DRM streams go
   down. It shows quality, length and size.
2. **yt-dlp** (`ytdlp/YtDlp.kt`, youtubedl-android: Python + yt-dlp, armeabi-v7a) for sites whose player doesn't load
   a castable stream (YouTube, Vimeo): the Share target tries it first (for sites without a rule with yt-dlp's generic
   extractor off, so an unknown site goes to the browser in seconds instead of half a minute); in the browser it
   starts by itself on video pages of yt-dlp sites (`ytdlpAuto`) and is the "Find with yt-dlp" button, also for each
   embedded player on the page. Its steps show live (Share dialog, Cast button, Cast list) and each run logs a
   timeline (`yt-dlp steps: 0.0 s start · …`) and its outcome; runs time out (60 s quick try, 150 s full run).
   **YouTube speed:** the default clients need YouTube's 2.6 MB player script and its JavaScript challenge, which took
   most of a minute on the reference tablet, every time. The `visionos` client returns the same HLS master without
   any of it, so the YouTube rule tries it first (`ytdlpTryFirst`) and falls back to the default clients if it fails
   (age check, signed-in cookies). `core/YtDlpPicker.kt` picks what the TV can play: HLS master > DASH > file with
   sound and picture (YouTube's separate video-only/audio-only files are skipped). yt-dlp updates itself weekly
   (Settings).
3. **Site rules** (JSON) for what neither of the two handles; see below.
4. **Sending.** FUTO's FCast sender SDK (MIT, the library Grayjay uses; Rust core) speaks both:
   - **FCast Receiver** on the TV (default, recommended): the TV gets the stream URL **plus the page's headers and
     cookies** (Referer, Origin, User-Agent, Accept-Language, Cookie) and its ExoPlayer sends them with every request,
     so protected streams play without the tablet in the middle.
   - **Chromecast built-in** (Default Media Receiver `CC1AD845`): it can't take headers and needs CORS for HLS/DASH,
     so by default the stream goes **through the tablet**: `core/RelayServer.kt` fetches with the page's headers and
     fresh WebView cookies, adds CORS, and rewrites manifests so segments and keys also go through it. Only random
     per-cast session paths are served (not an open proxy).
5. **What the TV can decode** (`core/HlsFilter.kt`): before an HLS master goes to the TV, the tablet reads it and
   leaves out variants above Settings → TV picture (default ≤1080p, H.264 only, ≤60 fps). YouTube offers up to
   2160p60 VP9 at 28 Mbit/s; the Chromecast HD's receiver switched up to one it couldn't decode and the picture froze
   while the sound went on. When something is left out, only the filtered master is served by the tablet (relay
   session in "playlist only" mode); the TV fetches everything else itself. If the TV hasn't fetched it within 12 s
   (can't reach the tablet), the original playlist is sent instead.
6. **Remote**: play/pause, seek, ±10/30 s, TV volume (also with the tablet's volume keys), stop. A foreground service
   (mediaPlayback, wake + Wi-Fi lock) keeps the connection and the relay alive with the screen off and owns the
   media notification + lock-screen controls for **every** cast, also pages open on Tab Cast TV. A command finding
   the connection lost reconnects first; a keep-alive reconnects during a cast. **Stop always works**: it ends the app
   side at once (state, relay, service, notification) and tells the TV on its own thread (4 s limit, then the
   connection is dropped); a cast still on its way can't restart the TV afterwards. If the app was killed while
   casting, the next start (or the notification's Stop) offers to stop what is still playing.
7. **Start page**: your sites as tiles (⋯ to rename/move/remove, ⋮ → "Add this page to the start page"), recent
   casts with "Cast again", public test pages folded away; `core/StartPage.kt`, screenshots with `jstest/shot.mjs`.

Robustness on a 2 GB tablet: the request hook never touches the WebView (it runs on Chromium's network threads,
also for service workers); if Android kills the page's renderer for memory, the browser reopens the page instead of
the whole app dying; a watchdog logs where the main thread is when it hangs for 5 s.

The TV is found by mDNS and by **scanning the Wi-Fi /24 for ports 46899 (FCast) and 8009 (Cast)**, because mDNS is
unreliable on some networks; it is remembered by IP, and if the IP changes (DHCP) a cast re-scans and updates it.
Everything runs on the tablet and the TV; there is no server.

### Why Kotlin and not C

This app is Android UI + the system WebView + Android networking/media-session APIs, which have no practical C (NDK)
API; the casting SDK and the yt-dlp wrapper are JVM libraries. So Kotlin. The one C part is the per-frame RGBA → YUV
conversion for the mirroring encoder (`native/yuv.c`, NEON on ARM), pure computation on every pixel at 30 fps.

## Tab Cast TV: the browser on the TV (0.2.0)

`tv/` is a browser with the same catching code as the tablet (`:shared`); the tablet opens pages in it and is its
remote, over `:link` (TCP, the TV listens on 46898; the tablet finds it at the saved TV's IP).

- **Opening a page:** Share → **Open on TV**, or ⋮ → **Open this page on the TV** (or **…with my login**, which
  sends the tablet's cookies for that site).
- **Playing:** when the page's video plays, the TV switches to Media3's player, full screen. The TV loaded the page,
  so its own cookies, user agent and Referer go with the requests and no relay is needed. If that player fails, the
  page's own player is stretched over the screen (inject.js `maximize`, no gesture needed).
- **Remote (TvRemoteActivity):**
  - a live picture of the TV page (JPEG, 3/s): tap, drag and hold are forwarded as touches
  - player controls (native player, or inject.js commands to the frame that plays)
  - TV volume (Android media volume)
  - the list of caught videos
  - Back / Reload / Type / Close
- **Pop-ups:** new windows are refused. A page that tries to jump to another site without a click is stopped; the
  remote offers "Open anyway".
- **Log:** `adb pull /sdcard/Android/data/dk.pennico.tabcast.tv/files/tabcast-tv.log` or `adb logcat -s TabCastTV`.

**Keeping control (0.3.3):** while a page is open on the TV, the tablet keeps the link up (reconnects 1 s … 15 s
backoff) and shows the media notification. Playback commands carry an id and Tab Cast TV answers `ACK`; one sent
on a dead link or unanswered for 4 s waits, the link is rebuilt and it is sent again (≤ 30 s old). Commands are
idempotent (play/pause/absolute seek/volume). `TvLinkSessionTest` covers it.

## Films from the tablet (0.4.0)

**The TV plays; the tablet only starts it.** Cast a file (or Share → Cast to TV from VLC/Files/Gallery) serves the
video as it is from the tablet (`link/FileServer.kt`: GET/HEAD with Range, random per-cast path, nothing else
reachable). Tab Cast TV copies it into a **rolling cache** on its disk (`link/FilmCache.kt`) and its player reads
only from there (`tv/FilmSource.kt`), so the tablet can sleep once the TV holds enough:

- chunks of 4 MB in `files/films/<id>/` on the TV; budget = what it holds + free space − 1 GB; download limit
  (default 20 Mbit/s, some home routers hang under full load) in the tablet's Settings › Advanced, sent with each film
- fetch order: the chunk being read, first + last chunk (container header and index, never evicted), forward to the
  end, then what lies before; full → evict furthest behind (96 MB kept for rewinds), then furthest ahead
- tablet unreachable → retry 1 s … 15 s forever; the player waits at the edge ("Waiting for the tablet…") and goes on
  by itself; a player error re-prepares where it was (3 s … 30 s)
- one film cached at a time: the next one deletes the previous; the end deletes it; the position is kept per film
  (`TvFilms.kt`, resumes from there unless in the first 30 s or the last minute)
- tablet: foreground service with wake + Wi-Fi lock only while the TV still needs bytes (`CastService.holdLocks`);
  a restarted tablet app serves the same file again when the TV still plays it (`FileCast.resumeIfNeeded`), the TV
  just switches address
- tracks: Media3 lists audio/subtitle tracks; unsupported ones (DTS on the Chromecast HD) are marked, and a film with
  no playable sound says so
- subtitles (0.5.1): remote › Subtitles… › **Choose a subtitle file…** (remembered per film), or a same-name file
  next to the film (`Film.srt`, `Film.en.srt`, …), found only when Android lets Tab Cast list the film's
  folder (optional `adb shell appops set --uid dk.pennico.tabcast MANAGE_EXTERNAL_STORAGE allow`, undo `…
  MANAGE_EXTERNAL_STORAGE default`). Read in any common encoding and sent as UTF-8 (`link/Subtitles.kt`); the TV
  keeps its own copy (the tablet may sleep) and shows them large on a dark box.
  Subtitle files (0.5.4): SubRip `.srt` (also with '.' before the milliseconds or without cue numbers), WebVTT `.vtt`,
  SSA/ASS, TTML/DFXP, and, converted to SubRip on the tablet, MicroDVD and SubViewer (`.sub`; MicroDVD without a
  frame rate in the file is read at 23.976 fps), YouTube `.sbv`, SAMI `.smi` (first language) and MPL2. Picture
  subtitles (VobSub `.idx`/`.sub`, PGS `.sup`) can't be sent as files.
  Subtitles inside the film (0.5.4: Media3 1.9): SubRip, SSA/ASS, WebVTT, TTML, DVD VobSub, Blu-ray PGS and DVB. A
  track the TV still can't use is marked "can't play here" in Subtitles…/Sound…; choosing it changes nothing, and a
  subtitle track that fails while playing is turned off while the film goes on

## Mirror screen (0.5.0)

The fallback for whatever has no castable source (a page that fails, an app): the tablet's whole screen and sound on
the TV. It is the one mode that needs the tablet awake. Sender `app/.../mirror/` (`MirrorService`, `MirrorEncoder`,
JNI `app/src/main/jni/mirror_jni.c` + `native/yuv.c`), receiver `tv/.../TvMirror.kt`:

- capture: MediaProjection → VirtualDisplay → ImageReader RGBA (960×720 box) → C/NEON → VP8 (hardware encoder, else
  c2 software; on the reference tablet's ROM the H.264 encoders are unusable for apps, see `probe/`)
- a lost encoder is made again; three losses a minute step down to the software encoder; when nothing is left,
  mirroring stops with a plain reason on the tablet and the TV (0.5.2)
- sound: AudioPlaybackCapture of media/game/unknown usages (apps that forbid capture stay silent), PCM 48 kHz stereo
- TV: MediaCodec VP8 → SurfaceView; AudioTrack; picture held back by the sound's delay; ≤ 0.3 s queue, then skip to
  a key frame
- tablet screen: kept on and dimmed by a 1-pixel overlay (optional permission), power button = stop
- tablet settings that help (adb, reversible): `adb shell appops set dk.pennico.tabcast PROJECT_MEDIA allow` (skips the
  capture dialog where the ROM honours it) and `… SYSTEM_ALERT_WINDOW allow` (the overlay); undo with `… default`

## Web remote and pairing (0.3.0)

Tab Cast TV also serves a **web remote** on port 46897 (`tv/.../WebRemote.kt`, page `tv/src/main/assets/remote/`),
for Safari on the iPhone (also as a Home Screen web app) and browsers on a computer on the same Wi-Fi.

**Pairing** (`link/.../Pairing.kt`) guards both the web remote and the tablet's link:
- The TV shows a 6-digit PIN for 2 minutes, from "Pair a device" on its waiting screen or when an unknown device
  knocks (at most once per 10 s).
- 5 wrong tries end the PIN and lock pairing for 60 s.
- A paired device presents a 256-bit token: link HELLO, the `X-TabCast-Token` header, or the `tc` cookie
  (HttpOnly, SameSite=Lax). A cookie-only POST also needs `X-Requested-With: tabcast` (no CSRF).
- The TV stores SHA-256s of the tokens only; "Paired devices" on the TV removes one.

**Open on TV from other apps:** `POST /api/open {"url": …}` with the token. Two clients:
- `tools/make-shortcut.py` writes an Apple Shortcut for the Share menu that asks for the TV's address and the token
  at import. Sign it on a Mac: `shortcuts sign --mode anyone`.
- The web remote shows a bookmarklet that opens `/open?url=…` (one tap to confirm).

Tests: `PairingTest` (PIN, tries, lockout, expiry, hashes, revoke), `LinkPairingTest` (an unpaired tablet can't
command or push the paired one out), `MiniHttpTest`, `TvSmokeTest` (the whole flow over real HTTP and the link, in
Robolectric), `jstest/remote-shot.mjs` (screenshots of the page against a mocked API).

## Ad and tracker blocking (0.2.2)

Both apps block ads and trackers with the filter lists Brave and uBlock Origin use: EasyList, EasyPrivacy, uBlock
filters + privacy (shipped in `shared/src/main/assets/filters/`, refreshed by `tools/fetch-filters.sh`) and Peter
Lowe's list (not shipped: the apps download it). The apps download all of them weekly on Wi-Fi into `files/filters/`,
the first time about a minute after the first start.

**Engine:** `shared/.../core/adblock/` is a compact matcher written for this, in Kotlin. Why not Brave's adblock-rust:
it would mean a Rust build from source; per-request matching is microseconds next to the WebView, and the data sits
in primitive arrays (as compact as C); the same code runs in both apps and in the JVM tests.

**How it works:**
- ~100k host rules sit in a hash table of 64-bit host hashes, and 13k pattern rules in a token index.
- Element hiding: site-specific rules apply per frame; generic rules apply only for the classes and ids a page
  actually has (inject.js sends them, at Aggressive).
- Compiled once into `files/filters/engine.bin`. After that a start only reads that file.

**Levels:** Off; Standard (default: third-party requests only, like Brave's default); Aggressive (also first-party,
plus generic element hiding). Per-site levels are set from the tablet's ⋮ menu or the TV remote. Blocked requests get
an empty 403.

**Numbers (desktop JVM):** compile 0.7 s, engine arrays 6.4 MB, cache load 10 ms, 14 µs per request. Replayed
recordings (Standard): Euronews 88 % of 2714 requests blocked, TED 66 %, Dailymotion 23 %, PeerTube 4 %, hls.js
demo 6 %, archive.org 0; no media or player request blocked.

Tests: `FilterEngineTest` (list-syntax semantics), `RealListsTest` (shipped lists: sample verdicts, speed, memory),
`AdblockReplayTest` (jstest recordings, when present), `PackagedListsTest` (the lists load from the built APK).

### "What prevents any site from working instead of a list of sites?"

Grayjay and VLC need per-site code because they don't run the site: they must know how each site hides its video.
Tab Cast runs the page in a real browser engine and takes the stream the page itself plays, so unknown sites work
without a list. The limits are the ones above: DRM, and players with no castable stream (then yt-dlp's ~1,800 site
extractors, then site rules).

## Site rules

Built-in: `shared/src/main/assets/site-rules.json`. Your own: Settings → Site rules (JSON, validated on save; yours
are applied before the built-in ones). Fields (all optional except `match`):

| Field | Meaning |
|---|---|
| `name` | Label |
| `match` | Regex on the page host, e.g. `(^\|\\.)example\\.com$` |
| `prefer` / `ignore` | Regexes on stream URLs: rank first / drop (e.g. ad CDNs) |
| `headers` | Extra headers sent with the stream (TV or relay), e.g. a fixed `Referer` |
| `resolver` | Share target route: `auto` (yt-dlp, then browser), `ytdlp`, `browser` |
| `userAgent` | `desktop`, `mobile` or a literal UA for this site's pages |
| `relay` | `true` = always through the tablet, `false` = never |
| `script` | JavaScript function body run in every frame of matching pages; return a URL or a list of URLs |
| `ytdlpArgs` | Extra yt-dlp options |
| `ytdlpRewrite` | `[regex, replacement]` applied to the page URL before yt-dlp (Vimeo: page → player URL) |
| `ytdlpTryFirst` | yt-dlp options for a quick first try; if it fails, yt-dlp runs without them (YouTube: `player_client=visionos`) |
| `ytdlpAuto` | Regex on the page URL: the browser starts yt-dlp by itself on such pages (video pages, not home/search) |

Built-in rules today: YouTube → yt-dlp; Vimeo → yt-dlp on the player URL (Vimeo's web player gets a
Widevine-encrypted DASH stream, and yt-dlp needs a login for vimeo.com pages but not for `player.vimeo.com`, which
gives plain HLS); Dailymotion → browser (yt-dlp needs curl_cffi impersonation, which the Android build lacks).

## Updates through F-Droid (0.3.3) and self-update (0.5.1)

**Tab Cast TV** is published in an F-Droid repo: **https://pennico.github.io/tab-cast-fdroid/repo** (GitHub
`pennico/tab-cast-fdroid`, Pages; APKs + signed index only). Tab Cast TV reads that repo itself
(`tv/TvUpdates.kt`, `tv/SelfUpdate.kt`, `link/RepoIndex.kt`): at start and once a day it downloads `index-v1.jar`,
accepts it only if its signer certificate has the pinned SHA-256 fingerprint, and compares the newest Tab Cast TV in
it with its own version code. A newer one shows as **Update to x.y.z** (TV start screen; tablet TV remote › **Update
TV app**; web remote banner). The update starts only when someone presses it: download with progress, SHA-256
against the index, PackageInstaller (Android checks the release signature). After an update the TV shows its home
screen: open Tab Cast TV again. "What's new": `tools/fdroid/whatsnew/tv-<code>.txt`.

- Release builds are signed with the release key (PKCS12 alias `tab-cast`), the index with a separate F-Droid key.
  Neither is in git: `tools/publish-fdroid.sh` reads them from `$TABCAST_KEYDIR` (base64 PKCS12 files + a
  `release.env` with the passwords) into a temp folder.
- `tools/publish-fdroid.sh`: `build.sh --pair` (lint + tests) → release Tab Cast TV → `fdroid update` (fdroidserver
  from `$FDROID` or the PATH, config written to a temp folder, last 3 versions per app in `repo/`, older in
  `archive/`) → commit + push → waits until Pages serves the new `index-v1.jar`, prints the repo fingerprint.
  `--dry-run` stops before the push (checkout in `build/fdroid-checkout/`). It refuses to publish a tablet APK.
- A release-signed app can't update a debug-signed one: switching means uninstalling once (pairing and settings are
  lost).
- Metadata and the repo icon: `tools/fdroid/`.

## Build and test

Toolchain: JDK 17, Android SDK platform 35 + build-tools 35.0.0, NDK 27.2.12479018 (r27c); the Gradle wrapper fetches
Gradle 8.11.1 (AGP 8.7.3, Kotlin 2.0.21). `tools/env.sh` uses `JAVA_HOME` + `ANDROID_HOME`, or a self-contained
folder in `TABCAST_TOOLCHAIN` (`jdk17/`, `sdk/`, `gradle-home/`); local settings can go in a git-ignored
`private/env.sh`, which it sources first. The tablet APK is armeabi-v7a only (`ndk { abiFilters }` in
`app/build.gradle.kts`); add `arm64-v8a` there for other devices.

```sh
tools/build.sh            # host C tests + lint + JVM/Robolectric tests + APK -> dist/tab-cast-<version>.apk (+ .sha256)
tools/build.sh --replay   # also replays jstest/out recordings through the catch code (network)
tools/build.sh --pair     # tablet app + Tab Cast TV -> dist/tab-cast-<v>.apk + dist/tab-cast-tv-<v>.apk
tools/build.sh --tv       # Tab Cast TV only
tools/build.sh --probe    # the mirroring probe instead -> dist/tab-cast-probe-<version>.apk (see probe/README.md)
make -C native test       # host tests of the shared C code (build.sh runs them too)
scripts/scan-private.sh   # no private patterns in the tree (maintainers; reads private/patterns.txt)
```

**Signing:** debug builds use `TABCAST_DEBUG_KEYSTORE` when set (keep one key so `adb install -r` updates in place),
else Gradle's `~/.android/debug.keystore`. Release builds need `TABCAST_RELEASE_STORE`, `TABCAST_RELEASE_STORE_PASSWORD`
(and optionally `TABCAST_RELEASE_KEY_ALIAS`); without them `assembleRelease` gives an unsigned APK.

Tests:
- `CoreTest`, `RelayTest` (fake CDN that requires Referer + cookie: HLS chain, keys, DASH redirect, ranges,
  keep-alive, CORS), `YtDlpPickerTest`: pure Kotlin.
- `ScreensSmokeTest`, `TvSmokeTest`: open every screen under Robolectric (Android 14) to catch launch crashes.
- `CatchReplayTest` (opt-in): `jstest/catch.mjs` loads a real page in headless Chromium with `inject.js`, an
  Android user agent and a tablet viewport, records requests + script messages; the test replays them through
  `CatchSession` and probes the winners. Report: `jstest/out/report.md` (recordings stay out of git).
  ```sh
  jstest/setup.sh                      # once: Playwright + Chromium (git-ignored)
  jstest/run.sh hlsjs https://hlsjs.video-dev.org/demo/ 20
  TABCAST_REPLAY=1 ./gradlew testDebugUnitTest --tests '*CatchReplayTest*'
  ```
- `YtDlpPickerTest.realOutputs` (opt-in): `TABCAST_YTJSON=<dir of yt-dlp --dump-single-json files>`.
- `jstest/play.mjs <url> [inject 1|0] [gesture|free]`: does the page's own video play (with/without `inject.js`,
  with the WebView's old "user gesture required" rule or without)?
- `jstest/shot.mjs`: screenshots of the start page (`StartPageTest` writes the HTML to `app/build/start-page/`) at
  1024×768 CSS px, light and dark, landscape and portrait → `jstest/out/start-*.png`.
- `WebCatcherThreadTest`: the request hook called off the main thread with a WebView that throws there.

Logs on the device: Settings → Show log, or `adb pull /sdcard/Android/data/dk.pennico.tabcast/files/tabcast.log`,
or `adb logcat -s TabCast`.

## Layout

```
app/         the tablet app
  src/main/java/dk/pennico/tabcast/
    cast/    CastManager (FCast SDK), CastService (foreground, notification, volume keys), Devices (store, scan, mDNS)
    files/   FileCast (films from the tablet)
    mirror/  MirrorService, MirrorEncoder, MirrorRecovery (Mirror screen) + src/main/jni/
    tvlink/  TvLink (the connection to Tab Cast TV)
    ytdlp/   YtDlp (youtubedl-android)
    ui/      BrowserActivity, CastSheet, ShareActivity, RemoteActivity, TvRemoteActivity, DevicesActivity, Settings…
shared/      Android library used by both apps: core/ (catching, manifests, relay, rules, adblock), web/WebCatcher +
             AdBlock, AppLog, assets (inject.js, site-rules.json, filters/); its tests
link/        tablet <-> Tab Cast TV wire format, pairing, film cache + file server, F-Droid index check (pure JVM)
tv/          Tab Cast TV: TvActivity, TvPlayer (Media3), TvMirror, TvFilms, WebRemote, TvUpdates/SelfUpdate
native/      shared C (RGBA -> YUV 4:2:0 for the mirroring encoder) + host tests
probe/       mirroring probe (separate debug app): capture/encode/projection measurements on a device
jstest/      headless-Chromium recorder for the catch replay, screenshots
tools/       env.sh, build.sh, publish-fdroid.sh, fetch-filters.sh, make-shortcut.py, run-probe*.sh
scripts/     scan-private.sh
```

## Licence

Tab Cast is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. See `LICENSE`. Third-party components and filter lists, with their licences: `THIRD_PARTY.md`.
