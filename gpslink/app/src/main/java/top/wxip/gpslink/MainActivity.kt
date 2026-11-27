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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import top.wxip.gpslink.ui.SatelliteBarsView
import top.wxip.gpslink.ui.SatelliteSkyView
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import top.wxip.gpslink.core.*
import top.wxip.gpslink.mock.MockLocationEngine
import top.wxip.gpslink.service.ReceiveService
import top.wxip.gpslink.service.SendService
import top.wxip.gpslink.ui.OverlayHud
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Local-only presentation host. Location and transport remain in foreground services. */
class MainActivity : AppCompatActivity(), LinkBus.Observer {
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
    /** 后台保活开关，进后台时据此决定是否挂悬浮窗 */
    private var keepAlive = false
    private var pendingAction: (() -> Unit)? = null
    private val logLines = ArrayDeque<String>()
    private val remoteExpiry = Runnable { publishSatellites() }

    // 自更新：保留最新安装包的文件名与已落盘 APK，供前端按需触发
    private var lastApkName = ""
    private var lastApkSize = 0L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题必须在 super.onCreate 之前定下来：这里设定后，Resources 会直接按
        // values-night 加载，不需要先画一遍再重建
        delegate.localNightMode = nightModeOf(Prefs.loadTheme(this))
        super.onCreate(savedInstanceState)
        useBt = Prefs.loadUseBt(this)
        keepAlive = Prefs.loadKeepAlive(this)
        btMac = Prefs.loadBtMac(this)
        // 原生界面：布局由 res/layout 提供，所有状态通过 render() 直接写控件
        setContentView(R.layout.activity_main)
        applyOrientation(Prefs.loadOrientation(this))
        uiRole = savedInstanceState?.getString(KEY_ROLE) ?: uiRole
        bindUi()
        findViewById<RadioGroup>(R.id.role).check(
            if (uiRole == "receiver") R.id.role_receive else R.id.role_send
        )
        ready = true
        LinkBus.add(this)
        // 原生界面没有 ready 握手，这里主动推一次首屏数据
        publishConfig()
        publishChecks()
        publishTracks()
        publishBtLabel()
        onStatus(LinkBus.status)
        publishSatellites()
        emitLog()
        // 旋转会重建 Activity，页签要回到用户原来那一页
        showPage(savedInstanceState?.getString(KEY_PAGE) ?: uiPage)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, uiPage)
        outState.putString(KEY_ROLE, uiRole)
    }

    // ---------------------------------------------------------------- 原生界面

    private var uiRole = "sender"
    private var uiPage = "link"
    private var uiBinding = false
    private var lastSatList: List<SatInfo> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiFirstTrack = ""
    private val uiTrackPaths = ArrayList<String>()

    /** 预设区上一次渲染时的状态指纹，用来跳过 10Hz 状态流里的重复刷新 */
    private var presetSig = ""

    /** 把界面事件转成原本的桥接动作，业务逻辑一行都不用改 */
    private fun act(action: String, data: JSONObject = JSONObject()) {
        Bridge().dispatch(action, data.toString())
    }

    private fun bindUi() {
        // 底部导航
        mapOf(
            "link" to R.id.dock_link,
            "sat" to R.id.dock_sat,
            "access" to R.id.dock_access,
            "settings" to R.id.dock_settings,
            "about" to R.id.dock_about
        ).forEach { (key, id) -> findViewById<LinearLayout>(id).setOnClickListener { showPage(key) } }

        // 连接页
        findViewById<RadioGroup>(R.id.role).setOnCheckedChangeListener { _, id ->
            if (!uiBinding) {
                uiRole = if (id == R.id.role_receive) "receiver" else "sender"
                refreshRoleUi()
            }
        }
        // start 的点击行为由 renderRunState 统一接管（未运行时启动、运行中则停止）
        renderRunState(LinkBus.status.running, LinkBus.status.role.name)
        findViewById<Button>(R.id.stop).setOnClickListener { act("stop") }
        findViewById<Button>(R.id.btn_scan).setOnClickListener { startScan() }

        // 授权页
        findViewById<Button>(R.id.btn_permissions).setOnClickListener { act("permissions") }
        findViewById<Button>(R.id.btn_mock).setOnClickListener { act("mock") }
        findViewById<Button>(R.id.btn_background).setOnClickListener { act("background") }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { act("battery") }
        findViewById<Button>(R.id.btn_recheck).setOnClickListener { act("recheck") }
        findViewById<Button>(R.id.btn_copy_adb).setOnClickListener {
            act("copy", JSONObject().put("text", findViewById<TextView>(R.id.adb_cmd).text.toString()))
        }
        buildPresetList()

        // 设置页
        findViewById<RadioGroup>(R.id.mode).setOnCheckedChangeListener { _, id ->
            if (!uiBinding) applyModeUi(id == R.id.mode_bt)
        }
        findViewById<Button>(R.id.btn_pick_bt).setOnClickListener { act("pick-bt") }
        findViewById<Button>(R.id.btn_track_play).setOnClickListener {
            if (uiFirstTrack.isBlank()) toast("暂无可回放的轨迹")
            else act("track-play", JSONObject().put("path", uiFirstTrack))
        }
        findViewById<Button>(R.id.btn_track_delete).setOnClickListener { confirmDeleteTracks() }

        // 设置页 · 界面
        findViewById<RadioGroup>(R.id.orientation).setOnCheckedChangeListener { _, id ->
            if (uiBinding) return@setOnCheckedChangeListener
            act("orientation", JSONObject().put("value", orientationValueOf(id)))
        }
        findViewById<RadioGroup>(R.id.theme).setOnCheckedChangeListener { _, id ->
            if (uiBinding) return@setOnCheckedChangeListener
            act("theme", JSONObject().put("value", themeValueOf(id)))
        }
        findViewById<Button>(R.id.btn_probe).setOnClickListener { act("probe") }
        findViewById<SwitchCompat>(R.id.keepalive).setOnCheckedChangeListener { _, on ->
            if (!uiBinding) act("keepalive", JSONObject().put("enabled", on))
        }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener { act("overlay-permission") }
        findViewById<SwitchCompat>(R.id.autostart).setOnCheckedChangeListener { _, on ->
            if (!uiBinding) act("autostart", JSONObject().put("enabled", on))
        }
        findViewById<SwitchCompat>(R.id.track_toggle).setOnCheckedChangeListener { _, on ->
            if (!uiBinding) act("track-toggle", JSONObject().put("enabled", on))
        }
        findViewById<SwitchCompat>(R.id.nmea).setOnCheckedChangeListener { _, on ->
            if (!uiBinding) act(
                "nmea",
                JSONObject().put("enabled", on)
                    .put("port", findViewById<EditText>(R.id.nmea_port).text.toString().toIntOrNull() ?: 10110)
            )
        }

        // 关于页
        findViewById<Button>(R.id.btn_check_update).setOnClickListener { act("check-update") }
        findViewById<Button>(R.id.btn_view_log).setOnClickListener { act("fetch-log") }
        findViewById<Button>(R.id.btn_github).setOnClickListener {
            act("open-url", JSONObject().put("url", "https://github.com/zhaokelei/gnss-link"))
        }
        findViewById<Button>(R.id.btn_home).setOnClickListener {
            act("open-url", JSONObject().put("url", "https://www.iqiq.cn"))
        }
    }

    private fun showPage(key: String) {
        uiPage = key
        val pages = mapOf(
            "link" to R.id.page_link,
            "sat" to R.id.page_sat,
            "access" to R.id.page_access,
            "settings" to R.id.page_settings,
            "about" to R.id.page_about
        )
        val docks = mapOf(
            "link" to R.id.dock_link,
            "sat" to R.id.dock_sat,
            "access" to R.id.dock_access,
            "settings" to R.id.dock_settings,
            "about" to R.id.dock_about
        )
        pages.forEach { (k, id) ->
            findViewById<View>(id).visibility = if (k == key) View.VISIBLE else View.GONE
        }
        docks.forEach { (k, id) -> findViewById<LinearLayout>(id).isSelected = k == key }
    }

    private fun setSwitch(id: Int, on: Boolean) {
        findViewById<SwitchCompat>(id).isChecked = on
    }

    /**
     * 「开始」按钮的运行态。
     *
     * 原来运行后直接 isEnabled=false，按钮变成一块灰，用户看不出服务到底起没起。
     * 现在运行中换成绿色实心 + 「共享中/接收中」文案，并且保持可点击——
     * 再点一次等同于停止，减少一次「先找停止按钮」的操作。
     */
    private fun renderRunState(running: Boolean, role: String) {
        val receive = role == "RECEIVER" || uiRole == "receiver"
        findViewById<Button>(R.id.start).apply {
            if (running) {
                setBackgroundResource(R.drawable.bg_btn_running)
                setTextColor(ContextCompat.getColor(context, R.color.text_on_brand))
                text = if (receive) "接收中" else "共享中"
                isEnabled = true
                setOnClickListener { act("stop") }
            } else {
                setBackgroundResource(R.drawable.bg_btn_primary)
                setTextColor(ContextCompat.getColor(context, R.color.text_on_brand))
                text = if (receive) "开始接收" else "开始共享"
                isEnabled = true
                setOnClickListener {
                    if (!LinkBus.status.running) act("start", currentConfig())
                }
            }
        }
    }

    private fun orientationValueOf(id: Int): String = when (id) {
        R.id.orientation_landscape -> "landscape"
        R.id.orientation_portrait -> "portrait"
        else -> "auto"
    }

    private fun orientationIdOf(value: String): Int = when (value) {
        "landscape" -> R.id.orientation_landscape
        "portrait" -> R.id.orientation_portrait
        else -> R.id.orientation_auto
    }

    private fun themeValueOf(id: Int): String = when (id) {
        R.id.theme_light -> "light"
        R.id.theme_dark -> "dark"
        else -> "system"
    }

    private fun themeIdOf(value: String): Int = when (value) {
        "light" -> R.id.theme_light
        "dark" -> R.id.theme_dark
        else -> R.id.theme_system
    }

    /** 轨迹是全量删除，误触代价高，先问一句 */
    private fun confirmDeleteTracks() {
        if (uiTrackPaths.isEmpty()) return toast("暂无轨迹")
        AlertDialog.Builder(this)
            .setTitle("删除全部轨迹")
            .setMessage("共 ${uiTrackPaths.size} 条轨迹将被永久删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                val count = uiTrackPaths.size
                uiTrackPaths.toList().forEach {
                    act("track-delete", JSONObject().put("path", it).put("silent", true))
                }
                toast("已删除 $count 条轨迹")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun currentConfig(): JSONObject = JSONObject()
        .put("sid", findViewById<EditText>(R.id.sid).text.toString().trim().ifBlank { "8888" })
        .put("port", findViewById<EditText>(R.id.port).text.toString().toIntOrNull() ?: Protocol.DEFAULT_PORT)
        .put("host", findViewById<EditText>(R.id.host).text.toString().trim())
        .put("useBt", findViewById<RadioGroup>(R.id.mode).checkedRadioButtonId == R.id.mode_bt)
        .put(
            "intervalMs",
            when (findViewById<RadioGroup>(R.id.sample).checkedRadioButtonId) {
                R.id.sample_500 -> 500
                R.id.sample_200 -> 200
                R.id.sample_100 -> 100
                else -> 1000
            }
        )
        .put(
            "injectMs",
            when (findViewById<RadioGroup>(R.id.inject).checkedRadioButtonId) {
                R.id.inject_1000 -> 1000
                R.id.inject_200 -> 200
                else -> 100
            }
        )
        .put("role", uiRole)

    private fun refreshRoleUi() {
        val receive = uiRole == "receiver"
        findViewById<TextView>(R.id.action_title).text =
            if (receive) "接收另一台手机的定位" else "共享此设备的位置"
        findViewById<TextView>(R.id.action_hint).text = if (receive)
            "需先把本机设为模拟位置应用，详见授权页。"
        else
            "两台设备连接同一 Wi-Fi 或热点，并使用相同房间码。"
        // 运行中由 renderRunState 接管文案与配色，这里不要去覆盖
        if (!LinkBus.status.running) {
            renderRunState(false, uiRole)
        }
        findViewById<Button>(R.id.stop).text = if (receive) "停止接收" else "停止共享"
        findViewById<TextView>(R.id.link_peer).text =
            if (receive) "另一台手机" else "另一台设备"
    }

    private fun applyModeUi(bt: Boolean) {
        findViewById<LinearLayout>(R.id.bt_row).visibility = if (bt) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_pick_bt).visibility = if (bt) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.link_transport).text =
            if (bt) "蓝牙 RFCOMM" else "UDP / " + findViewById<EditText>(R.id.port).text.toString()
    }

    private fun showUpdateDialog(tag: String, latest: String, sizeMb: Double) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v" + latest)
            .setMessage("安装包 %.1f MB，下载后可直接覆盖安装。".format(sizeMb))
            .setPositiveButton("立即更新") { _, _ -> act("download-update") }
            .setNeutralButton("跳过此版本") { _, _ ->
                act("skip-version", JSONObject().put("tag", tag))
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 授权项状态：已完成用绿色，未完成用警示橙，比一片灰色更容易扫到待办 */
    /** 授权项状态：已完成用绿色，未完成用警示橙，比一片灰色更容易扫到待办 */
    private fun mark(id: Int, ok: Boolean) {
        findViewById<TextView>(id).apply {
            text = if (ok) "已就绪" else "待设置"
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (ok) R.color.ok else R.color.warn
                )
            )
        }
    }

    /**
     * 按省市动态生成预设地点按钮。
     *
     * 每个地点一格按钮，点击即持续注入该坐标；再点一次停止。
     * 用代码生成而不是在 XML 里写死，是因为地点集合后续可能增删，
     * 且横竖屏两套布局不必各维护一份。
     */
    private fun buildPresetList() {
        val host = findViewById<LinearLayout>(R.id.preset_list)
        host.removeAllViews()
        val dp = resources.displayMetrics.density

        for ((province, list) in Presets.byProvince) {
            val title = TextView(this).apply {
                text = province
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
                setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
            }
            host.addView(title)

            // 每行两个按钮，窄屏也不会把地名挤断
            var row: LinearLayout? = null
            list.forEachIndexed { i, place ->
                if (i % 2 == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    host.addView(row)
                }
                val active = isInjecting(place)
                val btn = Button(this).apply {
                    text = if (active) "注入中 · ${place.name}" else place.name
                    textSize = 13f
                    isAllCaps = false
                    setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                    minHeight = (44 * dp).toInt()
                    minWidth = 0
                    setBackgroundResource(if (active) R.drawable.bg_btn_running else R.drawable.bg_btn)
                    tag = place.key
                    isSelected = active
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (active) R.color.text_on_brand else R.color.text_primary
                        )
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0, (44 * dp).toInt(), 1f
                    ).apply {
                        rightMargin = (8 * dp).toInt()
                        topMargin = (6 * dp).toInt()
                    }
                    setOnClickListener { togglePreset(place) }
                }
                row?.addView(btn)
            }
        }
        presetSig = ""          // 按钮是新建的，强制下一行把状态刷上去
        syncPresetState()
    }

    /**
     * 同步预设按钮的选中态与卡片底部的状态文字。
     *
     * 状态以服务为准（fixedName），而不是界面自己记的 key —— 服务可能被
     * 通知栏、开机自启或进程重建重新拉起，界面重进时要能对得上。
     */
    private fun syncPresetState() {
        // status 事件在注入期间是 10Hz 的，没有变化就别重复刷控件
        val s = LinkBus.status
        val sig = listOf(
            s.running, s.fixedName, s.fixedOverride,
            s.verifyAvailable, s.verifyMock, (s.verifyDeltaM * 10).toLong()
        ).joinToString("|")
        if (sig == presetSig) return
        presetSig = sig

        val list = findViewById<LinearLayout>(R.id.preset_list)
        val activeName = LinkBus.status.fixedName
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val btn = row.getChildAt(j) as? Button ?: continue
                val place = Presets.find(btn.tag as? String ?: "") ?: continue
                val active = isInjecting(place)
                btn.text = if (active) "注入中 · ${place.name}" else place.name
                btn.isSelected = active
                btn.setBackgroundResource(if (active) R.drawable.bg_btn_running else R.drawable.bg_btn)
                btn.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (active) R.color.text_on_brand else R.color.text_primary
                    )
                )
            }
        }
        findViewById<TextView>(R.id.preset_state).text = when {
            activeName.isEmpty() -> "未注入任何地点。点一个地点即开始持续注入，再点一次恢复接收共享坐标。"
            else -> "正在注入固定点：$activeName" +
                if (LinkBus.status.fixedOverride) "（接收已暂停，再点一次恢复接收）" else ""
        }
        renderVerifyHint(findViewById(R.id.preset_verify_hint))
    }

    /**
     * 把「注入到底有没有被系统采纳」翻成人话。
     *
     * verify 系列字段来自引擎每秒从 LocationManager 读回的值，也就是导航 App
     * 拿到的同一个值：距离注入点几十米内、且带 mock 标记，就能判定系统已经在
     * 用我们注入的位置，而不是本机 GPS。
     */
    private fun renderVerifyHint(view: TextView) {
        val s = LinkBus.status
        view.text = when {
            !s.running -> "如何判断模拟位置是否成功：先点上面的地点开始注入，再用导航软件核对蓝点坐标。"
            !s.verifyAvailable -> "正在等待系统回读定位…若一直无结果，请确认已选中本应用为模拟位置信息应用。"
            s.verifyDeltaM in 0.0..60.0 && s.verifyMock ->
                "✅ 系统回读与注入点相差 ${"%.0f".format(s.verifyDeltaM)} 米，且标记为模拟位置 —— 本机 GPS 已被顶替。"
            s.verifyDeltaM in 0.0..60.0 ->
                "系统回读已接近注入点（相差 ${"%.0f".format(s.verifyDeltaM)} 米），但未标记为模拟位置，个别 ROM 会省略该标记。"
            else ->
                "❌ 系统回读与注入点相差 ${"%.0f".format(s.verifyDeltaM)} 米，本机 GPS 可能仍在生效。"
        }
    }

    /** 当前是否正在注入这个固定点 */
    private fun isInjecting(place: PresetPlace): Boolean =
        LinkBus.status.running && LinkBus.status.fixedName == place.name

    /**
     * 切换某地点的注入状态。
     *
     * 已在注入同一个点时再点一次 = 取消，恢复接收共享端坐标；
     * 切到别的点则直接换目标，不需要用户先手动取消。
     */
    private fun togglePreset(place: PresetPlace) {
        if (isInjecting(place)) {
            if (LinkBus.status.fixedOverride) {
                ReceiveService.clearFixed(this)
                toast("已取消固定点，恢复接收共享坐标")
            } else {
                ReceiveService.clearFixed(this)
                toast("已停止注入")
            }
            return
        }
        withPermission {
            act("inject-fixed", JSONObject().put("key", place.key))
            toast(
                if (LinkBus.status.running) "固定点注入：${place.name}，接收已暂停"
                else "正在注入：${place.province} · ${place.name}"
            )
        }
    }

    private fun render(type: String, data: Any) {
        val d = data as? JSONObject
        when (type) {
            "init" -> {
                if (d == null) return
                uiBinding = true
                findViewById<EditText>(R.id.sid).setText(d.optString("sid", "8888"))
                findViewById<EditText>(R.id.port).setText(d.optInt("port", Protocol.DEFAULT_PORT).toString())
                findViewById<EditText>(R.id.host).setText(d.optString("host"))
                findViewById<RadioGroup>(R.id.mode).check(
                    if (d.optBoolean("useBt")) R.id.mode_bt else R.id.mode_lan
                )
                findViewById<RadioGroup>(R.id.sample).check(
                    when (d.optInt("intervalMs", Protocol.DEFAULT_INTERVAL_MS)) {
                        500 -> R.id.sample_500
                        200 -> R.id.sample_200
                        100 -> R.id.sample_100
                        else -> R.id.sample_1000
                    }
                )
                findViewById<RadioGroup>(R.id.inject).check(
                    when (d.optInt("injectMs", Protocol.DEFAULT_INJECT_MS)) {
                        1000 -> R.id.inject_1000
                        200 -> R.id.inject_200
                        else -> R.id.inject_100
                    }
                )
                setSwitch(R.id.autostart, d.optBoolean("autoStart"))
                setSwitch(R.id.track_toggle, d.optBoolean("trackEnabled"))
                setSwitch(R.id.nmea, d.optBoolean("nmeaEnabled"))
                findViewById<RadioGroup>(R.id.orientation)
                    .check(orientationIdOf(d.optString("orientation", "auto")))
                findViewById<RadioGroup>(R.id.theme)
                    .check(themeIdOf(d.optString("theme", "system")))
                findViewById<EditText>(R.id.nmea_port)
                    .setText(d.optInt("nmeaPort", Protocol.DEFAULT_NMEA_PORT).toString())
                findViewById<TextView>(R.id.about_version).text = "v" + d.optString("version")
                findViewById<TextView>(R.id.about_engine).text = d.optString("engine", "原生界面")
                findViewById<TextView>(R.id.link_room).text = d.optString("sid", "8888")
                findViewById<TextView>(R.id.link_transport).text =
                    if (d.optBoolean("useBt")) "蓝牙 RFCOMM" else "UDP / " + d.optInt("port")
                applyModeUi(d.optBoolean("useBt"))
                uiBinding = false
                refreshRoleUi()
            }
            "checks" -> {
                if (d == null) return
                mark(R.id.check_location, d.optBoolean("location"))
                mark(R.id.check_mock, d.optBoolean("mock"))
                mark(R.id.check_background, d.optBoolean("background"))
                mark(R.id.check_battery, d.optBoolean("battery"))
                val alive = d.optBoolean("keepAlive")
                val overlay = d.optBoolean("overlay")
                findViewById<TextView>(R.id.keepalive_state).apply {
                    text = if (alive) "已开启" else "未开启"
                    setTextColor(
                        ContextCompat.getColor(context, if (alive) R.color.ok else R.color.text_secondary)
                    )
                    setBackgroundResource(
                        if (alive) R.drawable.bg_tag_ok else R.drawable.bg_tag_idle
                    )
                }
                findViewById<Button>(R.id.btn_overlay).visibility =
                    if (overlay) View.GONE else View.VISIBLE
                findViewById<TextView>(R.id.keepalive_hint).text = when {
                    !overlay -> "尚未授予悬浮窗权限，当前保活只依赖常驻通知。"
                    alive -> "退到后台时角落会出现可拖动的小标签，点一下回到应用。"
                    else -> "开启后可在后台保持运行，前台使用时不会出现悬浮窗。"
                }
            }
            "access" -> {
                if (d == null) return
                findViewById<TextView>(R.id.auth_android).text = d.optString("androidName")
                findViewById<TextView>(R.id.auth_tier).text =
                    if (d.optString("tier") == "legacy") "系统开关" else "AppOps"
                findViewById<TextView>(R.id.auth_steps).text = d.optString("steps")
                val why = d.optString("why")
                findViewById<TextView>(R.id.auth_why).apply {
                    text = why
                    visibility = if (why.isBlank()) View.GONE else View.VISIBLE
                }
                findViewById<TextView>(R.id.adb_note).text =
                    "命令可复制到电脑终端执行，效果等同手动授权且重启不丢。"
                findViewById<TextView>(R.id.adb_cmd).text = d.optString("adb")
                findViewById<TextView>(R.id.adb_check).text = d.optString("adbCheck")
            }
            "status" -> {
                if (d == null) return
                val running = d.optBoolean("running")
                // 固定点注入期间接收被暂停，徽标单独给一个状态，避免显示成"等待"
                val paused = d.optString("fixedName").isNotBlank() && d.optBoolean("fixedOverride")
                syncPresetState()
                renderRunState(running, d.optString("role"))
                findViewById<Button>(R.id.stop).isEnabled = running
                findViewById<TextView>(R.id.state_badge).apply {
                    text = when {
                        !running -> "未运行"
                        paused -> "注入中 · 接收暂停"
                        d.optString("role") == "RECEIVER" && !d.optBoolean("online") -> "等待"
                        else -> "运行中"
                    }
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            when {
                                !running -> R.color.text_secondary
                                paused -> R.color.brand
                                d.optString("role") == "RECEIVER" && !d.optBoolean("online") -> R.color.warn
                                else -> R.color.ok
                            }
                        )
                    )
                    setBackgroundResource(
                        when {
                            !running -> R.drawable.bg_tag_idle
                            paused -> R.drawable.bg_tag
                            d.optString("role") == "RECEIVER" && !d.optBoolean("online") -> R.drawable.bg_tag_warn
                            else -> R.drawable.bg_tag_ok
                        }
                    )
                }
                findViewById<TextView>(R.id.link_ip).text = d.optString("localIp").ifBlank { "—" }
                refreshQrCard(running)
                findViewById<TextView>(R.id.link_peer).text = d.optString("peer").ifBlank {
                    if (d.optString("role") == "RECEIVER") "另一台手机" else "另一台设备"
                }
                val lat = d.optDouble("lat", 0.0)
                val lon = d.optDouble("lon", 0.0)
                findViewById<TextView>(R.id.fix_coord).text =
                    if (lat == 0.0 && lon == 0.0) "—" else "%.5f, %.5f".format(lat, lon)
                findViewById<TextView>(R.id.fix_rate).text =
                    "%.1f Hz · ±%.0fm".format(
                        d.optDouble("rateHz", 0.0),
                        d.optDouble("accuracy", 0.0)
                    )
                findViewById<TextView>(R.id.fix_count).text = d.optLong("fixCount", 0L).toString()
                val nmeaPort = d.optInt("nmeaPort", 0)
                findViewById<TextView>(R.id.nmea_address).text = if (nmeaPort > 0)
                    "监听中：" + d.optString("localIp") + ":" + nmeaPort
                else
                    "开始共享后显示连接地址"
            }
            "satellites" -> {
                findViewById<TextView>(R.id.sat_sub).text =
                    d?.optString("source")?.ifBlank { null } ?: getString(R.string.page_sat_subtitle)
                findViewById<SatelliteSkyView>(R.id.sky).setSatellites(lastSatList)
                findViewById<SatelliteBarsView>(R.id.bars).setSatellites(lastSatList)
                findViewById<TextView>(R.id.sat_visible).text = lastSatList.size.toString()
                findViewById<TextView>(R.id.sat_used).text = lastSatList.count { it.usedInFix }.toString()
                val avg = if (lastSatList.isEmpty()) 0.0
                else lastSatList.sumOf { it.cn0.toDouble() } / lastSatList.size
                findViewById<TextView>(R.id.sat_snr).text =
                    if (lastSatList.isEmpty()) "—" else "%.1f dB-Hz".format(avg)

                // 按「当前收到的卫星」统计频段，而不是手机硬件支持几频：
                // 手机标称多频，但此刻空中的卫星可能只覆盖到 L1 单频
                val bandCount = Gnss.observedBandCount(lastSatList)
                val bands = Gnss.observedBands(lastSatList)
                findViewById<TextView>(R.id.sat_bands).apply {
                    text = if (bandCount == 0) "—"
                    else Gnss.bandCountLabel(bandCount) + " · " + bands.joinToString("/")
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (bandCount >= 2) R.color.ok else R.color.text_primary
                        )
                    )
                }
                findViewById<TextView>(R.id.sat_bands_hint).apply {
                    visibility = View.VISIBLE
                    text = when {
                        lastSatList.isEmpty() -> "等待卫星数据后统计频段。"
                        bandCount == 0 -> "当前系统不提供载波频率，无法判断频段（需 Android 11 及以上）。"
                        else -> "按当前收到卫星的载波统计，共 ${bandCount} 个频点。"
                    }
                }
            }
            "log" -> {
                val lines = (data as? JSONArray)?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList()
                findViewById<TextView>(R.id.logs).text =
                    if (lines.isEmpty()) "暂无日志" else lines.takeLast(80).joinToString("\n")
            }
            "tracks" -> {
                val arr = d?.optJSONArray("items") ?: (data as? JSONArray)
                val names = ArrayList<String>()
                uiTrackPaths.clear()
                uiFirstTrack = ""
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        names.add(o.optString("name") + " · " + o.optInt("points") + " 点")
                        val path = o.optString("path")
                        if (path.isNotBlank()) {
                            uiTrackPaths.add(path)
                            if (uiFirstTrack.isBlank()) uiFirstTrack = path
                        }
                    }
                }
                findViewById<TextView>(R.id.track_list).text =
                    if (names.isEmpty()) "暂无轨迹" else names.joinToString("\n")
                findViewById<Button>(R.id.btn_track_delete).isEnabled = uiTrackPaths.isNotEmpty()
            }
            "probe" -> {
                findViewById<Button>(R.id.btn_probe).isEnabled = !(d?.optBoolean("running") ?: false)
                findViewById<TextView>(R.id.probe_result).text = d?.optString("message").orEmpty()
            }
            "bt" -> findViewById<TextView>(R.id.bt_label).text = data.toString()
            // 这里必须直接弹 Toast：走 toast() 会 emit("notice") 回到本分支，形成无限递归
            "notice" -> Toast.makeText(this, data.toString(), Toast.LENGTH_SHORT).show()
            "update-check" -> {
                if (d == null) return
                val latest = d.optString("latest")
                val cur = d.optString("current")
                if (latest.isBlank() || latest == cur) {
                    findViewById<TextView>(R.id.update_hint).text = "已是最新版本 v" + cur
                } else {
                    findViewById<TextView>(R.id.update_hint).text = "发现新版本 v" + latest
                    showUpdateDialog(d.optString("tag"), latest, d.optLong("size") / 1048576.0)
                }
            }
            "update-progress" -> {
                val pct = d?.optInt("value", 0) ?: 0
                findViewById<TextView>(R.id.update_hint).text = "正在下载 $pct%"
            }
            "update-ready" -> {
                val path = d?.optString("path").orEmpty()
                findViewById<TextView>(R.id.update_hint).text = "下载完成，可以安装"
                if (isFinishing || isDestroyed) return
                AlertDialog.Builder(this)
                    .setTitle("下载完成")
                    .setMessage("立即安装新版本？")
                    .setPositiveButton("安装") { _, _ -> installUpdate(path) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            "update-log" -> {
                val text = d?.optString("text").orEmpty()
                if (text.isNotBlank() && !isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle("更新日志")
                        .setMessage(text.take(2000))
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }
            else -> Unit
        }
    }

    private inner class Bridge {
        fun dispatch(action: String, payload: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    val data = JSONObject(payload)
                    when (action) {
                        "start" -> if (!LinkBus.status.running) {
                            configure(data)
                            withPermission { start(data.optString("role")) }
                        }
                        "stop" -> { SendService.stop(this@MainActivity); ReceiveService.stop(this@MainActivity); LinkBus.reset() }
                        "keepalive" -> setKeepAlive(data.optBoolean("enabled"))
                        "overlay-permission" -> requestOverlayPermission()
                        "mock" -> if (!engine.openMockSettings())
                            toast("本机没有可用的设置页，请用「命令行授权」里的 adb 命令授权")
                        "battery" -> requestIgnoreBattery()
                        "background" -> openAppSettings()
                        "permissions" -> withPermission { publishChecks() }
                        "recheck" -> publishChecks()
                        "pick-bt" -> withPermission { pickBtDevice() }
                        "probe" -> runProbe()
                        "theme" -> applyThemeMode(data.optString("value", "system"))
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
                        "track-delete" -> deleteTrack(data.optString("path"), data.optBoolean("silent"))
                        "inject-fixed" -> injectFixed(data.optString("key"))
                        "scan" -> startScan()
                        "scan-result" -> handleScanResult(data.optString("raw"))
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
                        "skip-version" -> skipVersion(data.optString("tag"))
                        "fetch-log" -> fetchUpdateLog()
                        "open-url" -> openExternalUrl(data.optString("url"))
                    }
                } catch (e: Exception) { toast("操作未完成：${e.message}") }
            }
        }
    }

    /** 唯一的界面出口：所有状态都从这里写进原生控件 */
    private fun emit(type: String, data: Any) {
        if (!ready || isDestroyed) return
        try {
            render(type, data)
        } catch (t: Throwable) {
            Log.w(TAG, "render $type failed", t)
        }
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
            .put("theme", Prefs.loadTheme(this))
            .put("package", packageName)
            .put("keepAlive", keepAlive)
            // 排查车机问题时最需要的一行：报出系统版本，便于区分不同 ROM 的差异
            .put("engine", engineLabel())
            .put("version", currentVersion))
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

    /**
     * 持续注入一个预设坐标。
     *
     * 已有任务在跑时先停掉再开新的，避免测试 provider 被两个循环同时写入。
     */
    private fun injectFixed(key: String) {
        val place = Presets.find(key) ?: return toast("未知地点")
        if (!engine.isSelectedAsMockApp()) {
            publishChecks()
            return toast("注入需要模拟位置授权：${engine.describe().steps}")
        }
        try {
            ReceiveService.injectFixed(this, place.key)
        } catch (t: Throwable) {
            toast("注入失败：${t.message}")
        }
    }

    /** 打开扫码页；相机权限在扫码页内部按需申请 */
    private fun startScan() {
        startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
    }

    /**
     * 处理扫码结果：把地址、端口、房间码填进界面并直接开始接收。
     *
     * 只填参数不自动连接会让用户以为没反应，所以这里填完立刻启动。
     */
    private fun handleScanResult(raw: String) {
        val payload = ConnectCode.decode(raw)
        if (payload == null) {
            toast("无法识别的二维码")
            return
        }
        findViewById<EditText>(R.id.host).setText(payload.ip)
        findViewById<EditText>(R.id.port).setText(payload.port.toString())
        findViewById<EditText>(R.id.sid).setText(payload.sid)
        findViewById<RadioGroup>(R.id.role).check(R.id.role_receive)
        findViewById<RadioGroup>(R.id.mode).check(R.id.mode_lan)
        toast("已连接 ${payload.ip}:${payload.port}")
        act("start", currentConfig())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            handleScanResult(data?.getStringExtra(ScanActivity.EXTRA_RAW).orEmpty())
        }
    }

    /**
     * 刷新共享端二维码。
     *
     * 二维码内容随 IP/端口/房间码变化，且只在「发送端运行中」时才展示——
     * 没跑起来时 IP 可能还没分配到，扫了也连不上。
     */
    private fun refreshQrCard(running: Boolean) {
        val card = findViewById<LinearLayout>(R.id.qr_card)
        val image = findViewById<ImageView>(R.id.qr_image)
        val addr = findViewById<TextView>(R.id.qr_address)
        val hint = findViewById<TextView>(R.id.qr_hint)

        val role = LinkBus.status.role
        if (!running || role == Role.RECEIVER) {
            card.visibility = View.GONE
            return
        }
        val ip = NetInfo.localIpv4()
        if (ip.isBlank()) {
            card.visibility = View.GONE
            return
        }
        val port = findViewById<EditText>(R.id.port).text.toString()
            .toIntOrNull() ?: Protocol.DEFAULT_PORT
        val sid = findViewById<EditText>(R.id.sid).text.toString().trim().ifBlank { "8888" }

        card.visibility = View.VISIBLE
        addr.text = "$ip:$port"
        hint.text = "房间码 $sid · 两台设备需在同一 Wi-Fi 或热点下"

        // 二维码按屏幕宽度自适应：横屏车机上不要画成巨大的方块
        val target = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.5)
            .toInt().coerceIn(240, 720)
        val bmp = ConnectCode.qrBitmap(ConnectCode.encode(ip, port, sid), target)
        if (bmp != null) {
            image.setImageBitmap(bmp)
        } else {
            image.setImageDrawable(null)
            addr.text = "二维码生成失败\n$ip:$port"
        }
    }

    private fun deleteTrack(path: String, silent: Boolean = false) {
        if (path.isBlank()) return
        val ok = TrackRecorder.delete(File(path))
        if (!silent) toast(if (ok) "轨迹已删除" else "删除失败")
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
        private const val REQ_SCAN = 0x5CA1
        private const val KEY_PAGE = "ui_page"
        private const val KEY_ROLE = "ui_role"

        // 更新日志走 CDN，固定 URL 会被缓存住导致迟迟收不到新版本提示，
        // 每次请求带上时间戳，让 CDN 按不同 URL 处理，始终回源。
        fun logUrl(): String = "$BASE_URL/update-log.txt?t=${System.currentTimeMillis()}"
        private const val UPDATER_UA = "GNSS-Link-Updater/1.3.0"
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


    private fun nightModeOf(value: String): Int = when (value) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /**
     * 切换主题。
     *
     * Manifest 里声明了 configChanges=uiMode，系统不会自己重建 Activity，
     * 所以改完 localNightMode 要手动 recreate；重建后 onCreate 会按新值加载
     * values-night，颜色与主题一起换掉。
     */
    private fun applyThemeMode(value: String) {
        Prefs.saveTheme(this, value)
        val mode = nightModeOf(value)
        if (delegate.localNightMode == mode) return
        delegate.localNightMode = mode
        recreate()
    }

    /** 应用屏幕方向偏好：车机系统常把界面锁成竖屏，这里给用户一个主动覆盖的入口 */
    private fun applyOrientation(value: String) {
        requestedOrientation = when (value) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        publishChecks()
        syncOverlay()
        publishBtLabel()
        if (!LinkBus.status.running) {
            try { engine.release() } catch (_: Exception) { }
        }
        if (hasLocationPermission()) satMonitor.start()
    }

    override fun onPause() {
        resumed = false
        satMonitor.stop()
        syncOverlay()
        super.onPause()
    }

    override fun onDestroy() {
        LinkBus.remove(this)
        satMonitor.stop()
        rateProbe.stop()
        uiHandler.removeCallbacks(remoteExpiry)
        ready = false
        super.onDestroy()
    }

    override fun onStatus(status: LinkStatus) {
        // 悬浮窗只在后台显示，这里顺手刷新内容，前台时 update 内部会直接返回
        if (OverlayHud.isShowing()) OverlayHud.update(status)
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
            .put("fixedName", status.fixedName)
            .put("fixedOverride", status.fixedOverride)
            .put("probing", rateProbe.isRunning)
            .put("nmeaPort", status.nmeaPort)
            .put("nmeaClients", status.nmeaClients)
        emit("status", json)
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 40) logLines.removeFirst()
        emitLog()
    }

    private fun emitLog() {
        emit("log", JSONArray(logLines.toList()))
    }

    override fun onRemoteSatellites(list: List<SatInfo>, from: String) {
        remoteSats = list
        remoteFrom = from
        remoteAt = SystemClock.elapsedRealtime()
        publishSatellites()
        uiHandler.removeCallbacks(remoteExpiry)
        uiHandler.postDelayed(remoteExpiry, 6001)
    }

    private fun publishSatellites() {
        val receiver = LinkBus.status.role == Role.RECEIVER
        val fresh = SystemClock.elapsedRealtime() - remoteAt < 6000
        val items = if (receiver) { if (fresh) remoteSats else emptyList() } else localSats
        lastSatList = items
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
            .put("background", hasBackgroundLocation())
            .put("keepAlive", keepAlive)
            .put("overlay", OverlayHud.canDraw(this)))
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    /** 关于页「内核」一栏：直接报系统版本，便于排查车机 ROM 差异 */
    private fun engineLabel(): String = "原生 · Android ${Build.VERSION.RELEASE}"

    /**
     * 切换后台保活。
     *
     * 没有悬浮窗权限时不阻断开关 —— 保活仍有常驻通知这一层，只是效果弱一点，
     * 所以这里只提示并引导去授权，用户拒绝也照常保存开关状态。
     */
    private fun setKeepAlive(enabled: Boolean) {
        keepAlive = enabled
        Prefs.saveKeepAlive(this, enabled)
        if (enabled && !OverlayHud.canDraw(this)) {
            toast("未授予悬浮窗权限，保活只依赖常驻通知")
            requestOverlayPermission()
        }
        if (!enabled) OverlayHud.hide()
        syncOverlay()
        publishChecks()
    }

    /** 按「开关 + 是否前台 + 服务是否在跑 + 有没有权限」四个条件决定悬浮窗去留 */
    private fun syncOverlay() {
        val shouldShow = keepAlive && !resumed && LinkBus.status.running && OverlayHud.canDraw(this)
        if (shouldShow) {
            OverlayHud.show(this)
            OverlayHud.update(LinkBus.status)
        } else {
            OverlayHud.hide()
        }
    }

    /** 跳悬浮窗授权页；部分车机把这个页面裁了，退到应用详情页让用户自己找 */
    private fun requestOverlayPermission() {
        val deep = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(deep)) return
        if (tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) return
        openAppSettings()
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (t: Throwable) {
        false
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
