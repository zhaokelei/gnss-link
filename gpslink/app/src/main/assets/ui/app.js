import { attachLiquidDock, attachButtonHighlights } from "./liquid-dock.mjs";
const $ = (id) => document.getElementById(id);
let role = "sender",
  mode = "lan",
  running = false,
  ready = false;

/* ===== Lens 材质三档降级 =====
   rich: 支持 url() backdrop-filter，保留 refraction.js + 弹簧透镜
   glass: 仅 blur()，CSS 毛玻璃
   static: 减弱动效/透明度，静态半透明 */
function applyMaterial() {
  const reduce =
    matchMedia("(prefers-reduced-motion: reduce)").matches ||
    matchMedia("(prefers-reduced-transparency: reduce)").matches ||
    document.documentElement.hasAttribute("data-reduce-motion");
  const supports = (v) =>
    !!(window.CSS && (CSS.supports("backdrop-filter", v) || CSS.supports("-webkit-backdrop-filter", v)));
  const material = reduce
    ? "static"
    : supports("url(#a)")
      ? "rich"
      : supports("blur(4px)")
        ? "glass"
        : "static";
  document.documentElement.dataset.material = material;
}
applyMaterial();
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
    ? "需先把本机设为模拟位置应用。打不开开发者选项时，用设置页提供的 adb 命令。"
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
  "background",
])
  $(action).onclick = () => native(action);

function setSwitch(id, on) {
  $(id).setAttribute("aria-checked", String(Boolean(on)));
}
const isOn = (id) => $(id).getAttribute("aria-checked") === "true";

$("autostart").onclick = () => {
  const next = !isOn("autostart");
  setSwitch("autostart", next);
  $("autostart-hint").textContent = next
    ? "下次开机将按上次使用的角色自动运行。"
    : "";
  native("autostart", { enabled: next });
};

$("track-toggle").onclick = () => {
  const next = !isOn("track-toggle");
  setSwitch("track-toggle", next);
  native("track-toggle", { enabled: next });
};

$("track-refresh").onclick = () => native("tracks");

const pushNmea = () =>
  native("nmea", {
    enabled: isOn("nmea"),
    port: Number($("nmea-port").value) || 10110,
  });
$("nmea").onclick = () => {
  setSwitch("nmea", !isOn("nmea"));
  pushNmea();
};
$("nmea-port").addEventListener("change", pushNmea);

$("copy-adb").onclick = () =>
  native("copy", {
    text: `${$("adb-cmd").textContent}\n${$("adb-check").textContent}`,
  });

const fmtDuration = (ms) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return s < 60
    ? `${s} 秒`
    : `${Math.floor(s / 60)} 分 ${String(s % 60).padStart(2, "0")} 秒`;
};
const fmtSize = (b) =>
  b >= 1048576
    ? `${(b / 1048576).toFixed(1)} MB`
    : `${Math.max(1, Math.round(b / 1024))} KB`;

