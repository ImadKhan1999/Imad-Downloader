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
