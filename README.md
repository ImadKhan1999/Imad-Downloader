# Imad Downloader

A full download manager for **Windows** (inspired by IDM) and **Android** (inspired by 1DM,
with a built-in browser), sharing one Kotlin download engine.

| Folder | What it is |
| --- | --- |
| `core/` | Shared download engine: multi-connection, pause/resume, queues, scheduler, HLS |
| `desktop/` | Windows app (Compose Desktop) |
| `androidApp/` | Android app (Jetpack Compose) with built-in browser |
| `extension/` | Chrome / Edge extension that sends downloads to the Windows app |
| `docs/` | Decisions, progress log, test results, chat log |

## Build

Requires JDK 17 and (for Android) the Android SDK.

```
gradlew.bat :core:test
```

See [docs/PROGRESS.md](docs/PROGRESS.md) for the current state.