function renderTracks(list) {
  const box = $("track-list");
  box.replaceChildren();
  if (!Array.isArray(list) || list.length === 0) {
    const empty = document.createElement("p");
    empty.className = "field-note";
    empty.textContent = "暂无轨迹";
    box.append(empty);
    return;
  }
  for (const track of list) {
    const row = document.createElement("div");
    row.className = "track-row";

    const info = document.createElement("div");
    const name = document.createElement("div");
    name.className = "track-name";
    name.textContent = track.name.replace(/\.jsonl$/, "");
    const meta = document.createElement("div");
    meta.className = "track-meta";
    meta.textContent = `${track.points} 帧 · ${fmtDuration(track.durationMs)} · ${fmtSize(track.bytes)}`;
    info.append(name, meta);

    const actions = document.createElement("div");
    actions.className = "track-actions";
    const play = document.createElement("button");
    play.className = "lg-btn";
    play.textContent = "回放";
    play.onclick = () => native("track-play", { path: track.path });
    const remove = document.createElement("button");
    remove.className = "lg-btn";
    remove.textContent = "删除";
    remove.onclick = () => {
      if (confirm(`删除轨迹 ${track.name}？`))
        native("track-delete", { path: track.path });
    };
    actions.append(play, remove);

    row.append(info, actions);
    box.append(row);
  }
}
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
  const nmeaOn = s.nmeaPort > 0;
  $("nmea-state").textContent = nmeaOn
    ? `监听 ${s.nmeaPort} · ${s.nmeaClients || 0} 个连接`
    : "未开启";
  $("nmea-address").textContent =
    nmeaOn && s.localIp
      ? `车机连接 ${s.localIp}:${s.nmeaPort}`
      : "开始共享后显示连接地址";
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
      setSwitch("autostart", data.autoStart);
      setSwitch("track-toggle", data.trackEnabled);
      setSwitch("nmea", data.nmeaEnabled);
      if (data.nmeaPort) $("nmea-port").value = data.nmeaPort;
      $("autostart-hint").textContent = data.autoStart
        ? "下次开机将按上次使用的角色自动运行。"
        : "";
      if (data.package) {
        $("adb-cmd").textContent =
          `adb shell appops set ${data.package} android:mock_location allow`;
        $("adb-check").textContent =
          `adb shell appops get ${data.package} android:mock_location`;
      }
      if (data.version) {
        $("about-version").textContent = data.version;
        $("update-current").textContent = data.version;
      }
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
      native("tracks");
    } else if (type === "status") renderStatus(data);
    else if (type === "checks")
      for (const name of ["location", "dev", "mock", "battery", "background"]) {
        $(`check-${name}`).textContent = data[name] ? "已就绪" : "待设置";
        $(`check-${name}`).classList.toggle("ok", data[name]);
      }
    else if (type === "satellites") renderSatellites(data);
    else if (type === "tracks") renderTracks(data);
    else if (type === "log") {
      $("logs").textContent = data.join("\n") || "暂无日志";
    } else if (type === "bt") $("bt-device").textContent = data;
    else if (type === "probe") {
      $("probe-result").textContent = data.message;
      $("probe").disabled = data.running || running;
    } else if (type === "notice") notice(data);
    else if (type === "motion") {
      document.documentElement.toggleAttribute("data-reduce-motion", data);
      applyMaterial();
    } else if (type === "update-check") onUpdateCheck(data);
    else if (type === "update-progress") onUpdateProgress(data);
    else if (type === "update-ready") onUpdateReady(data);
  },
  handleBack,
};
attachLiquidDock(document.querySelector(".bottom-dock"), (index) => {
  ["link", "satellites", "settings", "about"].forEach(
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

/* ==========================================================================
   关于页：链接跳转 / 复制
   ========================================================================== */
const openUrl = (url) => {
  if (window.Android) native("open-url", { url });
  else window.open(url, "_blank", "noopener");
};
document.querySelectorAll("[data-url]").forEach((button) => {
  button.onclick = () => openUrl(button.dataset.url);
});
document.querySelectorAll("[data-copy]").forEach((button) => {
  button.onclick = () => {
    const text = $(button.dataset.copy).textContent.trim();
    if (window.Android) native("copy", { text });
    else if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => notice("已复制到剪贴板"));
  };
});

/* ==========================================================================
   自更新：WebView 内 HTML 模态（唯一强确认态）
   - 遮罩不点击关闭；返回键 / Esc = 取消
   - 焦点陷阱；初始焦点落在模态内；3px 焦点环（见 app.css）
   - 主按钮含下载体积、tabular-nums、窄屏 flex-wrap
   ========================================================================== */
let updateInfo = {};
let updatePhase = "prompt";
let updateReady = false;
let previousFocus = null;

function showVersionBanner(text) {
  $("version-banner-text").textContent = text;
  $("version-banner").hidden = false;
}

function applyUpdatePhase() {
  const main = $("update-now");
  if (updatePhase === "downloading") {
    main.disabled = true;
    main.textContent = "下载中…";
    $("update-progress").hidden = false;
  } else {
    main.disabled = false;
    $("update-progress").hidden = true;
    if (updatePhase === "ready") {
      main.textContent = "安装更新";
      main.onclick = () => native("install-update");
    } else {
      const size = Number(updateInfo.size) || 0;
      main.textContent = `更新 · ${fmtSize(size)}`;
      main.onclick = startDownload;
    }
  }
}

function startDownload() {
  native("download-update");
  updatePhase = "downloading";
  applyUpdatePhase();
}

function openUpdateModal(phase = "prompt") {
  updatePhase = phase;
  $("update-latest").textContent = updateInfo.latest || updateInfo.tag || "—";
  $("update-current").textContent = $("about-version").textContent;
  const notes = $("update-notes");
  notes.replaceChildren();
  String(updateInfo.notes || "")
    .split(/\r?\n/)
    .map((line) => line.replace(/^[-*]\s*/, "").trim())
    .filter(Boolean)
    .slice(0, 5)
    .forEach((text) => {
      const li = document.createElement("li");
      li.textContent = text;
      notes.append(li);
    });
  notes.hidden = notes.childElementCount === 0;
  $("update-title").textContent = phase === "ready" ? "下载完成" : "发现新版本";
  $("update-desc").textContent =
    phase === "ready"
      ? "安装包已下载完成，点击安装即可覆盖当前版本。"
      : "新版本已发布，是否立即更新？";
  applyUpdatePhase();
  previousFocus = document.activeElement;
  $("update-modal").hidden = false;
  document.body.style.overflow = "hidden";
  requestAnimationFrame(() => $("update-now").focus());
}

function closeUpdateModal() {
  if ($("update-modal").hidden) return;
  $("update-modal").hidden = true;
  document.body.style.overflow = "";
  if (previousFocus && previousFocus.focus) previousFocus.focus();
}

/** 原生返回键调用：模态打开则消费并返回 true，否则交还 Activity */
function handleBack() {
  if ($("update-modal").hidden) return false;
  closeUpdateModal();
  return true;
}

function onUpdateCheck(data) {
  if (!data) return;
  updateInfo = data;
  if (data.needUpdate) {
    const version = data.latest || data.tag || "";
    $("update-state").textContent = `发现新版本 ${version}。`;
    showVersionBanner(`发现新版本 ${version}`);
    openUpdateModal("prompt");
  } else {
    $("update-state").textContent = data.current
      ? `已是最新版本 ${data.current}。`
      : "已是最新版本。";
    $("version-banner").hidden = true;
  }
}

function onUpdateProgress(data) {
  const value = Math.max(0, Math.min(100, Number(data && data.value) || 0));
  $("progress-fill").style.transform = `scaleX(${(value / 100).toFixed(3)})`;
  $("progress-text").textContent = `正在下载 ${Math.round(value)}%`;
}

function onUpdateReady(data) {
  updateReady = true;
  updateInfo = { ...updateInfo, ...(data || {}) };
  showVersionBanner("安装包已下载，点击安装");
  if (!$("update-modal").hidden) {
    updatePhase = "ready";
    applyUpdatePhase();
    $("update-now").focus();
  }
}

$("check-update").onclick = () => {
  if (!window.Android) {
    $("update-state").textContent = "预览模式：请在 Android 应用中检查更新。";
    return;
  }
  $("update-state").textContent = "正在检查更新…";
  native("check-update");
};
$("banner-update").onclick = () => {
  if (updateReady) openUpdateModal("ready");
  else if (updateInfo.latest) openUpdateModal("prompt");
  else if (window.Android) native("check-update");
};
$("update-skip").onclick = () => {
  native("skip-version", { tag: updateInfo.tag || updateInfo.latest || "" });
  $("version-banner").hidden = true;
  $("update-state").textContent = "已跳过此版本，不再提示。";
  closeUpdateModal();
};
$("update-later").onclick = closeUpdateModal;

/* 焦点陷阱：Tab / Shift+Tab 在模态内循环；Esc 取消 */
$("update-modal").addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    event.preventDefault();
    closeUpdateModal();
    return;
  }
  if (event.key !== "Tab") return;
  const list = Array.from($("update-modal").querySelectorAll("button:not(:disabled)"));
  if (!list.length) return;
  const first = list[0];
  const last = list[list.length - 1];
  if (!$("update-modal").contains(document.activeElement)) {
    event.preventDefault();
    first.focus();
  } else if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
});

// 首次进入静默检查；离线或失败由原生侧静默跳过，不打扰用户
if (window.Android) native("check-update");
