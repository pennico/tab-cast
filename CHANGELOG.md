# Changelog

## 0.5.4 (2026-11-26): subtitles that don't stop the film, more subtitle formats, steadier Mirror screen

1. **A subtitle or sound track the TV can't play no longer freezes the film.** Choosing one used to force it on; the
   player failed, and every retry failed again with the same track until subtitles were turned off. Now the TV
   refuses such a track with a note and keeps the current one, and a subtitle track that fails while playing is
   turned off while the film goes on. The tablet's Subtitles…/Sound… explain a track marked "can't play here" and
   offer "Choose a subtitle file" instead.
2. **DVD subtitles (VobSub) inside films now show**: Tab Cast TV moves from Media3 1.4.1 to 1.9.4. Films can also
   carry SubRip, SSA/ASS, WebVTT, TTML, Blu-ray PGS and DVB subtitles.
3. **More subtitle files**: MicroDVD and SubViewer (`.sub`), YouTube `.sbv`, SAMI `.smi` and MPL2 are converted to
   SubRip on the tablet (so older TV versions show them too); TTML/DFXP goes to the TV as it is. SubRip files with a
   dot before the milliseconds or without cue numbers, which were accepted but showed nothing, are rewritten in the
   strict form. Picture subtitle files (VobSub `.idx`/`.sub`) are refused with a plain message.
4. **Mirror screen keeps going after a lost encoder.** It used to step down to the software VP8 encoder after three
   losses within a minute, and on some tablets that encoder never produces a frame, so mirroring stopped within
   seconds. Now the hardware encoder is made again after a growing pause (0.3 s, 1 s, 2 s); the software one is used
   only when there is no hardware encoder or it has already worked; mirroring stops only after about ten losses in a
   minute. The encoder also gets evenly spaced timestamps instead of capture times (which jump by seconds on a still
   screen, the likely trigger of the losses), and the log line of a loss names the gap before it and the last
   setting change.

## 0.5.3 (2026-11-20): first public source release

1. **Open source** under GPL-3.0-or-later: `LICENSE`, `THIRD_PARTY.md` (every bundled library and filter list with
   its licence), SPDX headers, a public README. No change in what the apps do, except:
2. **Peter Lowe's ad and tracking server list is no longer shipped** in the APKs (its page states no licence for
   bundling). The apps download it with the weekly list update (the first time about a minute after the first start,
   on Wi-Fi) and compile without it until then. `lists.txt` marks such lists `download`; `PackagedListsTest` checks
   that they are not in the APK.
3. Build settings that differ per machine come from the environment (`TABCAST_TOOLCHAIN`, `TABCAST_DEBUG_KEYSTORE`,
   `TABCAST_KEYDIR`, `FDROID`) or a git-ignored `private/env.sh`; `scripts/scan-private.sh` checks the tree for
   private patterns.

## 0.5.2 (2026-11-19): Mirror screen survives a lost encoder; the first self-update of Tab Cast TV

1. **Mirror screen no longer takes the app down** (in 0.5.0 every attempt died 3-30 s
   in with `IllegalStateException: Invalid to call at Released state` from `dequeueOutputBuffer` in the once-a-second
   tick, uncaught on the "mirror" thread). The cause isn't in the logs (the media logs had rotated out; in three of the
   five crashes no video app was open, so "reclaimed by VLC/Brave" doesn't explain them all; the probe never ran the
   Exynos encoder longer than ~3 s). So:
   - The encoder runs in **async mode** (`MediaCodec.Callback` on the mirror thread): Android reports why a codec ends
     (`onError`: reclaimed for another app, no room on the chip, the component died, with its diagnostic code), and
     every codec call is guarded. Logged as `mirror: encoder lost: <codec> after N s and F frames (…, loss n): <why>;
     codec record: <Android's own metrics: error code, state>`.
   - **A lost encoder is made again** after 0.3 s, at the same level; three losses within a minute step down from the
     hardware encoder (960x720, 30 fps) to the software one (c2.android.vp8.encoder at 640x480, 15 fps, 1.2 Mbit/s;
     the TV restarts its picture at the new size). Projection, VirtualDisplay and sound carry on (`MirrorRecovery`).
   - **Nothing left:** mirroring stops cleanly with plain words on the tablet (toast + a notification that stays) and
     on the TV (12 s): "The tablet's video chip is busy with another app" (or "…video encoder keeps stopping").
     STATE `mirror=off` carries an optional `why` (an older TV ignores it).
   - An exception escaping the mirror thread now ends mirroring with a log line instead of killing the app.
   - The encoder log line says hardware/software and which settings stuck: `full` (CBR + real-time priority +
     latency), `realtime` (no CBR) or `minimal` (the Exynos refused "full" in probe 2; 0.5.2 tries without CBR before
     dropping the real-time priority).
