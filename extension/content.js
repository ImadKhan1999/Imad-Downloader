// Shows IDM-style "Download this video" button over playing videos.
(() => {
  if (window.__imadDownloader) return;
  window.__imadDownloader = true;

  let mediaCount = 0;
  let button = null;
  let target = null;
  let hideTimer = null;

  function ensureButton() {
    if (button) return button;
    button = document.createElement("div");
    button.className = "imad-dl-button";
    button.innerHTML = '<span class="imad-dl-icon">&#x2B07;</span><span>Download this video</span>';
    button.addEventListener("click", async (e) => {
      e.preventDefault();
      e.stopPropagation();
      const src = target && (target.currentSrc || target.src);
      button.classList.add("imad-dl-busy");
      const res = await chrome.runtime.sendMessage({ type: "download-best", src, title: document.title });
      button.classList.remove("imad-dl-busy");
      flash(res?.ok ? "Sent to Imad Downloader" : res?.found === false ? "No video found yet - press play first" : "Imad Downloader is not running");
    });
    button.addEventListener("mouseenter", () => clearTimeout(hideTimer));
    button.addEventListener("mouseleave", scheduleHide);
    document.documentElement.appendChild(button);
    return button;
  }

  function flash(text) {
    const label = button.querySelector("span:last-child");
    const old = "Download this video";
    label.textContent = text;
    setTimeout(() => (label.textContent = old), 2500);
  }

  function place(video) {
    const r = video.getBoundingClientRect();
    if (r.width < 200 || r.height < 120) return;
    const b = ensureButton();
    b.style.top = `${Math.max(4, r.top + 8)}px`;
    b.style.left = `${Math.max(4, r.right - b.offsetWidth - 8)}px`;
    b.classList.add("imad-dl-visible");
  }

  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => button && button.classList.remove("imad-dl-visible"), 1500);
  }

  function hasSource(video) {
    const src = video.currentSrc || video.src || "";
    return /^https?:/i.test(src) || mediaCount > 0 || /(^|\.)(youtube\.com|vimeo\.com|dailymotion\.com|facebook\.com|instagram\.com|x\.com|twitter\.com|tiktok\.com|twitch\.tv|reddit\.com|bilibili\.com)$/.test(location.hostname);
  }

  document.addEventListener(
    "mouseover",
    (e) => {
      const v = e.target.closest ? e.target.closest("video") : null;
      if (!v || !hasSource(v)) return;
      target = v;
      clearTimeout(hideTimer);
      place(v);
    },
    true,
  );
  document.addEventListener(
    "mouseout",
    (e) => {
      if (e.target.closest && e.target.closest("video")) scheduleHide();
    },
    true,
  );
  window.addEventListener("scroll", () => target && button?.classList.contains("imad-dl-visible") && place(target), { passive: true });

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.type === "imad-media") mediaCount = msg.count;
  });
})();
