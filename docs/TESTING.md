# Test results

How to run everything:

```
gradlew.bat :core:test :desktop:test
cd extension\test && npm install && npx playwright-core install chromium && cd ..\..
node extension\test\e2e.mjs
powershell -File desktop\test-installed.ps1
```

## 2026-10-14 Milestones 2 and 3: Windows app and browser extension

| Check | Result |
| --- | --- |
| `gradlew :desktop:test` | **14 tests, 0 failures**: clipboard capture (types, wildcards, fires once), schedule times, settings round trip and engine config, integration API (extension accepted, web origins and header-less calls rejected, non-http URLs rejected), 7 headless UI renders |
| UI renders | Main window light/dark, empty state, progress window, file info, options, scheduler: written to `docs/screenshots/` by the test run |
| `node extension/test/e2e.mjs` (Gradle build) | **PASS**: Chromium with the extension loaded, a 3 MB browser download is cancelled in the browser and finished by the app byte-identical; right-click hand-off keeps the referrer |
| Same e2e against the packaged `Imad Downloader.exe` | **PASS** |
| `desktop/test-installed.ps1` (packaged exe, real internet) | **PASS**: README.md from GitHub raw over HTTPS; 10 MB file from proof.ovh.net, 10485760 bytes |
| `gradlew :desktop:packageMsi :desktop:packageExe` | Built `Imad Downloader-1.0.0.msi` (95.7 MB) and `.exe` (96.3 MB) |

Notes: the PC screen was locked during this run, so desktop screenshots come from headless
rendering of the real composables instead of screen captures.

## 2026-10-14 Milestone 1: core engine

`gradlew :core:test`: **36 tests, 0 failures** (BUILD SUCCESSFUL, about 16 s).

Engine tests run against a real local HTTP server (`TestServer`) that supports byte ranges,
throttling, dropped connections, redirects, Content-Disposition and servers without range support.

| Suite | Tests | What is covered |
| --- | --- | --- |
| EngineTest | 17 | 8-connection download is byte-identical; no-range server; unknown size; pause/resume; resume after app restart; retry after dropped connections; 404 message; redirect + Content-Disposition name; user name kept and duplicate renamed; checksum pass/fail; global speed limit; concurrency limit; queue run + finished event; remove + redownload; category folders; HLS AES-128 decrypt and join; probe; events |
| FileNamesTest | 5 | Content-Disposition (quoted, plain, RFC 5987), URL names, sanitising, MIME extensions, unique names |
| SpeedTest | 3 | Token bucket rate, unlimited mode, speed/ETA meter |
| BatchUrlsTest | 3 | `[001-100]` and `[a-z]` expansion, link extraction |
| SchedulerTest | 2 | Start/stop window once per day, weekdays |
| Others | 6 | Categories, segment planner, checksums, formatting, JSON store with corrupt-file fallback, HLS parser |

## 2026-10-14 Milestone 4: Android app (final pass, all suites)

| Suite | Result |
| --- | --- |
| `:core:test` | 38 tests, 0 failures (adds 429 retry and connection-limit tests) |
| `:desktop:test` | 14 tests, 0 failures |
| `:androidApp:testDebugUnitTest` | 6 tests, 0 failures: address bar input, media detection, ad blocker, settings |
| `:androidApp:connectedDebugAndroidTest` on Pixel 7 emulator, Android 14 | 4 tests, 0 failures: 8-connection download on device byte-identical; pause/resume on device; app list shows a download until Completed; browser loads a page, detects its video, catches the page's download, sheet starts it, file completes with the page as referrer |
| Extension e2e against the rebuilt packaged app | PASS |
| Manual on emulator (release APK) | Shared a GitHub link, downloaded and completed; w3schools video page showed 2 detected media, the mp4 downloaded named after the page title |

Screens are in `docs/screenshots/android-*.png` (captured from the emulator).
## 2026-10-14 Features 1, 2 and 5, release 1.1.0

| Suite | Result |
| --- | --- |
| `:core:test` | 46 tests, 0 failures. New: DASH parser (templates, timelines, lists, single files, live refused), quality pick, MP4 merger (two tracks, interleaving, refuses non-fragmented), DASH download at 360p with merged audio, HLS with separate fMP4 audio merged |
| `:desktop:test` | 14 tests, 0 failures |
| `:androidApp:testDebugUnitTest` | 9 tests, 0 failures. New: next alarm time (today/tomorrow/chosen weekdays), schedule summary text |
| Android device tests on the Pixel 7 emulator (Android 14) | **7 tests, 0 failures**: earlier 4 plus queues screen starts a queue and both files finish; quality picker downloads exactly the 360p stream; real Apple HEVC test stream with separate audio downloaded and merged into one MP4 (40.3 MB) that Android's MediaExtractor opens with video and audio tracks || Extension e2e against the packaged 1.1.0 app | PASS |
| Packaged 1.1.0 app, real HTTPS download (10 MB) | PASS |
| Release APK 1.1.0 on the emulator | Opens a real DASH stream (Akamai Big Buck Bunny) and lists 8 qualities; queues screen opens |

Note: the emulator moved to `E:\AndroidAvd` because drive C had under 2 GB free.
## 2026-10-14 Release 1.2.0: video sites and in-app updates

