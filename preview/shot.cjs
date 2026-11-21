const puppeteer = require("C:/Users/Administrator/android-tools/shot-tool/node_modules/puppeteer-core");
const path = require("path");

const OUT = "C:/Users/Administrator/WorkBuddy/spaces/2026_11_20_14-54-32/gnss-link/preview";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const TABS = ["link", "satellites", "settings", "about"];

(async () => {
  const browser = await puppeteer.connect({ browserURL: "http://127.0.0.1:9222" });
  const page = await browser.newPage();
  await page.setViewport({ width: 393, height: 852, deviceScaleFactor: 2 });
  await page.goto("http://127.0.0.1:8766/index.html", {
    waitUntil: "networkidle0",
    timeout: 30000,
  });
  await sleep(1800);

  async function showTab(index) {
    await page.evaluate((i) => {
      ["link", "satellites", "settings", "about"].forEach((name, n) => {
        const el = document.getElementById("page-" + name);
        if (el) el.hidden = n !== i;
      });
      const tabs = [...document.querySelectorAll(".dock-tab")];
      tabs.forEach((b, n) => b.setAttribute("aria-selected", String(n === i)));
      const lens = document.querySelector(".dock-lens");
      if (lens && tabs.length) {
        const w = tabs[0].offsetWidth;
        lens.style.width = `${w}px`;
        lens.style.transform = `translate3d(${tabs[i].offsetLeft}px, 0, 0)`;
      }
      const m = document.getElementById("update-modal");
      if (m) m.hidden = true;
      document.body.style.overflow = "";
    }, index);
    await sleep(900);
  }

  for (let i = 0; i < TABS.length; i++) {
    await showTab(i);
    const file = `${TABS[i]}-light.png`;
    await page.screenshot({ path: path.join(OUT, file) });
    console.log("captured", file);
  }

  await showTab(3);
  await page.evaluate(() => {
    document.getElementById("update-current").textContent = "1.4.0";
    document.getElementById("update-latest").textContent = "1.5.0";
    document.getElementById("update-now").textContent = "更新 · 4.7 MB";
    const notes = document.getElementById("update-notes");
    notes.replaceChildren();
    [
      "自更新：仅当有新版本时提示",
      "关于页与 Lens 视觉升级",
      "修复设置页右溢出被裁",
    ].forEach((text) => {
      const li = document.createElement("li");
      li.textContent = text;
      notes.append(li);
    });
    notes.hidden = false;
    document.getElementById("update-modal").hidden = false;
    document.body.style.overflow = "hidden";
  });
  await sleep(700);
  await page.screenshot({ path: path.join(OUT, "update-modal-light.png") });
  console.log("captured update-modal-light.png");

  await page.evaluate(() => {
    document.getElementById("update-modal").hidden = true;
    document.documentElement.dataset.theme = "dark";
  });
  await sleep(800);
  await showTab(2);
  await page.screenshot({ path: path.join(OUT, "settings-dark.png") });
  console.log("captured settings-dark.png");

  await page.evaluate(() => {
    document.getElementById("update-modal").hidden = false;
    document.body.style.overflow = "hidden";
  });
  await sleep(600);
  await page.screenshot({ path: path.join(OUT, "update-modal-dark.png") });
  console.log("captured update-modal-dark.png");

  await page.close();
  await browser.disconnect();
})().catch((e) => {
  console.error("SHOT_ERROR", e.message);
  process.exit(1);
});