2. **Tab Cast TV 0.5.2** is the first version installed by its own updater; it shows the
   tablet's "why" when mirroring ends.
3. Tests: `MirrorRecoveryTest` (5), `TvSmokeTest` (a size change mid-mirror; the stop note on the TV). Lint 0 issues.

## 0.5.1 (2026-11-18): Tab Cast TV updates itself; subtitle files for films; probe 2 fixes

1. **Tab Cast TV updates itself** (no Droid-ify needed any more). At start and once a day it reads its F-Droid repo's
   signed index (`link/RepoIndex.kt`: the jar's signer certificate must have the pinned fingerprint `8cf18bc2…47f0dd`,
   checked against the real index in `RepoIndexTest`). A newer version shows as **Update to x.y.z** on its start
   screen (D-pad), as a banner on the web remote and as **Update TV app to x.y.z** on the tablet's TV remote (CMD
   `update`: `install` | `check`; STATUS `update`, `updatestate`). Pressing it: what's new → download into the
   app's cache with progress → SHA-256 from the index → PackageInstaller session (`tv/SelfUpdate.kt`), no OK needed
   on Android 12+ with `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, else one OK in Android's dialog; Android checks the
   signer. First time: "Install unknown apps" for Tab Cast TV (the TV opens that page; the adb line if it has none).
   Plain errors ("No internet: …", "Download failed, try again", "Update cancelled: the app stays as it was");
   failed downloads are deleted and sessions abandoned. A film's position is saved before the app is replaced.
   "What's new" comes from `tools/fdroid/whatsnew/tv-<versionCode>.txt` (F-Droid changelogs).
2. **Subtitle files for films from the tablet** (they weren't played). Remote › **Subtitles… › Choose a
   subtitle file…** (system picker; .srt, .vtt, .ass, .ssa, also when the file has no proper type), remembered per
   film; a same-name file next to the film is still used when Tab Cast can read the folder. Any encoding (UTF-8 with
   or without BOM, UTF-16 with BOM, else Windows-1252 = Latin-1 + €…) is sent as UTF-8 (`link/Subtitles.kt`,
   `SubtitlesTest`). The TV fetches the file once into its own storage (so the film keeps its subtitles while the
   tablet sleeps), goes on from the same place when they arrive mid-film, and shows them larger, white with an
   outline on a dark box, ignoring the file's own font sizes. Logged on both sides (`file: subtitles …`,
   `film "…": subtitles from the tablet, N bytes`).
3. **Mirroring, from probe 2:** the C converter now writes only into a buffer of Tab Cast's own; Java copies it into
   the encoder's buffer with bounds checks (c2.android.vp8.encoder crashed the probe with SIGSEGV). Encoders get an
   explicit I420/NV12 input layout (not "flexible", whose buffer layout is undefined). When the tablet turns, the
   one VirtualDisplay is resized and gets a new ImageReader (Android 14 allows one per projection). Probe 2 itself:
   the same converter guard.
4. Tests: `RepoIndexTest` (5: signed index, other key, changed index, unsigned index, what's new), `SubtitlesTest`
   (5), `TvSmokeTest` (update keys in STATUS; subtitles arriving mid-film are fetched and kept). Lint 0 issues.

## 0.5.0 (2026-11-18): mirror the screen; help for pages that fail

1. **Mirror screen** (home, third big button): whatever the tablet shows and plays goes to the TV through Tab Cast TV.
   `MirrorService` (foreground, type mediaProjection): MediaProjection of the whole screen → VirtualDisplay →
   ImageReader RGBA fitted into 960×720 (the 4:3 screen fills it) → C/NEON conversion (`native/yuv.c`, now also in
   the tablet app as `libtcmirror.so`) → VP8 with `MirrorEncoder` (OMX.Exynos.VP8.Encoder, else c2.android.vp8.encoder;
   CBR 3 Mbit/s, key frame every 2 s and on request) → the link (`Wire.VIDEO`, newest kept, ≤6 queued; after a drop
   the sender waits for the next key frame). Sound: AudioPlaybackCapture (48 kHz stereo PCM, 20 ms per `Wire.AUDIO`).
2. **On the TV** (`TvMirror`): VP8 decoder onto a SurfaceView letterboxed in 16:9, AudioTrack (low-latency) at Tab
   Cast TV's own volume; the picture is shown when its sound comes out (A/V by the tablet's capture timestamps); more
   than ~0.3 s behind → it drops to the next key frame and asks for one (`Wire.KEYFRAME`); a still screen gets the
   last key frame and what followed sent again.
3. Light on the CPU: new images only when the screen changes, at most 30/s; a slow Wi-Fi (10+ dropped frames) halves
   the bitrate once. Screen kept on while mirroring with the backlight at its minimum through a 1-pixel overlay
   (needs "display over other apps", asked once; else a dim wake lock keeps it on as it is). The power button, Stop
   (notification, remote) or Back on the TV end it cleanly. DRM video shows black (said in the start dialog).
4. **Pages that fail** (phase 3): the Cast list offers **Mirror this instead**; the TV remote has **This didn't
   work**: the TV sends its state, caught list and log to the tablet, which saves them with its own log in
   `Android/data/dk.pennico.tabcast/files/reports/<time>/` (one `adb pull`). Pop-ups and new windows
   stay refused on the TV (since 0.2).
5. Link (backward-compatible): HELLO_ACK `mirror=1`; uses the reserved `VIDEO_CONFIG/VIDEO/AUDIO_CONFIG/AUDIO/KEYFRAME`
   messages; `STATE mirror=off`; STATUS mode `mirror`; CMD `report` → `REPORT` (110). An older TV: the tablet asks to update it.
6. Build: the tablet app now builds native code (ndk-build), so AGP strips native libs; youtubedl-android's `*.zip.so`
   are kept as they are (`keepDebugSymbols`).
7. Tests: `TvSmokeTest.mirrorView` (start, frames, volume, end from either side), `ScreensSmokeTest` opens the mirror
   screen; lint 0 issues. The encoder/decoder path itself needs the devices (probe 2 and a device test).

## 0.4.0 (2026-11-18): films from the tablet; the TV keeps them

1. **Cast a file.** A video on the tablet plays on the TV as it is (MKV/MP4/WebM…, H.264/HEVC/VP9/AV1 as the TV
   decodes them; no transcoding): Tab Cast → **Cast a file** (the tablet's videos, newest first, or "Choose another
   file…"), or **Share / Open with → Cast to TV** from VLC, Files, Gallery (new share target for videos,
   `FileShareActivity`). The tablet serves the file over HTTP with Range requests at a random path only
   (`link/FileServer`); the share permission can end with the share screen, so the file descriptor is opened at once
   and kept for the cast.
2. **Rolling cache on the TV** (`link/FilmCache`, pure JVM, tested over real HTTP): Tab Cast TV copies the film into
   4 MB chunks on its disk, as fast as the download limit allows (default 20 Mbit/s, Settings › Advanced; the router
   hung under full load), into what it may use: its free space minus 1 GB. Order: the chunk the player reads, the
   container's first and last chunk, forward to the end, then what lies before. Full: it evicts what is furthest
   behind the play position (96 MB kept for rewinds), then what is furthest ahead. A film that fits ends up entirely
   on the TV. The player reads only from the cache (`FilmDataSource`): a missing piece makes it wait, never fail.
   The cache of a film is deleted when it ends or the next one starts; the TV remembers the position per film.
3. **The tablet can sleep.** Tablet asleep or gone: the TV plays on from the cache; at the end of what it holds it
   pauses with "Waiting for the tablet…" and goes on by itself when the tablet answers (retries 1 s … 15 s,
   forever). The tablet keeps its wake + Wi-Fi lock only while the TV still needs bytes ("the whole film is on the
   TV" → locks off, log `cast service: wake + Wi-Fi lock off`). If the tablet app was restarted meanwhile, it serves
   the same file again when it sees the TV still playing it, and the TV switches to the new address without stopping.
4. **Remote for films** (same screen as the TV browser's): play/pause, seek, ±10/30 s, volume, **Sound…** and
   **Subtitles…** (embedded tracks; a sidecar `.srt/.vtt/.ass` with the film's name when Android lets Tab Cast see
   it), Stop, and in plain words how long the tablet is still needed ("On the TV up to 1:12:00: the tablet can sleep
   for about 40 min" / "The whole film is on the TV"). The notification and the iPhone's web remote say the same.
   A film whose sound the TV can't decode (DTS…) says so.
5. **Light UI review:** the start page opens with big **Cast a page / Cast a file** buttons; Settings keeps the
   everyday items on top and folds the technical ones (TV picture limits, relay, browser, yt-dlp, site rules, file
   download limit) under **Advanced settings**; the TV's waiting screen and the notification lose protocol names.
6. Link (one version backward-compatible): new message `FILE` (20), CMD `audio`/`subs`, STATUS `kind=file`,
   `fileid`, `heldto`, `held`, `complete`, `waiting`, `free`, `cache`, `atracks`/`atrack`, `stracks`/`strack`,
   `nosound`; HELLO_ACK `files=1`, `free`. A 0.3.3 TV doesn't say `files=1`: the tablet asks to update it.
7. Probe 2 (separate debug app 0.2.0-probe2, `tools/run-probe2.sh`): VP8 encoders at
   960×720/1280×720, other apps' sound capture, mirror → VP8 pipeline, a long dimmed run, the PROJECT_MEDIA appop.
8. Tests: `FilmCacheTest` (ranges, whole film, restart keeps chunks, bigger than the budget, tablet asleep → waits →
   resumes, rate cap, jump ahead), `TvSmokeTest.filmFromTheTablet`, `TvRemoteTouchTest.filmLandscape` (Tab S2 size).

## 0.3.3 (2026-11-15): controls keep working in the background; media controls for every cast; F-Droid

1. **Media controls for every cast.** A page opened on Tab Cast TV (Share → Open on TV, or ⋮ → "Open this page on
   the TV" in the in-app browser) had no notification and no lock-screen controls: only FCast/Chromecast casts
   started the media service. Now the same foreground service (type mediaPlayback, wake + Wi-Fi lock) and
   MediaSession serve both: title, play/pause, −10/+30 s, seek, stop, volume keys → TV volume. It also starts when
   the TV remote screen sees a page open on the TV (e.g. opened from the iPhone). It ends with Stop, or 5 s after
   the TV has nothing open.
2. **Tab Cast TV link kept up during a session.** When the link drops (Wi-Fi power save, the TV app restarted…) the
   tablet reconnects by itself (1 s, 2 s, 4 s … 15 s). Playback commands from the notification, lock screen and
   remote now carry an id and the TV answers `ACK`; a command sent on a dead link, or not answered within 4 s,
   waits (notification: "reconnecting…" / "sending…"), the link is rebuilt and the command is sent again (up to
   30 s old). The commands are idempotent (play, pause, absolute seek, absolute volume), so a resend never toggles
   twice. A 0.3.2 TV (no ACK) still works, without the resend.
3. **FCast / Chromecast:** a command is never a silent no-op any more. If the connection is gone (or still
   reconnecting after 5 s), it reconnects to the TV first, then sends; a failed command reconnects and retries once.
   A keep-alive check every 10 s reconnects a lost connection during a cast before the next button press needs it.
   Events from a replaced connection are ignored (they could mark the new one as disconnected).
4. **Battery:** at the first cast, Tab Cast asks once to run in the background (battery optimisation off). With it
   on, Android may refuse the media notification for a cast that starts in the background (Share → yt-dlp) and
   pause the app while the screen is off. The refusal is now logged with that hint.
5. **Logging** for the next tablet log: `media session: …` / `cast service started|stopped`, `TV session start|end`,
   `TV link closed …; reconnecting`, `TV link: reconnected after N tries`, `TV command <c> #id sent|done (ms)|no
   answer…`, `TV command <c> sent (ms)` (FCast/Chromecast), `keep-alive: …`, `media control: …`.
6. **F-Droid:** release signing (key from a secret store, never in git); `tools/publish-fdroid.sh` builds and tests
   the pair and publishes **Tab Cast TV only** to the private F-Droid repo `pennico/tab-cast-fdroid` (GitHub Pages;
   Droid-ify on the TV). The tablet app stays on USB (GPL-3.0 youtubedl-android inside). Public strings no longer
   name the Wi-Fi network or a LAN address.
7. Tests: `TvLinkSessionTest` (ACK; link cut → reconnect → command delivered; no ACK → reconnect → resend; a newer
   play/pause replaces a waiting one); `TvRemoteTouchTest` expects PAUSE/SEEK instead of TOGGLE/SEEK_BY.

## 0.3.2 (2026-11-14): simpler on the iPhone

1. **Start view in the web remote**, shown when nothing is open on the TV (and under "Open something else"
   otherwise):
   - your sites as tiles: the tablet's start-page sites (sent over the link when it connects) plus any added with
     "Add the TV's page to your sites"
   - the last 12 pages opened on the TV
   - the four test pages (HLS demo, archive.org MP4, PeerTube, TED)
   One tap opens a page on the TV.
2. **"Open copied link"**, a big button at the top:
   - Where the browser can read the clipboard (https/localhost), it opens the copied link at once.
   - On the TV's plain-http page, Safari doesn't offer that. The button focuses the box, and pasting a link there
     opens it on the TV right away. A link inside shared text is picked out.
3. **One-step pairing:** an unpaired device asks the TV for a code by itself and the code box is focused. Six digits
   pair at once. The rate limits stay.
4. **More:** the Shortcut and the bookmarklet moved under "More", out of the main steps. WEB-REMOTE.md starts with 5
   short iPhone steps.

## 0.3.1 (2026-11-14): ad blocking actually loads

1. **Cause:** 0.2.2–0.3.0 shipped the filter lists as `filters/<name>.txt.gz` assets. The APK build decompressed
   them and dropped the suffix, so the app opened files that weren't there (`FileNotFoundException:
   filters/easylist.txt.gz`). **Nothing was blocked on the devices.** The tests read the source tree, so they passed.
2. **Fix:**
   - The lists ship as plain `filters/<name>.txt`; the APK's zip deflates them, so the APK size is about the same.
   - One loader (`FilterLists`) serves the apps, the source-tree tests and the new packaged-APK test.
   - The weekly downloads still keep their own gzipped copies in `files/filters/`.
3. **`PackagedListsTest`** (tablet and TV modules) opens the APK Gradle just built and loads every list from its
   assets with the app's loader: 112,911 network rules and 24,550 hiding rules in both APKs, and doubleclick is
   blocked.
4. **Visible state:**
   - The log says `ad blocking active: N network rules, M hiding rules …` on start, or
     `ad blocking NOT active: …`.
   - The status carries `adsready`; the tablet remote and the web remote show "lists not loaded!" when it's false.

## 0.3.0 (2026-11-14): web remote + pairing for the iPhone and the Mac

1. **Pairing everywhere:** Tab Cast TV obeys only paired devices, the tablet too (it pairs once after updating).
   - The TV shows a 6-digit code, valid for 2 minutes and 5 tries; after 5 wrong tries pairing is locked for a minute.
   - The device gets a 256-bit random token. The TV stores only its SHA-256.
   - "Paired devices" on the TV's waiting screen (and in the web remote) removes one.
   - An unpaired device can't push the paired tablet off the link.
2. **Web remote** at `http://<TV>:46897`, mobile-first, light/dark, Add to Home Screen. It does what the tablet's
   remote does:
   - open an address; the live picture with tap/drag/hold; Type…
   - ⏸/▶, −10/+30 s, seek, volume
   - videos on the page, Play full screen / Page's own player, Back, Reload, Close page
   - ad blocking for the site or all sites, and the banners
