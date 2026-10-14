const $ = (id) => document.getElementById(id);

function size(n) {
  if (!n || n < 0) return "";
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`;
}

async function init() {
  const status = await chrome.runtime.sendMessage({ type: "status" });
  const el = $("status");
  if (status.app) {
    el.textContent = `Connected (version ${status.app.version})`;
    el.className = "ok";
  } else {
    el.textContent = "Imad Downloader is not running";
    el.className = "bad";
  }
  $("capture").checked = status.settings.capture;
  $("skip").value = status.settings.skipTypes;
  $("capture").addEventListener("change", save);
  $("skip").addEventListener("change", save);

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const { items } = await chrome.runtime.sendMessage({ type: "media", tabId: tab.id });
  const list = $("media");
  if (items.length) {
    list.innerHTML = "";
    for (const m of items.slice().reverse()) {
      const li = document.createElement("li");
      const name = document.createElement("span");
      name.className = "name";
      name.title = m.url;
      name.textContent = decodeURIComponent(m.url.split("?")[0].split("/").pop() || m.url);
      const sz = document.createElement("span");
      sz.className = "size";
      sz.textContent = /m3u8/i.test(m.url) ? "stream" : size(m.size);
      const btn = document.createElement("button");
      btn.textContent = "Download";
      btn.addEventListener("click", async () => {
        const res = await chrome.runtime.sendMessage({ type: "download", url: m.url, referer: tab.url, mime: m.mime, size: m.size });
        btn.textContent = res.ok ? "Sent" : "Failed";
      });
      li.append(name, sz, btn);
      list.appendChild(li);
    }
  }

  $("all").addEventListener("click", async () => {
    const [res] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => {
        const seen = new Set();
        return Array.from(document.querySelectorAll("a[href]"))
          .map((a) => ({ url: a.href, name: (a.getAttribute("download") || "").trim() || null }))
          .filter((l) => /^https?:/i.test(l.url) && !seen.has(l.url) && seen.add(l.url));
      },
    });
    const links = res?.result || [];
    for (const port of [9614, 9615, 9616, 9617]) {
      try {
        const r = await fetch(`http://127.0.0.1:${port}/api/links`, {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Imad-Client": "extension" },
          body: JSON.stringify({ links, referer: tab.url, userAgent: navigator.userAgent }),
        });
        if (r.ok) { $("all").textContent = `Sent ${links.length} links`; return; }
      } catch (e) { /* try next port */ }
    }
    $("all").textContent = "Imad Downloader is not running";
  });
}

function save() {
  chrome.runtime.sendMessage({ type: "save-settings", settings: { capture: $("capture").checked, skipTypes: $("skip").value } });
}

init();
