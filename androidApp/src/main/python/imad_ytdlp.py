"""Bridge between the Android app and yt-dlp (runs inside Chaquopy's Python)."""
import json
import os
import sys

_loaded_from = None  # path of the downloaded yt-dlp in use, None = the copy built into the app
_loaded_stamp = None
_js_runtime = None  # QuickJS shipped with the app: lets yt-dlp solve YouTube's JS challenges


def set_js_runtime(path):
    """Uses QuickJS only if it really runs on this phone; otherwise yt-dlp works without it."""
    global _js_runtime
    _js_runtime = None
    if not path or not os.path.isfile(path):
        return False
    try:
        import subprocess
        r = subprocess.run([path, "-e", "console.log(1+1)"], capture_output=True, text=True, timeout=10)
        if r.returncode == 0 and r.stdout.strip() == "2":
            _js_runtime = path
    except Exception:
        pass
    return _js_runtime is not None


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
    # Python remembers where every file sits inside a zip it has imported from; after a new
    # yt-dlp replaced it, those old positions point into the wrong bytes ("zipimport error: bad
    # local file header"). Forget them for the old and the new file.
    import importlib
    import zipimport
    for old in {p for p in (_loaded_from, path) if p}:
        try:
            zipimport._zip_directory_cache.pop(old, None)
        except Exception:
            pass
        for key in [k for k in list(sys.path_importer_cache) if k == old or k.startswith(old + os.sep)]:
            sys.path_importer_cache.pop(key, None)
        while old in sys.path:
            sys.path.remove(old)
    importlib.invalidate_caches()
    sys.path.insert(0, path)
    _loaded_from, _loaded_stamp = path, stamp


def version(override_path):
    _use_override(override_path)
    import yt_dlp.version
    return yt_dlp.version.__version__


# YouTube sometimes answers "Sign in to confirm you're not a bot" for one player client while
# others still work from the same network; these are tried in turn before giving up.
YOUTUBE_FALLBACK_CLIENTS = ["tv_embedded", "android_vr", "mweb", "ios", "tv_simply"]


def _is_youtube(url):
    host = url.split("/")[2].lower() if "://" in url else ""
    return host == "youtu.be" or host == "youtube.com" or host.endswith(".youtube.com")


def _needs_other_client(message):
    m = message.lower()
    return "not a bot" in m or "sign in" in m or "page needs to be reloaded" in m or "requested format is not available" in m


def _run(url, opts):
    import yt_dlp

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        return json.dumps(ydl.sanitize_info(info))


def _extract(url, headers_json, cookie_file, override_path, extra):
    _use_override(override_path)
    opts = {"quiet": True, "no_warnings": True, "skip_download": True, "noprogress": True}
    opts.update(extra)
    if _js_runtime:
        opts["js_runtimes"] = {"quickjs": {"path": _js_runtime}}
    headers = json.loads(headers_json or "{}")
    if headers:
        opts["http_headers"] = headers
    if cookie_file:
        opts["cookiefile"] = cookie_file
    try:
        return _run(url, opts)
    except Exception as first:
        if not _is_youtube(url) or not _needs_other_client(str(first)):
            raise
        for client in YOUTUBE_FALLBACK_CLIENTS:
            try:
                return _run(url, dict(opts, extractor_args={"youtube": {"player_client": [client]}}))
            except Exception:
                continue
        raise first


def extract(url, headers_json, cookie_file, override_path):
    """Returns `yt-dlp -J` style JSON for a page, or raises with yt-dlp's own message."""
    return _extract(url, headers_json, cookie_file, override_path, {"noplaylist": True})


def extract_playlist(url, headers_json, cookie_file, override_path):
    """Returns `yt-dlp -J --flat-playlist` style JSON: the playlist's videos without their formats."""
    return _extract(url, headers_json, cookie_file, override_path, {"noplaylist": False, "extract_flat": "in_playlist"})