3. **Open on TV from other apps:**
   - `POST /api/open` with the `X-TabCast-Token` header
   - an Apple Shortcut "Open on TV" for the Share menu on iPhone and Mac (`tools/make-shortcut.py`; it asks for the TV
     address and token at import, the file holds no secret; sign it on the Mac)
   - a bookmarklet that opens a one-tap "Open … on the TV?" page
   - separate, removable tokens for Shortcuts
4. **Security:** the cookie alone doesn't authorize a POST without the `X-Requested-With: tabcast` header, so no
   CSRF. Only http(s) addresses are opened.

## 0.2.2 (2026-11-14): Brave-level ad and tracker blocking in both apps

1. **Filter lists:** the lists Brave and uBlock Origin use: EasyList, EasyPrivacy, uBlock filters, uBlock filters –
   Privacy, Peter Lowe's list. They ship in the APKs (work offline) and update weekly on Wi-Fi. List versions, engine
   size and load time go into the log.
2. **Own compact engine** (`shared/core/adblock`), instead of the 115-host list of 0.1/0.2:
   - Network rules: host rules, patterns, exceptions, `$important`, types, first/third party, `$domain`, `$badfilter`,
     page-level `$document`/`$generichide` exceptions.
   - Navigation to known ad/malware pages (`$document` rules) is stopped.
   - Element hiding: site rules, plus generic rules for just the classes/ids each page has.
   - Rules it can't apply faithfully are skipped and counted, never half-applied: regex, `$csp`, `$removeparam`,
     `$redirect-rule`, scriptlets, procedural selectors.
