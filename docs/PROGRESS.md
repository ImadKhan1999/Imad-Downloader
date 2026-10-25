# Progress

| Milestone | State |
| --- | --- |
| 1. Core download engine | Done 2026-10-14, 38 tests passing |
| 2. Windows app (IDM style) | Done 2026-10-14, 14 tests passing, MSI and EXE installers |
| 3. Browser extension (Chrome / Edge) | Done 2026-10-14, end-to-end test passing |
| 4. Android app with built-in browser (1DM style) | Done 2026-10-14, 6 unit + 4 device tests passing, release APK 1.9 MB |
| 5. Release v1.0.0 on GitHub | Done 2026-10-14 |
| 6. v1.1.0: quality picker, DASH + merging, Android queues and schedule | Done 2026-10-14 (phone/PC pairing dropped on request) |
| 7. v1.2.0: YouTube and other sites (yt-dlp), in-app updates, release script | Done 2026-10-14 |
| 8. v1.3.0: extension for Firefox too, app finds your browsers and adds the extension | Done 2026-10-15 |
| 9. v1.4.0: renamed to Hyper Downloader, new logo, icons and colours | Done 2026-10-15 |
| 10. v1.4.1: Android browser blank screen fixed, sturdier YouTube | Done 2026-10-23 |
| 11. v1.5.0: YouTube playlists (tick videos, one quality), no connections in the popup | Done 2026-10-25 |
| 12. v1.5.1: update prompt at startup and every few hours | Done 2026-10-25 |
| 13. v1.6.0: renamed to iDownloader by ImadLabs, ImadLabs icon and colours, Telegram invite | Done 2026-10-26 |
| 14. v1.7.0: YouTube bot-check handling (QuickJS, fallback clients, sign-in), mainstream sites, new cloud icon | Done 2026-10-26 |

## Log

### 2026-10-14

- Checked toolchains, chose Kotlin stack (DECISIONS D-001).
- Wrote the core engine: multi-connection downloads with dynamic segmentation, pause/resume,
  crash-safe resume state, retries, speed limits, queues and scheduler, HLS with AES-128,
  checksums, batch URL patterns, file naming and categories.
- Designed the logo (`branding/make_logo.py` renders every size).
- Built the Windows app, the Chrome/Edge extension and the MSI/EXE installers.
- Built the Android app: downloads list with filters, add/details/batch sheets, foreground
  service with notifications, built-in browser (tabs, incognito, bookmarks, history, desktop
  mode, find, ad blocker, media detection, page download capture, long-press menu,
  fullscreen video), share/open intents, copied-link offer, Wi-Fi only, settings.
- Real-device testing on the emulator found and fixed: launcher-icon crash on the start
  screen, completion notification crash for files outside shared folders, doubled status-bar
  padding, clipped address bar, and a busy public server answering 429 which now retries and
  lowers the connection count instead of failing (D-007).
- Release key created locally (`keystore/`, not in git). Back it up: updates to the Android
  app must be signed with the same key.
| 15. v1.8.0: Hyper Downloader logo, Android startup animation, MP3 and cover art, share sheet | Done 2026-10-26 |
