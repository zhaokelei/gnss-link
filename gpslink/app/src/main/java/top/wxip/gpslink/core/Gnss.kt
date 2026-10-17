package top.wxip.gpslink.core

import android.location.GnssStatus
import java.util.Locale

/** 星座 / 频段 的展示规则，仿 GPS Test 的分类方式 */
object Gnss {

    /** 列表里的短标签，控制宽度 */
    fun short(c: Int): String = when (c) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
        GnssStatus.CONSTELLATION_GLONASS -> "GLO"
        GnssStatus.CONSTELLATION_GALILEO -> "GAL"
        GnssStatus.CONSTELLATION_QZSS -> "QZS"
        GnssStatus.CONSTELLATION_SBAS -> "SBS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRN"
        else -> "???"
    }

    fun full(c: Int): String = when (c) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
        else -> "未知"
    }

    /** 卫星编号前缀，G01 / C19 / R07 这样 */
    fun prefix(c: Int): String = when (c) {
        GnssStatus.CONSTELLATION_GPS -> "G"
        GnssStatus.CONSTELLATION_BEIDOU -> "C"
        GnssStatus.CONSTELLATION_GLONASS -> "R"
        GnssStatus.CONSTELLATION_GALILEO -> "E"
        GnssStatus.CONSTELLATION_QZSS -> "J"
        GnssStatus.CONSTELLATION_SBAS -> "S"
        GnssStatus.CONSTELLATION_IRNSS -> "I"
        else -> "?"
    }

    fun color(c: Int): Int = when (c) {
        GnssStatus.CONSTELLATION_GPS -> 0xFF2F6FED.toInt()
        GnssStatus.CONSTELLATION_BEIDOU -> 0xFFE24B4A.toInt()
        GnssStatus.CONSTELLATION_GLONASS -> 0xFF639922.toInt()
        GnssStatus.CONSTELLATION_GALILEO -> 0xFF7F77DD.toInt()
        GnssStatus.CONSTELLATION_QZSS -> 0xFF1D9E75.toInt()
        GnssStatus.CONSTELLATION_SBAS -> 0xFF888780.toInt()
        GnssStatus.CONSTELLATION_IRNSS -> 0xFFBA7517.toInt()
        else -> 0xFF5B6472.toInt()
    }

    /**
     * 由载波频率反推频段名。
     *
     * Android 11（API 30）起 GnssStatus 才会给出每颗卫星的载波频率，
     * 之前拿不到，只能显示 "—"。
     */
    fun band(freqHz: Float): String = when {
        freqHz <= 0f -> "—"
        near(freqHz, 1575.42e6) -> "L1"
        near(freqHz, 1176.45e6) -> "L5"
        near(freqHz, 1227.60e6) -> "L2"
        near(freqHz, 1561.098e6) -> "B1I"
        near(freqHz, 1207.14e6) -> "B2I"
        near(freqHz, 1602.0e6) -> "G1"
        near(freqHz, 1246.0e6) -> "G2"
        else -> String.format(Locale.US, "%.0fM", freqHz / 1e6)
    }

    private fun near(v: Float, target: Double, tol: Double = 3.0e6): Boolean =
        Math.abs(v - target) < tol

    /**
     * 把当前实际收到的频点去重（按 0.1 MHz 粒度）。
     *
     * 返回的是频率值列表，比如 [1.17645E9, 1.57542E9]。
     * 数量决定"单频/双频/三频/四频"。
     */
    fun distinctFreqs(list: List<SatInfo>): List<Float> =
        list.filter { it.freqHz > 0f }
            .map { (Math.round(it.freqHz / 1e5) * 1e5).toFloat() }
            .distinct()
            .sorted()

    /** 频段数量描述 */
    fun bandCountLabel(n: Int): String = when (n) {
        0 -> "频段未知"
        1 -> "单频"
        2 -> "双频"
        3 -> "三频"
        4 -> "四频"
        else -> "${n} 频"
    }
}
