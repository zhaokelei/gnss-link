package top.wxip.gpslink.core

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * API 24+ 的卫星状态实现。
 *
 * 单独成类的原因：`GnssStatus` 是 API 24 才引入的，只要这个类被加载就会解析对
 * `GnssStatus` 的引用。把它隔离在这里，低版本（19~23）永远不实例化它，就不会崩。
 */
internal class GnssStatusSource(
    private val context: Context,
    private val lm: LocationManager,
    private val onUpdate: (List<SatInfo>) -> Unit
) : SatelliteMonitor.Source {

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

    override fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lm.registerGnssStatusCallback(context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    override fun stop() {
        try {
            lm.unregisterGnssStatusCallback(callback)
        } catch (_: Throwable) {
        }
    }
}
