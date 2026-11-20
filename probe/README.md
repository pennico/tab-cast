# Tab Cast probe (0.2 "Cast this tab", step 1)

A small separate debug app (`dk.pennico.tabcast.probe`) that measures on the Tab S2 what the mirroring PoC can be
built on, before building it. It installs next to Tab Cast
and changes nothing there. The TV isn't needed.

Why a probe: on this ROM the virtual-display → encoder-Surface path is broken (`screenrecord` gets 0 frames;
`gralloc: invalid gralloc handle`, `C2AllocatorGralloc … importBuffer`), so the sender must capture and encode another
way, and the numbers decide which.

## What it measures (one step after another on `assets/probe.html`)

| step | what | decides |
|---|---|---|
| `info` | device, display, GPU, WebView, every AVC encoder's claims (colour formats, CBR, 720p rates) | — |
| `page` | the page's video starts; page frame rate without capture | baseline |
| `copy` | `PixelCopy.request(Window)` at full size, fit-in-720p, 1280×720, 640×360, and paced 30 fps: fps, latency, time blocked inside `request()`, **is the video in the copy** (video area changing and not black), page fps while copying | the capture route |
| `convert` | RGBA → NV12 in C (`native/yuv.c`), NEON vs plain C time at 1280×720, **NEON and C must give identical bytes**, letterbox case | CPU budget |
| `encode` | each AVC encoder (Exynos OMX, c2 software, OMX.google) with ByteBuffer input, via `getInputImage` and via a plain buffer: burst throughput, paced 30 fps delay and bitrate, and a **decode-back check** of the produced `.mp4` against the source pictures | the encoder |
| `pipeline` | copy → convert → encode at 30 fps for 10 s, letterboxed into 1280×720, with the best encoder: real fps, capture-to-encoded delay, app CPU | PoC feasibility |
| `vd` | a private VirtualDisplay (no permission) → ImageReader, the page shown on it with a Presentation | off-screen route |
| `vdenc` | the same VirtualDisplay straight into the encoders' input Surface (confirms the broken path) | — |
| `projection` | MediaProjection consent (Android 14 foreground service), AudioPlaybackCapture of the page's own sound (video, then a 440 Hz Web Audio tone), whole-screen mirror → ImageReader | audio + later whole-screen |

## Run (Mac, tablet on USB)

`sh tools/run-probe.sh <tab-cast-probe-…apk> [results dir] [steps]` installs, grants RECORD_AUDIO, starts the probe,
taps "Start" in the capture dialog (uiautomator; otherwise tap it by hand), pulls the results, logcat and `top`
samples, and prints the summary lines. Single steps: `--es tests copy,encode` on the `am start` line or the third
argument of the script.

Results on the tablet: `/sdcard/Android/data/dk.pennico.tabcast.probe/files/run-<time>/`:
`probe.log` (readable), `results.json` (everything), `copy-*.png`, `vd-reader.png`, `mirror-reader.png`,
`enc-<codec>-image|buffer.mp4/.h264` (+ `-decoded.png`), `pipeline.mp4`, `vdenc-*.mp4`, `audio-capture.wav`.

## Build

`tools/build.sh --probe` (host C tests, lint, unit tests, `dist/tab-cast-probe-<version>.apk`). Needs the NDK
(r27c, in the user-level toolchain). minSdk 29 (playback capture), armeabi-v7a only.
