"""Bridge between the Android app and yt-dlp (runs inside Chaquopy's Python)."""
import json
import sys

_override_loaded = False


def _use_override(path):
    """Prefer a newer yt-dlp (official zipapp from GitHub) that the app downloaded."""
    global _override_loaded
    if path and not _override_loaded and "yt_dlp" not in sys.modules:
        sys.path.insert(0, path)
        _override_loaded = True


def version(override_path):
    _use_override(override_path)
    import yt_dlp.version
    return yt_dlp.version.__version__


def extract(url, headers_json, cookie_file, override_path):
    """Returns `yt-dlp -J` style JSON for a page, or raises with yt-dlp's own message."""
    _use_override(override_path)
    import yt_dlp

    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "noprogress": True,
    }
    headers = json.loads(headers_json or "{}")
    if headers:
        opts["http_headers"] = headers
    if cookie_file:
        opts["cookiefile"] = cookie_file
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        return json.dumps(ydl.sanitize_info(info))
