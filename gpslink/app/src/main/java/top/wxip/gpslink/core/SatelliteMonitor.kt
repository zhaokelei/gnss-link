package top.wxip.gpslink.core

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    val freqHz: Float
)

/**
 * 订阅系统 GNSS 状态。
 *
 * 注意：这是**本机** GNSS 的真实状态。接收端跑起来之后系统 GPS provider 被测试
 * provider 顶替，GNSS 引擎通常就不再工作了，此时这里会拿到空列表 —— 那是正常现象。
 */
class SatelliteMonitor(
    private val context: Context,
    private val onUpdate: (List<SatInfo>) -> Unit
) {

    companion object {
        private const val TAG = "SatelliteMonitor"
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var registered = false

    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = ArrayList<SatInfo>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    status.hasCarrierFrequencyHz(i)
                ) {
                    status.getCarrierFrequencyHz(i)
                } else 0f

                list.add(
                    SatInfo(
                        svid = status.getSvid(i),
                        constellation = status.getConstellationType(i),
                        cn0 = status.getCn0DbHz(i),
                        elevation = status.getElevationDegrees(i),
                        azimuth = status.getAzimuthDegrees(i),
                        usedInFix = status.usedInFix(i),
                        freqHz = freq
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

    fun start() {
        if (registered) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssStatusCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            }
            registered = true
        } catch (t: Throwable) {
            Log.w(TAG, "register failed: ${t.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            lm.unregisterGnssStatusCallback(callback)
        } catch (_: Throwable) {
        }
        registered = false
    }
}
