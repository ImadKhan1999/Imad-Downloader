// Builds the extension for each browser family from the shared source in this folder.
//   node extension/build.mjs  ->  extension/dist/chromium/  (Chrome, Edge, Brave, Opera, Vivaldi)
//                                 extension/dist/firefox/   (Firefox 115+)
//                                 extension/dist/*.zip      (store uploads)
// Chromium runs the background as a service worker; Firefox needs background "scripts" and an
// add-on id. Everything else is the same code (Firefox supports the chrome.* namespace).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";

const src = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(src, "dist");
const files = ["background.js", "content.js", "content.css", "popup.html", "popup.css", "popup.js"];
const base = JSON.parse(fs.readFileSync(path.join(src, "manifest.json"), "utf8"));

export const FIREFOX_ID = "imad-downloader@imadkhan1999.github.io";

function copyTree(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const a = path.join(from, e.name);
    const b = path.join(to, e.name);
    if (e.isDirectory()) copyTree(a, b);
    else fs.copyFileSync(a, b);
  }
}

// Minimal ZIP writer (stored + deflate) so the build needs no npm packages.
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function zipDir(dir, zipPath) {
  const entries = [];
  const walk = (d, rel) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const p = path.join(d, e.name);
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walk(p, r);
      else entries.push({ name: r, data: fs.readFileSync(p) });
    }
  };
  walk(dir, "");
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const { name, data } of entries) {
    const comp = zlib.deflateRawSync(data);
    const nameBuf = Buffer.from(name);
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0, 6); local.writeUInt16LE(8, 8);
    local.writeUInt32LE(0, 10); local.writeUInt32LE(crc, 14); local.writeUInt32LE(comp.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26); local.writeUInt16LE(0, 28);
    chunks.push(local, nameBuf, comp);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0, 8); c.writeUInt16LE(8, 10);
    c.writeUInt32LE(0, 12); c.writeUInt32LE(crc, 16); c.writeUInt32LE(comp.length, 20); c.writeUInt32LE(data.length, 24);
    c.writeUInt16LE(nameBuf.length, 28); c.writeUInt32LE(offset, 42);
    central.push(c, nameBuf);
    offset += 30 + nameBuf.length + comp.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  fs.writeFileSync(zipPath, Buffer.concat([...chunks, cd, end]));
}

function build(name, manifest) {
  const dir = path.join(out, name);
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  for (const f of files) fs.copyFileSync(path.join(src, f), path.join(dir, f));
  copyTree(path.join(src, "icons"), path.join(dir, "icons"));
  fs.writeFileSync(path.join(dir, "manifest.json"), JSON.stringify(manifest, null, 2));
  zipDir(dir, path.join(out, `idownloader-${name}-${manifest.version}.zip`));
  return dir;
}

const chromium = { ...base };
const firefox = {
  ...base,
  background: { scripts: ["background.js"] },
  browser_specific_settings: { gecko: { id: FIREFOX_ID, strict_min_version: "115.0", data_collection_permissions: { required: ["none"] } } },
};
delete firefox.minimum_chrome_version;

fs.mkdirSync(out, { recursive: true });
console.log("built", build("chromium", chromium));
console.log("built", build("firefox", firefox));
