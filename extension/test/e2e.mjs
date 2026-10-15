// End-to-end test: real Windows app + real browser with the extension loaded.
//
//  1. Serves a test page and a 3 MB file from a local server (with byte ranges).
//  2. Starts Hyper Downloader (gradlew :desktop:run) with a throwaway data folder.
//  3. Opens Chromium with the unpacked extension and clicks the download link.
//  4. Passes when the browser download was taken over and the app saved an identical file.
//
// Run from the repo root:  node extension/test/e2e.mjs
import { chromium } from "playwright-core";
import { spawn } from "node:child_process";
import { idmRunning, stopIdm } from "./stop-idm.mjs";

// IDM injects itself into chrome.exe and wakes up from there; run a copy under a neutral name.
function chromePath() {
  const exe = process.env.CHROME_PATH;
  if (!exe) return undefined;
  const copy = path.join(path.dirname(exe), "cr-imadtest.exe");
  if (!fs.existsSync(copy)) fs.copyFileSync(exe, copy);
  return copy;
}
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const work = fs.mkdtempSync(path.join(os.tmpdir(), "imad-e2e-"));
const dataDir = path.join(work, "data");
const saveDir = path.join(work, "downloads");
fs.mkdirSync(dataDir, { recursive: true });
fs.writeFileSync(
  path.join(dataDir, "settings.json"),
  JSON.stringify({ saveDir, categorySubfolders: false, showStartDialog: false, showProgressWindow: false, showCompleteDialog: false, monitorClipboard: false, notifications: false, soundOnComplete: false }),
);

