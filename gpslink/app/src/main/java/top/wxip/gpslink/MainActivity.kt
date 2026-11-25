package top.wxip.gpslink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import top.wxip.gpslink.core.*
import top.wxip.gpslink.mock.MockLocationEngine
import top.wxip.gpslink.service.ReceiveService
import top.wxip.gpslink.service.SendService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Local-only presentation host. Location and transport remain in foreground services. */
class MainActivity : AppCompatActivity(), LinkBus.Observer {
    private lateinit var web: WebView
    private val engine by lazy { MockLocationEngine(this) }
    private val rateProbe by lazy { RateProbe(this) }
    private val satMonitor by lazy { SatelliteMonitor(this) { localSats = it; publishSatellites() } }
    private var localSats: List<SatInfo> = emptyList()
    private var remoteSats: List<SatInfo> = emptyList()
    private var remoteFrom = ""
    private var remoteAt = 0L
    private var ready = false
    private var resumed = false
    private var useBt = false
    private var btMac = ""
    private var pendingAction: (() -> Unit)? = null
    private val logLines = ArrayDeque<String>()
    private val remoteExpiry = Runnable { publishSatellites() }

    // 自更新：保留最新安装包的文件名与已落盘 APK，供前端按需触发
    private var lastApkName = ""
    private var lastApkSize = 0L
    private var lastApkPath = ""
    private val updatePrefs by lazy { getSharedPreferences("update", Context.MODE_PRIVATE) }
    private val currentVersion: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.2.6" }
        catch (_: Throwable) { "1.2.6" }
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) {
            if (resumed) satMonitor.start()
            val bluetoothGranted = !useBt || Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (bluetoothGranted) pendingAction?.invoke() else toast("蓝牙连接需要附近设备权限")
        } else toast("需要定位权限才能运行")
        pendingAction = null
        publishChecks()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useBt = Prefs.loadUseBt(this)
        btMac = Prefs.loadBtMac(this)
        web = WebView(this)
        setContentView(web)
        applyOrientation(Prefs.loadOrientation(this))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportZoom(false)
        web.settings.builtInZoomControls = false
        web.settings.displayZoomControls = false
        web.settings.textZoom = 100
        // 尊重 viewport meta 的 device-width，横屏车机上按实际宽度排版而不是固定 980px
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // API 24 以下只会回调 String 版
            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serveAsset(request.url.toString())

            // API 21 以下只会回调 String 版
            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                serveAsset(url)
        }
        loadStartPage()
        LinkBus.add(this)
    }

    /**
     * 把 `https://appassets.androidplatform.net/...` 的请求映射回 assets 目录。
     *
     * 完整界面在 `ui/`，低内核用的精简界面在 `ui-lite/`，两边都按相对路径引用资源，
     * 所以这里要放行两套前缀。返回 null 表示不拦截、交给 WebView 自己处理。
     */
    private fun serveAsset(rawUrl: String): WebResourceResponse? {
        val uri = Uri.parse(rawUrl)
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return null
        val fullPath = uri.path.orEmpty()
        val dir = when {
            fullPath.startsWith("/ui-lite/") -> "ui-lite"
            fullPath.startsWith("/ui/") -> "ui"
            else -> return buildResponse("text/plain", 403, "Forbidden", "".byteInputStream())
        }
        val path = fullPath.removePrefix("/$dir/")
        if (!path.matches(Regex("[a-zA-Z0-9._-]+"))) {
            return buildResponse("text/plain", 403, "Forbidden", "".byteInputStream())
        }
        val mime = when (path.substringAfterLast('.')) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            else -> "application/octet-stream"
        }
        return try {
            buildResponse(mime, 200, "OK", assets.open("$dir/$path"))
        } catch (_: Exception) {
            buildResponse("text/plain", 404, "Not Found", "".byteInputStream())
        }
    }

    /** 只用 3 参构造：它在 API 1 就有。
     *  6 参构造是 API 21 才加的，即使写在 SDK_INT 判断里，Dalvik 校验阶段也会因为
     *  「引用了不存在的方法」把整个方法标记为不可用（日志里的
     *  `Could not find method android.webkit.WebResourceResponse.<init>`），
     *  结果是 4.4 上连页面都加载不出来。本地 assets 不需要状态码，直接给 200 语义。 */
    private fun buildResponse(
        mime: String,
        code: Int,
        reason: String,
        data: java.io.InputStream
    ): WebResourceResponse = WebResourceResponse(mime, "UTF-8", data)

    /**
     * 按 WebView 内核版本选择界面。
     *
     * 阈值 Chrome 80：`>= 80` 用现有的完整界面 `ui/`，`< 80` 或解析不到版本时用精简界面
     * `ui-lite/`。精简界面由另一位同事维护，可能暂时为空 —— 找不到时给一个纯文本兜底页，
     * 保证不会白屏或崩溃。
     */
    private fun loadStartPage() {
        val ua = try { web.settings.userAgentString } catch (_: Throwable) { null }
        val chrome = ua?.let { Regex("Chrome/(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val useLite = chrome == null || chrome < 80
        Log.i(
            TAG,
            "WebView UA Chrome=${chrome ?: "unknown"} -> ${if (useLite) "ui-lite(精简界面)" else "ui(完整界面)"}"
        )
        if (useLite) {
            val hasLite = try {
                assets.open("ui-lite/index.html").use { true }
            } catch (_: Throwable) {
                false
            }
            if (!hasLite) {
                Log.w(TAG, "assets/ui-lite/index.html 不存在，显示兜底页")
                web.loadDataWithBaseURL(null, FALLBACK_HTML, "text/html", "utf-8", null)
            } else {
                web.loadUrl("https://appassets.androidplatform.net/ui-lite/index.html")
            }
        } else {
            web.loadUrl("https://appassets.androidplatform.net/ui/index.html")
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun dispatch(action: String, payload: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    val data = JSONObject(payload)
                    when (action) {
                        "ready" -> {
                            ready = true
                            publishConfig()
                            publishChecks()
                            publishBtLabel()
                            publishTracks()
                            onStatus(LinkBus.status)
                            publishSatellites()
                            emit("log", JSONArray(logLines.toList()))
                        }
                        "configure" -> configure(data)
                        "start" -> if (!LinkBus.status.running) {
                            configure(data)
                            withPermission { start(data.optString("role")) }
                        }
                        "stop" -> { SendService.stop(this@MainActivity); ReceiveService.stop(this@MainActivity); LinkBus.reset() }
                        "mock" -> if (!engine.openMockSettings())
                            toast("本机没有可用的设置页，请用「命令行授权」里的 adb 命令授权")
                        "battery" -> requestIgnoreBattery()
                        "background" -> openAppSettings()
                        "permissions" -> withPermission { publishChecks() }
                        "recheck" -> publishChecks()
                        "pick-bt" -> withPermission { pickBtDevice() }
                        "probe" -> runProbe()
                        "theme" -> applySystemTheme(data.optBoolean("dark"))
                        "autostart" -> {
                            val on = data.optBoolean("enabled")
                            Prefs.saveAutoStart(this@MainActivity, on)
                            LinkBus.log(if (on) "已开启开机自动运行" else "已关闭开机自动运行")
                            publishConfig()
                        }
                        "copy" -> copyText(data.optString("text"))
                        "track-toggle" -> toggleTrack(data.optBoolean("enabled"))
                        "tracks" -> publishTracks()
                        "track-play" -> playTrack(data.optString("path"))
                        "track-delete" -> deleteTrack(data.optString("path"))
                        "nmea" -> configureNmea(data.optBoolean("enabled"), data.optInt("port"))
                        "orientation" -> {
                            val value = data.optString("value", "auto")
                            Prefs.saveOrientation(this@MainActivity, value)
                            applyOrientation(value)
                            publishConfig()
                        }
                        // 自更新桥接
                        "check-update" -> checkUpdate()
                        "download-update" -> downloadUpdate()
                        "install-update" -> installUpdate(lastApkPath)
                        "skip-version" -> skipVersion(data.optString("tag"))
                        "fetch-log" -> fetchUpdateLog()
                        "open-url" -> openExternalUrl(data.optString("url"))
                    }
                } catch (e: Exception) { toast("操作未完成：${e.message}") }
            }
        }
    }

    private fun emit(type: String, data: Any) {
        if (!ready || isDestroyed) return
        val encoded = JSONObject().put("type", type).put("data", data)
        // 可选链 `?.` 需要 Chrome 80，低内核会整条脚本语法错误，改成 && 短路
        // 必须用 ES5 写法：低版本 WebView（Chromium 30）不认箭头函数与 const，
        // 一旦语法错误，原生发过去的所有事件都会被静默丢弃，界面停在空态。
        web.evaluateJavascript(
            "(function(){var e=$encoded;if(window.GnssUI){window.GnssUI.receive(e.type,e.data)}})()",
            null
        )
    }

    private fun publishConfig() {
        val cfg = Prefs.loadSend(this)
        emit("init", JSONObject().put("sid", cfg.sid).put("port", cfg.port).put("host", cfg.host)
            .put("useBt", useBt).put("intervalMs", cfg.intervalMs).put("injectMs", Prefs.loadInject(this))
            .put("autoStart", Prefs.loadAutoStart(this))
            .put("trackEnabled", Prefs.loadTrackEnabled(this))
            .put("nmeaEnabled", Prefs.loadNmeaEnabled(this))
            .put("nmeaPort", Prefs.loadNmeaPort(this))
            .put("orientation", Prefs.loadOrientation(this))
            .put("package", packageName)
            .put("version", currentVersion)
            .put("dark", resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            .put("reduceMotion", reducedMotion()))
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("gpslink", text))
            toast("已复制到剪贴板")
        } catch (t: Throwable) {
            toast("复制失败：${t.message}")
        }
    }

    /** NMEA over TCP：开关与端口保存在本地，下次开始共享时生效 */
    private fun configureNmea(enabled: Boolean, port: Int) {
        val safePort = when {
            port in 1024..65535 -> port
            LinkBus.status.nmeaPort > 0 -> LinkBus.status.nmeaPort
            else -> Protocol.DEFAULT_NMEA_PORT
        }
        Prefs.saveNmea(this, enabled, safePort)
        if (LinkBus.status.running && LinkBus.status.role == Role.SENDER) {
            toast("NMEA 设置已保存，重新开始共享后生效")
        }
        LinkBus.log(
            if (enabled) "已开启 NMEA 输出：端口 $safePort" else "已关闭 NMEA 输出"
        )
        publishConfig()
    }

    private fun toggleTrack(enabled: Boolean) {        Prefs.saveTrackEnabled(this, enabled)
        if (!enabled && TrackRecorder.isRecording) {
            TrackRecorder.stop()
            LinkBus.log("已停止记录轨迹")
        }
        LinkBus.log(if (enabled) "已开启轨迹记录，收到定位后自动保存" else "已关闭轨迹记录")
        publishConfig()
        publishTracks()
    }

    private fun publishTracks() {
        val array = JSONArray()
        TrackRecorder.list(this).forEach { info ->
            array.put(
                JSONObject()
                    .put("name", info.name)
                    .put("path", info.file.absolutePath)
                    .put("points", info.points)
                    .put("durationMs", info.durationMs)
                    .put("bytes", info.bytes)
            )
        }
        emit("tracks", array)
    }

    private fun playTrack(path: String) {
        if (path.isBlank()) return toast("轨迹文件不存在")
        if (LinkBus.status.running) return toast("请先停止当前任务再回放")
        if (!engine.isSelectedAsMockApp()) {
            publishChecks()
            return toast("回放需要模拟位置授权：${engine.describe().steps}")
        }
        try {
            ReceiveService.playTrack(this, path)
        } catch (t: Throwable) {
            toast("回放失败：${t.message}")
        }
    }

    private fun deleteTrack(path: String) {
        if (path.isBlank()) return
        val ok = TrackRecorder.delete(File(path))
        toast(if (ok) "轨迹已删除" else "删除失败")
        publishTracks()
    }

    private fun configure(data: JSONObject) {
        val cfg = Prefs.loadSend(this)
        val port = data.optInt("port", cfg.port)
        require(port in 1024..65535) { "端口范围为 1024–65535" }
        val interval = data.optInt("intervalMs", cfg.intervalMs).takeIf { it in Protocol.INTERVAL_OPTIONS } ?: cfg.intervalMs
        val inject = data.optInt("injectMs", Prefs.loadInject(this)).takeIf { it in Protocol.INJECT_OPTIONS } ?: Protocol.DEFAULT_INJECT_MS
        if (!LinkBus.status.running) {
            useBt = data.optBoolean("useBt")
            Prefs.saveUseBt(this, useBt)
            Prefs.saveSend(this, data.optString("host").trim(), port, data.optString("sid").trim().ifBlank { "8888" }, interval)
        } else Prefs.saveInterval(this, interval)
        Prefs.saveInject(this, inject)
        if (LinkBus.status.running) {
            if (LinkBus.status.role == Role.SENDER) SendService.setInterval(this, interval)
            else ReceiveService.setInjectInterval(this, inject)
        }
    }

    private fun start(role: String) {
        val cfg = Prefs.loadSend(this)
        // 记住角色，供开机自启使用
        Prefs.saveLastRole(this, if (role == "receiver") Role.RECEIVER.name else Role.SENDER.name)
        if (role == "receiver") {
            if (!engine.isSelectedAsMockApp()) {
                publishChecks()
                toast(engine.describe().steps)
                return
            }
            ReceiveService.start(this, cfg.port, cfg.sid, Prefs.loadInject(this), useBt)
        } else {
            if (useBt && btMac.isBlank()) { toast("请先在设置中选择蓝牙接收设备"); return }
            SendService.start(this, cfg.host, cfg.port, cfg.sid, cfg.intervalMs, useBt, btMac)
        }
    }

    /* ==================== 自更新（HTTP） ====================
       更新包与更新日志都由 gps.iqiq.cn 分发，服务器上的文件通过 FTP 上传维护。
       网络不通、内容异常一律静默跳过，不打扰主流程。 */
    companion object {
        private const val TAG = "GnssLink"
        private const val BASE_URL = "https://gps.iqiq.cn/download"

        // 更新日志走 CDN，固定 URL 会被缓存住导致迟迟收不到新版本提示，
        // 每次请求带上时间戳，让 CDN 按不同 URL 处理，始终回源。
        fun logUrl(): String = "$BASE_URL/update-log.txt?t=${System.currentTimeMillis()}"
        private const val UPDATER_UA = "GNSS-Link-Updater/1.3.0"

        /** 精简界面缺失时的纯文本兜底页，不依赖任何脚本/样式 */
        private const val FALLBACK_HTML =
            "<!doctype html><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<body style='font-family:sans-serif;padding:24px;line-height:1.6;color:#333'>" +
                "<h3>GNSS Link</h3>" +
                "<p>当前 WebView 内核版本较低，精简界面尚未安装。</p>" +
                "<p>请升级系统 WebView 或安装完整版后再试。</p>" +
                "</body>"
    }

    private fun open(url: String, readTimeout: Int = 20000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            this.readTimeout = readTimeout
            setRequestProperty("User-Agent", UPDATER_UA)
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            instanceFollowRedirects = true
        }

    /** 从更新日志正文里截出某个版本的说明段落，找不到返回空串 */
    private fun extractNotes(log: String, version: String): String {
        val lines = log.lines()
        val start = lines.indexOfFirst { l -> Regex("^##\\s*v?$version\\b").containsMatchIn(l.trim()) }
        if (start < 0) return ""
        return lines.drop(start + 1)
            .takeWhile { !it.trimStart().startsWith("## ") }
            .map { it.trim().trimStart('-', '*', '·').trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .joinToString("\n")
    }

    /** 比较形如 v1.5.0 的版本号，按每段数字逐位比较 */
    private fun newerVersion(remote: String, current: String): Boolean {
        val numbers = { text: String ->
            Regex("(\\d+)").findAll(text).map { it.value.toIntOrNull() ?: 0 }.toList()
        }
        val a = numbers(remote)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 永久跳过某个 tag：写入本地偏好，后续启动不再提示 */
    private fun skipVersion(tag: String) {
        if (tag.isBlank()) return
        updatePrefs.edit().putString("skipped_tag", tag).apply()
        LinkBus.log("已跳过版本 $tag，不再提示")
    }

    private fun checkUpdate() {
        // 低版本用 file:// 安装 APK 会失败，这类老设备也基本不联网更新，直接跳过自更新
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.i(TAG, "系统版本过低（<24），跳过自更新检查")
            emit("update-check", JSONObject()
                .put("tag", "")
                .put("latest", "")
                .put("current", currentVersion)
                .put("size", 0)
                .put("needUpdate", false)
                .put("skipped", true)
                .put("notes", ""))
            return
        }
        thread {
            try {
                // 版本号与安装包名由发布流程写在更新日志头部，这里直接读它
                val conn = open(logUrl())
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@thread
                val log = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val latestVer = Regex("最新版本[：:]\\s*([0-9][0-9.]*)")
                    .find(log)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@thread
                val apkName = Regex("安装包[：:]\\s*(\\S+\\.apk)")
                    .find(log)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "gnss-link-$latestVer.apk"
                val notes = extractNotes(log, latestVer)

                // 体积靠 HEAD 取，拿不到就留 0，前端会退化成「立即更新」
                val apkSize = runCatching {
                    val head = URL("$BASE_URL/$apkName").openConnection() as HttpURLConnection
                    head.requestMethod = "HEAD"
                    head.connectTimeout = 12000
                    head.readTimeout = 12000
                    head.setRequestProperty("User-Agent", UPDATER_UA)
                    head.connect()
                    val len = head.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                    head.disconnect()
                    len
                }.getOrDefault(0L)

                val tag = "v$latestVer"
                val skipped = updatePrefs.getString("skipped_tag", "") == tag
                val needUpdate = newerVersion(latestVer, currentVersion) && !skipped
                runOnUiThread {
                    lastApkName = apkName
                    lastApkSize = apkSize
                    emit("update-check", JSONObject()
                        .put("tag", tag)
                        .put("latest", latestVer)
                        .put("current", currentVersion)
                        .put("size", apkSize)
                        .put("needUpdate", needUpdate)
                        .put("skipped", skipped)
                        .put("notes", notes))
                }
            } catch (_: Throwable) {
                // 服务器不可达、超时、内容格式异常：回一个空结果，让手动检查的按钮能复位
                runOnUiThread {
                    emit("update-check", JSONObject()
                        .put("tag", "")
                        .put("latest", "")
                        .put("current", currentVersion)
                        .put("size", 0)
                        .put("needUpdate", false)
                        .put("skipped", false)
                        .put("notes", ""))
                }
            }
        }
    }

    private fun downloadUpdate() {
        val name = lastApkName
        if (name.isBlank()) { toast("没有可用的安装包，请重新检查更新"); return }
        thread {
            try {
                val dir = File(cacheDir, "update").apply { mkdirs() }
                val apk = File(dir, "app-update.apk")
                if (apk.exists()) apk.delete()
                val conn = open("$BASE_URL/$name", 30000)
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.getHeaderField("Content-Length")?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: lastApkSize
                conn.inputStream.use { input ->
                    FileOutputStream(apk).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var written = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread { emit("update-progress", JSONObject().put("value", pct)) }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (apk.length() < 1024) throw IllegalStateException("安装包不完整")
                runOnUiThread {
                    lastApkPath = apk.absolutePath
                    emit("update-progress", JSONObject().put("value", 100))
                    emit("update-ready", JSONObject()
                        .put("path", apk.absolutePath)
                        .put("size", apk.length()))
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("下载失败：${t.message}") }
            }
        }
    }

    /** 更新日志：整段拉服务器上的 txt 交给前端渲染 */
    private fun fetchUpdateLog() {
        thread {
            try {
                val conn = open(logUrl())
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                runOnUiThread {
                    emit("update-log", JSONObject().put("ok", true).put("text", text))
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    emit("update-log", JSONObject().put("ok", false).put("text", ""))
                }
            }
        }
    }

    private fun installUpdate(path: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            toast("当前系统版本不支持应用内安装，请手动安装新版")
            return
        }
        val apk = File(path)
        if (!apk.exists() || apk.length() < 1024) {
            toast("安装包不存在或不完整，请重新下载")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            else toast("系统没有可用的安装程序")
        } catch (t: Throwable) {
            toast("无法打开安装界面：${t.message}")
        }
    }

    private fun openExternalUrl(url: String) {
        if (!url.startsWith("https://")) return toast("链接无效")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            toast("无法打开浏览器：${t.message}")
        }
    }

    /** 返回键先交给 WebView 模态：打开则取消，未打开才退出 Activity */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "Boolean(window.GnssUI && window.GnssUI.handleBack && window.GnssUI.handleBack())"
        ) { result -> if (result != "true") super.onBackPressed() }
    }

    private fun reducedMotion() = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    @Suppress("DEPRECATION")
    private fun applySystemTheme(dark: Boolean) {
        val color = android.graphics.Color.parseColor(if (dark) "#101923" else "#eef3fa")
        // setStatusBarColor / setNavigationBarColor 都是 API 21
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = color
            window.navigationBarColor = color
        }
        window.decorView.systemUiVisibility = if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    /** 应用屏幕方向偏好：车机系统常把界面锁成竖屏，这里给用户一个主动覆盖的入口 */
    private fun applyOrientation(value: String) {
        requestedOrientation = when (value) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** 横竖屏切换不重建 Activity，但要主动让 WebView 按新尺寸重排，媒体查询才会生效 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        web.requestLayout()
        emit("motion", reducedMotion())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        publishChecks()
        publishBtLabel()
        emit("motion", reducedMotion())
        if (!LinkBus.status.running) {
            try { engine.release() } catch (_: Exception) { }
        }
        if (hasLocationPermission()) satMonitor.start()
        web.onResume()
    }

    override fun onPause() {
        resumed = false
        satMonitor.stop()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        LinkBus.remove(this)
        satMonitor.stop()
        rateProbe.stop()
        web.removeCallbacks(remoteExpiry)
        ready = false
        web.removeJavascriptInterface("Android")
        web.destroy()
        super.onDestroy()
    }

    override fun onStatus(status: LinkStatus) {
        val json = JSONObject()
            .put("running", status.running)
            .put("role", status.role.name)
            .put("peer", status.peer)
            .put("online", status.online)
            .put("localIp", status.localIp)
            .put("fixCount", status.fixCount)
            .put("rateHz", status.rateHz)
            .put("rttMs", status.rttMs)
            .put("lossPercent", status.lossPercent)
            .put("lat", status.lat)
            .put("lon", status.lon)
            .put("accuracy", status.accuracy)
            .put("altitude", status.altitude)
            .put("speed", status.speed)
            .put("bearing", status.bearing)
            .put("fixAgeMs", status.fixAgeMs)
            .put("rxCount", status.rxCount)
            .put("txCount", status.txCount)
            .put("verifyAvailable", status.verifyAvailable)
            .put("verifyMock", status.verifyMock)
            .put("verifyProvider", status.verifyProvider)
            .put("verifyLat", status.verifyLat)
            .put("verifyLon", status.verifyLon)
            .put("verifyDeltaM", status.verifyDeltaM)
            .put("probing", rateProbe.isRunning)
            .put("nmeaPort", status.nmeaPort)
            .put("nmeaClients", status.nmeaClients)
        emit("status", json)
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 40) logLines.removeFirst()
        emit("log", JSONArray(logLines.toList()))
    }

    override fun onRemoteSatellites(list: List<SatInfo>, from: String) {
        remoteSats = list
        remoteFrom = from
        remoteAt = SystemClock.elapsedRealtime()
        publishSatellites()
        web.removeCallbacks(remoteExpiry)
        web.postDelayed(remoteExpiry, 6001)
    }

    private fun publishSatellites() {
        val receiver = LinkBus.status.role == Role.RECEIVER
        val fresh = SystemClock.elapsedRealtime() - remoteAt < 6000
        val items = if (receiver) { if (fresh) remoteSats else emptyList() } else localSats
        val array = JSONArray()
        items.forEach { sat ->
            array.put(JSONObject().put("svid", sat.svid).put("name", Gnss.short(sat.constellation))
                .put("cn0", sat.cn0.toDouble()).put("elevation", sat.elevation.toDouble())
                .put("azimuth", sat.azimuth.toDouble()).put("used", sat.usedInFix)
                .put("band", Gnss.band(sat.freqHz))
                .put("freq", (sat.freqHz / 1e6).toDouble()))
        }
        emit("satellites", JSONObject().put("items", array).put("source", if (receiver) {
            if (fresh) "来自 $remoteFrom" else "等待发送端卫星数据"
        } else "本机 GNSS 信号"))
    }

    private fun publishChecks() {
        val access = engine.describe()
        emit("checks", JSONObject().put("location", hasLocationPermission()).put("dev", access.devOptions)
            .put("mock", access.granted).put("battery", isIgnoringBattery())
            .put("background", hasBackgroundLocation()))
        // 授权机制按系统版本分档，界面据此显示不同步骤与命令
        emit("access", JSONObject()
            .put("sdk", access.sdk)
            .put("androidName", access.androidName)
            .put("tier", if (access.tier == MockLocationEngine.Tier.LEGACY) "legacy" else "appops")
            .put("granted", access.granted)
            .put("switchOn", access.switchOn)
            .put("permission", access.permission)
            .put("devOptions", access.devOptions)
            .put("settingsPage", access.settingsPage)
            .put("adb", access.adb)
            .put("adbCheck", access.adbCheck)
            .put("steps", access.steps)
            .put("why", access.why))
    }

    /** Android 10 起「始终允许」必须由用户在设置页手动开启，无法用运行时弹窗申请 */
    private fun hasBackgroundLocation(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) true
        else ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            toast("无法打开应用设置：${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishBtLabel() {
        val name = try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(btMac)?.name
        } catch (_: Exception) { null }
        emit("bt", name ?: btMac.ifBlank { "未选择设备" })
    }

    private fun runProbe() {
        if (LinkBus.status.running) { toast("请先停止共享或接收再检测"); return }
        withPermission {
            emit("probe", JSONObject().put("running", true).put("message", "检测中，请保持室外或窗边…"))
            rateProbe.start { r -> emit("probe", JSONObject().put("running", false).put("message", "${r.note}（${r.samples} 个样本）")) }
        }
    }

    private fun toast(message: String) {
        if (ready) emit("notice", message)
        else Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun isIgnoringBattery(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (_: Throwable) {
        true
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("无法打开电池优化设置，请手动在系统设置里允许后台运行")
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun withPermission(action: () -> Unit) {
        val needed = buildList {
            if (!hasLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (useBt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permLauncher.launch(needed.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickBtDevice() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            toast("本机不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            toast("请先打开蓝牙")
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Throwable) {
            }
            return
        }

        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (t: Throwable) {
            toast("读取配对列表失败：${t.message}")
            return
        }
        if (bonded.isEmpty()) {
            toast("没有已配对的设备，请先在系统蓝牙设置里配对")
            return
        }

        val labels = bonded.map { "${it.name ?: "未知设备"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择接收端")
            .setItems(labels) { _, which ->
                val d = bonded[which]
                btMac = d.address
                Prefs.saveBtMac(this, btMac)
                publishBtLabel()
                toast("已选择 ${d.name ?: d.address}")
            }
            .setNegativeButton("取消", null)
            .show()
    }


}
