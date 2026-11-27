package top.wxip.gpslink.core

import android.content.Context
import android.location.GpsStatus
import android.location.LocationManager
import android.os.Build
import android.util.Log

/** 一颗卫星的可见状态 */
data class SatInfo(
    val svid: Int,
    val constellation: Int,
    /** 载噪比 C/N0，单位 dB-Hz，越大信号越好；40 以上算强 */
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean,
    /** 载波频率（Hz），Android 11 以下拿不到，为 0 */
    val freqHz: Float,
    /**
     * 该卫星的频点数。
     *
     * 老接口和 API 30 以下都拿不到频率，恒为 0（未知）。
     * GnssStatus 每颗卫星只给一个主载波频率，所以在本机侧能判断出
     * 「收到几个不同频点」但无法知道单颗卫星自身是否多频——
     * 这里的值统一按「该卫星有 1 个已知频点 = 1」处理，
     * 整体几频由 [top.wxip.gpslink.core.Gnss.observedBandCount] 统计。
     */
    val bandCount: Int = if (freqHz > 0f) 1 else 0
)

/**
 * 订阅系统 GNSS 状态。
 *
 * 注意：这是**本机** GNSS 的真实状态。接收端跑起来之后系统 GPS provider 被测试
 * provider 顶替，GNSS 引擎通常就不再工作了，此时这里会拿到空列表 —— 那是正常现象。
 *
 * 兼容策略：`GnssStatus` 是 API 24 才有的类，只要在低版本类加载期解析到它就会直接崩。
 * 因此 24+ 的实现被拆到独立的 [GnssStatusSource]，只有运行到 24+ 分支才会被实例化；
 * 19~23 走 [LegacyGpsStatusSource]（`GpsStatus` 老接口）。
 */
class SatelliteMonitor(
    private val context: Context,
    private val onUpdate: (List<SatInfo>) -> Unit
) {

    companion object {
        private const val TAG = "SatelliteMonitor"
    }

    /** 两种实现共用的生命周期接口，避免主流程里出现 `GnssStatus` 类型引用 */
    internal interface Source {
        fun start()
        fun stop()
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var registered = false
    private var source: Source? = null

    fun start() {
        if (registered) return
        try {
            val s: Source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                GnssStatusSource(context, lm, onUpdate)
            } else {
                LegacyGpsStatusSource(lm, onUpdate)
            }
            s.start()
            source = s
            registered = true
        } catch (t: Throwable) {
            Log.w(TAG, "register failed: ${t.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            source?.stop()
        } catch (_: Throwable) {
        }
        source = null
        registered = false
    }
}

/**
 * API 19~23：`LocationManager.addGpsStatusListener` + `GpsStatus.getSatellites()`。
 *
 * 老接口只给得出卫星编号/信噪比/仰角/方位角，拿不到星座类型与载波频率，
 * 因此 `constellation` 统一填 0（CONSTELLATION_UNKNOWN）、`freqHz` 填 0。
 */
@Suppress("DEPRECATION")
internal class LegacyGpsStatusSource(
    private val lm: LocationManager,
    private val onUpdate: (List<SatInfo>) -> Unit
) : SatelliteMonitor.Source {

    private val listener = object : GpsStatus.Listener {
        override fun onGpsStatusChanged(event: Int) {
            if (event != GpsStatus.GPS_EVENT_SATELLITE_STATUS &&
                event != GpsStatus.GPS_EVENT_FIRST_FIX
            ) return

            val status = try {
                lm.getGpsStatus(null)
            } catch (_: Throwable) {
                null
            } ?: return

            val list = ArrayList<SatInfo>()
            for (s in status.satellites) {
                list.add(
                    SatInfo(
                        svid = s.prn,
                        constellation = 0,
                        cn0 = s.snr,
                        elevation = s.elevation,
                        azimuth = s.azimuth,
                        usedInFix = s.usedInFix(),
                        freqHz = 0f
                    )
                )
            }
            // 参与定位的排前面，其次按信号强度降序 —— 和 GPS Test 的排序一致
            list.sortWith(
                compareByDescending<SatInfo> { it.usedInFix }
                    .thenByDescending { it.cn0 }
            )
            onUpdate(list)
        }
    }

    override fun start() {
        lm.addGpsStatusListener(listener)
    }

    override fun stop() {
        try {
            lm.removeGpsStatusListener(listener)
        } catch (_: Throwable) {
        }
    }
}
