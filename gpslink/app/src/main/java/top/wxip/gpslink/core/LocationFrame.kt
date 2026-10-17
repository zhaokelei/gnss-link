package top.wxip.gpslink.core

import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 一帧可跨设备传输的定位数据。
 *
 * 注意坐标系：Android 系统定位输出 **WGS-84**，这里原样透传，绝不做 GCJ-02/BD-09 转换。
 * 接收端注入到 test provider 后，高德/腾讯/百度地图 App 会按各自规则再做偏移，
 * 如果这里先转成 GCJ-02 再灌进去，地图上会稳定偏移 300~700 米。
 */
data class LocationFrame(
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val verticalAccuracy: Float,
    val speedAccuracy: Float,
    val bearingAccuracy: Float,
    val timeMs: Long,
    /**
     * 这一帧生成时，GPS 源点已经"老"了多少毫秒。
     *
     * 接收端直接用 `srcAgeMs + 收到之后过去的时间` 算源点年龄，
     * 完全不依赖两台手机的时钟同步 —— 比直接比较两端时间戳可靠得多。
     */
    val srcAgeMs: Long = 0L
) {

    fun toJson(sid: String, seq: Long): JSONObject = JSONObject().apply {
        put("v", Protocol.VERSION)
        put("t", Protocol.T_LOC)
        put("sid", sid)
        put("seq", seq)
        put("lat", lat.round(7))
        put("lon", lon.round(7))
        put("alt", altitude.round(2))
        put("acc", accuracy.toDouble().round(2))
        put("spd", speed.toDouble().round(2))
        put("brg", bearing.toDouble().round(2))
        put("vacc", verticalAccuracy.toDouble().round(2))
        put("sacc", speedAccuracy.toDouble().round(2))
        put("bacc", bearingAccuracy.toDouble().round(2))
        put("tms", timeMs)
        put("age", srcAgeMs)
    }

    /**
     * 转成可以直接喂给 setTestProviderLocation 的 Location。
     *
     * 有三个字段是硬性要求，缺一个就抛 IllegalArgumentException：
     *  - time != 0
     *  - elapsedRealtimeNanos != 0（必须是本机时钟，不能沿用发送端的时间）
     *  - accuracy > 0
     */
    fun toLocation(provider: String, stats: SatStats? = null): Location = Location(provider).apply {
        latitude = this@LocationFrame.lat
        longitude = this@LocationFrame.lon
        altitude = this@LocationFrame.altitude
        accuracy = this@LocationFrame.accuracy.coerceAtLeast(1f)
        speed = this@LocationFrame.speed.coerceAtLeast(0f)
        bearing = this@LocationFrame.bearing
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        verticalAccuracyMeters = this@LocationFrame.verticalAccuracy.coerceAtLeast(0f)
        speedAccuracyMetersPerSecond = this@LocationFrame.speedAccuracy.coerceAtLeast(0f)
        bearingAccuracyDegrees = this@LocationFrame.bearingAccuracy.coerceAtLeast(0f)

        // AOSP 的 GnssLocationProvider 会给定位结果塞这几个 extras，
        // 读 getExtras() 的应用（GPS Test、部分导航 SDK）就是靠它显示卫星数。
        // 模拟位置默认没有这些字段，我们补上，让下游应用至少能读到"多少颗星"。
        if (stats != null && stats.visible > 0) {
            extras = Bundle().apply {
                putInt("satellites", stats.used)
                putFloat("meanCn0", stats.meanCn0)
                putFloat("maxCn0", stats.maxCn0)
            }
        }
    }

    companion object {

        fun fromLocation(loc: Location): LocationFrame = LocationFrame(
            lat = loc.latitude,
            lon = loc.longitude,
            altitude = if (loc.hasAltitude()) loc.altitude else 0.0,
            accuracy = if (loc.hasAccuracy()) loc.accuracy else 5f,
            speed = if (loc.hasSpeed()) loc.speed else 0f,
            bearing = if (loc.hasBearing()) loc.bearing else 0f,
            verticalAccuracy = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else 0f,
            speedAccuracy = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else 0f,
            bearingAccuracy = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees else 0f,
            timeMs = System.currentTimeMillis(),
            srcAgeMs = if (loc.time > 0) {
                (System.currentTimeMillis() - loc.time).coerceIn(0L, 60_000L)
            } else 0L
        )

        fun fromJson(o: JSONObject): LocationFrame? {
            if (!o.has("lat") || !o.has("lon")) return null
            return LocationFrame(
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                altitude = o.optDouble("alt", 0.0),
                accuracy = o.optDouble("acc", 5.0).toFloat(),
                speed = o.optDouble("spd", 0.0).toFloat(),
                bearing = o.optDouble("brg", 0.0).toFloat(),
                verticalAccuracy = o.optDouble("vacc", 0.0).toFloat(),
                speedAccuracy = o.optDouble("sacc", 0.0).toFloat(),
                bearingAccuracy = o.optDouble("bacc", 0.0).toFloat(),
                timeMs = o.optLong("tms", System.currentTimeMillis()),
                srcAgeMs = o.optLong("age", 0L)
            )
        }
    }
}

/** 保留 digits 位小数，减小 JSON 体积 */
private fun Double.round(digits: Int): Double {
    val f = 10.0.pow(digits)
    return (this * f).roundToLong() / f
}
