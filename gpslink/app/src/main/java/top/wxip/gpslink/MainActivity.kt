package top.wxip.gpslink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import top.wxip.gpslink.core.LinkBus
import top.wxip.gpslink.core.LinkStatus
import top.wxip.gpslink.core.Gnss
import top.wxip.gpslink.core.Prefs
import top.wxip.gpslink.core.Protocol
import top.wxip.gpslink.core.RateProbe
import top.wxip.gpslink.core.Role
import top.wxip.gpslink.core.SatInfo
import top.wxip.gpslink.core.SatelliteMonitor
import top.wxip.gpslink.ui.SatelliteBarsView
import top.wxip.gpslink.mock.MockLocationEngine
import top.wxip.gpslink.service.ReceiveService
import top.wxip.gpslink.service.SendService
import java.util.Locale

class MainActivity : AppCompatActivity(), LinkBus.Observer {

    private lateinit var etSid: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var etHost: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnSend: MaterialButton
    private lateinit var btnReceive: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnMock: MaterialButton
    private lateinit var btnRecheck: MaterialButton
    private lateinit var tvCheckDev: TextView
    private lateinit var tvCheckPerm: TextView
    private lateinit var tvCheckMock: TextView
    private lateinit var tvCheckBattery: TextView
    private lateinit var cardVerify: MaterialCardView
    private lateinit var tvVerifyInjected: TextView
    private lateinit var tvVerifyReadback: TextView
    private lateinit var tvVerifyResult: TextView

    private lateinit var groupRate: MaterialButtonToggleGroup
    private lateinit var groupInject: MaterialButtonToggleGroup
    private lateinit var groupMode: MaterialButtonToggleGroup
    private lateinit var tilPort: TextInputLayout
    private lateinit var tilHost: TextInputLayout
    private lateinit var rowBt: LinearLayout
    private lateinit var tvBtDevice: TextView
    private lateinit var btnPickBt: MaterialButton
    private lateinit var btnProbe: MaterialButton
    private lateinit var tvProbeResult: TextView
    private lateinit var satView: SatelliteBarsView
    private lateinit var tvSatSummary: TextView
    private lateinit var tvSatLegend: TextView
    private lateinit var cardDetail: MaterialCardView
    private lateinit var tvDLatLon: TextView
    private lateinit var tvDAccuracy: TextView
    private lateinit var tvDAltitude: TextView
    private lateinit var tvDSpeed: TextView
    private lateinit var tvDBearing: TextView
    private lateinit var tvDAge: TextView

    private val engine by lazy { MockLocationEngine(this) }
    private val rateProbe by lazy { RateProbe(this) }
    private val satMonitor by lazy { SatelliteMonitor(this) { list -> onSatellites(list) } }
    private val logLines = ArrayDeque<String>()
    private var pendingAction: (() -> Unit)? = null

