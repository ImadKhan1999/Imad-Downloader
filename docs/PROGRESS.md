# Progress

| Milestone | State |
| --- | --- |
| 1. Core download engine | Done 2026-10-14, 36 tests passing |
| 2. Windows app (IDM style) | Done 2026-10-14, 14 tests passing, MSI and EXE installers build |
| 3. Browser extension (Chrome / Edge) | Done 2026-10-14, end-to-end test passing |
| 4. Android app with built-in browser (1DM style) | In progress |
| 5. Release (GitHub release with installers and APK) | Not started |

## Log

### 2026-10-14

- Checked toolchains, chose Kotlin stack (DECISIONS D-001).
- Set up Gradle multi-module repo: `core`, `desktop`, `androidApp`.
- Wrote the core engine: multi-connection downloads with dynamic segmentation, pause/resume,
  crash-safe resume state, retries, speed limits, queues and scheduler, HLS with AES-128,
  checksums, batch URL patterns, file naming and categories. 36 tests pass.
- Designed the logo (`branding/make_logo.py` renders every size: Windows .ico, tray, Android
  mipmaps and adaptive foreground, extension icons).
- Built the Windows app: IDM-style main window, menus, toolbar, category tree, resizable
  sortable table, right-click menu, keyboard shortcuts (Del, Ctrl+A, Ctrl+N, Ctrl+V, Enter),
  file info dialog, progress window with segment map, completion dialog, options, scheduler
  with queue actions (exit, sleep, shutdown), batch download, link list, properties, tray,
  clipboard monitoring, single instance, launch on startup, light/dark theme.
- Built the Chrome/Edge extension: download takeover, right-click menu, all-links grab,
  media sniffing with popup list and "Download this video" button.
- Packaged MSI and EXE installers; e2e and real-internet smoke tests pass (TESTING.md).
