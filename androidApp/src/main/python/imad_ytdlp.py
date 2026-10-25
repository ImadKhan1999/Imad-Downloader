"""Bridge between the Android app and yt-dlp (runs inside Chaquopy's Python)."""
import json
import os
import sys

_loaded_from = None  # path of the downloaded yt-dlp in use, None = the copy built into the app
_loaded_stamp = None


def _use_override(path):
    """Prefer a newer yt-dlp (official zipapp from GitHub) that the app downloaded.

    A copy downloaded while the app runs replaces the loaded one right away: the old yt_dlp
    modules are dropped and imported again from the new file.
    """
    global _loaded_from, _loaded_stamp
    if not path or not os.path.isfile(path):
        return
    stamp = os.path.getmtime(path)
    if _loaded_from == path and _loaded_stamp == stamp:
        return
    for name in [m for m in sys.modules if m == "yt_dlp" or m.startswith("yt_dlp.")]:
        del sys.modules[name]
    if path in sys.path:
        sys.path.remove(path)
    sys.path.insert(0, path)
    _loaded_from, _loaded_stamp = path, stamp


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


def extract_playlist(url, headers_json, cookie_file, override_path):
    """Returns `yt-dlp -J --flat-playlist` style JSON: the playlist's videos without their formats."""
    _use_override(override_path)
    import yt_dlp

    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": False,
        "extract_flat": "in_playlist",
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
