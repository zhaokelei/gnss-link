package top.wxip.gpslink.core

import android.location.GnssStatus
import java.util.Locale

/** 星座 / 频段 的展示规则，仿 GPS Test 的分类方式 */
object Gnss {

    /**
     * 星座常量。
     *
     * 直接写数值而不是引用 GnssStatus.CONSTELLATION_*，有两个原因：
     * 1. CONSTELLATION_NAVIC 是 API 31 才加的，低版本类加载期引用会崩；
     * 2. 这些常量本身就是公开稳定的协议值（与 NMEA / RINEX 一致）。
     *
     * 取值来源：android.location.GnssStatus 的常量定义。
     */
    private const val C_UNKNOWN = 0
    private const val C_GPS = 1
    private const val C_SBAS = 2
    private const val C_GLONASS = 3
    private const val C_QZSS = 4
    private const val C_BEIDOU = 5
    private const val C_GALILEO = 6
    private const val C_IRNSS = 7
    /** API 31 起 IRNSS 的新名字，数值与 IRNSS 相同，保留以便未来区分 */
    private const val C_NAVIC = 8

    /** 列表里的短标签，控制宽度（超过 3 字符会挤掉信号条） */
    fun short(c: Int): String = when (c) {
        C_GPS -> "GPS"
        C_BEIDOU -> "北斗"
        C_GLONASS -> "GLO"
        C_GALILEO -> "GAL"
        C_QZSS -> "QZS"
        C_SBAS -> "SBS"
        C_IRNSS -> "IRN"
        C_NAVIC -> "NAV"
        // 老接口（API 19~23）拿不到星座类型，统一按未知处理；
        // 显示「卫星」而不是「???」，避免用户以为出了故障
        else -> "卫星"
    }

    fun full(c: Int): String = when (c) {
        C_GPS -> "GPS"
        C_BEIDOU -> "北斗"
        C_GLONASS -> "GLONASS"
        C_GALILEO -> "Galileo"
        C_QZSS -> "QZSS"
        C_SBAS -> "SBAS"
        C_IRNSS -> "NavIC (IRNSS)"
        C_NAVIC -> "NavIC"
        else -> "未知星座"
    }

    /** 卫星编号前缀，G01 / C19 / R07 这样；未知星座不给前缀，直接显示编号 */
    fun prefix(c: Int): String = when (c) {
        C_GPS -> "G"
        C_BEIDOU -> "C"
        C_GLONASS -> "R"
        C_GALILEO -> "E"
        C_QZSS -> "J"
        C_SBAS -> "S"
        C_IRNSS -> "I"
        C_NAVIC -> "I"
        else -> ""
    }

    fun color(c: Int): Int = when (c) {
        C_GPS -> 0xFF2F6FED.toInt()
        C_BEIDOU -> 0xFFE24B4A.toInt()
        C_GLONASS -> 0xFF639922.toInt()
        C_GALILEO -> 0xFF7F77DD.toInt()
        C_QZSS -> 0xFF1D9E75.toInt()
        C_SBAS -> 0xFF888780.toInt()
        C_IRNSS -> 0xFFBA7517.toInt()
        C_NAVIC -> 0xFFBA7517.toInt()
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

    /**
     * 从「实际收到的卫星」推算当前观测量属于几频。
     *
     * 注意这不是问手机硬件支持几频，而是问此刻空中的卫星信号覆盖了几个频段。
     * 统计口径：把所有可见卫星携带的频点去重，数量即几频。
     * 一颗卫星都没拿到频点（API 30 以下）时返回 0，界面显示「频段未知」。
     */
    fun observedBandCount(list: List<SatInfo>): Int = distinctFreqs(list).size

    /** 频点对应的频段名列表，用于在界面上列出具体是哪几个频段 */
    fun observedBands(list: List<SatInfo>): List<String> =
        distinctFreqs(list).map { band(it) }
}
