// Imad Downloader browser integration (Manifest V3 service worker).
//
// - Takes over browser downloads of the file types the app is configured for.
// - Adds "Download with Imad Downloader" to the right-click menu.
// - Sniffs video/audio streams per tab (mp4, webm, m3u8 ...) for the popup and the
//   "Download this video" button the content script shows over players.

const PORTS = [9614, 9615, 9616, 9617];
const MEDIA_TYPES = /^(video\/|audio\/|application\/(vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))/i;
const MEDIA_EXT = /\.(mp4|m4v|webm|mkv|mov|flv|m3u8|mpd|mp3|m4a|aac|ogg|opus|wav|flac)(\?|$)/i;
const MIN_MEDIA_BYTES = 300 * 1024;

let appPort = null;
let appInfo = null;
const media = new Map(); // tabId -> [{url, mime, size, title, ts}]

async function settings() {
  const s = await chrome.storage.local.get({ capture: true, minSizeKB: 0, skipTypes: "" });
  return s;
}

async function call(path, body) {
  const ports = appPort ? [appPort, ...PORTS.filter((p) => p !== appPort)] : PORTS;
  for (const port of ports) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Imad-Client": "extension" },
        body: JSON.stringify(body ?? {}),
      });
      if (!res.ok) continue;
      const json = await res.json();
      if (path === "/api/ping" && json.app !== "Imad Downloader") continue;
      appPort = port;
      return json;
    } catch (e) {
      // Not listening on this port; try the next one.
    }
  }
  appPort = null;
  return null;
}

async function ping() {
  appInfo = await call("/api/ping");
  await chrome.action.setBadgeBackgroundColor({ color: appInfo ? "#1565C0" : "#9E9E9E" });
  return appInfo;
}

async function cookiesFor(url) {
  try {
    const list = await chrome.cookies.getAll({ url });
    return list.map((c) => `${c.name}=${c.value}`).join("; ");
  } catch (e) {
    return "";
  }
}

async function sendDownload({ url, fileName, referer, size, mime, mode }) {
  const body = {
    url,
    fileName: fileName || null,
    referer: referer || null,
    cookies: await cookiesFor(url),
    userAgent: navigator.userAgent,
    size: size ?? -1,
    mime: mime || null,
    mode: mode || "ask",
  };
  return (await call("/api/download", body)) != null;
}

function extensionOf(name) {
  const clean = (name || "").split(/[?#]/)[0];
  const dot = clean.lastIndexOf(".");
  return dot >= 0 ? clean.slice(dot + 1).toLowerCase() : "";
}

function matchesTypes(ext, types) {
  if (!ext) return false;
  return types.some((t) => {
    t = t.toLowerCase();
    return t.endsWith("*") ? ext.startsWith(t.slice(0, -1)) : ext === t;
  });
}

// ------------------------------------------------------------------ download capture

chrome.downloads.onCreated.addListener(async (item) => {
  const s = await settings();
  if (!s.capture) return;
  const url = item.finalUrl || item.url;
  if (!/^https?:/i.test(url)) return; // blob:, data:, file: stay in the browser
  const info = appInfo || (await ping());
  if (!info || !info.enabled) return;
  const name = item.filename ? item.filename.split(/[\\/]/).pop() : url.split("/").pop();
  const ext = extensionOf(name) || extensionOf(url);
  const skip = s.skipTypes.split(/[\s,;]+/).filter(Boolean).map((t) => t.replace(/^\./, "").toLowerCase());
  if (skip.includes(ext)) return;
  if (!matchesTypes(ext, info.types) && !MEDIA_TYPES.test(item.mime || "")) return;
  if (s.minSizeKB > 0 && item.fileSize > 0 && item.fileSize < s.minSizeKB * 1024) return;

  const ok = await sendDownload({
    url,
    fileName: item.filename ? name : null,
    referer: item.referrer,
    size: item.totalBytes || item.fileSize || -1,
    mime: item.mime,
  });
  if (ok) {
    try {
      await chrome.downloads.cancel(item.id);
      await chrome.downloads.erase({ id: item.id });
    } catch (e) {
      // Already finished (tiny file); nothing to clean up.
    }
  }
});

// ------------------------------------------------------------------ context menus

function buildMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: "imad-link", title: "Download with Imad Downloader", contexts: ["link"] });
    chrome.contextMenus.create({ id: "imad-media", title: "Download this media with Imad Downloader", contexts: ["video", "audio", "image"] });
    chrome.contextMenus.create({ id: "imad-all", title: "Download all links with Imad Downloader", contexts: ["page"] });
    chrome.contextMenus.create({ id: "imad-selection", title: "Download selected links with Imad Downloader", contexts: ["selection"] });
  });
}

