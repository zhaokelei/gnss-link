/* GNSS Link · 精简界面脚本
 * 目标内核 Chromium 30（Android 4.4 原生 WebView）。
 * 仅使用 ES5 语法：var / function，无箭头函数、无 let/const、无模板字符串、
 * 无可选链、无解构、无默认参数、无 Promise、无 Array.prototype.includes。
 */
(function () {
  "use strict";

  var doc = document;

  function $(id) {
    return doc.getElementById(id);
  }

  function trim(text) {
    return String(text == null ? "" : text).replace(/^\s+|\s+$/g, "");
  }

  function num(value, digits) {
    var n = typeof value === "number" ? value : parseFloat(value);
    if (!isFinite(n)) return "—";
    return n.toFixed(digits == null ? 1 : digits);
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  /* ==================== 桥接 ==================== */

  function send(action, data) {
    if (!window.Android || typeof window.Android.dispatch !== "function") return false;
    try {
      window.Android.dispatch(action, JSON.stringify(data || {}));
      return true;
    } catch (e) {
      return false;
    }
  }

  var state = {
    role: "sender",
    mode: "lan",
    sample: "1000",
    inject: "100",
    orientation: "auto",
    running: false,
    ready: false
  };

  function portValue() {
    var port = parseInt($("port").value, 10);
    return isFinite(port) ? port : 7007;
  }

  function config() {
    return {
      sid: trim($("sid").value) || "8888",
      port: portValue(),
      host: trim($("host").value),
      useBt: state.mode === "bt",
      intervalMs: parseInt(state.sample, 10),
      injectMs: parseInt(state.inject, 10)
    };
  }

  /* ==================== 分段控件 ==================== */

  function segSet(id, value) {
    var box = $(id);
    if (!box) return;
    var buttons = box.getElementsByTagName("button");
    for (var i = 0; i < buttons.length; i++) {
      var on = buttons[i].getAttribute("data-value") === String(value);
      buttons[i].className = on ? "seg-btn on" : "seg-btn";
      buttons[i].setAttribute("aria-selected", on ? "true" : "false");
    }
  }

  function bindSeg(id, onPick) {
    var box = $(id);
    if (!box) return;
    box.onclick = function (event) {
      var target = event.target || event.srcElement;
      while (target && target !== box && target.tagName !== "BUTTON") {
        target = target.parentNode;
      }
      if (!target || target === box || target.disabled) return;
      var value = target.getAttribute("data-value");
      segSet(id, value);
      onPick(value);
    };
  }

  function segDisable(id, locked) {
    var box = $(id);
    if (!box) return;
    var buttons = box.getElementsByTagName("button");
    for (var i = 0; i < buttons.length; i++) buttons[i].disabled = !!locked;
  }

  /* ==================== 连接页 ==================== */

  function refreshRole() {
    var receive = state.role === "receiver";
    $("start").textContent = receive ? "开始接收" : "开始共享";
    $("stop").textContent = receive ? "停止接收" : "停止共享";
    $("action-hint").textContent = receive
      ? "先把本机设为模拟位置应用，再开始接收；房间码与端口需和发送端一致。"
      : "两台设备连接同一 Wi-Fi 或热点，并使用相同房间码。";
  }

  function refreshMode() {
    var bt = state.mode === "bt";
    $("lan-settings").hidden = bt;
    $("bt-settings").hidden = !bt;
    $("mode-label").textContent = bt ? "蓝牙 RFCOMM" : "UDP " + portValue();
    $("room-label").textContent = trim($("sid").value) || "8888";
  }

  function lockInputs(locked) {
    segDisable("role", locked);
    segDisable("mode", locked);
    $("sid").disabled = locked;
    $("port").disabled = locked;
    $("host").disabled = locked;
    $("pick-bt").disabled = locked;
  }

  bindSeg("role", function (value) {
    state.role = value;
    refreshRole();
  });

  bindSeg("mode", function (value) {
    state.mode = value;
    refreshMode();
    send("configure", config());
  });

  bindSeg("sample", function (value) {
    state.sample = value;
    send("configure", config());
  });

  bindSeg("inject", function (value) {
    state.inject = value;
    send("configure", config());
  });

  bindSeg("orientation", function (value) {
    state.orientation = value;
    send("orientation", { value: value });
  });

  $("sid").onchange = function () {
    refreshMode();
    send("configure", config());
  };
  $("port").onchange = function () {
    refreshMode();
    send("configure", config());
  };
  $("host").onchange = function () {
    send("configure", config());
  };

  $("start").onclick = function () {
    var cfg = config();
    if (state.mode === "lan" && !(cfg.port >= 1024 && cfg.port <= 65535)) {
      notice("端口请填写 1024–65535 之间的整数");
      return;
    }
    cfg.role = state.role;
    if (!send("start", cfg)) notice("界面预览：未连接 Android 应用");
  };

  $("stop").onclick = function () {
    send("stop");
  };

  $("pick-bt").onclick = function () {
    send("pick-bt");
  };

  /* ==================== 设置页 ==================== */

  function pushNmea() {
    send("nmea", {
      enabled: $("nmea").checked,
      port: parseInt($("nmea-port").value, 10) || 10110
    });
  }

  $("autostart").onchange = function () {
    send("autostart", { enabled: this.checked });
    $("autostart-hint").textContent = this.checked
      ? "下次开机将按上次使用的角色自动运行。"
      : "按上次使用的角色与参数自动运行。";
  };

  $("nmea").onchange = function () {
    pushNmea();
  };

  $("nmea-port").onchange = function () {
    pushNmea();
  };

  $("track-toggle").onchange = function () {
    send("track-toggle", { enabled: this.checked });
  };

  $("permissions").onclick = function () {
    send("permissions");
  };
  $("mock").onclick = function () {
    send("mock");
  };
  $("recheck").onclick = function () {
    send("recheck");
  };

  /* ==================== 页签 ==================== */

  var PAGES = ["link", "satellites", "settings", "about"];

  function showPage(name) {
    for (var i = 0; i < PAGES.length; i++) {
      $("page-" + PAGES[i]).hidden = PAGES[i] !== name;
    }
    var tabs = $("tabs").getElementsByTagName("button");
    for (var j = 0; j < tabs.length; j++) {
      var on = tabs[j].getAttribute("data-tab") === name;
      tabs[j].className = on ? "tab on" : "tab";
      tabs[j].setAttribute("aria-selected", on ? "true" : "false");
    }
    window.scrollTo(0, 0);
  }

  $("tabs").onclick = function (event) {
    var target = event.target || event.srcElement;
    while (target && target !== this && target.tagName !== "BUTTON") {
      target = target.parentNode;
    }
    if (!target || target === this) return;
    showPage(target.getAttribute("data-tab"));
  };

  /* ==================== 提示条 ==================== */

  var noticeTimer = null;

  function notice(message) {
    if (!message) return;
    $("notice").textContent = message;
    $("notice").hidden = false;
    if (noticeTimer) clearTimeout(noticeTimer);
    noticeTimer = setTimeout(function () {
      $("notice").hidden = true;
    }, 4000);
  }

  /* ==================== 渲染：状态 ==================== */

  function renderStatus(s) {
    var running = !!s.running;
    var receive = s.role === "RECEIVER";
    state.running = running;

    if (running && receive !== (state.role === "receiver")) {
      state.role = receive ? "receiver" : "sender";
      segSet("role", state.role);
      refreshRole();
    }

    lockInputs(running);
    $("start").hidden = running;
    $("stop").hidden = !running;

    var stateEl = $("state");
    stateEl.textContent = running ? (s.online ? "已连接" : "等待连接") : "未启动";
    stateEl.className = "state" + (running ? (s.online ? " online" : " waiting") : "");

    var peer = "等待建立连接";
    if (running) {
      if (s.peer) peer = (s.online ? "已连接 · " : "对方无响应 · ") + s.peer;
      else peer = "正在等待另一台设备…";
    }
    $("peer").textContent = peer;

    var fix = running && s.fixCount > 0;
    $("coordinates").textContent = fix ? num(s.lat, 6) + ", " + num(s.lon, 6) : "—";
    $("coord-sub").textContent = fix ? "WGS-84 经纬度" : "启动后显示实时经纬度";

    $("accuracy").textContent = fix && s.accuracy > 0 ? num(s.accuracy, 1) + " m" : "—";
    $("speed").textContent = fix ? num(s.speed * 3.6, 1) + " km/h" : "—";
    $("altitude").textContent = fix ? num(s.altitude, 1) + " m" : "—";
    $("bearing").textContent = fix && s.bearing >= 0 ? num(s.bearing, 0) + "°" : "—";
    $("age").textContent =
      fix && s.fixAgeMs >= 0
        ? "源定位 " + num(s.fixAgeMs / 1000, 1) + " 秒前更新" + (s.fixAgeMs > 3000 ? " · 数据偏旧" : "")
        : "";

    var fixBadge = $("fix-badge");
    fixBadge.textContent = fix ? "已有定位" : "等待定位";
    fixBadge.className = "badge" + (fix ? " ok" : "");

    $("local-ip").textContent = s.localIp || "—";
    $("peer-addr").textContent = s.peer || "—";
    $("rate").textContent = running ? num(s.rateHz, 0) : "—";
    if (receive) {
      $("latency-label").textContent = "丢包率";
      $("latency").textContent = running ? num(s.lossPercent, 1) + " %" : "—";
    } else {
      $("latency-label").textContent = "往返延迟";
      $("latency").textContent = running && s.rttMs >= 0 ? s.rttMs + " ms" : "—";
    }
    $("packets").textContent = s.rxCount || 0;

    $("nmea-state").textContent =
      s.nmeaPort > 0
        ? "监听 " + s.nmeaPort + " · " + (s.nmeaClients || 0) + " 个连接"
        : "未开启";
  }

  /* ==================== 渲染：卫星 ==================== */

  var FLAGS = {
    北斗: "flag-cn",
    GPS: "flag-us",
    GLO: "flag-ru",
    GAL: "flag-eu",
    QZS: "flag-jp",
    IRN: "flag-in",
    SBS: "flag-globe"
  };

  var SVG_NS = "http://www.w3.org/2000/svg";
  var XLINK_NS = "http://www.w3.org/1999/xlink";

  function flagIcon(name) {
    var svg = doc.createElementNS(SVG_NS, "svg");
    svg.setAttribute("class", "flag");
    svg.setAttribute("viewBox", "0 0 24 16");
    var use = doc.createElementNS(SVG_NS, "use");
    use.setAttributeNS(XLINK_NS, "xlink:href", "#" + (FLAGS[name] || "flag-globe"));
    svg.appendChild(use);
    return svg;
  }

  function renderSatellites(payload) {
    var items = payload && payload.items ? payload.items : [];
    var used = 0;
    for (var k = 0; k < items.length; k++) if (items[k].used) used++;

    $("sat-source").textContent = payload && payload.source ? payload.source : "本机 GNSS 信号";
    $("sat-count").textContent = "可见 " + items.length + " · 定位 " + used;
    $("sat-hint").textContent = items.length
      ? "蓝色编号为参与定位的卫星，信号强度单位为 dB-Hz。"
      : "开启系统定位，到室外或窗边接收信号。";

    var body = $("sat-body");
    clear(body);

    if (!items.length) {
      var empty = doc.createElement("p");
      empty.className = "note dim";
      empty.textContent = "等待卫星数据";
      body.appendChild(empty);
      return;
    }

    for (var i = 0; i < items.length; i++) {
      var sat = items[i];
      var row = doc.createElement("div");
      row.className = sat.used ? "sat-row used" : "sat-row";

      var cellId = doc.createElement("span");
      cellId.className = "c-id";
      cellId.appendChild(flagIcon(sat.name));
      var svid = doc.createElement("b");
      svid.textContent = sat.svid;
      cellId.appendChild(svid);

      var cellBand = doc.createElement("span");
      cellBand.className = "c-band";
      cellBand.textContent = sat.band && sat.band !== "—" ? sat.band : "—";

      var cellCn0 = doc.createElement("span");
      cellCn0.className = "c-cn0";
      cellCn0.textContent = num(sat.cn0, 0);

      row.appendChild(cellId);
      row.appendChild(cellBand);
      row.appendChild(cellCn0);
      body.appendChild(row);
    }
  }

  /* ==================== 渲染：授权 / 日志 ==================== */

  function renderChecks(data) {
    var pairs = [
      ["check-location", data.location],
      ["check-mock", data.mock]
    ];
    for (var i = 0; i < pairs.length; i++) {
      var el = $(pairs[i][0]);
      el.textContent = pairs[i][1] ? "已就绪" : "待设置";
      el.style.color = pairs[i][1] ? "#1f8a4c" : "#b26a12";
    }
  }

  function renderLog(text) {
    var body = $("log-body");
    clear(body);
    var lines = String(text == null ? "" : text).split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      var line = trim(lines[i]);
      if (!line) continue;
      if (/^[=\-_]{3,}$/.test(line)) continue;
      if (/^GNSS Link\s*更新日志$/i.test(line)) continue;
      var p = doc.createElement("p");
      if (line.indexOf("## ") === 0) {
        p.className = "log-h";
        p.textContent = trim(line.slice(3));
      } else {
        p.textContent = line;
        if (line.charAt(0) === "-" || line.charAt(0) === "·") p.className = "log-note";
      }
      body.appendChild(p);
    }
    if (!body.firstChild) {
      var empty = doc.createElement("p");
      empty.className = "hint";
      empty.textContent = "日志为空";
      body.appendChild(empty);
    }
    body.scrollTop = 0;
  }

  /* ==================== 关于页动作 ==================== */

  function bindAbout() {
    var openers = $("page-about").querySelectorAll("[data-open]");
    for (var i = 0; i < openers.length; i++) {
      openers[i].onclick = function () {
        send("open-url", { url: this.getAttribute("data-open") });
      };
    }
    var copiers = $("page-about").querySelectorAll("[data-copy]");
    for (var j = 0; j < copiers.length; j++) {
      copiers[j].onclick = function () {
        send("copy", { text: this.getAttribute("data-copy") });
      };
    }
  }

  function showModal() {
    $("log-modal").hidden = false;
  }

  function hideModal() {
    $("log-modal").hidden = true;
  }

  function fetchLog() {
    showModal();
    clear($("log-body"));
    var tip = doc.createElement("p");
    tip.className = "hint";
    tip.textContent = "正在获取…";
    $("log-body").appendChild(tip);
    $("log-retry").hidden = true;
    send("fetch-log");
  }

  $("open-log").onclick = fetchLog;
  $("log-retry").onclick = fetchLog;
  $("log-close").onclick = hideModal;
  $("log-mask").onclick = hideModal;

  $("check-update").onclick = function () {
    this.disabled = true;
    this.textContent = "检查中…";
    $("update-state").textContent = "正在检查，请稍候…";
    send("check-update");
  };

  function onUpdateCheck(data) {
    var button = $("check-update");
    button.disabled = false;
    button.textContent = "检查更新";
    if (data.needUpdate) {
      $("update-state").textContent = "发现新版本 " + data.latest + "，请到发布页下载安装。";
    } else if (data.skipped) {
      $("update-state").textContent = "已跳过 " + data.latest + "，如需更新请到发布页下载。";
    } else if (data.latest) {
      $("update-state").textContent = "已是最新版本 " + data.current + "。";
    } else {
      $("update-state").textContent = "当前版本 " + data.current + "，检查失败，请确认网络后重试。";
    }
  }

  /* ==================== init ==================== */

  function applyInit(data) {
    if (data.sid) $("sid").value = data.sid;
    if (data.port) $("port").value = data.port;
    if (data.host) $("host").value = data.host;

    state.mode = data.useBt ? "bt" : "lan";
    segSet("mode", state.mode);

    if (data.intervalMs) {
      state.sample = String(data.intervalMs);
      segSet("sample", state.sample);
    }
    if (data.injectMs) {
      state.inject = String(data.injectMs);
      segSet("inject", state.inject);
    }
    if (data.orientation) {
      state.orientation = data.orientation;
      segSet("orientation", data.orientation);
    }

    $("autostart").checked = !!data.autoStart;
    $("track-toggle").checked = !!data.trackEnabled;
    $("nmea").checked = !!data.nmeaEnabled;
    if (data.nmeaPort) $("nmea-port").value = data.nmeaPort;
    $("autostart-hint").textContent = data.autoStart
      ? "下次开机将按上次使用的角色自动运行。"
      : "按上次使用的角色与参数自动运行。";

    if (data.version) {
      $("about-version").textContent = data.version;
      $("update-state").textContent = "当前版本 " + data.version + "，点按钮可立即检查。";
    }

    refreshRole();
    refreshMode();
    state.ready = true;
  }

  /* ==================== 来自原生的消息 ==================== */

  window.GnssUI = {
    receive: function (type, data) {
      if (type === "init") {
        applyInit(data || {});
      } else if (type === "status") {
        renderStatus(data || {});
      } else if (type === "satellites") {
        renderSatellites(data || {});
      } else if (type === "checks") {
        renderChecks(data || {});
      } else if (type === "log") {
        var lines = data && data.length ? data.join("\n") : "";
        $("logs").textContent = lines || "暂无日志";
      } else if (type === "bt") {
        $("bt-device").textContent = String(data || "未选择设备");
      } else if (type === "notice") {
        notice(String(data || ""));
      } else if (type === "update-check") {
        onUpdateCheck(data || {});
      } else if (type === "update-log") {
        if (data && data.ok && data.text) {
          renderLog(data.text);
        } else {
          clear($("log-body"));
          var tip = doc.createElement("p");
          tip.className = "hint";
          tip.textContent = "获取失败，请检查网络";
          $("log-body").appendChild(tip);
          $("log-retry").hidden = false;
        }
      }
    }
  };

  /* ==================== 启动 ==================== */

  bindAbout();
  refreshRole();
  refreshMode();
  showPage("link");

  // 通知原生：界面已就绪，可以下发 init / status / satellites
  var sent = send("ready");
  if (!sent) {
    // 浏览器里预览：给一组演示数据，方便查看排版
    applyInit({
      sid: "8888",
      port: 7007,
      host: "",
      useBt: false,
      intervalMs: 1000,
      injectMs: 100,
      autoStart: true,
      trackEnabled: false,
      nmeaEnabled: false,
      nmeaPort: 10110,
      orientation: "auto",
      version: "1.2.6"
    });
  }
})();
