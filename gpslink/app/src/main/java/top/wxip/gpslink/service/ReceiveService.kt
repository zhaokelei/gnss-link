package top.wxip.gpslink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import top.wxip.gpslink.MainActivity
import top.wxip.gpslink.R
import top.wxip.gpslink.core.Geo
import top.wxip.gpslink.core.LinkBus
import top.wxip.gpslink.core.LocationFrame
import top.wxip.gpslink.core.NetInfo
import top.wxip.gpslink.core.Prefs
import top.wxip.gpslink.core.PresetPlace
import top.wxip.gpslink.core.Presets
import top.wxip.gpslink.core.Protocol
import top.wxip.gpslink.core.Role
import top.wxip.gpslink.core.SatStats
import top.wxip.gpslink.core.SatWire
import top.wxip.gpslink.core.TrackRecorder
import top.wxip.gpslink.mock.MockLocationEngine
import top.wxip.gpslink.transport.BtReceiver
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 接收端（手机 B）：监听 UDP，把收到的坐标注入为模拟位置。
 *
 * 设计要点：
 *  - 跑在前台服务里，息屏 / 切后台都不会断；
 *  - 校验房间码，防止同网段其他设备误注入；
 *  - 同时顶替 gps 与 network 两个 provider，兼容只读 network 的设备；
 *  - 统计丢包与刷新率，方便判断链路质量。
 */
class ReceiveService : Service() {

