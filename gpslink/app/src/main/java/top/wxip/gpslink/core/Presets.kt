package top.wxip.gpslink.core

import java.util.Locale

/**
 * 预设坐标：用于快速验证「模拟位置是否真的生效」。
 *
 * 用法是把某个点持续注入，然后打开任意导航 App —— 如果它显示的是这个位置，
 * 说明注入链路（开发者选项授权 → 测试 provider → 系统定位）整条是通的。
 *
 * 坐标取各地标志性地点，便于肉眼核对；不追求测绘精度，误差在几十米内无妨。
 */
data class PresetPlace(
    val key: String,
    val province: String,
    val name: String,
    val lat: Double,
    val lon: Double
) {
    /** 界面下方展示的坐标串，保留 5 位小数约等于 1 米级 */
    val coordText: String
        get() = String.format(Locale.US, "%.5f, %.5f", lat, lon)
}

object Presets {

    /**
     * 每地只留一个标志性地点，够验证即可；四地南北与东西跨度大，
     * 便于核对不同纬度下的米制换算与地图显示。
     */
    val places: List<PresetPlace> = listOf(
        PresetPlace("bj_tiananmen", "北京", "天安门广场", 39.90550, 116.39775),
        PresetPlace("sh_bund", "上海", "外滩", 31.23972, 121.49000),
        PresetPlace("gd_canton", "广东", "广州塔（小蛮腰）", 23.10661, 113.32452),
        PresetPlace("xz_potala", "西藏", "布达拉宫", 29.65780, 91.11700)
    )

    /** 按省份分组，界面按这个顺序渲染分组标题 */
    val byProvince: Map<String, List<PresetPlace>> =
        places.groupBy { it.province }

    fun find(key: String): PresetPlace? = places.firstOrNull { it.key == key }
}
