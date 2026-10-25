// End-to-end test in real Firefox: the app + Firefox (driven by Mozilla's geckodriver) with the
// Firefox build of the extension. A click on a download link must end up saved by the app.
//   node extension/build.mjs && node extension/test/e2e-firefox.mjs
// FIREFOX_PATH / GECKODRIVER_PATH choose the binaries (defaults: E:\TestTools\...).
import { spawn } from "node:child_process";
import { idmRunning, stopIdm } from "./stop-idm.mjs";
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
// A copy of firefox.exe under another name, so IDM does not inject itself into the test browser.
const firefox = process.env.FIREFOX_PATH || "E:\\TestTools\\firefox\\core\\ff-imadtest.exe";
if (!fs.existsSync(firefox)) fs.copyFileSync(path.join(path.dirname(firefox), "firefox.exe"), firefox);
const gecko = process.env.GECKODRIVER_PATH || "E:\\TestTools\\geckodriver.exe";
const work = fs.mkdtempSync(path.join(os.tmpdir(), "imad-ffe2e-"));
const dataDir = path.join(work, "data");
const saveDir = path.join(work, "downloads");
fs.mkdirSync(dataDir, { recursive: true });
fs.mkdirSync(path.join(work, "browser"), { recursive: true }); // Firefox fails downloads silently into a missing folder
fs.writeFileSync(path.join(dataDir, "settings.json"), JSON.stringify({
  saveDir, categorySubfolders: false, showStartDialog: false, showProgressWindow: false, showCompleteDialog: false,
  monitorClipboard: false, notifications: false, soundOnComplete: false, autoUpdate: false,
}));

