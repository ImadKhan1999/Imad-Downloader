# Decisions

Each decision records what was chosen, the alternatives and why. New ones go at the bottom.

## D-001 Tech stack: Kotlin everywhere (2026-10-14)

**Toolchains found on the build PC** (checked 2026-10-14):

| Tool | State |
| --- | --- |
| JDK | Microsoft OpenJDK 17.0.17 (`JAVA_HOME` set) |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`: platforms 34 and 36, build-tools 34-36, emulator running |
| Gradle | 8.14.3 (wrapper distribution cached) |
| Node / npm | installed |
| Python | 3.13 with Pillow 12 |
| .NET | runtimes 6 and 8 only, **no SDK** |
| Rust, Go, Flutter | not installed |

**Choice**

- `core/`: pure Kotlin/JVM download engine (OkHttp, coroutines, kotlinx.serialization).
- `desktop/`: Windows app in Compose Multiplatform Desktop, packaged with `jpackage` (bundled runtime, no Java install needed).
- `androidApp/`: native Android app in Jetpack Compose with a WebView based browser.
- `extension/`: Chrome / Edge (Manifest V3) extension for IDM-style browser capture.

**Why**

- One engine shared by both apps: every fix and test covers Windows and Android at once.
- Everything needed is already installed; no admin installs (a .NET SDK would need one).
- Compose on both platforms means one UI toolkit and shared look (colours, icons).
- Alternatives rejected: WPF/WinUI (no .NET SDK), Electron (separate JS engine, ~150 MB, no code sharing with Android), Flutter/Rust (not installed).

## D-002 Engine design (2026-10-14)

- **Dynamic segmentation** like IDM: a download starts with up to N equal ranges (default 8,
  max 32). When a connection finishes, it takes the back half of the largest remaining range,
  so all connections stay busy until the end.
- Data is written in place into `name.ext.imdl` (preallocated), renamed when complete.
- Resume state (ranges and bytes done) is saved every 2 s and on pause/exit, so a crash loses at most 2 s.
- Resume is refused (restart from zero) when the server's size, ETag or Last-Modified changed.
- If a server claims ranges but then ignores them, the engine falls back to one plain stream.
- Retries: up to 8 per connection with exponential backoff (1 s .. 30 s); 4xx errors like 403/404 fail at once
  with a plain-language message (e.g. "The link may have expired; refresh the address").
- Speed limits: global and per-download token buckets, adjustable live.
- HLS (`.m3u8`): best variant chosen, pieces downloaded in parallel, AES-128 decrypted, joined to `.ts`/`.mp4`.
- Queues with max-concurrent limits and a daily schedule (start/stop time, weekdays).
- Categories (Compressed, Documents, Music, Programs, Video, Images, Other) with IDM-style per-category folders.
- State is a JSON file written atomically with a `.bak` fallback.

## D-003 Minimum Android version (2026-10-14)

`minSdk 26` (Android 8.0). It covers over 95% of active devices and lets the shared engine use
`java.nio.file` without desugaring.
