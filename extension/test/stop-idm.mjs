// The real IDM (Internet Download Manager) on the PC grabs browser downloads and would steal
// the test's file, so it is closed first (not uninstalled). It stays closed until reopened.
// IDM also injects itself into processes named like browsers (firefox.exe, chrome.exe) and
// restarts IDMan.exe from there, so tests run browsers under neutral names too.
import { spawnSync } from "node:child_process";
export const IDM_PROCESSES = ["IDMan.exe", "IEMonitor.exe", "IDMMsgHost.exe", "idmBroker.exe", "IDMGrHlp.exe", "IDMIntegrator64.exe", "MediumILStart.exe"];
export function stopIdm() {
  for (const p of IDM_PROCESSES) spawnSync("taskkill", ["/im", p, "/f"], { stdio: "ignore" });
}
export function idmRunning() {
  const out = spawnSync("tasklist", ["/fo", "csv", "/nh"], { encoding: "utf8" }).stdout || "";
  return IDM_PROCESSES.filter((p) => out.toLowerCase().includes(`"${p.toLowerCase()}"`));
}