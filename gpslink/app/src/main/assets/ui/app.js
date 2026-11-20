import { attachLiquidDock, attachButtonHighlights } from "./liquid-dock.mjs";
const $ = (id) => document.getElementById(id);
let role = "sender",
  mode = "lan",
  running = false,
  ready = false;
const native = (action, data = {}) =>
  window.Android?.dispatch(action, JSON.stringify(data));
function select(group, value) {
  $(group).querySelectorAll("button").forEach((button) => {
    const selected = button.dataset.value === String(value);
    button.setAttribute("aria-selected", String(selected));
    button.setAttribute("aria-pressed", String(selected));
  });
}
function selected(group) {
  return $(group).querySelector("[aria-selected=true]").dataset.value;
}
function config() {
  return {
    sid: $("sid").value.trim() || "8888",
    port: Number($("port").value),
    host: $("host").value.trim(),
    useBt: mode === "bt",
    intervalMs: Number(selected("sample")),
    injectMs: Number(selected("inject")),
  };
}
function refreshMode() {
  $("lan-settings").hidden = mode === "bt";
  $("bt-settings").hidden = mode !== "bt";
  $("mode-label").textContent = mode === "bt" ? "蓝牙" : "局域网";
  $("room-label").textContent = $("sid").value.trim() || "8888";
  $("transport-label").textContent =
    mode === "bt" ? "蓝牙 RFCOMM" : `UDP / ${$("port").value || 7007}`;
}
function refreshRole() {
  select("role", role);
  const receive = role === "receiver";
  $("action-title").textContent = receive
    ? "接收另一台手机的定位"
    : "共享此设备的位置";
  $("action-hint").textContent = receive
    ? "先在开发者选项中，将 GNSS Link 设为模拟位置应用。"
    : "两台设备连接同一 Wi-Fi 或热点，并使用相同房间码。";
  $("start").firstChild.textContent = receive ? "开始接收 " : "开始共享 ";
  $("stop").textContent = receive ? "停止接收" : "停止共享";
  $("source-caption").textContent = receive ? "另一台手机" : "这台手机";
  $("target-caption").textContent = receive ? "这台设备" : "另一台设备";
}
for (const group of ["role", "mode", "sample", "inject"]) {
  $(group).addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    select(group, button.dataset.value);
    if (group === "role") {
      role = button.dataset.value;
      refreshRole();
    } else {
      if (group === "mode") {
        mode = button.dataset.value;
        refreshMode();
      }
      native("configure", config());
    }
  });
}
for (const id of ["sid", "port", "host"])
  $(id).addEventListener("change", () => {
    refreshMode();
    native("configure", config());
  });
$("start").onclick = () => {
  if (
    mode === "lan" &&
    (!Number.isInteger(config().port) ||
      config().port < 1024 ||
      config().port > 65535)
  )
    return notice("端口请填写 1024–65535 之间的整数");
  native("start", { ...config(), role });
  if (!window.Android) notice("界面预览，请安装 Android 应用以连接设备");
};
$("stop").onclick = () => native("stop");
for (const action of [
  "probe",
  "pick-bt",
  "recheck",
  "permissions",
  "mock",
  "battery",
])
  $(action).onclick = () => native(action);
function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  localStorage.setItem("theme", theme);
  native("theme", { dark: theme === "dark" });
}
$("theme").onclick = () =>
  applyTheme(
    document.documentElement.dataset.theme === "dark" ? "light" : "dark",
  );
function tint(value) {
  document.documentElement.style.setProperty("--glass-tint", value / 100);
  $("tint").value = value;
  $("tint-value").textContent = `${value}%`;
  localStorage.setItem("tint", value);
}
$("tint").oninput = () => tint(Number($("tint").value));
let noticeTimer;
function notice(message) {
  $("notice").textContent = message;
  $("notice").hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => ($("notice").hidden = true), 4000);
}
const number = (value, digits = 1) =>
  Number.isFinite(value) ? value.toFixed(digits) : "—";
