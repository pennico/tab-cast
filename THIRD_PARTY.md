# Third-party components

Tab Cast's own code is GPL-3.0-or-later (`LICENSE`). The APKs also contain the components below, each under its own
licence; all are compatible with distributing the apps under the GPL-3.0. Versions are the ones in the Gradle files
at the time of writing.

## Libraries in the APKs

| Component | Used in | Licence | Source |
|---|---|---|---|
| youtubedl-android `library` 0.18.1 (JunkFood02 fork) | tablet | GPL-3.0 | https://github.com/yausername/youtubedl-android |
| ↳ yt-dlp (bundled, updated at runtime) | tablet | Unlicense | https://github.com/yt-dlp/yt-dlp |
| ↳ CPython (`libpython.so`, `libpython.zip.so`) and the libraries its Android build includes (OpenSSL, libffi, zlib, SQLite, bzip2, xz…) | tablet | PSF-2.0; each library its own permissive licence (Apache-2.0, MIT, zlib, public domain…) | https://www.python.org/ |
| ↳ QuickJS (`libqjs.so`, JavaScript runtime for yt-dlp) | tablet | MIT | https://bellard.org/quickjs/ |
| FCast sender SDK `sender-sdk-minimal` 0.4.1 (FUTO; Rust core `libfcast_sender_sdk.so` with its crates) | tablet | MIT (FCast project); the crates MIT and/or Apache-2.0 | https://gitlab.futo.org/videostreaming/fcast |
| JNA 5.13.0 (`libjnidispatch.so`, used by the FCast SDK's bindings) | tablet | Apache-2.0 or LGPL-2.1-or-later (dual) | https://github.com/java-native-access/jna |
| AndroidX Media3 1.9.4 (ExoPlayer, HLS, DASH, UI) | TV | Apache-2.0 | https://github.com/androidx/media |
| AndroidX (core, appcompat, activity, webkit, media), Material Components for Android | both | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library, kotlinx.coroutines | both | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines |
| Android NDK toolchain (r27c): only the C runtime pieces the compiler links into `libtcmirror.so` | tablet | Apache-2.0 with LLVM exception | https://developer.android.com/ndk |

The C code in `native/` and `app/src/main/jni/` is Tab Cast's own. Some vector icons in `res/drawable/` follow the
shapes of Google's Material Icons (Apache-2.0).

## Ad and tracker filter lists

| List | Shipped in the APKs | Licence | Source |
|---|---|---|---|
| EasyList | yes | GPL-3.0-or-later or CC BY-SA 3.0 Unported-or-later (dual; "The EasyList authors" must be attributed) | https://easylist.to/pages/licence.html |
| EasyPrivacy | yes | same as EasyList | https://easylist.to/ |
| uBlock filters, uBlock filters – Privacy (uAssets) | yes | GPL-3.0 | https://github.com/uBlockOrigin/uAssets |
| Peter Lowe's ad and tracking server list | **no**: the apps download it from its site with the weekly list update | none stated (the site invites people to use it) | https://pgl.yoyo.org/adservers/ |

The apps show this attribution in Settings › Ad and tracker blocking (tablet) and on the waiting screen (TV). The lists
are refreshed in the source tree with `tools/fetch-filters.sh`; lists marked `download` in
`shared/src/main/assets/filters/lists.txt` are never shipped.

## Test data

- `link/src/test/resources/index-v1.jar`: a copy of Tab Cast's own F-Droid repo index (public), for `RepoIndexTest`.
- Test pages named in the apps and tests (archive.org's Big Buck Bunny, the hls.js demo, a Framatube PeerTube video,
  a Vimeo staff pick, a TED talk) are links only; nothing from them is in the tree.
