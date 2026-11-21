package top.wxip.gpslink.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * NMEA 0183 语句生成。
 *
 * 只输出两条，这是绝大多数接收端（车机导航、上位机、飞控）需要的全部内容：
 *  - `$GPRMC`：时间、状态、经纬度、对地速度、航向、日期
 *  - `$GPGGA`：时间、经纬度、定位质量、卫星数、HDOP、海拔
 *
 * 两个最容易对接失败的坑：
 *  1. 速度单位是**节**（knots），不是 m/s；
 *  2. 经纬度格式是 `ddmm.mmmm` / `dddmm.mmmm`，不是十进制度。
 */
object Nmea {

    /** 生成这一帧对应的完整 NMEA 数据块（每条语句以 CRLF 结尾） */
    fun bytes(frame: LocationFrame, satsUsed: Int, hdop: Float = 0.9f): ByteArray =
        (rmc(frame) + gga(frame, satsUsed, hdop)).toByteArray(Charsets.US_ASCII)

    private fun rmc(f: LocationFrame): String {
        val body = listOf(
            "GPRMC",
            time(f.timeMs),
            "A",                                  // A = 有效定位
            coordinate(f.lat, 2),
            if (f.lat >= 0) "N" else "S",
            coordinate(f.lon, 3),
            if (f.lon >= 0) "E" else "W",
            knots(f.speed),
            if (f.bearing >= 0) one(f.bearing) else "",
            date(f.timeMs),
            "",                                   // 磁偏角
            "",                                   // 磁偏角方向
            "A"                                   // 定位模式：自主
        ).joinToString(",")
        return sentence(body)
    }

    private fun gga(f: LocationFrame, satsUsed: Int, hdop: Float): String {
        val body = listOf(
            "GPGGA",
            time(f.timeMs),
            coordinate(f.lat, 2),
            if (f.lat >= 0) "N" else "S",
            coordinate(f.lon, 3),
            if (f.lon >= 0) "E" else "W",
            "1",                                  // 1 = 单点定位
            satsUsed.coerceIn(0, 99).toString().padStart(2, '0'),
            one(hdop),
            one(f.altitude),
            "M",                                  // 海拔单位：米
            "0.0",
            "M",                                  // 大地水准面差距
            "",                                   // 差分龄期
            ""                                    // 差分站号
        ).joinToString(",")
        return sentence(body)
    }

    /** 加 `$` 前缀、`*XX` 校验和与 CRLF；校验和是 `$` 与 `*` 之间所有字符的异或 */
    private fun sentence(body: String): String {
        var sum = 0
        for (c in body) sum = sum xor c.code
        return "$" + body + "*" + String.format(Locale.US, "%02X", sum and 0xFF) + "\r\n"
    }

    /** 十进制度 → `ddmm.mmmm`（纬度）或 `dddmm.mmmm`（经度） */
    private fun coordinate(value: Double, degreeDigits: Int): String {
        val v = abs(value)
        val degree = v.toInt()
        val minute = (v - degree) * 60.0
        val degFmt = "%0" + degreeDigits + "d"
        return String.format(Locale.US, degFmt, degree) +
            String.format(Locale.US, "%07.4f", minute)
    }

    /** m/s → 节 */
    private fun knots(metersPerSecond: Float): String =
        String.format(Locale.US, "%.2f", metersPerSecond * 1.943844f)

    private fun one(v: Float): String = String.format(Locale.US, "%.1f", v)
    private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun time(ms: Long): String = format("HHmmss.SS", ms)
    private fun date(ms: Long): String = format("ddMMyy", ms)

    private fun format(pattern: String, ms: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(if (ms > 0) ms else System.currentTimeMillis()))
}