    companion object {
        private const val TAG = "ReceiveService"
        private const val CHANNEL_ID = "gpslink_recv"
        private const val NOTIF_ID = 1002

        const val ACTION_START = "top.wxip.gpslink.action.START_RECV"
        const val ACTION_SET_INJECT = "top.wxip.gpslink.action.SET_INJECT"
        const val ACTION_PLAY = "top.wxip.gpslink.action.PLAY_TRACK"
        const val ACTION_FIXED = "top.wxip.gpslink.action.FIXED_POINT"
        const val ACTION_CLEAR_FIXED = "top.wxip.gpslink.action.CLEAR_FIXED_POINT"
        const val EXTRA_PORT = "port"
        const val EXTRA_SID = "sid"
        const val EXTRA_INJECT = "inject"
        const val EXTRA_USE_BT = "use_bt"
        const val EXTRA_TRACK = "track_path"
        const val EXTRA_FIXED_KEY = "fixed_key"

        /**
         * 回放一条轨迹：不绑端口、不连蓝牙，只按记录时的节奏把帧重新注入系统。
         * 适合在没有任何发送端的情况下验证注入链路与地图表现。
         */
        fun playTrack(ctx: Context, path: String) {
            val i = Intent(ctx, ReceiveService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_TRACK, path)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /**
         * 持续注入一个固定坐标。
         *
         * 用途是自检：打开任意导航 App，若蓝点停在这个位置，就说明
         * 「模拟位置授权 → 测试 provider → 系统定位」整条链路是通的。
         * 不依赖发送端，也不需要先连上对端。
         */
        fun injectFixed(ctx: Context, key: String) {
            val i = Intent(ctx, ReceiveService::class.java).apply {
                action = ACTION_FIXED
                putExtra(EXTRA_FIXED_KEY, key)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /**
         * 取消固定点，回到「只注入发送端坐标」。
         *
         * 只在服务已经运行时才发这条指令：服务本来没起时既没有固定点可撤销，
         * 也没必要为了一次取消把它拉起来。
         */
        fun clearFixed(ctx: Context) {
            val i = Intent(ctx, ReceiveService::class.java).apply { action = ACTION_CLEAR_FIXED }
            try {
                ctx.startService(i)
            } catch (_: Throwable) {
            }
        }

        fun start(ctx: Context, port: Int, sid: String, injectMs: Int, useBt: Boolean) {
            val i = Intent(ctx, ReceiveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_SID, sid)
                putExtra(EXTRA_INJECT, injectMs)
                putExtra(EXTRA_USE_BT, useBt)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /** 运行中切换注入频率，不用重启服务 */
        fun setInjectInterval(ctx: Context, injectMs: Int) {
            val i = Intent(ctx, ReceiveService::class.java).apply {
                action = ACTION_SET_INJECT
                putExtra(EXTRA_INJECT, injectMs)
            }
            try {
                ctx.startService(i)
            } catch (_: Throwable) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ReceiveService::class.java))
        }
    }

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private var port = Protocol.DEFAULT_PORT
    private var sid = "8888"

    /** 传输方式：true=蓝牙 RFCOMM，false=局域网 UDP */
    private var useBt = false
    private var bt: BtReceiver? = null

    private val engine by lazy { MockLocationEngine(this) }

    private var expectedSeq = -1L
    private var received = 0L
    private var lost = 0L
    private var windowCount = 0

    @Volatile
    private var lastPeerAt = 0L

    private var lastPeerAddr: String = ""
    private var lastError = ""
    private var lastNotifAt = 0L

    /** 发送端地址，收到 WHO / LOC 后记住，用来主动发心跳 */
    @Volatile
    private var senderAddr: InetAddress? = null
    private var senderPort = 0

    private var whoCount = 0L
    private var startedAt = 0L
    private var warnedWho = false
    private var warnedSilent = false

    /** 最近一帧注入值，用来和系统回读值比对 */
    private var lastFrame: LocationFrame? = null

    /** 收到该帧时的本机 elapsedRealtime，用来算源点年龄（不依赖两端时钟同步） */
    private var lastFrameElapsed = 0L

    /** 最近一次真正注入系统的位置（可能是外推出来的，不一定等于 lastFrame） */
    private var lastInjected: LocationFrame? = null

    /** 注入间隔（毫秒）：1000=只按收到的帧注入，200/100=两次帧之间做外推补点 */
    private var injectIntervalMs = Protocol.DEFAULT_INJECT_MS

    /** 发送端同步过来的卫星统计，会挂到注入的 Location.extras 上 */
    @Volatile
    private var satStats: SatStats = SatStats.EMPTY

    /** 换帧时的残差，400ms 内线性衰减到 0，避免每秒"跳一下" */
    private var offsetLat = 0.0
    private var offsetLon = 0.0
    private var offsetSetAt = 0L

    /**
     * 固定点自检：非空时这一个点会持续注入，期间**暂停接收**发送端的数据。
     *
     * socket / 蓝牙连接本身不断（心跳照发，避免发送端误判离线），
     * 但发送端发来的定位帧与卫星快照一律丢弃：不计帧、不注入、不刷坐标。
     * 取消固定点后立刻恢复按发送端坐标注入，不需要重连。
     */
    private var fixedPlace: PresetPlace? = null
    private var fixedTicker: Runnable? = null

    /** 纯自检（没有接收会话）时用的回读定时器，start() 里的秒级 ticker 此时不跑 */
    private var verifyTicker: Runnable? = null

    /** 主线程定时器：固定点注入与取消都要能跨动作回调，不能每次新建 Handler */
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            startAsForeground()
            startPlayback(intent.getStringExtra(EXTRA_TRACK).orEmpty())
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_FIXED) {
            startAsForeground()
            startFixed(intent.getStringExtra(EXTRA_FIXED_KEY).orEmpty())
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CLEAR_FIXED) {
            clearFixed()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SET_INJECT) {
            injectIntervalMs = intent.getIntExtra(EXTRA_INJECT, Protocol.DEFAULT_INJECT_MS)
                .coerceIn(50, 2000)
            Prefs.saveInject(this, injectIntervalMs)
            if (running) {
                LinkBus.log("注入频率调整为 ${1000 / injectIntervalMs} Hz")
            } else {
                stopSelf()
            }
            return START_STICKY
        }

        if (intent != null && intent.action == ACTION_START) {
            port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
            sid = intent.getStringExtra(EXTRA_SID) ?: "8888"
            injectIntervalMs = intent.getIntExtra(EXTRA_INJECT, Protocol.DEFAULT_INJECT_MS)
                .coerceIn(50, 2000)
            useBt = intent.getBooleanExtra(EXTRA_USE_BT, false)
            Prefs.saveReceive(this, port, sid)
            Prefs.saveInject(this, injectIntervalMs)
        } else {
            val sp = Prefs.loadSend(this)
            port = sp.port
            sid = sp.sid
            injectIntervalMs = Prefs.loadInject(this)
            useBt = Prefs.loadUseBt(this)
        }

        startAsForeground()

        if (!running) {
            // 先把模拟位置准备好，失败就直接停，不浪费用户时间
            try {
                engine.prepare()
            } catch (t: Throwable) {
                LinkBus.log(t.message ?: "模拟位置初始化失败")
                LinkBus.update { it.copy(running = false, role = Role.NONE) }
                stopSelf()
                return START_NOT_STICKY
            }
            start()
        } else if (socket == null && bt == null) {
            // 正在做固定点自检（running 为真但没有任何传输通道）：
            // 用户此时点「开始接收」，要把真正的接收会话补起来，而不是当作已在运行直接返回。
            try {
                engine.prepare()
            } catch (t: Throwable) {
                LinkBus.log(t.message ?: "模拟位置初始化失败")
                stopSelf()
                return START_NOT_STICKY
            }
            start()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        LinkBus.log("任务被移除，服务继续运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 保活

    /**
     * 接收端不申请定位更新，没有 GNSS 引擎帮它顶着唤醒锁；
     * 一旦息屏后 CPU 挂起，10Hz 的注入定时器就会被拖慢甚至停摆。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpslink:recv").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            LinkBus.log("获取唤醒锁失败：${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    // ---------------------------------------------------------------- 生命周期

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("等待发送端…"), type
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "定位共享（接收端）", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持模拟位置持续注入"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("GNSS Link · 接收端")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }

    // ---------------------------------------------------------------- 主流程

    /**
     * 轨迹回放。
     *
     * 按记录时的相对时间偏移调度注入，节奏与真实采集一致；
     * 不绑端口、不起网络线程，因此不依赖发送端，也不需要同网段。
     */
    private fun startPlayback(path: String) {
        val file = File(path)
        val frames = TrackRecorder.read(file)
        if (frames.isEmpty()) {
            LinkBus.log("轨迹为空或无法读取：${file.name}")
            LinkBus.update { it.copy(running = false, role = Role.NONE) }
            stopSelf()
            return
        }
        try {
            engine.prepare()
        } catch (t: Throwable) {
            LinkBus.log(t.message ?: "模拟位置初始化失败")
            LinkBus.update { it.copy(running = false, role = Role.NONE) }
            stopSelf()
            return
        }

        running = true
        acquireWakeLock()
        val totalMs = frames.last().first
        LinkBus.update {
            it.copy(
                running = true, role = Role.RECEIVER, peer = "轨迹回放",
                online = true, localIp = "", rxCount = 0, txCount = 0,
                lossPercent = 0f
            )
        }
        LinkBus.log("轨迹回放：${file.name}，${frames.size} 帧，${totalMs / 1000} 秒")

        val h = Handler(Looper.getMainLooper())
        val began = SystemClock.elapsedRealtime()
        var index = 0

        val step = object : Runnable {
            override fun run() {
                if (!running) return
                val elapsed = SystemClock.elapsedRealtime() - began
                var latest: LocationFrame? = null
                while (index < frames.size && frames[index].first <= elapsed) {
                    latest = frames[index].second
                    index++
                }
                if (latest != null) {
                    lastFrame = latest
                    lastFrameElapsed = SystemClock.elapsedRealtime()
                    injectNow(latest)
                    LinkBus.update {
                        it.copy(
                            lat = latest.lat, lon = latest.lon,
                            accuracy = latest.accuracy, altitude = latest.altitude,
                            speed = latest.speed, bearing = latest.bearing,
                            fixCount = it.fixCount + 1, fixAgeMs = 0L
                        )
                    }
                }
                if (index >= frames.size) {
                    // 留 1.5 秒让最后一帧被系统消化，再撤销 provider
                    h.postDelayed({
                        if (running) {
                            LinkBus.log("轨迹回放结束")
                            stopAll()
                            stopSelf()
                        }
                    }, 1500)
                    return
                }
                h.postDelayed(this, 100)
            }
        }
        h.postDelayed(step, 50)
    }

    /**
     * 持续注入一个固定坐标。
     *
     * 和轨迹回放的区别：只有一个点，无限循环，直到用户手动取消。
     * 接收端已在跑时，注入期间**暂停接收**发送端数据：链路保持（心跳照发），
     * 但 LOC/SAT 一律丢弃，不再计帧、不再刷坐标；取消后立刻恢复接收。
     */
    private fun startFixed(key: String) {
        val place = Presets.find(key)
        if (place == null) {
            LinkBus.log("未知的预设地点：$key")
            if (!running) {
                LinkBus.update { it.copy(running = false, role = Role.NONE) }
                stopSelf()
            }
            return
        }
        try {
            engine.prepare()
        } catch (t: Throwable) {
            LinkBus.log(t.message ?: "模拟位置初始化失败")
            if (!running) {
                LinkBus.update { it.copy(running = false, role = Role.NONE) }
                stopSelf()
            }
            return
        }

        // 换点时不重启服务，只替换目标，避免接收端被连带断开
        fixedTicker?.let { handler.removeCallbacks(it) }
        fixedPlace = place

        if (!running) {
            // 没有接收会话：就是纯自检，不开 socket
            running = true
            startedAt = System.currentTimeMillis()
            acquireWakeLock()
        }
        val paused = socket != null || bt != null
        if (paused) resetReceiveStats()
        LinkBus.update {
            it.copy(
                running = true, role = Role.RECEIVER,
                peer = place.name, online = true,
                lat = place.lat, lon = place.lon, accuracy = 5f,
                fixedName = place.name, fixedOverride = paused,
                rateHz = if (paused) 0f else it.rateHz, lossPercent = 0f
            )
        }
        LinkBus.log(
            if (paused)
                "固定点注入：${place.province} · ${place.name}（已暂停接收发送端数据，取消后恢复）"
            else
                "固定位置注入：${place.province} · ${place.name}"
        )
        updateNotification(
            if (paused) "固定点注入中 · 接收已暂停" else "固定点注入中 · ${place.name}"
        )

        // 没有接收会话时，start() 的秒级 ticker 不存在，验证回读得自己起一个，
        // 否则「系统回读与注入点相差几米」的判定永远不会刷新，界面上永远是「等待回读」。
        if (socket == null && bt == null) startVerifyTicker()

        val step = object : Runnable {
            override fun run() {
                if (!running || fixedPlace !== place) return
                // 每次重新取时间戳：若停在同一个 Location 上太久，
                // 部分系统会判定为过期定位，导航 App 反而不跟了
                val frame = LocationFrame(
                    lat = place.lat,
                    lon = place.lon,
                    altitude = 30.0,
                    accuracy = 5f,
                    speed = 0f,
                    bearing = 0f,
                    verticalAccuracy = 5f,
                    speedAccuracy = 0.5f,
                    bearingAccuracy = 90f,
                    timeMs = System.currentTimeMillis(),
                    srcAgeMs = 0L
                )
                lastFrame = frame
                lastFrameElapsed = SystemClock.elapsedRealtime()
                injectNow(frame)
                LinkBus.update {
                    it.copy(
                        lat = place.lat, lon = place.lon,
                        accuracy = 5f, altitude = 30.0,
                        speed = 0f, bearing = 0f,
                        fixCount = it.fixCount + 1, fixAgeMs = 0L
                    )
                }
                handler.postDelayed(this, injectIntervalMs.toLong())
            }
        }
        fixedTicker = step
        handler.postDelayed(step, 50)
    }

    /**
     * 纯自检模式下的回读刷新。
     *
     * 和接收会话里的 ticker 读的是同一组字段，但没有网络统计与源点年龄。
     * 只做一件事：把系统里真实的定位回读出来和注入点比对，供界面判定注入是否生效。
     */
    private fun startVerifyTicker() {
        verifyTicker?.let { handler.removeCallbacks(it) }
        val step = object : Runnable {
            override fun run() {
                if (!running || fixedPlace == null) return
                val rb = engine.readBack()
                val inj = lastInjected
                val delta = if (rb != null && inj != null) {
                    Geo.distanceMeters(inj.lat, inj.lon, rb.lat, rb.lon)
                } else -1.0
                LinkBus.update {
                    it.copy(
                        verifyAvailable = rb != null && inj != null,
                        verifyMock = rb?.isMock ?: false,
                        verifyProvider = rb?.provider ?: "",
                        verifyLat = rb?.lat ?: 0.0,
                        verifyLon = rb?.lon ?: 0.0,
                        verifyDeltaM = delta
                    )
                }
                handler.postDelayed(this, 1000)
            }
        }
        verifyTicker = step
        handler.postDelayed(step, 1000)
    }

    private fun stopVerifyTicker() {
        verifyTicker?.let { handler.removeCallbacks(it) }
        verifyTicker = null
    }

    /** 取消固定点注入；接收端若还在跑就恢复接收发送端坐标，否则整体停下 */
    private fun clearFixed() {
        fixedTicker?.let { handler.removeCallbacks(it) }
        fixedTicker = null
        val was = fixedPlace ?: return
        fixedPlace = null
        lastFrame = null
        lastFrameElapsed = 0
        offsetLat = 0.0
        offsetLon = 0.0

        if (socket != null || bt != null) {
            // 恢复接收前清掉暂停期间的统计口子，速率从下一帧重新计
            resetReceiveStats()
            stopVerifyTicker()
            LinkBus.update {
                it.copy(
                    fixedName = "", fixedOverride = false, fixCount = 0, fixAgeMs = 0L,
                    verifyAvailable = false, verifyMock = false, verifyDeltaM = -1.0
                )
            }
            LinkBus.log("已取消固定点（${was.name}），恢复接收发送端坐标")
            updateNotification("等待发送端…")
        } else {
            stopVerifyTicker()
            LinkBus.log("已取消固定点（${was.name}）")
            stopAll()
            stopSelf()
        }
    }

    /** 暂停/恢复接收时把丢包与速率的口子清零，避免把暂停期间的"丢帧"算进去 */
    private fun resetReceiveStats() {
        expectedSeq = -1L
        windowCount = 0
        lost = 0L
        lastFrame = null
        lastFrameElapsed = 0L
        offsetLat = 0.0
        offsetLon = 0.0
        // 恢复后重新给发送端 5 秒窗口，否则第一条超时会立刻判成"发送端已断开"
        lastPeerAt = System.currentTimeMillis()
    }

    private fun start() {
        running = true
        startedAt = System.currentTimeMillis()
        acquireWakeLock()
        // 接收会话自带秒级 ticker（含回读校验），自检用的那个就不需要了，避免双份 LinkBus 更新
        stopVerifyTicker()

        if (useBt) {
            bt = BtReceiver(
                context = this,
                onLine = { line, _ -> handleBtLine(line) },
                onState = { connected, desc ->
                    LinkBus.update { it.copy(peer = desc, online = connected) }
                    LinkBus.log(desc)
                }
            ).also { it.start() }
        } else {
            try {
                socket = DatagramSocket(port).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                }
            } catch (t: Throwable) {
                LinkBus.log("端口 $port 绑定失败：${t.message}")
                running = false
                stopSelf()
                return
            }
        }

        val ip = if (useBt) "" else NetInfo.localIpv4()
        if (fixedPlace != null) resetReceiveStats()
        LinkBus.update {
            it.copy(
                running = true, role = Role.RECEIVER, peer = fixedPlace?.name.orEmpty(),
                online = fixedPlace != null,
                localIp = ip, rxCount = 0, txCount = 0,
                // 自检中点「开始接收」：固定点仍在注入，接收通道虽已建好但暂不处理数据
                fixedOverride = fixedPlace != null
            )
        }
        if (useBt) {
            LinkBus.log("接收端启动（蓝牙）：房间码 $sid，等待发送端连接")
        } else {
            LinkBus.log("接收端启动（局域网）：监听 0.0.0.0:$port，房间码 $sid，本机 $ip")
        }
        if (fixedPlace != null) LinkBus.log("固定点仍在注入，暂停接收发送端数据；取消固定点后自动恢复")

        if (!useBt) {
            thread = Thread({ loop() }, "gps-recv").also {
                it.isDaemon = true
                it.start()
            }
        }

        val h = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                if (!running) return
                val rate = windowCount.toFloat()
                windowCount = 0
                val total = received + lost
                val loss = if (total > 0) lost * 100f / total else 0f
                // 暂停接收期间不按帧判在线：链路心跳还在，发送端并未断开
                val online = fixedPlace != null ||
                    (lastPeerAt > 0 && System.currentTimeMillis() - lastPeerAt < 6000)

                // 从系统读回当前定位，和刚注入的值比对 —— 证明注入真的生效了
                val rb = engine.readBack()
                val inj = lastInjected
                val delta = if (rb != null && inj != null) {
                    Geo.distanceMeters(inj.lat, inj.lon, rb.lat, rb.lon)
                } else -1.0

                // 源点年龄 = 发送端生成该帧时它已经老的时长 + 从收到到现在过去的时间
                val baseFrame = lastFrame
                val ageNow = if (baseFrame != null && lastFrameElapsed > 0) {
                    baseFrame.srcAgeMs + (SystemClock.elapsedRealtime() - lastFrameElapsed)
                } else -1L

                LinkBus.update { cur ->
                    // 界面显示"实际注入的位置"，而不是原始帧 —— 两者可能差几米
                    val base = if (inj != null) cur.copy(
                        lat = inj.lat,
                        lon = inj.lon,
                        accuracy = inj.accuracy,
                        altitude = inj.altitude,
                        speed = inj.speed,
                        bearing = inj.bearing
                    ) else cur

                    base.copy(
                        rateHz = rate,
                        lossPercent = loss,
                        online = online,
                        rxCount = whoCount + received,
                        peer = when {
                            fixedPlace != null -> cur.peer
                            lastPeerAddr.isEmpty() -> cur.peer
                            else -> lastPeerAddr
                        },
                        fixAgeMs = ageNow,
                        verifyAvailable = rb != null && inj != null,
                        verifyMock = rb?.isMock ?: false,
                        verifyProvider = rb?.provider ?: "",
                        verifyLat = rb?.lat ?: 0.0,
                        verifyLon = rb?.lon ?: 0.0,
                        verifyDeltaM = delta
                    )
                }
                h.postDelayed(this, 1000)
            }
        }
        h.postDelayed(ticker, 1000)

        // 主动心跳：即使 LOC 一条都收不到，也让发送端知道接收端还在，别把连接判死
        val beat = object : Runnable {
            override fun run() {
                if (!running) return
                sendToPeer(JSONObject().apply {
                    put("v", Protocol.VERSION)
                    put("t", Protocol.T_BEAT)
                    put("sid", sid)
                })
                h.postDelayed(this, 2000)
            }
        }
        h.postDelayed(beat, 2000)

        // 外推补点：把 1Hz 的输入补成 10Hz 的输出，蓝点才是连续移动的
        val injector = object : Runnable {
            override fun run() {
                if (!running) return
                if (fixedPlace == null && injectIntervalMs < 1000) {
                    currentPosition()?.let { injectNow(it) }
                }
                h.postDelayed(this, injectIntervalMs.toLong().coerceAtLeast(50))
            }
        }
        h.postDelayed(injector, injectIntervalMs.toLong())
    }