function renderStatus(s) {
  running = s.running;
  if (running) {
    role = s.role === "RECEIVER" ? "receiver" : "sender";
    select("role", role);
    refreshRole();
  }
  for (const group of ["role", "mode"])
    $(group)
      .querySelectorAll("button")
      .forEach((b) => (b.disabled = running));
  for (const id of ["sid", "port", "host", "pick-bt"]) $(id).disabled = running;
  $("probe").disabled = running || s.probing;
  $("start").hidden = running;
  $("stop").hidden = !running;
  $("state").textContent = running
    ? s.online
      ? "已连接"
      : "等待连接"
    : "未启动";
  $("state").classList.toggle("online", running && s.online);
  $("peer").textContent = running
    ? s.peer
      ? `${s.online ? "已连接" : "对方无响应"} · ${s.peer}`
      : "正在等待另一台设备…"
    : "等待建立连接";
  const fix = running && s.fixCount > 0;
  $("coordinates").replaceChildren();
  $("coordinates").append(
    document.createTextNode(
      fix ? `${number(s.lat, 6)}, ${number(s.lon, 6)}` : "—",
    ),
  );
  const subtitle = document.createElement("small");
  subtitle.textContent = fix ? "WGS-84 经纬度" : "启动后显示实时经纬度";
  $("coordinates").append(subtitle);
  for (const [id, value, unit] of [
    ["accuracy", fix && s.accuracy > 0 ? number(s.accuracy) : "—", "m"],
    ["speed", fix ? number(s.speed * 3.6) : "—", "km/h"],
    ["altitude", fix ? number(s.altitude) : "—", "m"],
  ]) {
    $(id).replaceChildren(document.createTextNode(`${value} `));
    const small = document.createElement("small");
    small.textContent = unit;
    $(id).append(small);
  }
  $("bearing").textContent =
    fix && s.bearing >= 0 ? `${number(s.bearing, 0)}°` : "—";
  $("age").textContent =
    fix && s.fixAgeMs >= 0
      ? `源定位 ${number(s.fixAgeMs / 1000)} 秒前更新${s.fixAgeMs > 3000 ? " · 数据偏旧" : ""}`
      : "等待第一帧定位数据";
  $("fix-badge").textContent = fix ? "已有定位" : "等待定位";
  $("rate").textContent = running ? number(s.rateHz, 0) : "—";
  const receive = s.role === "RECEIVER";
  $("latency").textContent = running
    ? receive
      ? number(s.lossPercent)
      : s.rttMs >= 0
        ? s.rttMs
        : "—"
    : "—";
  $("latency-label").textContent = receive ? "丢包率 %" : "往返延迟 ms";
  $("packets").textContent = s.rxCount || 0;
  $("local-ip").textContent = s.localIp
    ? `本机地址  ${s.localIp}`
    : "启动后显示本机地址";
  $("verification").hidden = !(fix && receive);
  $("verify-value").textContent =
    `注入坐标 ${number(s.lat, 6)}, ${number(s.lon, 6)}`;
  $("verify-result").textContent = !s.verifyAvailable
    ? "系统回读不可用，请检查模拟位置授权"
    : `系统回读偏差 ${number(s.verifyDeltaM)} m · ${s.verifyMock ? "已标记模拟位置" : "未标记模拟位置"}`;
}
// SBAS augments multiple satellite systems and has no single national flag.
const satelliteFlags = {
  北斗: "🇨🇳", GPS: "🇺🇸", GLO: "🇷🇺", GAL: "🇪🇺",
  QZS: "🇯🇵", IRN: "🇮🇳", SBS: "🌐",
};
function renderSatellites(payload) {
  const { items, source } = payload;
  $("sat-source").textContent = source;
  $("sat-count").textContent =
    `可见 ${items.length} · 定位 ${items.filter((s) => s.used).length}`;
  $("sky")
    .querySelectorAll(".sky-dot")
    .forEach((n) => n.remove());
  $("sky").querySelector(".sky-empty").hidden = items.length > 0;
  $("sat-hint").textContent = items.length
    ? "蓝色为参与定位的卫星，信号强度单位为 dB-Hz。"
    : "开启系统定位，到室外或窗边接收信号。";
  $("sat-bars").replaceChildren();
  for (const sat of items) {
    const angle = (sat.azimuth * Math.PI) / 180,
      radius = ((90 - sat.elevation) / 90) * 44;
    const dot = document.createElement("span");
    dot.className = `sky-dot${sat.used ? " used" : ""}`;
    dot.textContent = sat.svid;
    dot.style.left = `${50 + Math.sin(angle) * radius}%`;
    dot.style.top = `${50 - Math.cos(angle) * radius}%`;
    dot.title = `${sat.name} ${sat.svid} · ${number(sat.cn0)} dB-Hz`;
    $("sky").append(dot);
    const bar = document.createElement("div");
    bar.className = `sat-bar${sat.used ? " used" : ""}`;
    const strength = document.createElement("span");
    strength.textContent = number(sat.cn0, 0);
    const column = document.createElement("i");
    column.style.height = `${Math.max(2, Math.min(60, sat.cn0)) * 3}px`;
    const label = document.createElement("span");
    label.className = "sat-label";
    const flag = document.createElement("span");
    flag.className = "sat-flag";
    flag.textContent = satelliteFlags[sat.name] || "🛰️";
    flag.setAttribute("aria-hidden", "true");
    const identifier = document.createElement("span");
    identifier.textContent = sat.svid;
    label.append(flag, identifier);
    bar.title = `${sat.name} ${sat.svid} · ${number(sat.cn0)} dB-Hz`;
    bar.setAttribute("role", "img");
    bar.setAttribute("aria-label", `${sat.name}卫星 ${sat.svid}，信号强度 ${number(sat.cn0)} dB-Hz${sat.used ? "，参与定位" : ""}`);
    bar.append(strength, column, label);
    $("sat-bars").append(bar);
  }
}
window.GnssUI = {
  receive(type, data) {
    if (type === "init") {
      for (const id of ["sid", "port", "host"]) $(id).value = data[id];
      mode = data.useBt ? "bt" : "lan";
      select("mode", mode);
      select("sample", data.intervalMs);
      select("inject", data.injectMs);
      refreshMode();
      document.documentElement.toggleAttribute(
        "data-reduce-motion",
        data.reduceMotion,
      );
      if (!ready)
        applyTheme(
          localStorage.getItem("theme") || (data.dark ? "dark" : "light"),
        );
      ready = true;
    } else if (type === "status") renderStatus(data);
    else if (type === "checks")
      for (const name of ["location", "dev", "mock", "battery"]) {
        $(`check-${name}`).textContent = data[name] ? "已就绪" : "待设置";
        $(`check-${name}`).classList.toggle("ok", data[name]);
      }
    else if (type === "satellites") renderSatellites(data);
    else if (type === "log") {
      $("logs").textContent = data.join("\n") || "暂无日志";
    } else if (type === "bt") $("bt-device").textContent = data;
    else if (type === "probe") {
      $("probe-result").textContent = data.message;
      $("probe").disabled = data.running || running;
    } else if (type === "notice") notice(data);
    else if (type === "motion")
      document.documentElement.toggleAttribute("data-reduce-motion", data);
  },
};
attachLiquidDock(document.querySelector(".bottom-dock"), (index) => {
  ["link", "satellites", "settings"].forEach(
    (name, i) => ($(`page-${name}`).hidden = i !== Number(index)),
  );
  window.scrollTo(0, 0);
});
attachButtonHighlights();
tint(Number(localStorage.getItem("tint") ?? 35));
applyTheme(
  localStorage.getItem("theme") ||
    (matchMedia("(prefers-color-scheme:dark)").matches ? "dark" : "light"),
);
refreshRole();
refreshMode();
native("ready");
