package top.wxip.gpslink.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 探测本机 GPS 实际能跑多快。
 *
 * 做法：以 50ms 的间隔申请定位更新，统计连续两次回调之间
 * `Location.elapsedRealtimeNanos` 的差值，取中位数换算成频率。
 *
 * 为什么不用回调到达时间：主线程可能被别的任务卡住，到达时间会偏大；
 * `elapsedRealtimeNanos` 是这一帧真正被 GPS 芯片打上的时间戳，更准。
 *
 * 注意：这个值受环境影响（室内可能一直没星），只是"实测参考"，不是硬件规格。
 */
class RateProbe(private val context: Context) {

    data class Result(val hz: Double, val samples: Int, val note: String)

    companion object {
        private const val TAG = "RateProbe"
        private const val WANT_SAMPLES = 15
        private const val TIMEOUT_MS = 12_000L
        private const val REQUEST_MS = 50L
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())

    private val deltas = ArrayList<Long>()
    private var lastNanos = 0L
    private var finished = true
    private var callback: ((Result) -> Unit)? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = location.elapsedRealtimeNanos
            if (lastNanos > 0 && now > lastNanos) {
                val d = (now - lastNanos) / 1_000_000L
                // 过滤明显异常的间隔（重复点、暂停后恢复）
                if (d in 20..3000) deltas.add(d)
            }
            lastNanos = now
            if (deltas.size >= WANT_SAMPLES) finish()
        }

        @Deprecated("Deprecated in API 29, 仍需实现以兼容旧系统")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            finishWith("GPS 被关闭，检测中止")
        }
    }

    private val timeout = Runnable {
        if (!finished) finish()
    }

    val isRunning: Boolean
        get() = !finished

    @SuppressLint("MissingPermission")
    fun start(cb: (Result) -> Unit) {
        if (!finished) return
        callback = cb
        deltas.clear()
        lastNanos = 0
        finished = false

        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                finishWith("系统定位开关未打开")
                return
            }
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, REQUEST_MS, 0f, listener, Looper.getMainLooper()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
            finishWith("无法启动检测：${t.message}")
            return
        }

        main.postDelayed(timeout, TIMEOUT_MS)
    }

    fun stop() {
        finished = true
        main.removeCallbacks(timeout)
        try {
            lm.removeUpdates(listener)
        } catch (_: Throwable) {
        }
    }

    private fun finishWith(note: String) {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        try {
            lm.removeUpdates(listener)
        } catch (_: Throwable) {
        }
        callback?.invoke(Result(-1.0, 0, note))
        callback = null
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        try {
            lm.removeUpdates(listener)
        } catch (_: Throwable) {
        }

        val cb = callback
        callback = null
        if (cb == null) return

        if (deltas.size < 3) {
            cb(Result(-1.0, deltas.size, "样本太少，请到室外或窗边重试"))
            return
        }

        val sorted = deltas.sorted()
        val median = sorted[sorted.size / 2]
        val hz = 1000.0 / median.toDouble()

        val note = when {
            hz < 1.3 -> "实测约 1 Hz —— 硬件上限基本就是 1 Hz，调高也没用"
            hz < 2.6 -> "实测约 2 Hz"
            hz < 6.5 -> "实测约 5 Hz"
            else -> String.format(Locale.US, "实测约 %.0f Hz，支持高采样", hz)
        }
        cb(Result(hz, deltas.size, note))
    }
}
