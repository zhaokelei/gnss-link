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
// 用内联 SVG 画国旗：Emoji 在部分车机与 Windows 上会退化成 US/CN 这样的字母
const satelliteFlags = {
  北斗: "flag-cn", GPS: "flag-us", GLO: "flag-ru", GAL: "flag-eu",
  QZS: "flag-jp", IRN: "flag-in", SBS: "flag-globe",
};
function flagIcon(name) {
  const SVGN = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(SVGN, "svg");
  svg.setAttribute("class", "sat-flag");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(SVGN, "use");
  use.setAttribute("href", `#${satelliteFlags[name] || "flag-globe"}`);
  svg.append(use);
  return svg;
}
/* ==================== 天空图（Canvas 极坐标） ==================== */
let lastSats = [];

function drawSky(sats) {
  const canvas = $("sky");
  if (!canvas || !canvas.parentElement) return;
  const size = Math.round(canvas.clientWidth || canvas.parentElement.clientWidth);
  if (!size) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = size * dpr;
  canvas.height = size * dpr;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, size, size);

  const css = getComputedStyle(document.documentElement);
  const ink = css.getPropertyValue("--label-2").trim() || "#586c86";
  const tint = css.getPropertyValue("--tint").trim() || "#2f6fed";
  const line = css.getPropertyValue("--separator").trim() || "rgba(41,78,121,0.11)";
  const font = 'Roboto, "Noto Sans", system-ui, sans-serif';

  const cx = size / 2;
  const cy = size / 2;
  const R = size / 2 - 16;

  // 圆内一层极淡的向心渐变，中心略亮
  const veil = ctx.createRadialGradient(cx, cy, 0, cx, cy, R);
  veil.addColorStop(0, "rgba(47,111,237,0.10)");
  veil.addColorStop(1, "rgba(47,111,237,0.01)");
  ctx.fillStyle = veil;
  ctx.beginPath();
  ctx.arc(cx, cy, R, 0, Math.PI * 2);
  ctx.fill();

  // 仰角同心环：0° / 30° / 60°
  ctx.strokeStyle = line;
  ctx.lineWidth = 1;
  [1, 2 / 3, 1 / 3].forEach((k) => {
    ctx.beginPath();
    ctx.arc(cx, cy, R * k, 0, Math.PI * 2);
    ctx.stroke();
  });

  // 每 30° 一条放射线
  for (let deg = 0; deg < 360; deg += 30) {
    const a = (deg * Math.PI) / 180;
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx + Math.sin(a) * R, cy - Math.cos(a) * R);
    ctx.stroke();
  }

  // 四方位 + 间隔度数
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillStyle = ink;
  ctx.font = `600 11px ${font}`;
  [
    ["N", 0],
    ["E", 90],
    ["S", 180],
    ["W", 270],
  ].forEach(([label, deg]) => {
    const a = (deg * Math.PI) / 180;
    ctx.fillText(
      label,
      cx + Math.sin(a) * (R + 10),
      cy - Math.cos(a) * (R + 10),
    );
  });
  ctx.font = `500 9px ${font}`;
  ctx.globalAlpha = 0.75;
  [30, 60, 120, 150, 210, 240, 300, 330].forEach((deg) => {
    const a = (deg * Math.PI) / 180;
    ctx.fillText(
      String(deg),
      cx + Math.sin(a) * (R + 10),
      cy - Math.cos(a) * (R + 10),
    );
  });
  ctx.globalAlpha = 1;

  // 卫星点：半径=仰角，角度=方位角，点径分三档映射 C/N0
  const dots = sats.map((sat) => {
    const rr = ((90 - sat.ele) / 90) * R;
    const a = (sat.azi * Math.PI) / 180;
    const big = sat.cno >= 35;
    const mid = sat.cno >= 25;
    return {
      sat,
      x: cx + Math.sin(a) * rr,
      y: cy - Math.cos(a) * rr,
      big,
      rad: big ? 7.6 : mid ? 6 : 4.4,
    };
  });

  dots.forEach(({ sat, x, y, rad }) => {

    // 球体质感：左上偏亮，右下收暗
    const grad = ctx.createRadialGradient(
      x - rad * 0.35,
      y - rad * 0.35,
      rad * 0.15,
      x,
      y,
      rad,
    );
    if (sat.used) {
      grad.addColorStop(0, "rgba(255,255,255,0.95)");
      grad.addColorStop(0.38, tint);
      grad.addColorStop(1, tint);
    } else {
      grad.addColorStop(0, "rgba(255,255,255,0.8)");
      grad.addColorStop(0.4, "#9aa9bd");
      grad.addColorStop(1, "#7d8ca1");
    }
    ctx.save();
    if (sat.used) {
      ctx.shadowColor = tint;
      ctx.shadowBlur = 10;
    }
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(x, y, rad, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  });

  // PRN 沿"点相对圆心"的方向外推标注，冲突就换到反侧，再冲突才跳过
  const placed = [];
  const hit = (box) =>
    placed.some(
      (p) =>
        !(
          box.x + box.w < p.x ||
          box.x > p.x + p.w ||
          box.y + box.h < p.y ||
          box.y > p.y + p.h
        ),
    );

  dots
    .filter((d) => d.big)
    .sort((a, b) => b.sat.cno - a.sat.cno)
    .forEach(({ sat, x, y }) => {
      const text = String(sat.prn);
      ctx.font = `600 9px ${font}`;
      const w = ctx.measureText(text).width + 8;
      const h = 12;
      const box = { x: x - w / 2, y: y - h / 2, w: w, h: h };
      if (hit(box)) return;
      placed.push(box);
      ctx.lineWidth = 2.2;
      ctx.strokeStyle = "rgba(8,20,38,0.5)";
      ctx.strokeText(text, x, y + 0.5);
      ctx.fillStyle = "#fff";
      ctx.fillText(text, x, y + 0.5);
    });
}