3. **Levels**, each app with its own setting:
   - **Off**
   - **Standard** (default): cross-site ads and trackers, like Brave's default
   - **Aggressive**: also the site's own ads, plus generic element hiding
4. **Per-site exceptions:**
   - TV, from the tablet's remote: "Ad blocking on the TV…" sets this site's level or the level for all sites, and
     the page reloads. When a page's video doesn't start and requests were blocked, the remote offers "Turn ad
     blocking off for this site".
   - Tablet: ⋮ → Ad blocking on this site…; Settings → Ad and tracker blocking.
5. **Counts:** blocked requests per page are logged (`ad blocking: N of M requests blocked on host (level)`, TV:
   `page loaded in X s: …`) and shown on the TV remote.
6. **No stutter:** the weekly recompile waits while Tab Cast TV's player plays.
7. **Attribution:** in the tablet's Settings and on the TV's waiting screen.

## 0.2.1 (2026-11-14): fixes from the first TV-browser test

1. **TV remote in landscape:** the controls ended up under the tablet's navigation bar. The picture took the full
   width at 16:9 (558 dp of the 768 dp screen). Landscape is now two panes: info and picture on the left (as large as
   fits), controls on the right, nothing below the edge. Drags on the picture always go to the TV page. A Robolectric
   test taps every control through the real touch dispatch at the Tab S2's size, in both orientations.
