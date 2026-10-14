# Progress

| Milestone | State |
| --- | --- |
| 1. Core download engine | Done 2026-10-14, 36 tests passing |
| 2. Windows app (IDM style) | In progress |
| 3. Browser extension (Chrome / Edge) | Not started |
| 4. Android app with built-in browser (1DM style) | Not started |
| 5. Logos, installers, release | Not started |

## Log

### 2026-10-14

- Checked toolchains, chose Kotlin stack (DECISIONS D-001).
- Set up Gradle multi-module repo: `core`, `desktop`, `androidApp`.
- Wrote the core engine: multi-connection downloads with dynamic segmentation, pause/resume,
  crash-safe resume state, retries, speed limits, queues and scheduler, HLS with AES-128,
  checksums, batch URL patterns, file naming and categories.
- 36 tests pass (TESTING.md).