    /** 当前传输方式 */
    private var useBt = false
    private var btMac = ""

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            pendingAction?.invoke()
        } else {
            toast("需要定位权限才能运行")
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSid = findViewById(R.id.et_sid)
        etPort = findViewById(R.id.et_port)
        etHost = findViewById(R.id.et_host)
        tvStatus = findViewById(R.id.tv_status)
        tvDetail = findViewById(R.id.tv_detail)
        tvLog = findViewById(R.id.tv_log)
        btnSend = findViewById(R.id.btn_send)
        btnReceive = findViewById(R.id.btn_receive)
        btnStop = findViewById(R.id.btn_stop)
        btnMock = findViewById(R.id.btn_mock)
        btnRecheck = findViewById(R.id.btn_recheck)
        tvCheckDev = findViewById(R.id.tv_check_dev)
        tvCheckPerm = findViewById(R.id.tv_check_perm)
        tvCheckMock = findViewById(R.id.tv_check_mock)
        tvCheckBattery = findViewById(R.id.tv_check_battery)
        cardVerify = findViewById(R.id.card_verify)
        tvVerifyInjected = findViewById(R.id.tv_verify_injected)
        tvVerifyReadback = findViewById(R.id.tv_verify_readback)
        tvVerifyResult = findViewById(R.id.tv_verify_result)
        groupRate = findViewById(R.id.group_rate)
        groupInject = findViewById(R.id.group_inject)
        cardDetail = findViewById(R.id.card_detail)
        tvDLatLon = findViewById(R.id.tv_d_latlon)
        tvDAccuracy = findViewById(R.id.tv_d_accuracy)
        tvDAltitude = findViewById(R.id.tv_d_altitude)
        tvDSpeed = findViewById(R.id.tv_d_speed)
        tvDBearing = findViewById(R.id.tv_d_bearing)
        tvDAge = findViewById(R.id.tv_d_age)
        groupMode = findViewById(R.id.group_mode)
        tilPort = findViewById(R.id.til_port)
        tilHost = findViewById(R.id.til_host)
        rowBt = findViewById(R.id.row_bt)
        tvBtDevice = findViewById(R.id.tv_bt_device)
        btnPickBt = findViewById(R.id.btn_pick_bt)
        btnProbe = findViewById(R.id.btn_probe)
        tvProbeResult = findViewById(R.id.tv_probe_result)
        satView = findViewById(R.id.sat_view)
        tvSatSummary = findViewById(R.id.tv_sat_summary)
        tvSatLegend = findViewById(R.id.tv_sat_legend)

        val cfg = Prefs.loadSend(this)
        etSid.setText(cfg.sid)
        etPort.setText(cfg.port.toString())
        etHost.setText(cfg.host)

        // 恢复上次选的采样档位（先 check 再挂监听，避免触发一次多余的回调）
        when (cfg.intervalMs) {
            500 -> groupRate.check(R.id.btn_rate_2)
            200 -> groupRate.check(R.id.btn_rate_5)
            100 -> groupRate.check(R.id.btn_rate_10)
            else -> groupRate.check(R.id.btn_rate_1)
        }
        groupRate.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val interval = intervalOf(checkedId)
            Prefs.saveInterval(this, interval)
            if (LinkBus.status.running && LinkBus.status.role == Role.SENDER) {
                SendService.setInterval(this, interval)
            }
        }

        // 注入频率（接收端）
        when (Prefs.loadInject(this)) {
            1000 -> groupInject.check(R.id.btn_inject_1)
            200 -> groupInject.check(R.id.btn_inject_5)
            else -> groupInject.check(R.id.btn_inject_10)
        }
        groupInject.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val interval = injectIntervalOf(checkedId)
            Prefs.saveInject(this, interval)
            if (LinkBus.status.running && LinkBus.status.role == Role.RECEIVER) {
                ReceiveService.setInjectInterval(this, interval)
            }
        }

        // 传输方式
        useBt = Prefs.loadUseBt(this)
        btMac = Prefs.loadBtMac(this)
        groupMode.check(if (useBt) R.id.btn_mode_bt else R.id.btn_mode_lan)
        applyModeUi()
        groupMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useBt = checkedId == R.id.btn_mode_bt
            Prefs.saveUseBt(this, useBt)
            applyModeUi()
        }

        btnPickBt.setOnClickListener { withPermission { pickBtDevice() } }
        btnProbe.setOnClickListener { runProbe() }

        btnSend.setOnClickListener { withPermission { startSend() } }
        btnReceive.setOnClickListener { withPermission { startReceive() } }
        btnStop.setOnClickListener {
            SendService.stop(this)
            ReceiveService.stop(this)
            LinkBus.reset()
        }
        btnMock.setOnClickListener {
            try {
                startActivity(engine.developerOptionsIntent())
            } catch (t: Throwable) {
                toast("无法打开开发者选项：${t.message}")
            }
        }
        btnRecheck.setOnClickListener { refreshChecks() }

        LinkBus.add(this)
        refreshChecks()
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
        refreshBtLabel()

        // 兜底清理：上次进程被 ROM 杀掉时 onDestroy 可能没执行，
        // 系统里会残留测试 provider，导致设备一直卡在假位置上
        if (!LinkBus.status.running) {
            try {
                engine.release()
            } catch (_: Throwable) {
            }
        }

        if (hasLocationPermission()) satMonitor.start()
    }

    override fun onPause() {
        super.onPause()
        satMonitor.stop()
    }

    // ---------------------------------------------------------------- 卫星

    private var localSats: List<SatInfo> = emptyList()
    private var remoteSats: List<SatInfo> = emptyList()
    private var remoteFrom = ""
    private var remoteAt = 0L

    override fun onRemoteSatellites(list: List<SatInfo>, from: String) {
        remoteSats = list
        remoteFrom = from
        remoteAt = SystemClock.elapsedRealtime()
        renderSatellites()
    }

    private fun onSatellites(list: List<SatInfo>) {
        localSats = list
        renderSatellites()
    }

    private fun renderSatellites() {
        val st = LinkBus.status
        val remoteFresh = remoteSats.isNotEmpty() &&
                SystemClock.elapsedRealtime() - remoteAt < 6000
        val fromRemote = st.role == Role.RECEIVER && remoteFresh
        val list = if (fromRemote) remoteSats else localSats

        satView.setSatellites(list)

        if (list.isEmpty()) {
            tvSatSummary.text = if (st.running && st.role == Role.RECEIVER) {
                "等待发送端的卫星数据…"
            } else {
                "等待卫星数据…请到室外或窗边"
            }
            tvSatLegend.text = ""
            return
        }

        val used = list.count { it.usedInFix }
        val counts = list.groupingBy { Gnss.short(it.constellation) }.eachCount()
        val parts = counts.entries
            .sortedByDescending { it.value }
            .joinToString(" / ") { "${it.key} ${it.value}" }

        val freqs = Gnss.distinctFreqs(list)
        val bandLabel = if (freqs.isEmpty()) "频段未知" else Gnss.bandCountLabel(freqs.size)
        val head = if (fromRemote) "来自 $remoteFrom" else "本机"

        tvSatSummary.text = "$head · 可见 ${list.size} · 定位 $used · $bandLabel\n$parts"

        tvSatLegend.text = if (freqs.isEmpty()) {
            "本机未上报载波频率（Android 11 以下不支持）"
        } else {
            freqs.joinToString("  +  ") {
                String.format(Locale.US, "%s %.2f MHz", Gnss.band(it), it / 1e6f)
            }
        }
    }

    // ---------------------------------------------------------------- 传输方式 / 蓝牙

    private fun applyModeUi() {
        tilPort.visibility = if (useBt) View.GONE else View.VISIBLE
        tilHost.visibility = if (useBt) View.GONE else View.VISIBLE
        rowBt.visibility = if (useBt) View.VISIBLE else View.GONE
        refreshBtLabel()
    }

    @SuppressLint("MissingPermission")
    private fun refreshBtLabel() {
        if (btMac.isBlank()) {
            tvBtDevice.text = getString(R.string.bt_none)
            return
        }
        val name = try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.getRemoteDevice(btMac)?.name
        } catch (_: Throwable) {
            null
        }
        tvBtDevice.text = name ?: btMac
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
                refreshBtLabel()
                toast("已选择 ${d.name ?: d.address}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 采样上限检测

    private fun runProbe() {
        if (LinkBus.status.running) {
            toast("请先停止再检测")
            return
        }
        withPermission {
            btnProbe.isEnabled = false
            tvProbeResult.text = "检测中…保持室外或窗边，约 10 秒"
            rateProbe.start { r ->
                btnProbe.isEnabled = true
                tvProbeResult.text = if (r.hz > 0) {
                    "${r.note}（${r.samples} 个样本）"
                } else {
                    r.note
                }
            }
        }
    }

    /** 四项前置条件：开发者选项 / 定位权限 / 模拟位置应用 / 电池优化 */
    private fun refreshChecks() {
        val dev = engine.isDeveloperOptionsEnabled()
        val perm = hasLocationPermission()
        val mock = engine.isSelectedAsMockApp()
        val battery = isIgnoringBattery()

        setCheck(tvCheckDev, "开发者选项", dev, "已开启", "未开启")
        setCheck(tvCheckPerm, "定位权限", perm, "已授予", "未授予")
        setCheck(tvCheckMock, "模拟位置应用（接收端需要）", mock, "已选中", "未选中")
        setCheck(
            tvCheckBattery, "电池优化", battery,
            "已忽略，可长期后台运行",
            "未忽略，息屏可能被系统杀掉（点击加入白名单）"
        )
        tvCheckBattery.setOnClickListener {
            if (!isIgnoringBattery()) requestIgnoreBattery()
        }
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

    private fun setCheck(tv: TextView, label: String, ok: Boolean, okText: String, badText: String) {
        tv.text = "● $label：${if (ok) okText else badText}"
        tv.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ok else R.color.error)
        )
    }

    override fun onDestroy() {
        LinkBus.remove(this)
        satMonitor.stop()
        rateProbe.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 启动

    private fun startSend() {
        if (useBt && btMac.isBlank()) {
            toast("请先选择蓝牙接收端")
            return
        }
        val sid = sid()
        val port = port()
        val host = etHost.text?.toString()?.trim().orEmpty()
        etSid.setText(sid)
        etPort.setText(port.toString())
        SendService.start(this, host, port, sid, intervalMs(), useBt, btMac)
        toast(if (useBt) "发送端已启动（蓝牙）" else "发送端已启动（局域网）")
    }

    private fun intervalOf(id: Int): Int = when (id) {
        R.id.btn_rate_2 -> 500
        R.id.btn_rate_5 -> 200
        R.id.btn_rate_10 -> 100
        else -> 1000
    }

    private fun intervalMs(): Int = intervalOf(groupRate.checkedButtonId)

    private fun injectIntervalOf(id: Int): Int = when (id) {
        R.id.btn_inject_1 -> 1000
        R.id.btn_inject_5 -> 200
        else -> 100
    }

    private fun injectIntervalMs(): Int = injectIntervalOf(groupInject.checkedButtonId)

    private fun startReceive() {
        if (!engine.isSelectedAsMockApp()) {
            refreshChecks()
            toast("请先在开发者选项里把本应用设为模拟位置应用")
            return
        }
        val sid = sid()
        val port = port()
        etSid.setText(sid)
        etPort.setText(port.toString())
        ReceiveService.start(this, port, sid, injectIntervalMs(), useBt)
        toast(if (useBt) "接收端已启动（蓝牙）" else "接收端已启动（局域网）")
    }

    private fun sid(): String =
        etSid.text?.toString()?.trim().orEmpty().ifBlank { "8888" }

    private fun port(): Int =
        etPort.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it in 1024..65535 } ?: Protocol.DEFAULT_PORT

    // ---------------------------------------------------------------- 权限

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

    // ---------------------------------------------------------------- LinkBus

    override fun onStatus(status: LinkStatus) {
        val roleText = when (status.role) {
            Role.SENDER -> "发送端"
            Role.RECEIVER -> "接收端"
            Role.NONE -> "未运行"
        }
        tvStatus.text = if (status.running) "● $roleText 运行中" else getString(R.string.status_idle)

        val lines = mutableListOf<String>()
        if (status.localIp.isNotBlank()) lines += "本机 IP：${status.localIp}"
        lines += when {
            status.peer.isBlank() -> "对方：等待中…"
            status.online -> "对方：${status.peer}（在线）"
            else -> "对方：${status.peer}（无响应）"
        }
        if (status.running) {
            if (status.role == Role.SENDER) {
                lines += "已发 ${status.txCount} 包 / 已收 ${status.rxCount} 包"
            } else {
                lines += "已收 ${status.rxCount} 包"
            }
            if (status.role == Role.RECEIVER) {
                lines += String.format(
                    Locale.US, "接收：%.0f 帧/秒 · 注入 %d Hz",
                    status.rateHz, 1000 / injectIntervalMs()
                )
            } else {
                lines += String.format(Locale.US, "发送：%.0f 帧/秒", status.rateHz)
            }
            if (status.role == Role.SENDER && status.rttMs >= 0) {
                lines += "往返延迟：${status.rttMs} ms"
            }
            if (status.role == Role.RECEIVER) {
                lines += String.format(Locale.US, "丢包：%.1f%%", status.lossPercent)
            }
        }
        tvDetail.text = lines.joinToString("\n")

        btnSend.isEnabled = !status.running
        btnReceive.isEnabled = !status.running
        btnStop.isEnabled = status.running

        updateDetail(status)
        updateVerify(status)
    }

    /** 定位数据卡片：精度 / 高度 / 速度 / 方向 / 源点年龄 */
    private fun updateDetail(status: LinkStatus) {
        val show = status.running && status.fixCount > 0
        cardDetail.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        tvDLatLon.text = String.format(Locale.US, "%.6f, %.6f", status.lat, status.lon)

        tvDAccuracy.text = if (status.accuracy > 0f) {
            String.format(Locale.US, "%.1f m", status.accuracy)
        } else "—"

        tvDAltitude.text = String.format(Locale.US, "%.1f m", status.altitude)

        tvDSpeed.text = String.format(
            Locale.US, "%.2f m/s（%.1f km/h）", status.speed, status.speed * 3.6f
        )

        tvDBearing.text = if (status.bearing >= 0f) {
            String.format(Locale.US, "%.0f° %s", status.bearing, compass(status.bearing))
        } else "—"

        tvDAge.text = when {
            status.fixAgeMs < 0 -> "—"
            status.fixAgeMs > 3000 -> String.format(
                Locale.US, "%.1f s（数据偏旧）", status.fixAgeMs / 1000.0
            )
            else -> String.format(Locale.US, "%.1f s", status.fixAgeMs / 1000.0)
        }
    }

    private fun compass(b: Float): String {
        val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val norm = ((b % 360f) + 360f) % 360f
        return dirs[(((norm + 22.5f) / 45f).toInt()) % 8]
    }

    /** 定位校验卡片：证明系统确实在对外提供我们注入的坐标 */
    private fun updateVerify(status: LinkStatus) {
        val show = status.running && status.role == Role.RECEIVER && status.fixCount > 0
        cardVerify.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        tvVerifyInjected.text = String.format(
            Locale.US, "注入值    %.6f, %.6f", status.lat, status.lon
        )

        if (status.verifyAvailable) {
            tvVerifyReadback.text = String.format(
                Locale.US, "系统回读  %s · mock=%s · %.6f, %.6f",
                status.verifyProvider,
                if (status.verifyMock) "true" else "false",
                status.verifyLat, status.verifyLon
            )
        } else {
            tvVerifyReadback.text = "系统回读  不可用"
        }

        val okColor = ContextCompat.getColor(this, R.color.ok)
        val warnColor = ContextCompat.getColor(this, R.color.warn)
        val errColor = ContextCompat.getColor(this, R.color.error)

        when {
            !status.verifyAvailable -> {
                tvVerifyResult.text = "结论     系统回读不可用，请检查模拟位置授权"
                tvVerifyResult.setTextColor(errColor)
            }

            status.verifyDeltaM <= 5.0 && status.verifyMock -> {
                tvVerifyResult.text = String.format(
                    Locale.US,
                    "结论     系统正在向所有 App 提供模拟位置（偏差 %.1f m）",
                    status.verifyDeltaM
                )
                tvVerifyResult.setTextColor(okColor)
            }

            status.verifyDeltaM <= 5.0 -> {
                tvVerifyResult.text = "结论     坐标一致，但系统未标记为 mock，部分 App 可能忽略"
                tvVerifyResult.setTextColor(warnColor)
            }

            else -> {
                tvVerifyResult.text = String.format(
                    Locale.US,
                    "结论     回读与注入不一致（偏差 %.1f m），注入可能未生效",
                    status.verifyDeltaM
                )
                tvVerifyResult.setTextColor(errColor)
            }
        }
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 40) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