| Suite | Result |
| --- | --- |
| `:core:test` | 57 tests, 0 failures. New: yt-dlp JSON parsing, quality list (merged MP4, HLS, progressive, audio), pick on resume, video page download with joined audio, pause/resume re-fetching links, audio only, update manifest, verified download with progress, tampered download refused |
| `:desktop:test` | 16 tests pass (+2 UI renders: video page dialog, update dialog); 4 real-site tests run separately |
| `:desktop:test -PrealNetwork=true` (real yt-dlp.exe) | SoundCloud audio, archive.org video, Dailymotion HLS video downloaded; YouTube refused this PC's network with the bot check and the app shows the sign-in explanation |
| Android unit tests | 9 tests, 0 failures |
| Android device tests (Pixel 7 emulator, Android 14) | 11 tests, 0 failures. New: yt-dlp runs inside the app; SoundCloud audio and archive.org video downloaded on the phone; YouTube bot check explained; updater downloads, verifies signature and prepares Android's installer; tampered APK refused |
| Windows installer | 1.2.0 MSI installs per-user without admin (exit 0), installed app starts and answers, uninstall clean |
| Release APK on emulator | Shared an archive.org video page: options 720p/360p/300p with sizes |

## 2026-10-15 Release 1.3.0: extension for every browser

IDM closed before every run (not uninstalled; it stays closed until reopened).

| Suite | Result |
| --- | --- |
| `:core:test` | 57 tests, 0 failures |
| `:desktop:test` | 22 tests, 0 failures. New: browser told apart by user agent (Chrome, Edge, Opera, Firefox), no store listing means guided install, both extension builds ship with the app, render of the "Connect your browsers" dialog |
| `:desktop:test -PrealNetwork=true` (real yt-dlp.exe) | SoundCloud audio, archive.org video, Dailymotion HLS pass; YouTube refused this PC's network with the bot check and the app shows the sign-in explanation |
| Android unit tests | 9 tests, 0 failures |
| Android device tests (Pixel 7 emulator, Android 14) | 11 tests, 0 failures, real-site tests included |
| Extension e2e, Chromium (Chrome for Testing 155, packaged 1.3.0 app) | PASS: link download taken over by the app, right-click "Download with Imad Downloader" keeps the referer, no browser copy, IDM stayed closed |
| Extension e2e, Firefox (real Firefox through geckodriver, packaged app) | PASS: download taken over, Firefox kept no copy, IDM stayed closed |
| Packaged 1.3.0 app, real HTTPS download (10 MB) | PASS |

Notes: the Android SDK's build tools and platform had been deleted from drive C; they were
reinstalled (build-tools 35, android-36) before the run. The release script now always runs the
real-site device tests.

## 2026-10-15 Release 1.4.0: renamed to Hyper Downloader

IDM closed before every run. Android device tests now run on LDPlayer 14 (Android 14, x86_64,
720x1280) instead of Android Studio's emulator, at Imad's request.

| Suite | Result |
| --- | --- |
| `:core:test` | 57 tests, 0 failures |
| `:desktop:test` | 22 tests, 0 failures; UI renders show the new name and colours |
| Android unit tests | 9 tests, 0 failures |
| Android device tests on LDPlayer (real-site tests included) | 11 tests, 0 failures |
| Extension e2e, Chromium and Firefox, packaged 1.4.0 app | PASS both, IDM stayed closed |
| Packaged 1.4.0 app, real HTTPS download (10 MB) | PASS |
| Manual check on LDPlayer | New launcher icon, round splash icon, "Hyper Downloader" title, add-link sheet saving to Download/Hyper Downloader, live download, settings About shows 1.4.0 |

## 2026-10-23 Release 1.4.1: Android browser and YouTube

Released without the test suites at Imad's request ("do all without any kind of tests", to get
the update out fast). Checked before that:

| Check | Result |
| --- | --- |
| `:core:test` | 59 tests, 0 failures (new: YouTube video pages, 1080p phone default, normal audio over "-drc") |
| LDPlayer, browser start page and a local test page | Search bar and page visible (before: blank white screen) |
| LDPlayer, YouTube video in the app's browser, download button | Quality list read, 240p downloaded, MP4 has a video and a sound track |
| Same app path on the PC (yt-dlp without a JS runtime, like the phone) | Big Buck Bunny 720p, 160 MB, downloaded and joined |

Not run for this release: desktop tests, Android device tests, extension e2e, installed-app smoke test.

## 2026-10-25 Release 1.5.0: YouTube playlists

IDM closed; phone tests on LDPlayer 14.

| Suite | Result |
| --- | --- |
| `:core:test` | 64 tests, 0 failures (new: flat playlist parsing without private videos, playlist links, one download per ticked video, audio-only pick, player-style lengths) |
| `:desktop:test` | 26 tests, 0 failures (new: playlist window render) |
| `:desktop:test -PrealNetwork=true` playlist test | Real YouTube playlist "Settlers" (8 videos) listed; 2 videos downloaded as audio (17.8 MB, 15.0 MB) |
| Android unit tests | 9 tests, 0 failures |
| Android device tests on LDPlayer (real sites included) | 12 tests, 0 failures; new: the same playlist listed and its first video downloaded at 360p (34.9 MB) with video and audio tracks |
| Manual on LDPlayer | Shared playlist link opens the tick-box list; All/None, ticks and quality chips work; the download popup has no connections slider |
| Extension e2e (Chromium, Firefox) and installed-app HTTPS smoke test | PASS |