2. **Poster over the video:** when the TV switched to its own player, the page's video layer (a poster or a paused
   frame) could stay on top: two media layers with no defined order. The page is now hidden while the TV's player
   plays, the player's video sits above other media layers, artwork is off, a spinner shows while it buffers, and the
   first frame is logged.
3. **Volume −/+:** they did nothing because the Chromecast's HDMI volume is fixed for apps. Tab Cast TV now uses the
   media volume where an app can move it, else its own level (the player and the page's media, 10 % steps). Every
   change is logged; the remote shows the level. The tablet's volume keys control the TV while the remote is open.
4. After Share → Open on TV: a hint to pause the video in the other app (Brave keeps playing in the background).

## 0.2.0 (2026-11-14): Tab Cast TV, a browser on the TV (with the tablet app 0.2.0 as a pair)

Decision: the TV gets its own browser first; mirroring comes after.

1. **Tab Cast TV** (`tv/`, `dk.pennico.tabcast.tv`): an Android TV app with its own WebView and the tablet's catching
   code (inject.js in every frame, the request hook, the ad blocklist). It blocks pop-up windows and stops page jumps
   to another site without a click (the tablet can still open the blocked page).
2. **Open on TV.** On the tablet, Share → **Open on TV**, or ⋮ → **Open this page on the TV**. The page opens on the
   TV.
3. **Full screen by itself.** When the page's video plays (and isn't a short preview), the TV plays it in its own
   player (Media3), full screen, with the TV WebView's cookies, user agent and Referer. If that player fails, the
   page's own player is stretched over the screen instead.