const payload = crypto.randomBytes(2 * 1024 * 1024);
const server = http.createServer((req, res) => {
  if (process.env.E2E_VERBOSE) console.log("[site]", req.method, req.url, req.headers.range || "", req.headers["user-agent"]?.slice(0, 60));
  if (req.url === "/") {
    res.writeHead(200, { "Content-Type": "text/html" });
    res.end('<html><body><h1>Firefox test</h1><a id="dl" href="/files/ff-test.zip">Download</a></body></html>');
  } else if (req.url.startsWith("/files/")) {
    const range = req.headers.range;
    const h = { "Content-Type": "application/zip", "Accept-Ranges": "bytes" };
    if (range) {
      const [a, b] = range.replace("bytes=", "").split("-");
      const s = parseInt(a, 10);
      const e = b ? Math.min(parseInt(b, 10), payload.length - 1) : payload.length - 1;
      res.writeHead(206, { ...h, "Content-Range": `bytes ${s}-${e}/${payload.length}`, "Content-Length": e - s + 1 });
      res.end(payload.subarray(s, e + 1));
    } else {
      res.writeHead(200, { ...h, "Content-Length": payload.length, "Content-Disposition": 'attachment; filename="ff-test.zip"' });
      res.end(payload);
    }
  } else res.writeHead(404).end();
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;
const log = (...a) => console.log("[ff-e2e]", ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function ping() {
  try { return (await fetch("http://127.0.0.1:9614/api/ping", { method: "POST", headers: { "X-Imad-Client": "e2e" }, body: "{}" })).ok; } catch { return false; }
}
async function waitFor(fn, ms, what) {
  const until = Date.now() + ms;
  while (Date.now() < until) { if (await fn()) return true; await sleep(500); }
  throw new Error(`timed out waiting for ${what}`);
}

const wd = "http://127.0.0.1:4455";
async function w(method, p, body) {
  const r = await fetch(wd + p, { method, headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json();
  if (!r.ok) throw new Error(`${method} ${p}: ${JSON.stringify(j.value).slice(0, 300)}`);
  return j.value;
}

let app, driver, session, failed = false;
try {
  stopIdm();
  if (await ping()) throw new Error("Another iDownloader is already running; close it first.");
  const cmd = process.env.APP_EXE ? [process.env.APP_EXE, []] : ["cmd.exe", ["/d", "/c", path.join(root, "gradlew.bat"), ":desktop:run", "--console=plain", "-q"]];
  app = spawn(cmd[0], cmd[1], { cwd: root, env: { ...process.env, IMAD_DATA_DIR: dataDir }, stdio: "ignore" });
  await waitFor(ping, 240_000, "app to start");
  log("app is up");

  driver = spawn(gecko, ["--port", "4455", "--allow-system-access"], { stdio: "ignore" });
  await waitFor(async () => { try { return (await fetch(wd + "/status")).ok; } catch { return false; } }, 20_000, "geckodriver");
  const s = await w("POST", "/session", {
    capabilities: { alwaysMatch: { browserName: "firefox", "moz:firefoxOptions": {
      binary: firefox, args: ["-headless"],
      prefs: { "browser.download.always_ask_before_handling_new_types": false, "browser.download.folderList": 2, "browser.download.dir": path.join(work, "browser") },
    } } },
  });
  session = s.sessionId;
  const id = await w("POST", `/session/${session}/moz/addon/install`, { path: path.join(root, "extension", "dist", "firefox"), temporary: true });
  log("extension installed in Firefox", s.capabilities.browserVersion, id);
  // Like a user accepting the "access your data for all websites" prompt at install.
  await w("POST", `/session/${session}/moz/context`, { context: "chrome" });
  const granted = await w("POST", `/session/${session}/execute/async`, { args: [id], script: `const [id, done] = arguments;
    const { ExtensionPermissions } = ChromeUtils.importESModule("resource://gre/modules/ExtensionPermissions.sys.mjs");
    const ext = WebExtensionPolicy.getByID(id).extension;
    ExtensionPermissions.add(id, { permissions: [], origins: ["<all_urls>"] }, ext).then(() => done("ok"), (e) => done(String(e)));` });
  await w("POST", `/session/${session}/moz/context`, { context: "content" });
  log("site access granted:", granted);
  await sleep(2000);
  await w("POST", `/session/${session}/url`, { url: base });
  const el = await w("POST", `/session/${session}/element`, { using: "css selector", value: "#dl" });
  await w("POST", `/session/${session}/element/${Object.values(el)[0]}/click`, {});
  log("clicked the download link");

  const target = path.join(saveDir, "ff-test.zip");
  await waitFor(() => fs.existsSync(target) && fs.statSync(target).size === payload.length, 90_000, "the app to finish the download");
  if (Buffer.compare(fs.readFileSync(target), payload) !== 0) throw new Error("file differs");
  const browserCopy = path.join(work, "browser", "ff-test.zip");
  await sleep(1500);
  if (fs.existsSync(browserCopy) && fs.statSync(browserCopy).size === payload.length) throw new Error("Firefox also kept its own copy; capture did not cancel it");
  const idm = idmRunning();
  if (idm.length) throw new Error("IDM started during the test: " + idm.join(", "));
  log("PASS: Firefox download captured and completed by iDownloader (IDM stayed closed)");
} catch (e) {
  failed = true;
  console.error("[ff-e2e] FAIL:", e.message);
  console.error("[ff-e2e] work tree:", fs.readdirSync(work, { recursive: true }).join(" | "));
  console.error("[ff-e2e] app folder:", fs.existsSync(saveDir) ? fs.readdirSync(saveDir) : "missing", "browser folder:", fs.readdirSync(path.join(work, "browser")));
  const st = path.join(dataDir, "downloads.json");
  if (fs.existsSync(st)) console.error("[ff-e2e] app list:", fs.readFileSync(st, "utf8").slice(0, 600));
  if (session) {
    try {
      await w("POST", `/session/${session}/moz/context`, { context: "chrome" });
      const dl = await w("POST", `/session/${session}/execute/async`, { args: [], script: `const done = arguments[arguments.length - 1];
        const { Downloads } = ChromeUtils.importESModule("resource://gre/modules/Downloads.sys.mjs");
        Downloads.getList(Downloads.ALL).then((l) => l.getAll()).then((all) => done(all.map((d) => ({ url: d.source.url, target: d.target.path, ok: d.succeeded, err: d.error ? String(d.error) : null }))));` });
      console.error("[ff-e2e] Firefox downloads:", JSON.stringify(dl));
      const msgs = await w("POST", `/session/${session}/execute/sync`, { args: [], script: `return Services.console.getMessageArray().map((m) => m.message || String(m)).filter((t) => /moz-extension|background|Imad|Error/i.test(t)).slice(-15);` });
      console.error("[ff-e2e] console:\n  " + msgs.join("\n  "));
    } catch (x) { console.error("[ff-e2e] could not list Firefox downloads:", x.message); }
  }
} finally {
  if (session) await w("DELETE", `/session/${session}`).catch(() => {});
  driver?.kill();
  server.close();
  if (app && !process.env.KEEP_APP) spawn("taskkill", ["/pid", String(app.pid), "/t", "/f"], { stdio: "ignore" });
  if (process.env.KEEP_APP) { console.log("[ff-e2e] kept", work); setTimeout(() => process.exit(failed ? 1 : 0), 1000); } else setTimeout(() => { fs.rmSync(work, { recursive: true, force: true, maxRetries: 5 }); process.exit(failed ? 1 : 0); }, 2000);
}