/* ==================== 信号柱（横向玻璃管） ==================== */
function buildTubes(sats) {
  const host = $("sat-bars");
  if (!host) return;
  host.replaceChildren();
  host.scrollLeft = 0;

  sats.forEach((sat) => {
    const item = document.createElement("div");
    item.className =
      `tube-item${sat.used ? " used" : ""}${sat.cno < 25 ? " weak" : ""}`;

    const tube = document.createElement("div");
    tube.className = "tube";
    const fill = document.createElement("i");
    fill.className = "fill";
    fill.style.height = `${Math.max(4, Math.min(100, (sat.cno / 50) * 100))}%`;
    tube.append(fill);

    const meta = document.createElement("div");
    meta.className = "meta";
    const cno = document.createElement("b");
    cno.textContent = number(sat.cno, 0);
    const flag = flagIcon(sat.sys);
    const pill = document.createElement("span");
    pill.className = "pill";
    pill.textContent = sat.sig || "—";
    if (!sat.sig) pill.style.visibility = "hidden";
    const prn = document.createElement("em");
    prn.textContent = sat.prn;

    meta.append(cno, flag, pill, prn);
    item.append(tube, meta);
    host.append(item);
  });
}

function renderSatellites(payload) {
  const { items, source } = payload;
  $("sat-source").textContent = source;
  $("sat-count").textContent =
    `可见 ${items.length} · 定位 ${items.filter((s) => s.used).length}`;
  $("sky-empty").hidden = items.length > 0;
  $("sat-hint").textContent = items.length
    ? satMode === "list"
      ? "蓝色为参与定位的卫星，信号强度单位为 dB-Hz。"
      : "圆盘中心为天顶，外圈为地平线；点越大信号越强。"
    : "开启系统定位，到室外或窗边接收信号。";

  // 统一成布局用的字段，数据来源仍是原生推上来的那份
  const sats = items.map((sat) => ({
    prn: sat.svid,
    sys: sat.name,
    sig: sat.band && sat.band !== "—" ? sat.band : "",
    cno: sat.cn0,
    azi: sat.azimuth,
    ele: sat.elevation,
    used: !!sat.used,
  }));
  lastSats = sats;

  drawSky(sats);
  buildTubes(sats);

  const table = $("sat-table");
  table.replaceChildren();

  for (const sat of items) {
    const band = sat.band && sat.band !== "—" ? sat.band : "";

    // 列表行
    const row = document.createElement("div");
    row.className = `sat-row${sat.used ? " used" : ""}`;
    const cellId = document.createElement("span");
    cellId.className = "sat-cell-id";
    const rowFlag = flagIcon(sat.name);
    const rowId = document.createElement("b");
    rowId.textContent = sat.svid;
    cellId.append(rowFlag, rowId);

    const cellBand = document.createElement("span");
    cellBand.className = "sat-cell-band";
    cellBand.textContent = band || "—";

    const cellCn0 = document.createElement("span");
    cellCn0.className = "sat-cell-cn0";
    const track = document.createElement("span");
    track.className = "cn0-track";
    const meter = document.createElement("i");
    meter.style.width = `${Math.min(100, (sat.cn0 / 50) * 100)}%`;
    track.append(meter);
    const cn0Text = document.createElement("em");
    cn0Text.textContent = number(sat.cn0, 0);
    cellCn0.append(track, cn0Text);

    const cellPos = document.createElement("span");
    cellPos.className = "sat-cell-pos";
    cellPos.textContent = `${Math.round(sat.elevation)}° / ${Math.round(sat.azimuth)}°`;

    row.append(cellId, cellBand, cellCn0, cellPos);
    table.append(row);
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
      if (data.orientation) select("orientation", data.orientation);
      $("autostart-hint").textContent = data.autoStart
        ? "下次开机将按上次使用的角色自动运行。"
        : "";
      if (data.package) {
        $("adb-cmd").textContent =
          `adb shell appops set ${data.package} android:mock_location allow`;
        $("adb-check").textContent =
          `adb shell appops get ${data.package} android:mock_location`;
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
      if (data.version) {
        $("about-version").textContent = data.version;
        $("update-state").textContent = `当前版本 ${data.version}，点右侧按钮可立即检查。`;
      }
      ready = true;
      native("tracks");
      native("check-update");
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
    }     else if (type === "notice") notice(data);
    else if (type === "motion")
      document.documentElement.toggleAttribute("data-reduce-motion", data);
    else if (type === "update-check") onUpdateCheck(data);
    else if (type === "update-progress") {
      $("update-bar").style.width = `${data.value}%`;
      $("update-pct").textContent = `${data.value}%`;
    } else if (type === "update-ready") {
      hideModal("update-modal");
      native("install-update");
    } else if (type === "update-log") {
      if (data.ok && data.text) renderLog(data.text);
      else {
        $("log-body").innerHTML =
          '<p class="field-note">获取失败，请检查网络</p>';
        $("log-retry").hidden = false;
      }
    }
  },
};
/* ==================== 卫星显示模式 ==================== */
let satMode = "sky";
function applySatMode(mode) {
  satMode = mode;
  select("sat-mode", mode);
  const isList = mode === "list";
  $("sat-view-sky").hidden = isList;
  $("sat-view-list").hidden = !isList;
  localStorage.setItem("satMode", mode);
  $("sat-hint").textContent = lastSats.length
    ? isList
      ? "蓝色为参与定位的卫星，信号强度单位为 dB-Hz。"
      : "圆盘中心为天顶，外圈为地平线；点越大信号越强。"
    : "开启系统定位，到室外或窗边接收信号。";
  // 隐藏时画布量不到宽度，切回来按当前尺寸重绘一次
  if (!isList) requestAnimationFrame(() => drawSky(lastSats));
}
$("sat-mode").addEventListener("click", (event) => {
  const btn = event.target.closest("button[data-value]");
  if (btn) applySatMode(btn.dataset.value);
});