4. **TV browser remote on the tablet:**
   - a live picture of the TV page: tap = click on the TV, drag = scroll, hold = long tap
   - play/pause, seek, ±10/30 s, TV volume
   - the videos the TV found
   - Back, Reload, Type…, Close
   - "Open anyway" for a blocked jump
5. **"Open on the TV with my login"** (optional): sends the tablet's cookies for that one site along with the link.
6. Under the hood:
   - `:shared` holds the catching code, its tests and the page assets, used by both apps.
   - `:link` is the tablet ↔ TV connection: TCP 46898, a handshake that ignores port scans, latest-only picture
     queues, ping. Mirroring will use the same link.
   - inject.js takes player commands in every frame (play, toggle, seek, maximize) and reports the playing video's
     position.

## Unreleased: 0.2 "Cast this tab" (mirror the page to the TV), paused for the TV browser

- Step 1, probe: `probe/` (separate debug app `dk.pennico.tabcast.probe` 0.2.0-probe1) measures PixelCopy, C colour
  conversion, ByteBuffer encoders, VirtualDisplay paths and MediaProjection + playback audio capture on the tablet;
  `tools/run-probe.sh` runs it from the Mac. Shared C code in `native/` (RGBA → NV12/I420, BT.709, NEON).

## 0.1.1 (2026-11-14): fixes from the first tablet tests

