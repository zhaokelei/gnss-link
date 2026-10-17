package top.wxip.gpslink.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import top.wxip.gpslink.MainActivity
import top.wxip.gpslink.R
import top.wxip.gpslink.core.LinkBus
import top.wxip.gpslink.core.LocationFrame
import top.wxip.gpslink.core.NetInfo
import top.wxip.gpslink.core.Prefs
import top.wxip.gpslink.core.Protocol
import top.wxip.gpslink.core.Role
import top.wxip.gpslink.core.SatInfo
import top.wxip.gpslink.core.SatWire
import top.wxip.gpslink.core.SatelliteMonitor
import top.wxip.gpslink.transport.BtSender
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 发送端（手机 A）：读取本机 GPS，通过 UDP 发给接收端。
 *
 * 为什么必须是前台服务：Android 8 以后后台进程随时会被冻结，
 * 参考项目把逻辑写在 Activity 里，息屏 1~2 分钟位置就停了。
 */
class SendService : Service(), LocationListener {

    companion object {
        private const val TAG = "SendService"
        private const val CHANNEL_ID = "gpslink_send"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "top.wxip.gpslink.action.START_SEND"
        const val ACTION_STOP = "top.wxip.gpslink.action.STOP_SEND"
        const val ACTION_SET_RATE = "top.wxip.gpslink.action.SET_RATE"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SID = "sid"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_USE_BT = "use_bt"
        const val EXTRA_MAC = "mac"

        fun start(
            ctx: Context,
            host: String,
            port: Int,
            sid: String,
            intervalMs: Int,
            useBt: Boolean,
            btMac: String
        ) {
            val i = Intent(ctx, SendService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_SID, sid)
                putExtra(EXTRA_INTERVAL, intervalMs)
                putExtra(EXTRA_USE_BT, useBt)
                putExtra(EXTRA_MAC, btMac)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /** 运行中调整采样频率，不用重启服务（重启会丢掉已配对的接收端） */
        fun setInterval(ctx: Context, intervalMs: Int) {
            val i = Intent(ctx, SendService::class.java).apply {
                action = ACTION_SET_RATE
                putExtra(EXTRA_INTERVAL, intervalMs)
            }
            try {
                ctx.startService(i)
            } catch (_: Throwable) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SendService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private lateinit var lm: LocationManager

    private var sendThread: HandlerThread? = null
    private var sendHandler: Handler? = null
    private var rxThread: Thread? = null

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** 手动指定的接收端 IP；为空则走局域网广播自动发现 */
    private var manualHost: String = ""

    /**
     * 一旦发现过接收端就记住它，不再因为丢几个包就清空。
     * 之前"收不到 ACK 就清 peer → 重新广播 → 又只发 10 秒"的死循环就是这里造成的。
     */
    @Volatile
    private var peer: InetAddress? = null

    /** 最近一次听到对方（HERE / ACK / BEAT）的时间 */
    @Volatile
    private var peerSeenAt = 0L

    private var peerPort: Int = Protocol.DEFAULT_PORT
    private var sid: String = "8888"

    private var seq = 0L
    private var sentInWindow = 0
    private var txCount = 0L
    private var rxCount = 0L
    private var lastLoggedError = ""
    private var warnedNoReply = false
    private var lastGpsFixAt = 0L
    private var lastNotifAt = 0L

    /** 采样间隔（毫秒）：1000=1Hz / 500=2Hz / 200=5Hz / 100=10Hz */
    private var sampleIntervalMs = Protocol.DEFAULT_INTERVAL_MS

    /** 传输方式：true=蓝牙 RFCOMM，false=局域网 UDP */
    private var useBt = false
    private var btMac = ""
    private var bt: BtSender? = null

    /** 本机卫星状态，每 2 秒同步给接收端 */
    private var satMonitor: SatelliteMonitor? = null

    @Volatile
    private var latestSats: List<SatInfo> = emptyList()
    private var lastSatSentAt = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SET_RATE) {
            sampleIntervalMs = intent.getIntExtra(EXTRA_INTERVAL, Protocol.DEFAULT_INTERVAL_MS)
                .coerceIn(100, 5000)
            Prefs.saveInterval(this, sampleIntervalMs)
            if (running) {
                reRegisterLocation()
                LinkBus.log("采样频率调整为 ${1000 / sampleIntervalMs} Hz")
            } else {
                stopSelf()
            }
            return START_STICKY
        }

        if (intent != null && intent.action == ACTION_START) {
            manualHost = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
            peerPort = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
            sid = intent.getStringExtra(EXTRA_SID) ?: "8888"
            sampleIntervalMs = intent.getIntExtra(EXTRA_INTERVAL, Protocol.DEFAULT_INTERVAL_MS)
                .coerceIn(100, 5000)
            useBt = intent.getBooleanExtra(EXTRA_USE_BT, false)
            btMac = intent.getStringExtra(EXTRA_MAC).orEmpty()
            Prefs.saveSend(this, manualHost, peerPort, sid, sampleIntervalMs)
        } else {
            // 被系统回收后重启，intent 为 null，从偏好里恢复
            val cfg = Prefs.loadSend(this)
            manualHost = cfg.host
            peerPort = cfg.port
            sid = cfg.sid
            sampleIntervalMs = cfg.intervalMs
            useBt = Prefs.loadUseBt(this)
            btMac = Prefs.loadBtMac(this)
        }

        startAsForeground()
        if (!running) start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务里划掉时，部分 ROM 会顺手把服务也杀掉。这里只记录，不主动停止。
        LinkBus.log("任务被移除，服务继续运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 保活

    /**
     * 前台服务只能保证进程不被冻结，挡不住系统进入挂起。
     * 息屏后 CPU 一睡，定位回调和发送线程都会停摆，所以必须自己持一个 partial wake lock。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpslink:send").apply {
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
            this, NOTIF_ID, buildNotification("正在共享定位"), type
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "定位共享（发送端）", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 GPS 采集与数据发送"
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
            .setContentTitle("GNSS Link · 发送端")
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

    // ---------------------------------------------------------------- 启动/停止

    private fun start() {
        running = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        acquireWakeLock()

        // 发送队列统一走后台线程：蓝牙的 write 是阻塞调用，放在主线程会 ANR
        sendThread = HandlerThread("gps-send").also { it.start() }
        sendHandler = Handler(sendThread!!.looper)

        if (useBt) {
            if (btMac.isBlank()) {
                LinkBus.log("还没选蓝牙接收端：请先配对，再在「配对设备」里选一台")
                running = false
                stopSelf()
                return
            }
            bt = BtSender(
                context = this,
                mac = btMac,
                onLine = { line -> handleIncoming(line) },
                onState = { connected, desc ->
                    if (connected) peerSeenAt = System.currentTimeMillis()
                    LinkBus.update { it.copy(peer = desc, online = connected) }
                    LinkBus.log(if (connected) "蓝牙已连接：$desc" else desc)
                }
            ).also { it.start() }
        } else {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 800
                }
            } catch (t: Throwable) {
                LinkBus.log("UDP 初始化失败：${t.message}")
                running = false
                stopSelf()
                return
            }

            rxThread = Thread({ receiveLoop() }, "gps-send-rx").also {
                it.isDaemon = true
                it.start()
            }
        }

        LinkBus.update {
            it.copy(
                running = true,
                role = Role.SENDER,
                peer = "",
                online = false,
                localIp = if (useBt) "" else NetInfo.localIpv4(),
                rxCount = 0,
                txCount = 0
            )
        }
        if (useBt) {
            LinkBus.log("发送端启动（蓝牙）：目标 $btMac，房间码 $sid")
        } else {
            LinkBus.log("发送端启动（局域网）：端口 $peerPort，房间码 $sid，本机 ${NetInfo.localIpv4()}")
        }

        main.post { registerLocation() }
        satMonitor = SatelliteMonitor(this) { list -> latestSats = list }.also { it.start() }
        main.post(ticker)
        if (!useBt) main.postDelayed(discovery, 300)
    }

    private fun stopAll() {
        running = false
        releaseWakeLock()
        try {
            if (this::lm.isInitialized) lm.removeUpdates(this)
        } catch (_: Throwable) {
        }
        main.removeCallbacksAndMessages(null)
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        bt?.stop()
        bt = null
        satMonitor?.stop()
        satMonitor = null
        latestSats = emptyList()
        sendThread?.quitSafely()
        sendThread = null
        sendHandler = null
        rxThread = null
        peer = null
        peerSeenAt = 0
        // 清掉坐标，避免停止后界面还挂着上一次的位置，看起来像"还在工作"
        LinkBus.update {
            it.copy(
                running = false, role = Role.NONE, peer = "", online = false,
                rateHz = 0f, rttMs = -1L, lat = 0.0, lon = 0.0, fixCount = 0
            )
        }
        LinkBus.log("发送端已停止")
    }

    @SuppressLint("MissingPermission")
    private fun registerLocation() {
        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            LinkBus.log("系统定位开关未打开，请先开启 GPS")
            return
        }
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, sampleIntervalMs.toLong(), 0f, this, Looper.getMainLooper())
                LinkBus.log("已订阅定位源：$p @ ${1000 / sampleIntervalMs} Hz")
            } catch (t: Throwable) {
                LinkBus.log("订阅 $p 失败：${t.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun reRegisterLocation() {
        try {
            if (this::lm.isInitialized) lm.removeUpdates(this)
        } catch (_: Throwable) {
        }
        registerLocation()
    }

    // ---------------------------------------------------------------- 定位回调

    override fun onLocationChanged(location: Location) {
        if (!running) return

        // 同时订阅了 gps 和 network，两者各自 1Hz，不去重就会每秒发两帧、
        // 精度来回跳，接收端地图上会看到抖动。GPS 有新鲜数据时直接忽略网络定位。
        val now = System.currentTimeMillis()
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixAt = now
        } else if (now - lastGpsFixAt < 5000) {
            return
        }

        val frame = LocationFrame.fromLocation(location)

        LinkBus.update {
            it.copy(
                lat = frame.lat,
                lon = frame.lon,
                accuracy = frame.accuracy,
                altitude = frame.altitude,
                speed = frame.speed,
                bearing = frame.bearing,
                fixAgeMs = frame.srcAgeMs,
                fixCount = it.fixCount + 1
            )
        }

        if (useBt) {
            if (bt?.connected != true) return
        } else if (peer == null) {
            return
        }
        sendBytes(frame.toJson(sid, ++seq).toString().toByteArray(Charsets.UTF_8))
    }

    @Deprecated("Deprecated in API 29, 仍需实现以兼容旧系统")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) {
        LinkBus.log("定位源已开启：$provider")
    }

    override fun onProviderDisabled(provider: String) {
        LinkBus.log("定位源已关闭：$provider")
    }

    // ---------------------------------------------------------------- 网络

    /** 收 ACK / HERE / BEAT，用来测 RTT 和探活。UDP 和蓝牙共用。 */
    private fun handleIncoming(text: String, from: InetAddress? = null) {
        val o = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return
        }

        when (o.optString("t")) {
            Protocol.T_ACK, Protocol.T_BEAT -> {
                rxCount++
                peerSeenAt = System.currentTimeMillis()
                if (peer == null && from != null) peer = from
                val echo = o.optLong("echo", 0L)
                val rtt = if (echo > 0) {
                    (System.currentTimeMillis() - echo).coerceIn(0, 9999)
                } else -1L
                LinkBus.update {
                    it.copy(
                        peer = from?.hostAddress ?: it.peer,
                        online = true,
                        rxCount = rxCount,
                        rttMs = if (rtt >= 0) rtt else it.rttMs
                    )
                }
            }

            Protocol.T_HERE -> {
                rxCount++
                peerSeenAt = System.currentTimeMillis()
                val addr = from ?: return
                val changed = peer?.hostAddress != addr.hostAddress
                peer = addr
                LinkBus.update {
                    it.copy(
                        peer = addr.hostAddress ?: "",
                        online = true,
                        rxCount = rxCount
                    )
                }
                if (changed) {
                    LinkBus.log("已发现接收端：${addr.hostAddress}（${o.optString("name")}）")
                    updateNotification("已连接 ${addr.hostAddress}")
                }
            }

            Protocol.T_BYE -> {
                LinkBus.log("接收端拒绝：${o.optString("msg")}")
            }
        }
    }

    private fun receiveLoop() {
        val s = socket ?: return
        val buf = ByteArray(Protocol.MAX_PACKET)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                handleIncoming(String(pkt.data, 0, pkt.length, Charsets.UTF_8), pkt.address)
            } catch (e: SocketTimeoutException) {
                // 正常，用来定期检查循环条件
            } catch (t: Throwable) {
                if (running) {
                    logOnce("接收线程异常：${t.message}")
                    Thread.sleep(200)
                }
            }
        }
    }