/* 信号柱横向滑动：鼠标按住拖拽、滚轮竖转横；触摸保留原生惯性 */
(function bindTubeRail() {
  const rail = $("sat-bars");
  if (!rail) return;
  let dragging = false;
  let startX = 0;
  let startLeft = 0;

  rail.addEventListener("pointerdown", (event) => {
    if (event.pointerType !== "mouse") return;
    dragging = true;
    startX = event.clientX;
    startLeft = rail.scrollLeft;
    rail.classList.add("grabbing");
  });

  window.addEventListener("pointermove", (event) => {
    if (!dragging) return;
    rail.scrollLeft = startLeft - (event.clientX - startX);
  });

  window.addEventListener("pointerup", () => {
    if (!dragging) return;
    dragging = false;
    rail.classList.remove("grabbing");
  });

  rail.addEventListener(
    "wheel",
    (event) => {
      if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
      const atStart = rail.scrollLeft <= 0 && event.deltaY < 0;
      const atEnd =
        rail.scrollLeft + rail.clientWidth >= rail.scrollWidth - 1 &&
        event.deltaY > 0;
      // 滑到头就把滚动交回页面
      if (atStart || atEnd) return;
      rail.scrollLeft += event.deltaY;
      event.preventDefault();
    },
    { passive: false },
  );
})();

window.addEventListener("resize", () => {
  if (satMode === "sky") drawSky(lastSats);
});
applySatMode(localStorage.getItem("satMode") === "list" ? "list" : "sky");

