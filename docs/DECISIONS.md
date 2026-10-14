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
## D-009 Stream quality picker and DASH (2026-10-14, features 1 and 2)

- The engine reads HLS master playlists (including `EXT-X-MEDIA` audio groups) and DASH
  manifests (`SegmentTemplate` with `$Number$`/`$Time$`/timelines, `SegmentList`, single-file
  `BaseURL`) and lists the qualities. The add dialogs on Windows and Android show them; the chosen
  height is stored with the download (`streamHeight`, 0 = best) so resume uses the same stream.
- When video and audio come as separate fragmented-MP4 tracks, `Mp4Merge` joins them into one MP4
  without re-encoding (pure Kotlin, no ffmpeg): audio gets the next track id, its `trak`/`trex` go
  into the video `moov`, fragments are interleaved by decode time.
- When the tracks cannot be joined that way (MPEG-TS audio, WebM) the audio is kept as a separate
  file next to the video and the download's description says so.
- Live streams are refused with a clear message.

## D-010 Android queues and schedule (2026-10-14, feature 5)

- New "Queues and schedule" screen (Downloads menu): create, rename, delete queues, files at the
  same time, start/stop times, weekdays, start or stop now. "Later" in the add sheet and the
  details sheet put downloads into a queue.
- Alarms wake the app for scheduled starts and stops even when it is closed, and are set again
  after a reboot. Exact alarms are used when the user allows them (Android 12+ setting, the
  screen offers a button); otherwise Android may start a few minutes late. If Android refuses to
  start the download service from the background, a notification asks the user to tap.

## D-011 Phone and PC pairing dropped (2026-10-14)

Feature 3 (send links between phone and PC over Wi-Fi) was built and then removed on Imad's
request ("dont ADD Phone and PC pairing"). No pairing code ships.
## D-012 YouTube and other video sites through yt-dlp (2026-10-14)

Imad asked for YouTube and other sites with quality choice, using yt-dlp if needed.
- yt-dlp only reads the page and lists formats (`-J`); the app's own multi-connection engine
  downloads them (new `MEDIA` download kind), so pause/resume, speed limits and queues work as
  usual. Links are fetched again on every start because sites' links expire; finished parts are
  kept. Video-only MP4 + M4A audio are joined into one MP4 by `Mp4Merge` (no ffmpeg). WebM-only
  4K/VP9 formats are not offered because they cannot be joined without ffmpeg; AV1/H.264 MP4 are.
- Windows: the official `yt-dlp.exe` is downloaded on first use from github.com/yt-dlp
  (checked against the release's SHA2-256SUMS) into `%APPDATA%\Imad Downloader\tools` and
  updated with `yt-dlp -U` every 3 days. YouTube needs a JavaScript runtime: Node.js if
  installed, otherwise the official Deno build is fetched (checksum verified).
- Android: Python runs inside the app via Chaquopy (MIT) with yt-dlp bundled at build time; the
  app swaps in the newest official yt-dlp from GitHub every 3 days (checksum verified). Chosen
  over youtubedl-android because that library is GPL-3.0. Python 3.13 builds exist only for
  64-bit devices, so the APK supports arm64-v8a (and x86_64 for emulators); 32-bit-only phones
  are not supported. APK size grows to about 40 MB.
- Sign-in: YouTube now asks some networks to "confirm you're not a bot" (it does so for this
  PC's connection). The browser extension's "Download this video" sends the page with your
  YouTube cookies, and the Android browser passes its cookies, so signed-in users get through.
  Windows can also read a browser's cookies (Options > Video Sites; Firefox works best).
- Limits found in testing: sites that need browser impersonation (e.g. Dailymotion) work on
  Windows (yt-dlp.exe includes it) but not yet on Android, and Android has no JS runtime, so
  some YouTube formats may be missing there.

## D-013 In-app updates like Imad Tunnel (2026-10-14)

- `update/version.json` in the repo lists the newest Android and Windows builds (version,
  download URL on the GitHub release, SHA-256, size, changelog, mandatory flag). Both apps check
  it once a day (can be turned off) and from Settings/Help > Check for updates.
- The update dialog shows what's new, downloads with a progress bar, refuses anything whose
  SHA-256 does not match, then installs:
  - Android: also checks the APK is signed with the app's own certificate, then opens Android's
    installer (asks once for "install unknown apps"), same as Imad Tunnel.
  - Windows: closes the app, runs `msiexec /i <new msi> /passive` (per-user, no admin prompt,
    the MSI upgrades in place) and starts the app again.
- `release.ps1` does a whole release: all tests (including emulator device tests and the
  browser end-to-end test), installers, GitHub release, then writes and pushes version.json last
  so apps never see an update before its files exist.