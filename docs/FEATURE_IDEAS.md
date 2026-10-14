# Feature ideas (asked 2026-10-14)

Twenty features that Imad Downloader 1.0.0 does not have yet. Effort is a rough guess:
S = a day or less, M = a few days, L = a week or more. ★ = recommended, ✅ = built, ✗ = dropped.

| # | Feature | Where | Effort | Why it matters |
| --- | --- | --- | --- | --- |
| 1 ✅ | **Video quality picker** (1080p / 720p / 480p) for streaming videos instead of always the best | Both | M | 1DM and IDM both offer it; saves data on phones |
| 2 ✅ | **DASH streams and merging video + audio** (sites that send them separately), using ffmpeg | Both | L | Many big video sites use DASH; today only HLS works |
| 3 ✗ | **Send to PC / send to phone** (dropped on request): pair the Android and Windows apps on the same Wi-Fi (QR code) and push links between them | Both | M | Find a file on the phone, download it on the PC |
| 4 ★ | **Auto-update**: check GitHub releases and offer the new version | Both | S | Users stay on the latest fixes without visiting GitHub |
| 5 | **Torrent and magnet links** | Both | L | 1DM supports them; a common request |
| 6 | **Site grabber** (IDM): download every file of chosen types from a site, following links to a set depth | Windows | M | Bulk downloads of image galleries, docs, courses |
| 7 ✅ | **Queues and scheduler screen on Android** (the engine already supports them, only the screen is missing) | Android | S | Night-time downloads on Wi-Fi |
| 8 | **Firefox extension** | Windows | S | Same features as the Chrome/Edge one |
| 9 | **Virus scan after download** with Windows Defender | Windows | S | IDM has it; safer for .exe and .zip files |
| 10 | **Auto-extract archives** (.zip, .7z, .rar) after download, optional delete of the archive | Both | M | One step less for users |
| 11 | **Speed graph and statistics** (today / month, per site) | Both | S | Nice dashboard, shows where data goes |
| 12 | **Saved site logins** for password-protected downloads, stored in Windows Credential Manager / Android Keystore | Both | M | IDM's "Site logins"; no retyping passwords |
| 13 | **SD card and any-folder picker on Android** (Storage Access Framework) | Android | M | Save straight to SD cards or custom folders |
| 14 | **Home-screen widget and quick-settings tile** (speed, pause all, add link) | Android | S | Control without opening the app |
| 15 | **Built-in media player** that can play a video while it is still downloading | Android | M | 1DM-style preview |
| 16 | **Private folder with PIN / fingerprint** that hides chosen downloads from the gallery | Android | M | Privacy feature users ask for |
| 17 | **More languages** (Urdu, Hindi, Arabic with right-to-left layout, ...) | Both | M | Reach more users |
| 18 | **Smart rules**: auto-rename, folder and connection count per website or file type | Both | S | Power-user control, IDM-like |
| 19 | **Battery and data rules** on Android: pause below a battery level, pause on roaming, daily data cap | Android | S | Protects phone and data plan |
| 20 | **Backup and restore** of the download list, queues, bookmarks and settings (file or Google Drive) | Both | S | Safe move to a new phone or PC |
