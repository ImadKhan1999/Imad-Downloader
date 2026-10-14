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

## D-004 Windows UI (2026-10-14)

- Layout copies IDM: menu bar (Tasks, File, Downloads, View, Help), big-icon toolbar (Add URL,
  Resume, Stop, Stop All, Delete, Delete Completed, Options, Scheduler, Start/Stop Queue, Speed
  Limit), category tree on the left, sortable and resizable download table, status bar.
- IDM dialogs reproduced: "Download File Info" (category, Save As, Download Later into a queue),
  per-download progress window (status, segment map, connections table, speed limiter tab),
  "Download complete" (Open / Open with / Open folder), Options, Scheduler, batch download,
  "Download selected links", Properties, Refresh download address.
- Closing the window keeps the app in the system tray (optional); notifications use the tray.
- Light and dark themes; the system setting is followed by default.
- Data lives in `%APPDATA%\Imad Downloader` (`settings.json`, `downloads.json`). `IMAD_DATA_DIR`
  overrides it for tests.

## D-005 Browser integration protocol (2026-10-14)

- The app listens on `127.0.0.1:9614` (falls back to 9615-9617). Endpoints: `/api/ping`,
  `/api/download`, `/api/links`, `/api/show`.
- Web pages cannot call it: every request needs an `X-Imad-Client` header (forces a CORS
  preflight) and only `chrome-extension://` / `moz-extension://` origins are allowed.
- The same port gives single-instance behaviour: a second launch hands its URL to the running app.
- The extension takes over downloads whose type is in the app's list (IDM's default list),
  adds right-click menu items, sniffs media per tab and shows a "Download this video" button
  over players. Blob/data URLs stay in the browser.
- The unpacked extension ships inside the installer (`app\resources\extension`). Branded Chrome and
  Edge no longer accept `--load-extension`, so the automated test uses Playwright's Chromium.

## D-006 Installers (2026-10-14)

`jpackage` through the Compose plugin builds both an `.msi` and an `.exe` (WiX 3 downloaded
by the build). Per-user install, Start menu group, desktop shortcut, folder choice. The Java
runtime is bundled, so users install nothing else.

## D-007 Busy servers (2026-10-14)

Found on the emulator: a public test server answered `429 Too Many Requests` and the download
failed at once. Now the first request retries with backoff like data connections, and when a
connection gets 429/503 while others are still working it steps back and hands its range to
them, so the download continues with fewer connections. The error text explains 429 plainly.

## D-008 Android app (2026-10-14)

- Bottom navigation: Downloads, Browser, Settings. Downloads use cards with progress, speed and
  time left, filter chips by state and category, a details sheet with the connection map.
- Files go to `Download/Imad Downloader/<Category>` (public, visible in file managers, no
  storage permission needed on Android 10+; Android 8-9 ask for it).
- A foreground service with a progress notification runs only while something downloads.
- Browser on Android System WebView: downloads a page starts open the add sheet with cookies,
  referrer and user agent; video/audio requests are detected (floating button with count);
  long-press on links offers download; host-based ad blocking; incognito tabs keep no history or
  cache (cookies are shared with normal tabs, a WebView limit).
- Release builds use R8; APK is 1.9 MB. Signing key lives in `keystore/` (git-ignored).