chrome.runtime.onInstalled.addListener(() => {
  buildMenus();
  ping();
});
chrome.runtime.onStartup.addListener(() => {
  buildMenus();
  ping();
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const referer = tab?.url;
  if (info.menuItemId === "imad-link" && info.linkUrl) {
    await sendDownload({ url: info.linkUrl, referer });
  } else if (info.menuItemId === "imad-media" && info.srcUrl) {
    const src = /^https?:/i.test(info.srcUrl) ? info.srcUrl : bestMedia(tab?.id)?.url;
    if (src) await sendDownload({ url: src, referer });
  } else if ((info.menuItemId === "imad-all" || info.menuItemId === "imad-selection") && tab?.id != null) {
    const onlySelection = info.menuItemId === "imad-selection";
    const [res] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      args: [onlySelection],
      func: (selOnly) => {
        let anchors = Array.from(document.querySelectorAll("a[href]"));
        if (selOnly) {
          const sel = window.getSelection();
          anchors = anchors.filter((a) => sel && sel.containsNode(a, true));
        }
        const seen = new Set();
        return anchors
          .map((a) => ({ url: a.href, name: (a.getAttribute("download") || "").trim() || null }))
          .filter((l) => /^https?:/i.test(l.url) && !seen.has(l.url) && seen.add(l.url));
      },
    });
    const links = res?.result || [];
    if (links.length) await call("/api/links", { links, referer, cookies: await cookiesFor(referer || ""), userAgent: navigator.userAgent });
  }
});

// ------------------------------------------------------------------ media sniffing

function remember(tabId, entry) {
  if (tabId < 0) return;
  const list = media.get(tabId) || [];
  const base = entry.url.split("?")[0];
  if (list.some((m) => m.url.split("?")[0] === base)) return;
  list.push(entry);
  media.set(tabId, list.slice(-50));
  chrome.action.setBadgeText({ tabId, text: String(media.get(tabId).length) });
  chrome.tabs.sendMessage(tabId, { type: "imad-media", count: media.get(tabId).length }).catch(() => {});
}

function bestMedia(tabId) {
  const list = media.get(tabId) || [];
  const hls = list.find((m) => /m3u8/i.test(m.url) || /mpegurl/i.test(m.mime || ""));
  if (hls) return hls;
  return list.slice().sort((a, b) => (b.size || 0) - (a.size || 0))[0];
}

chrome.webRequest.onHeadersReceived.addListener(
  (d) => {
    if (d.tabId < 0 || d.statusCode >= 400) return;
    const header = (n) => d.responseHeaders?.find((h) => h.name.toLowerCase() === n)?.value || "";
    const mime = header("content-type").split(";")[0].trim();
    const size = parseInt(header("content-length") || "-1", 10);
    const range = header("content-range");
    const total = range.includes("/") ? parseInt(range.split("/")[1], 10) : size;
    const isMedia = MEDIA_TYPES.test(mime) || MEDIA_EXT.test(d.url);
    if (!isMedia) return;
    // Skip HLS/DASH pieces and tiny previews; keep playlists and real files.
    if (/\.(ts|m4s)(\?|$)/i.test(d.url)) return;
    const playlist = /m3u8|mpd/i.test(d.url) || /mpegurl|dash/i.test(mime);
    if (!playlist && total > 0 && total < MIN_MEDIA_BYTES) return;
    chrome.tabs.get(d.tabId).then((tab) => {
      remember(d.tabId, { url: d.url, mime, size: total, title: tab.title, ts: Date.now() });
    }).catch(() => {});
  },
  { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] },
  ["responseHeaders"],
);

chrome.tabs.onUpdated.addListener((tabId, change) => {
  if (change.status === "loading" && change.url) {
    media.delete(tabId);
    chrome.action.setBadgeText({ tabId, text: "" });
  }
});
chrome.tabs.onRemoved.addListener((tabId) => media.delete(tabId));

// ------------------------------------------------------------------ messages from popup / content script

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  (async () => {
    switch (msg.type) {
      case "status":
        reply({ app: await ping(), settings: await settings(), port: appPort });
        break;
      case "media":
        reply({ items: media.get(msg.tabId) || [] });
        break;
      case "download":
        reply({ ok: await sendDownload(msg) });
        break;
      case "download-best": {
        const tabId = sender.tab?.id;
        const best = msg.src && /^https?:/i.test(msg.src) ? { url: msg.src } : bestMedia(tabId);
        let fileName = null;
        if (best && msg.title) {
          const ext = extensionOf(best.url) || "mp4";
          fileName = `${msg.title.replace(/[\\/:*?"<>|]+/g, " ").trim().slice(0, 120)}.${ext}`;
        }
        const ok = best ? await sendDownload({ url: best.url, referer: sender.tab?.url, fileName }) : false;
        reply({ ok, found: !!best });
        break;
      }
      case "save-settings":
        await chrome.storage.local.set(msg.settings);
        reply({ ok: true });
        break;
      default:
        reply({});
    }
  })();
  return true; // async reply
});
