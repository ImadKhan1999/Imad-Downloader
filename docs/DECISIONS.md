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

## D-014 One extension for every browser, added by the app (2026-10-15)

- Imad asked for the extension to be "auto installed" in Chrome, Edge, Firefox and others.
- One source, two builds (`extension/build.mjs`): `dist/chromium` (Chrome, Edge, Brave, Opera,
  Vivaldi) and `dist/firefox` (background scripts instead of a service worker, gecko id). The
  code uses `browser.*` when present, else `chrome.*`.
- Browsers do not let a program silently install an extension that is not in their store
  (Chrome and Edge ignore registry installs of non-store extensions; Firefox needs signed add-ons).
  So the Windows app does what is allowed: it finds the installed browsers (App Paths registry and
  usual folders), and on first start and from Help > Connect browsers... shows each one with an
  "Add extension" button. That copies the extension to a fixed folder in the user profile, puts the
  path on the clipboard, opens the browser's extensions page and shows the 2-3 clicks needed. A row
  turns green as soon as that browser's extension talks to the app.
- Store listings make this one click (and permanent in Firefox). The code is ready
  (`ExtensionStores` ids + HKCU external-extension registration); it needs Imad's developer
  accounts (Chrome Web Store has a one-time 5 USD fee; Edge Add-ons and Firefox AMO are free). The
  release uploads store-ready zips for both families.
- Testing: the real IDM on Imad's PC grabs browser downloads and restarts itself from processes
  named chrome.exe/firefox.exe. Tests close IDM (not uninstalled, settings untouched; it stays
  closed until reopened) and run browsers under neutral names (Chrome for Testing as
  `cr-imadtest.exe`, Firefox as `ff-imadtest.exe`). Branded Chrome no longer accepts
  `--load-extension`, so the Chromium test uses Chrome for Testing.

## D-015 Rename to Hyper Downloader (2026-10-15)

- Imad picked "Hyper Downloader" from name ideas. A search found two small apps with similar
  names (an Android "Hyper Downloader - Video Saver" with about 100 installs, and a desktop
  "Hyper Download Manager"); Imad was told and the rename went ahead.
- New mark: a lightning bolt that ends in a download arrow, with speed lines, on a violet to blue
  to cyan gradient (#7C3AED, #2563EB, #06B6D4). `branding/make_logo.py` draws every icon from the
  same points: Windows .ico/.png and tray, Android launcher, adaptive foreground, notification and
  monochrome icons, Play Store icon, extension icons, `branding/logo.svg` and `branding/banner.png`.
  App colours moved from blue (#1565C0) to indigo (#4F46E5) with cyan accents to match.
- Everything people see says Hyper Downloader: window titles, menus, dialogs, Android app name and
  browser ("Hyper Browser"), extension name and menus, installer, Start menu, release files.
- Kept on purpose, so updates keep working: the Android package id (com.imad.downloader, a new id
  would be a separate app that cannot update the old one), the Windows installer upgrade id, the
  Firefox add-on id, the GitHub repository name (update/version.json is read from it) and the
  extension protocol header. The app and the extension accept the old name in each other's
  handshake, so a 1.3.0 extension keeps working with the renamed app and the other way round.
- Moved over automatically: the Windows data folder (%APPDATA%\Imad Downloader becomes
  ...\Hyper Downloader with settings, list and tools), the "start with Windows" entry. Existing
  download folders stay where they are; new installs default to Downloads\Hyper Downloader.
- Updating from 1.3.0 on Windows installs into the new "Hyper Downloader" folder; the 1.3.0
  updater then tries to reopen the old path, so the app has to be opened once from the Start menu.

## D-016 Android browser blank screen and YouTube downloads (2026-10-24)

- Imad could not see the browser's search bar. Cause: the start page kept an empty, zero-size
  WebView in the layout, and on LDPlayer (and likely other GPUs) a WebView that has not painted
  yet draws white over the whole screen, search bar included. The start page no longer creates the
  WebView, and the address bar and progress line are drawn above the page so a slow page cannot
  hide them. The bar also got a clearer outline.
- YouTube from the phone browser: the download button now reads the page's live address (YouTube
  changes it without reloading) and only shows on single-video pages, not on YouTube's home or
  search. yt-dlp no longer gets the browser's phone User-Agent (it chooses its own per YouTube
  client; the phone one breaks some). When a video fails, the app fetches the newest yt-dlp from
  GitHub (checked against SHA2-256SUMS) and retries at once; new copies are used without
  restarting the app. The copy built into the APK is pinned to 2026.10.21 (builds had kept a
  2026.08.19 copy). The quality picked first on phones is the best up to 1080p (H.264 where
  offered) instead of 4K AV1, and normal audio is preferred over YouTube's "-drc" copies.

## D-017 YouTube playlists and a simpler download popup (2026-10-25)

- Imad asked for YouTube playlist downloads where people tick which videos to get and choose the
  quality, and for the connections setting to leave the download popup (it stays in Settings as
  the default for every download).
- Both apps: a playlist link (`list=` on youtube.com / youtu.be) is read with
  `yt-dlp --flat-playlist`, which lists titles and lengths quickly without reading every video.
  Private and deleted videos are left out. The popup shows every video with a tick box (all ticked
  at first), "All" / "None", and one quality for the whole playlist: Best, 1080p (default), 720p,
  480p, 360p or Audio only. A video without the chosen height gets the next lower one.