1. TV "Test" button: Google's sample video answers 403 now; the button checks a list (archive.org MP4 ×2, Mux HLS)
   from the tablet and casts the first that answers.
2. Share while something plays (or while the dialog is open) always handles the new link (the share dialog is
   `singleInstance` with `onNewIntent`; the Remote opens in the app's own task).
3. YouTube via yt-dlp: tries the `visionos` client first (no player script, no JavaScript challenge in QuickJS);
   live steps in the Share dialog, Cast button and Cast list; a timeline of each run in the log; time limits.
4. Frozen picture on YouTube 4K: HLS masters are trimmed to what the TV decodes (default ≤1080p, H.264, ≤60 fps;
   Settings → TV picture); quality shown on the Remote.
5. Page videos didn't start in Tab Cast: pages may now start their own video (WebView no longer requires a tap for
   every play; setting). Refused plays and video errors are logged.
6. New start page: your sites as tiles (add, rename, move, remove), recently cast with "Cast again", test pages
   folded away, TV status; light/dark with the app.
7. Crash on every site with a service worker (YouTube, many others): fixed; the request hook never touches the
   WebView, and a failing hook lets the request through.
8. Share on a site yt-dlp doesn't know: opens the page in the browser within seconds (no 35 s generic extractor).
9. Browser mode on YouTube/Vimeo video pages: yt-dlp starts by itself, shows progress, logs found/failed/timeout.
10. Stop always works: the notification's Stop ends the service and notification at once and stops the TV on its own
    thread (4 s limit); a cast still on its way can't restart the TV; after the app was killed while casting, Tab
    Cast offers to stop what is still playing. If Android kills the page renderer (low memory), the browser reopens
    the page instead of the app dying. A watchdog logs where the main thread hangs.
11. Cast list: when nothing is playing on the page, the first stream is no longer called "Best match" (it may be an
    ad or another video).

## 0.1.0 (2026-11-14)

First build: catching browser, Share target with yt-dlp, FCast/Chromecast casting, relay, remote, site rules.