const payload = crypto.randomBytes(3 * 1024 * 1024);
const server = http.createServer((req, res) => {
  if (req.url === "/") {
    res.writeHead(200, { "Content-Type": "text/html" });
    res.end('<html><body><h1>Test</h1><a id="dl" href="/files/e2e-test.zip">Download</a><a href="/files/other.pdf">pdf</a></body></html>');
    return;
  }
  if (req.url.startsWith("/files/")) {
    const range = req.headers.range;
    const headers = { "Content-Type": "application/zip", "Accept-Ranges": "bytes" };
    if (range) {
      const [a, b] = range.replace("bytes=", "").split("-");
      const start = parseInt(a, 10);
      const end = b ? Math.min(parseInt(b, 10), payload.length - 1) : payload.length - 1;
      res.writeHead(206, { ...headers, "Content-Range": `bytes ${start}-${end}/${payload.length}`, "Content-Length": end - start + 1 });
      res.end(payload.subarray(start, end + 1));
    } else {
      res.writeHead(200, { ...headers, "Content-Length": payload.length, "Content-Disposition": 'attachment; filename="e2e-test.zip"' });
      res.end(payload);
    }
    return;
  }
  res.writeHead(404).end();
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;

const log = (...a) => console.log("[e2e]", ...a);
let app;
let browser;
let failed = false;
let diag = null;

async function ping() {
  for (const port of [9614, 9615, 9616, 9617]) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/ping`, { method: "POST", headers: { "X-Imad-Client": "e2e" }, body: "{}" });
      if (r.ok) return port;
    } catch {}
  }
  return null;
}

async function waitFor(fn, ms, what) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const v = await fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`timed out waiting for ${what}`);
}

try {
  stopIdm();
  if (await ping()) throw new Error("Another Hyper Downloader is already running; close it first.");
  log("starting app...");
  // APP_EXE runs the packaged app instead of the Gradle dev build.
  const cmd = process.env.APP_EXE ? [process.env.APP_EXE, []] : ["cmd.exe", ["/d", "/c", path.join(root, "gradlew.bat"), ":desktop:run", "--console=plain", "-q"]];
  app = spawn(cmd[0], cmd[1], {
    cwd: root,
    env: { ...process.env, IMAD_DATA_DIR: dataDir },
    stdio: process.env.E2E_VERBOSE ? "inherit" : "ignore",
  });
  const port = await waitFor(ping, 240_000, "app to start");
  log("app is listening on port", port);

  const ext = path.join(root, "extension", "dist", "chromium");
  // Chromium cannot run under another name (side-by-side manifest), so IDM is only stopped and
  // checked: if IDM restarts itself during the test, the test fails loudly below.
  browser = await chromium.launchPersistentContext(path.join(work, "profile"), {
    executablePath: chromePath(),
    headless: false,
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
  });
  let [worker] = browser.serviceWorkers();
  if (!worker) worker = await browser.waitForEvent("serviceworker", { timeout: 15_000 });
  log("extension loaded:", worker.url());

  const page = await browser.newPage();
  await page.goto(base);
  // Playwright intercepts link-click downloads itself, so start the download the way the
  // browser does for a normal user: through the downloads API, which fires onCreated.
  await worker.evaluate((u) => chrome.downloads.download({ url: u }), `${base}/files/e2e-test.zip`);

  const target = path.join(saveDir, "e2e-test.zip");
  diag = async () => {
    log("save dir:", fs.existsSync(saveDir) ? fs.readdirSync(saveDir, { recursive: true }) : "missing");
    const st = path.join(dataDir, "downloads.json");
    log("app state:", fs.existsSync(st) ? fs.readFileSync(st, "utf8").slice(0, 1500) : "none");
    log("browser downloads:", JSON.stringify(await worker.evaluate(() => new Promise((r) => chrome.downloads.search({}, r)))).slice(0, 800));
  };
  await waitFor(() => fs.existsSync(target) && fs.statSync(target).size === payload.length, 60_000, "the app to finish the download");
  const same = Buffer.compare(fs.readFileSync(target), payload) === 0;
  if (!same) throw new Error("downloaded file differs from the original");
  const state = JSON.parse(fs.readFileSync(path.join(dataDir, "downloads.json"), "utf8"));
  const item = state.downloads.find((d) => d.fileName === "e2e-test.zip");
  log("app saved", target, "status", item?.status, "referer", item?.referer);
  

  const browserDownloads = await worker.evaluate(() => new Promise((r) => chrome.downloads.search({}, r)));
  if (browserDownloads.some((d) => d.url.includes("e2e-test.zip") && d.state === "complete")) {
    throw new Error("the browser also downloaded the file; capture did not cancel it");
  }
  // Right-click "Download with Hyper Downloader" path: same function the context menu calls.
  const sent = await worker.evaluate((a) => sendDownload({ url: a.url, referer: a.ref }), { url: `${base}/files/menu-pick.zip`, ref: `${base}/` });
  if (!sent) throw new Error("context menu hand-off failed");
  const menuTarget = path.join(saveDir, "menu-pick.zip");
  await waitFor(() => fs.existsSync(menuTarget) && fs.statSync(menuTarget).size === payload.length, 60_000, "the context-menu download");
  const st2 = JSON.parse(fs.readFileSync(path.join(dataDir, "downloads.json"), "utf8"));
  const menuItem = st2.downloads.find((d) => d.fileName === "menu-pick.zip");
  if (menuItem?.referer !== `${base}/`) throw new Error("referer was not passed from the browser");
  log("context menu download saved with referer", menuItem.referer);
  const idm = idmRunning();
  if (idm.length) throw new Error("IDM started during the test: " + idm.join(", "));
  log("PASS: browser download captured and completed by Hyper Downloader (IDM stayed closed)");
} catch (e) {
  failed = true;
  console.error("[e2e] FAIL:", e.message);
  if (diag) await diag().catch((x) => log("diag failed", x.message));
} finally {
  await browser?.close().catch(() => {});
  server.close();
  setTimeout(() => fs.rmSync(work, { recursive: true, force: true, maxRetries: 5 }), 1000);
  if (app) spawn("taskkill", ["/pid", String(app.pid), "/t", "/f"], { stdio: "ignore" });
  setTimeout(() => process.exit(failed ? 1 : 0), 1500);
}