    // ---------------------------------------------------------------- 插值注入

    /** 收到新帧：把"上一帧外推到此刻"与真实值的差记成残差，随后 400ms 内衰减掉 */
    private fun onFrame(f: LocationFrame) {
        // 轨迹记录：开关打开时自动开始，逐帧落盘，便于离线复现
        if (Prefs.loadTrackEnabled(this)) {
            if (!TrackRecorder.isRecording) {
                TrackRecorder.start(this)?.let { LinkBus.log("开始记录轨迹：${it.name}") }
            }
            TrackRecorder.appendFrame(f)
        }

        val now = SystemClock.elapsedRealtime()
        val prev = lastFrame
        val prevAt = lastFrameElapsed

        if (prev != null && prevAt > 0 && injectIntervalMs < 1000) {
            val predicted = extrapolate(prev, now - prevAt)
            val jump = Geo.distanceMeters(predicted.lat, predicted.lon, f.lat, f.lon)
            if (jump < 100.0) {
                offsetLat = predicted.lat - f.lat
                offsetLon = predicted.lon - f.lon
                offsetSetAt = now
            } else {
                offsetLat = 0.0
                offsetLon = 0.0
            }
        } else {
            offsetLat = 0.0
            offsetLon = 0.0
        }

        lastFrame = f
        lastFrameElapsed = now
    }

