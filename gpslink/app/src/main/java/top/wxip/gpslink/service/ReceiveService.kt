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
import top.wxip.gpslink.core.Protocol
import top.wxip.gpslink.core.Role
import top.wxip.gpslink.core.SatStats
import top.wxip.gpslink.core.SatWire
import top.wxip.gpslink.mock.MockLocationEngine
import top.wxip.gpslink.transport.BtReceiver
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 接收端（手机 B）：监听 UDP，把收到的坐标注入为模拟位置。
 *
 * 与参考项目的关键差异：
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
        const val EXTRA_PORT = "port"
        const val EXTRA_SID = "sid"
        const val EXTRA_INJECT = "inject"
        const val EXTRA_USE_BT = "use_bt"

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

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    private fun start() {
        running = true
        startedAt = System.currentTimeMillis()
        acquireWakeLock()

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
        LinkBus.update {
            it.copy(
                running = true, role = Role.RECEIVER, peer = "", online = false,
                localIp = ip, rxCount = 0, txCount = 0
            )
        }
        if (useBt) {
            LinkBus.log("接收端启动（蓝牙）：房间码 $sid，等待发送端连接")
        } else {
            LinkBus.log("接收端启动（局域网）：监听 0.0.0.0:$port，房间码 $sid，本机 $ip")
        }

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
                val online = lastPeerAt > 0 && System.currentTimeMillis() - lastPeerAt < 6000

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
                        peer = if (lastPeerAddr.isEmpty()) cur.peer else lastPeerAddr,
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
                if (injectIntervalMs < 1000) {
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

                // 收到就立刻注入一次，保证零延迟；两次帧之间的补点由 injector 定时器负责
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
