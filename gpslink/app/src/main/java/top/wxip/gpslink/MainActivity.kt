package top.wxip.gpslink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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

    // 自更新：保留最新 release 的下载地址与已落盘 APK，供前端按需触发
    private var lastDownloadUrl = ""
    private var lastApkPath = ""
    private val updatePrefs by lazy { getSharedPreferences("update", Context.MODE_PRIVATE) }
    private val currentVersion: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.5.0" }
        catch (_: Throwable) { "1.5.0" }
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
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportZoom(false)
        web.settings.builtInZoomControls = false
        web.settings.displayZoomControls = false
        web.settings.textZoom = 100
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val path = uri.path.orEmpty().removePrefix("/ui/")
                if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" ||
                    !uri.path.orEmpty().startsWith("/ui/") || !path.matches(Regex("[a-zA-Z0-9.-]+"))) {
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
                }
                val mime = when (path.substringAfterLast('.')) {
                    "html" -> "text/html"
                    "css" -> "text/css"
                    "js", "mjs" -> "application/javascript"
                    else -> "application/octet-stream"
                }
                return try { WebResourceResponse(mime, "UTF-8", assets.open("ui/$path")) }
                catch (_: Exception) { WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), "".byteInputStream()) }
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/ui/index.html")
        LinkBus.add(this)
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
                        "mock" -> startActivity(engine.developerOptionsIntent())
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
                        // 自更新桥接
                        "check-update" -> checkUpdate()
                        "download-update" -> downloadUpdate(lastDownloadUrl)
                        "install-update" -> installUpdate(lastApkPath)
                        "skip-version" -> skipVersion(data.optString("tag"))
                        "open-url" -> openExternalUrl(data.optString("url"))
                    }
                } catch (e: Exception) { toast("操作未完成：${e.message}") }
            }
        }
    }

    private fun emit(type: String, data: Any) {
        if (!ready || isDestroyed) return
        val encoded = JSONObject().put("type", type).put("data", data)
        web.evaluateJavascript("(()=>{const event=$encoded;window.GnssUI?.receive(event.type,event.data)})()", null)
    }

    private fun publishConfig() {
        val cfg = Prefs.loadSend(this)
        emit("init", JSONObject().put("sid", cfg.sid).put("port", cfg.port).put("host", cfg.host)
            .put("useBt", useBt).put("intervalMs", cfg.intervalMs).put("injectMs", Prefs.loadInject(this))
            .put("autoStart", Prefs.loadAutoStart(this))
            .put("trackEnabled", Prefs.loadTrackEnabled(this))
            .put("nmeaEnabled", Prefs.loadNmeaEnabled(this))
            .put("nmeaPort", Prefs.loadNmeaPort(this))
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
            return toast("回放需要模拟位置授权，请先在开发者选项或 adb 中授权")
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
                toast("请在开发者选项中选择 GNSS Link 作为模拟位置应用")
                return
            }
            ReceiveService.start(this, cfg.port, cfg.sid, Prefs.loadInject(this), useBt)
        } else {
            if (useBt && btMac.isBlank()) { toast("请先在设置中选择蓝牙接收设备"); return }
            SendService.start(this, cfg.host, cfg.port, cfg.sid, cfg.intervalMs, useBt, btMac)
        }
    }

    /* ==================== 自更新 ====================
       仅访问 GitHub releases/latest；离线、限流或解析失败一律静默跳过，不打扰用户。 */
    companion object {
        private const val RELEASES_API = "https://api.github.com/repos/zhaokelei/gnss-link/releases/latest"
        private const val UPDATER_UA = "GNSS-Link-Updater/1.5.0"
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
        thread {
            try {
                val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", UPDATER_UA)
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@thread
                val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = release.optString("tag_name")
                val latest = tag.removePrefix("v").removePrefix("V")
                val skipped = updatePrefs.getString("skipped_tag", "") == tag
                val needUpdate = latest.isNotBlank() && newerVersion(latest, currentVersion) && !skipped

                var downloadUrl = ""
                var size = 0L
                val assets = release.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url")
                            size = asset.optLong("size")
                            if (name.contains("arm64", ignoreCase = true)) break
                        }
                    }
                }
                val notes = release.optString("body")
                    .lines().map { it.trim().trimStart('-', '*').trim() }
                    .filter { it.length > 1 && !it.startsWith("#") }
                    .take(5).joinToString("\n")
                runOnUiThread {
                    lastDownloadUrl = downloadUrl
                    emit("update-check", JSONObject()
                        .put("tag", tag)
                        .put("latest", latest)
                        .put("current", currentVersion)
                        .put("size", size)
                        .put("needUpdate", needUpdate)
                        .put("skipped", skipped)
                        .put("notes", notes))
                }
            } catch (_: Throwable) {
                // 离线、限流、解析失败：静默跳过
            }
        }
    }

    private fun downloadUpdate(url: String) {
        if (url.isBlank()) { toast("下载地址不可用，请稍后重试"); return }
        thread {
            try {
                val dir = File(cacheDir, "update").apply { mkdirs() }
                val apk = File(dir, "app-update.apk")
                if (apk.exists()) apk.delete()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", UPDATER_UA)
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.contentLength.toDouble()
                conn.inputStream.use { input ->
                    FileOutputStream(apk).use { out ->
                        val buffer = ByteArray(8192)
                        var written = 0L
                        var read = input.read(buffer)
                        while (read != -1) {
                            out.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                                runOnUiThread { emit("update-progress", JSONObject().put("value", pct)) }
                            }
                            read = input.read(buffer)
                        }
                    }
                }
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

    private fun installUpdate(path: String) {
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
        window.statusBarColor = color
        window.navigationBarColor = color
        window.decorView.systemUiVisibility = if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
                .put("azimuth", sat.azimuth.toDouble()).put("used", sat.usedInFix))
        }
        emit("satellites", JSONObject().put("items", array).put("source", if (receiver) {
            if (fresh) "来自 $remoteFrom" else "等待发送端卫星数据"
        } else "本机 GNSS 信号"))
    }

    private fun publishChecks() {
        emit("checks", JSONObject().put("location", hasLocationPermission()).put("dev", engine.isDeveloperOptionsEnabled())
            .put("mock", engine.isSelectedAsMockApp()).put("battery", isIgnoringBattery())
            .put("background", hasBackgroundLocation()))
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