    /** 统一出口：蓝牙走 RFCOMM 写流，局域网走 UDP 单播。两种都放到后台线程。 */
    private fun sendBytes(payload: ByteArray, countRate: Boolean = true) {
        val h = sendHandler ?: return
        h.post {
            if (useBt) {
                if (bt?.send(payload) == true) {
                    if (countRate) sentInWindow++
                    txCount++
                }
                return@post
            }

            val target = peer ?: return@post
            try {
                socket?.send(DatagramPacket(payload, payload.size, target, peerPort))
                if (countRate) sentInWindow++
                txCount++
            } catch (t: Throwable) {
                logOnce("发送失败：${t.message}")
            }
        }
    }

    /** 每秒统计速率、更新在线状态。注意：这里不再清空 peer。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val rate = sentInWindow.toFloat()
            sentInWindow = 0

            val online = if (useBt) {
                bt?.connected == true
            } else {
                peer != null && System.currentTimeMillis() - peerSeenAt < 6000
            }

            // 卫星快照每 2 秒发一次，不跟定位帧同频 —— 它变化慢，没必要每帧都发
            val nowMs = System.currentTimeMillis()
            val linked = if (useBt) bt?.connected == true else peer != null
            if (linked && latestSats.isNotEmpty() && nowMs - lastSatSentAt >= 2000) {
                lastSatSentAt = nowMs
                sendBytes(
                    SatWire.encode(sid, latestSats).toString().toByteArray(Charsets.UTF_8),
                    countRate = false
                )
            }

            if (txCount >= 20 && rxCount == 0L && !warnedNoReply) {
                warnedNoReply = true
                LinkBus.log("已发出 $txCount 个包，但一个回执都没收到")
                if (useBt) {
                    LinkBus.log("→ 接收端可能没有启动，或两端房间码不一致")
                } else {
                    LinkBus.log("→ 检查两台设备是否连同一个 Wi-Fi；路由器是否开了 AP 隔离；端口 $peerPort 是否被占用")
                }
            }

            LinkBus.update {
                it.copy(
                    rateHz = rate,
                    peer = if (useBt) it.peer else (peer?.hostAddress ?: ""),
                    online = online,
                    txCount = txCount
                )
            }

            // 通知栏常驻：让用户一眼看到链路是通的，也是前台服务保活的凭证
            if (nowMs - lastNotifAt > 5000) {
                lastNotifAt = nowMs
                val target = if (useBt) LinkBus.status.peer else (peer?.hostAddress ?: "")
                updateNotification(
                    when {
                        !online -> "等待接收端…"
                        useBt -> "蓝牙 $target · ${rate.toInt()} 帧/秒"
                        else -> "$target · ${rate.toInt()} 帧/秒"
                    }
                )
            }

            main.postDelayed(this, 1000)
        }
    }

    /** 还没连上、或者对方掉线时，周期性广播 WHO 重新找 */
    private val discovery = object : Runnable {
        override fun run() {
            if (!running || useBt) return
            val online = peer != null && System.currentTimeMillis() - peerSeenAt < 6000
            if (peer == null || !online) {
                sendHandler?.post { sendWho() }
            }
            main.postDelayed(this, 3000)
        }
    }

