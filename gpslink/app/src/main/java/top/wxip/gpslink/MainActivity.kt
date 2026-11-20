package top.wxip.gpslink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import org.json.JSONArray
import org.json.JSONObject
import top.wxip.gpslink.core.*
import top.wxip.gpslink.mock.MockLocationEngine
import top.wxip.gpslink.service.ReceiveService
import top.wxip.gpslink.service.SendService

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
                        "permissions" -> withPermission { publishChecks() }
                        "recheck" -> publishChecks()
                        "pick-bt" -> withPermission { pickBtDevice() }
                        "probe" -> runProbe()
                        "theme" -> applySystemTheme(data.optBoolean("dark"))
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
            .put("dark", resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            .put("reduceMotion", reducedMotion()))
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
            .put("mock", engine.isSelectedAsMockApp()).put("battery", isIgnoringBattery()))
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