$("orientation").addEventListener("click", (event) => {
  const btn = event.target.closest("button[data-value]");
  if (!btn) return;
  select("orientation", btn.dataset.value);
  native("orientation", { value: btn.dataset.value });
});

/* ==================== 更新与更新日志 ==================== */
let pendingTag = "";

function showModal(id) {
  $(id).hidden = false;
  document.documentElement.classList.add("modal-open");
}

function hideModal(id) {
  $(id).hidden = true;
  if ([...document.querySelectorAll(".modal")].every((m) => m.hidden))
    document.documentElement.classList.remove("modal-open");
}

function onUpdateCheck(data) {
  pendingTag = data.tag || "";
  $("update-current").textContent = data.current || "—";
  $("update-latest").textContent = data.latest || "—";
  $("update-go").textContent = data.size > 0 ? `更新 · ${fmtSize(data.size)}` : "立即更新";
  $("update-go").disabled = false;
  $("update-progress").hidden = true;
  $("update-bar").style.width = "0%";
  $("update-pct").textContent = "0%";

  // 关于页的检查入口：无论有没有新版本都要给出结果，否则看起来像没反应
  const checkBtn = $("check-update");
  checkBtn.disabled = false;
  checkBtn.textContent = "检查更新";
  if (data.needUpdate) {
    $("update-state").textContent = `发现新版本 ${data.latest}，可立即更新。`;
  } else if (data.skipped) {
    $("update-state").textContent = `已跳过 ${data.latest}，如需更新请到发布页下载。`;
  } else if (data.latest) {
    $("update-state").textContent = `已是最新版本 ${data.current}。`;
  } else {
    $("update-state").textContent = "检查失败，请确认网络后重试。";
  }

  const notes = $("update-notes");
  notes.textContent = "";
  for (const line of String(data.notes || "").split("\n")) {
    if (!line.trim()) continue;
    const li = document.createElement("li");
    li.textContent = line.trim();
    notes.appendChild(li);
  }
  if (data.needUpdate) showModal("update-modal");
}

/** 把更新日志 txt 渲染成带版本标题的段落，全部走 textContent 避免注入 */
function renderLog(text) {
  const body = $("log-body");
  body.textContent = "";
  const frag = document.createDocumentFragment();
  let inNote = false;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (/^[=\-_]{3,}$/.test(line)) continue;
    if (/^GNSS Link\s*更新日志$/i.test(line)) continue;
    const p = document.createElement("p");
    if (line.startsWith("## ")) {
      p.className = "log-h";
      p.textContent = line.slice(3).trim();
      inNote = true;
    } else {
      p.textContent = line;
      if (inNote) p.classList.add("log-note");
    }
    frag.appendChild(p);
  }
  body.appendChild(frag);
  body.scrollTop = 0;
}

document.querySelectorAll("#page-about [data-open]").forEach((btn) => {
  btn.addEventListener("click", () =>
    native("open-url", { url: btn.dataset.open }),
  );
});
document.querySelectorAll("#page-about [data-copy]").forEach((btn) => {
  btn.addEventListener("click", () =>
    native("copy", { text: btn.dataset.copy }),
  );
});
$("update-go").addEventListener("click", () => {
  $("update-go").disabled = true;
  $("update-progress").hidden = false;
  native("download-update");
});
$("update-skip").addEventListener("click", () => {
  if (pendingTag) native("skip-version", { tag: pendingTag });
  hideModal("update-modal");
});
$("update-later").addEventListener("click", () => hideModal("update-modal"));
$("check-update").addEventListener("click", () => {
  const btn = $("check-update");
  btn.disabled = true;
  btn.textContent = "检查中…";
  $("update-state").textContent = "正在检查，请稍候…";
  native("check-update");
});
$("open-log").addEventListener("click", () => {
  showModal("log-modal");
  $("log-body").innerHTML = '<p class="field-note">正在获取…</p>';
  $("log-retry").hidden = true;
  native("fetch-log");
});
$("log-close").addEventListener("click", () => hideModal("log-modal"));
$("log-retry").addEventListener("click", () => {
  $("log-body").innerHTML = '<p class="field-note">正在获取…</p>';
  $("log-retry").hidden = true;
  native("fetch-log");
});
document.querySelectorAll(".modal-mask").forEach((mask) => {
  mask.addEventListener("click", () => hideModal(mask.parentElement.id));
});

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