    private fun sendWho() {
        if (useBt) return
        val target = if (manualHost.isNotBlank()) {
            try {
                InetAddress.getByName(manualHost)
            } catch (t: Throwable) {
                logOnce("解析地址失败：$manualHost")
                null
            }
        } else null

        val payload = JSONObject().apply {
            put("v", Protocol.VERSION)
            put("t", Protocol.T_WHO)
            put("sid", sid)
            put("name", Build.MODEL ?: "android")
        }.toString().toByteArray(Charsets.UTF_8)

        val targets = if (target != null) listOf(target) else localBroadcasts()
        for (addr in targets) {
            try {
                socket?.send(DatagramPacket(payload, payload.size, addr, peerPort))
            } catch (t: Throwable) {
                logOnce("广播失败：${t.message}")
            }
        }

        // 手动指定 IP 时，无需等待 HERE 也能直接开始发送
        if (target != null && peer?.hostAddress != target.hostAddress) {
            peer = target
            peerSeenAt = System.currentTimeMillis()
            LinkBus.update { it.copy(peer = target.hostAddress ?: "") }
            LinkBus.log("使用指定接收端：${target.hostAddress}")
        }
    }

    /** 255.255.255.255 + 各网卡的子网广播地址，兼容路由器丢弃定向广播的情况 */
    private fun localBroadcasts(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Throwable) {
        }
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (_: Throwable) {
        }
        return out.toList()
    }

    private fun logOnce(msg: String) {
        if (msg != lastLoggedError) {
            lastLoggedError = msg
            LinkBus.log(msg)
        }
    }
}
