package top.wxip.gpslink.core

import org.json.JSONArray
import org.json.JSONObject

/** 卫星统计摘要，用于注入 Location 的 extras，也是界面顶部的汇总行 */
data class SatStats(
    val visible: Int,
    val used: Int,
    val meanCn0: Float,
    val maxCn0: Float
) {
    companion object {
        val EMPTY = SatStats(0, 0, 0f, 0f)
    }
}

/**
 * 卫星状态的传输编解码。
 *
 * 用数组而不是对象键名，是为了把 30 颗卫星压在 900 字节以内 ——
 * UDP 单包上限 1400，留足余量；蓝牙是流式，但小包也更省电。
 *
 * 每颗卫星编码成 [svid, 星座, cn0, 频率MHz, 是否参与定位]。
 */
object SatWire {

    private const val MAX_SATS = 40

    fun stats(list: List<SatInfo>): SatStats {
        if (list.isEmpty()) return SatStats.EMPTY
        val used = list.filter { it.usedInFix }
        val src = if (used.isNotEmpty()) used else list
        return SatStats(
            visible = list.size,
            used = used.size,
            meanCn0 = src.map { it.cn0 }.average().toFloat(),
            maxCn0 = src.maxOf { it.cn0 }
        )
    }

    fun encode(sid: String, list: List<SatInfo>): JSONObject {
        val arr = JSONArray()
        list.take(MAX_SATS).forEach { s ->
            val e = JSONArray()
            e.put(s.svid)
            e.put(s.constellation)
            e.put(round(s.cn0.toDouble(), 1))
            e.put(round(s.freqHz / 1e6, 2))
            e.put(if (s.usedInFix) 1 else 0)
            arr.put(e)
        }

        val st = stats(list)
        return JSONObject().apply {
            put("v", Protocol.VERSION)
            put("t", Protocol.T_SAT)
            put("sid", sid)
            put("vis", st.visible)
            put("used", st.used)
            put("mean", round(st.meanCn0.toDouble(), 1))
            put("max", round(st.maxCn0.toDouble(), 1))
            put("s", arr)
        }
    }

    fun decode(o: JSONObject): List<SatInfo> {
        val arr = o.optJSONArray("s") ?: return emptyList()
        val out = ArrayList<SatInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONArray(i) ?: continue
            out.add(
                SatInfo(
                    svid = e.optInt(0),
                    constellation = e.optInt(1),
                    cn0 = e.optDouble(2, 0.0).toFloat(),
                    elevation = 0f,
                    azimuth = 0f,
                    usedInFix = e.optInt(4, 0) == 1,
                    freqHz = (e.optDouble(3, 0.0) * 1e6).toFloat()
                )
            )
        }
        return out
    }

    private fun round(v: Double, digits: Int): Double {
        var f = 1.0
        repeat(digits) { f *= 10 }
        return Math.round(v * f) / f
    }
}