- Each ticked video becomes its own download in the list (so it can be paused, retried or removed
  on its own). The exact format is chosen when it starts, from fresh links. Cookies, referer,
  folder and queue come from the playlist page, so a signed-in browser session carries over.
- A video opened from a playlist (`watch?v=...&list=...`) offers "This video" or "Whole playlist";
  a playlist page goes straight to the list. The phone browser's download button also shows on
  YouTube playlist pages.
- The Android download sheet no longer has the connections slider; Windows had none in its
  download dialog (the per-download Properties window still has it, for an existing download).
- Seen in testing: after many test downloads YouTube started asking this PC's network to "confirm
  you're not a bot", for the PC and LDPlayer alike. That is YouTube limiting the address, not the
  app; signing in to YouTube in the app's browser (or the PC browser with the extension) passes it.

## D-018 Updates pop up by themselves (2026-10-25)

- Imad had to open Settings and press "Check for updates" to see 1.5.0: the apps only checked once
  a day. Now both apps check when they start and again every 2 to 6 hours (random) while open,
  and a new version opens the update dialog by itself. A check never interrupts a download or a
  dialog that is already open.
- Removed at Imad's request: "Check for updates", "Check for updates automatically" and "Project
  page" from Android Settings; on Windows the matching Options checkbox, Help menu items and the
  About box buttons.

## D-019 iDownloader by ImadLabs (2026-10-26)

- Imad renamed the app to **iDownloader**; **ImadLabs** is his brand (always spelled that way).
  His brand kit is github.com/ImadKhan1999/Graphics ("The HexaCore Matrix").
- Icon: the kit's downloader icon (apps/hyper-downloader/...-master-icon.svg): isometric cube in
  Sky #38BDF8, Deep Cyan #0284C7 and Electric Cobalt #0066FF facets, white and Quantum Dot
  #00FFC2 download chevrons, the Cyber Mint #00F5A0 "L", on the Deep Void #070B14 tile.
  `branding/make_logo.py` draws it from the same coordinates for every size (Windows, Android
  launcher/adaptive/notification, extension, Play Store) and builds `branding/banner.png` with the
  ImadLabs logo. The kit files used are copied into `branding/imadlabs/`.
- Colours follow the kit's tokens: Cobalt primary (Sky on dark), Cyber Mint accents, and the kit's
  dark surfaces (#070B14, #0B0F19, #111827, #162032, borders #1E293B) for dark mode.
- Telegram: "ImadLabs on Telegram" (https://t.me/ImadLabs) in Android Settings > About, the
  Windows About box and Help menu. A popup invites people to the channel each time the app
  opens until they tick "Don't show this again" (it waits for the update and first-run browser
  windows so nothing stacks).
- Vendor and copyright are now ImadLabs. Kept for updates: Android package id, the Windows
  upgrade id, the repository name and the extension protocol; the apps still accept the old
  names ("Hyper Downloader", "Imad Downloader") in the extension handshake and move the old
  Windows data folder and start-with-Windows entry over.

## D-020 YouTube's bot check, mainstream apps, new icon (2026-10-26)

- Imad pointed at ytdlnis (github.com/deniscerri/ytdlnis) for its 1000+ sites. ytdlnis gets them
  from yt-dlp, which iDownloader already bundles in full on both apps, so every site it supports
  works here too at no extra size. The quick-detection list (download button in the phone
  browser, extension's "Download this video") gained Threads, Pinterest short links, Snapchat,
  Bluesky, 9GAG, Imgur, Tumblr, Audiomack, Mixcloud, Kwai, Douyin, Bilibili, Odysee and more.
  Checked on the PC with yt-dlp run like the phone (no JS runtime): TikTok, Instagram reel and
  Facebook video read fine.
- YouTube / YouTube Music "YouTube wants you signed in": that is YouTube's "confirm you're not a
  bot" check for the network. Seen in testing: it can hit one YouTube player client and not
  others, and at times every client for the address. So:
  - both apps retry with other player clients (tv_embedded, android_vr, mweb, ios, tv_simply);
  - Android now ships QuickJS-ng 0.17.0 (MIT, static build, about 2.5 MB per ABI, as libqjs.so)
    and gives it to yt-dlp as its JavaScript runtime, which signed-in YouTube and most formats
    need; it is only used if it runs on the phone;
  - links shared from other apps use the in-app browser's cookies for that site, so one sign-in
    to YouTube (or Instagram, Facebook, X) there carries over; the bot-check message has a
    "Sign in to YouTube" button that opens Google's sign-in in the app's browser.
- YouTube Music (and SoundCloud, Bandcamp, Audiomack, Mixcloud) pre-select audio only.
- Icon: Imad's updated kit replaced the cube with a cloud and Cyber Mint download arrow plus the
  ImadLabs quantum dot (Graphics repo apps/downloader). The kit's 1024 px icon and Android
  adaptive layers are used as they are (copied to branding/imadlabs/downloader/).
- Imad asked not to test the Android app this time, only to build it properly.