    /** 当前应该注入的位置：基准帧按 speed/bearing 外推，再叠加正在衰减的残差 */
    private fun currentPosition(): LocationFrame? {
        val base = lastFrame ?: return null
        val now = SystemClock.elapsedRealtime()
        val p = extrapolate(base, now - lastFrameElapsed)

        if (offsetLat == 0.0 && offsetLon == 0.0) return p

        val k = ((now - offsetSetAt) / 400.0).coerceIn(0.0, 1.0)
        val decay = 1.0 - k
        if (decay <= 0.0) {
            offsetLat = 0.0
            offsetLon = 0.0
            return p
        }
        return p.copy(
            lat = p.lat + offsetLat * decay,
            lon = p.lon + offsetLon * decay
        )
    }

    /**
     * 沿 speed/bearing 直线外推。
     *
     * 最多外推 1.5 秒：断流时如果一直沿最后方向推，120km/h 的车 3 秒就飘出去 100 米，
     * 地图上会看到蓝点直接飞出道路。宁可停住也不要瞎跑。
     */
    private fun extrapolate(f: LocationFrame, dtMs: Long): LocationFrame {
        val dt = (dtMs / 1000.0).coerceIn(0.0, 1.5)
        if (f.speed < 0.5f || dt <= 0.0) return f
        val d = f.speed * dt
        val rad = Math.toRadians(f.bearing.toDouble())
        val dLat = d * Math.cos(rad) / 111_320.0
        val dLon = d * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(f.lat)))
        return f.copy(
            lat = f.lat + dLat,
            lon = f.lon + dLon,
            srcAgeMs = f.srcAgeMs + dtMs
        )
    }

    private fun injectNow(f: LocationFrame) {
        if (engine.inject(f, satStats)) {
            lastInjected = f
        } else {
            logOnce("注入失败，请检查模拟位置授权是否被系统回收")
        }
    }

    private fun stopAll() {
        running = false
        fixedTicker?.let { handler.removeCallbacks(it) }
        fixedTicker = null
        stopVerifyTicker()
        fixedPlace = null
        releaseWakeLock()
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        thread = null
        bt?.stop()
        bt = null
        try {
            engine.release()
        } catch (_: Throwable) {
        }
        lastFrame = null
        lastInjected = null
        lastFrameElapsed = 0
        offsetLat = 0.0
        offsetLon = 0.0
        if (TrackRecorder.isRecording) {
            LinkBus.log("轨迹已保存：${TrackRecorder.currentFile?.name ?: ""}")
            TrackRecorder.stop()
        }
        LinkBus.update {
            it.copy(
                running = false, role = Role.NONE, peer = "", online = false,
                rateHz = 0f, lossPercent = 0f, lat = 0.0, lon = 0.0, fixCount = 0,
                verifyAvailable = false, verifyDeltaM = -1.0, verifyProvider = ""
            )
        }
        LinkBus.log("接收端已停止，模拟位置已撤销")
    }

    private fun loop() {
        val s = socket ?: return
        val buf = ByteArray(Protocol.MAX_PACKET)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                handle(pkt)
            } catch (e: SocketTimeoutException) {
                checkPeerAlive()
            } catch (t: Throwable) {
                if (running) {
                    logOnce("接收异常：${t.message}")
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }

    private fun handle(pkt: DatagramPacket) {
        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
        val o = parse(text) ?: return
        handleMessage(o, pkt.address, pkt.port) { body -> reply(pkt.address, pkt.port, body) }
    }

    /** 蓝牙链路上来的一行 JSON */
    private fun handleBtLine(text: String) {
        val o = parse(text) ?: return
        handleMessage(o, null, 0) { body ->
            bt?.write(body.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (t: Throwable) {
        null
    }

    /** UDP 与蓝牙共用的报文处理。from 为 null 表示走蓝牙。 */
    private fun handleMessage(
        o: JSONObject,
        from: InetAddress?,
        fromPort: Int,
        respond: (JSONObject) -> Unit
    ) {
        when (o.optString("t")) {
            Protocol.T_WHO -> {
                if (o.optString("sid") != sid) {
                    logOnce("收到广播但房间码不一致（对方 ${o.optString("sid")} / 本机 $sid）")
                    return
                }
                whoCount++
                if (from != null) {
                    senderAddr = from
                    senderPort = fromPort
                }
                if (!warnedWho) {
                    warnedWho = true
                    LinkBus.log("收到发送端广播：${from?.hostAddress ?: "蓝牙"}，已应答")
                }
                LinkBus.update { it.copy(peer = from?.hostAddress ?: it.peer) }
                respond(JSONObject().apply {
                    put("v", Protocol.VERSION)
                    put("t", Protocol.T_HERE)
                    put("sid", sid)
                    put("name", Build.MODEL ?: "android")
                })
            }

            Protocol.T_LOC -> {
                // 固定点注入期间暂停接收：帧直接丢弃，不计帧、不回执、不刷坐标。
                // 丢弃而不是缓存，是为了恢复时立刻用最新一帧，而不是回放积压的旧点。
                if (fixedPlace != null) return
                if (o.optString("sid") != sid) {
                    logOnce("房间码不匹配，已忽略来自 ${from?.hostAddress ?: "蓝牙"} 的数据")
                    return
                }
                val frame = LocationFrame.fromJson(o) ?: return
                onFrame(frame)

                val seq = o.optLong("seq", 0L)
                if (expectedSeq >= 0 && seq > expectedSeq + 1) {
                    lost += seq - expectedSeq - 1
                }
                expectedSeq = seq

                received++
                windowCount++
                lastPeerAt = System.currentTimeMillis()
                if (from != null) {
                    lastPeerAddr = from.hostAddress ?: ""
                    senderAddr = from
                    senderPort = fromPort
                }
                if (received == 1L) {
                    LinkBus.log("收到第一帧定位：${from?.hostAddress ?: "蓝牙"}（seq=$seq）")
                }

                // 收到就立刻注入一次，保证零延迟；两次帧之间的补点由 injector 定时器负责。
                injectNow(frame)

                LinkBus.update {
                    it.copy(
                        peer = if (from != null) lastPeerAddr else it.peer,
                        online = true,
                        lat = frame.lat,
                        lon = frame.lon,
                        accuracy = frame.accuracy,
                        altitude = frame.altitude,
                        speed = frame.speed,
                        bearing = frame.bearing,
                        fixAgeMs = frame.srcAgeMs,
                        fixCount = received,
                        rxCount = whoCount + received
                    )
                }
                // 通知栏 1Hz 刷新太费电，5 秒更新一次就够
                val now = System.currentTimeMillis()
                if (now - lastNotifAt > 5000) {
                    lastNotifAt = now
                    val src = if (from != null) lastPeerAddr else "蓝牙"
                    updateNotification("正在接收 $src · 注入 ${1000 / injectIntervalMs} Hz")
                }

                // 回执里带上发送端自己的时间戳，让发送端用同一时钟算 RTT（避开两台设备的时钟偏差）
                respond(JSONObject().apply {
                    put("v", Protocol.VERSION)
                    put("t", Protocol.T_ACK)
                    put("sid", sid)
                    put("echo", o.optLong("tms", 0L))
                })
            }

            Protocol.T_SAT -> {
                // 同定位帧：暂停接收期间卫星快照也一并丢弃
                if (fixedPlace != null) return
                if (o.optString("sid") != sid) return
                val list = SatWire.decode(o)
                satStats = SatWire.stats(list)
                lastPeerAt = System.currentTimeMillis()
                if (from != null) {
                    senderAddr = from
                    senderPort = fromPort
                }
                LinkBus.emitSatellites(list, from?.hostAddress ?: "蓝牙")
            }
        }
    }

    /** 主动发给发送端（心跳用），蓝牙和 UDP 都能走 */
    private fun sendToPeer(body: JSONObject): Boolean {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        if (useBt) return bt?.write(bytes) == true

        val addr = senderAddr ?: return false
        if (senderPort <= 0) return false
        return try {
            socket?.send(DatagramPacket(bytes, bytes.size, addr, senderPort))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun reply(addr: InetAddress, port: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        try {
            socket?.send(DatagramPacket(bytes, bytes.size, addr, port))
        } catch (_: Throwable) {
        }
    }

    private fun checkPeerAlive() {
        // 固定点注入期间接收已暂停，不能因为"没收到帧"就把发送端判成断开
        if (fixedPlace != null) return
        val now = System.currentTimeMillis()
        if (lastPeerAt > 0 && now - lastPeerAt > 5000) {
            lastPeerAt = 0
            LinkBus.update { it.copy(peer = "", online = false) }
            LinkBus.log("发送端已断开（5 秒没有收到定位帧）")
            updateNotification("等待发送端…")
        }
        // 从头到尾一个包都没收到，基本可以断定是网络层不通，直接给排查提示
        if (!warnedSilent && whoCount == 0L && received == 0L && now - startedAt > 15_000) {
            warnedSilent = true
            LinkBus.log("15 秒内没有收到任何数据包")
            LinkBus.log("→ 两台设备是否连同一个 Wi-Fi？路由器是否开了 AP 隔离？")
            LinkBus.log("→ 可在发送端手动填接收端 IP：${NetInfo.localIpv4()}:$port")
        }
    }

    private fun logOnce(msg: String) {
        if (msg != lastError) {
            lastError = msg
            LinkBus.log(msg)
        }
    }
}
