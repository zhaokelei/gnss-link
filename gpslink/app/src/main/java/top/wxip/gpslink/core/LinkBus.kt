package top.wxip.gpslink.core

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class Role { NONE, SENDER, RECEIVER }

/** 给界面看的运行状态快照 */
data class LinkStatus(
    val running: Boolean = false,
    val role: Role = Role.NONE,
    val peer: String = "",
    val online: Boolean = false,
    val localIp: String = "",
    val fixCount: Long = 0,
    val rateHz: Float = 0f,
    val rttMs: Long = -1L,
    val lossPercent: Float = 0f,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** 定位详情 */
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = -1f,
    /** 源点年龄：这一帧数据从 GPS 产生到现在过了多久（毫秒），-1 表示未知 */
    val fixAgeMs: Long = -1L,
    /** 已收 / 已发包总数，排障用 */
    val rxCount: Long = 0,
    val txCount: Long = 0,
    /** 定位校验：从系统读回的当前定位 */
    val verifyAvailable: Boolean = false,
    val verifyMock: Boolean = false,
    val verifyProvider: String = "",
    val verifyLat: Double = 0.0,
    val verifyLon: Double = 0.0,
    val verifyDeltaM: Double = -1.0
)

/**
 * 服务与界面之间的极简事件总线。
 *
 * 刻意不用 LiveData / Flow / 广播：这里只有一个进程、一个界面，
 * 用回调 + 主线程 Handler 最少依赖、最少坑。
 */
object LinkBus {

    interface Observer {
        fun onStatus(status: LinkStatus)
        fun onLog(line: String)

        /** 接收端收到发送端发来的卫星快照 */
        fun onRemoteSatellites(list: List<SatInfo>, from: String) = Unit
    }

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile
    var status: LinkStatus = LinkStatus()
        private set

    fun add(o: Observer) {
        observers.addIfAbsent(o)
        val s = status
        main.post { o.onStatus(s) }
    }

    fun remove(o: Observer) {
        observers.remove(o)
    }

    @Synchronized
    fun update(block: (LinkStatus) -> LinkStatus) {
        status = block(status)
        val s = status
        main.post { observers.forEach { it.onStatus(s) } }
    }

    @Synchronized
    fun reset() {
        status = LinkStatus()
        val s = status
        main.post { observers.forEach { it.onStatus(s) } }
    }

    fun log(msg: String) {
        val line = fmt.format(Date()) + "  " + msg
        main.post { observers.forEach { it.onLog(line) } }
    }

    fun emitSatellites(list: List<SatInfo>, from: String) {
        main.post { observers.forEach { it.onRemoteSatellites(list, from) } }
    }
}
