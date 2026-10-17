package top.wxip.gpslink.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import top.wxip.gpslink.core.Gnss
import top.wxip.gpslink.core.SatInfo
import java.util.Locale

/**
 * 仿 GPS Test 的卫星信号条。
 *
 * 每行：星座标签 · 卫星编号 · C/N0 信号条 · 数值与频段。
 * 用自绘而不是 RecyclerView，是因为行数会随卫星数量剧烈变化（0~40），
 * 自绘不需要复用逻辑，也不会有一堆 View 的测量开销。
 */
class SatelliteBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var sats: List<SatInfo> = emptyList()

    private val d = resources.displayMetrics.density
    private val rowHeight = 21f * d
    private val padTop = 2f * d

    private val tagWidth = 34f * d
    private val prnWidth = 32f * d
    private val rightWidth = 72f * d
    private val barHeight = 8f * d

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * d
        color = 0xFF111318.toInt()
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * d
        color = 0xFF111318.toInt()
    }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3E6EA.toInt()
    }

    fun setSatellites(list: List<SatInfo>) {
        sats = list
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = sats.size.coerceAtLeast(1)
        val h = (padTop * 2 + rows * rowHeight).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (sats.isEmpty()) {
            small.color = 0xFF5B6472.toInt()
            drawCenter(canvas, "等待卫星数据…", 0f, padTop + rowHeight / 2f, small)
            return
        }

        val w = width.toFloat()
        val barLeft = tagWidth + prnWidth
        val barRight = (w - rightWidth).coerceAtLeast(barLeft + 40f * d)

        var top = padTop
        for (s in sats) {
            val cy = top + rowHeight / 2f
            val color = Gnss.color(s.constellation)

            small.color = color
            small.isFakeBoldText = true
            drawCenter(canvas, Gnss.short(s.constellation), 0f, cy, small)
            small.isFakeBoldText = false

            text.color = 0xFF111318.toInt()
            drawCenter(canvas, prn(s), tagWidth, cy, text)

            val barTop = cy - barHeight / 2f
            canvas.drawRoundRect(
                barLeft, barTop, barRight, barTop + barHeight,
                barHeight / 2f, barHeight / 2f, barBg
            )
            val ratio = (s.cn0 / 50f).coerceIn(0f, 1f)
            if (ratio > 0f) {
                small.color = color
                canvas.drawRoundRect(
                    barLeft, barTop, barLeft + (barRight - barLeft) * ratio,
                    barTop + barHeight, barHeight / 2f, barHeight / 2f, small
                )
            }

            small.color = if (s.usedInFix) 0xFF111318.toInt() else 0xFF9AA4B2.toInt()
            val label = String.format(Locale.US, "%.0f  %s", s.cn0, Gnss.band(s.freqHz))
            drawCenter(canvas, label, barRight + 8f * d, cy, small)

            top += rowHeight
        }
    }

    private fun drawCenter(canvas: Canvas, s: String, x: Float, cy: Float, p: Paint) {
        val y = cy - (p.descent() + p.ascent()) / 2f
        canvas.drawText(s, x, y, p)
    }

    private fun prn(s: SatInfo): String =
        String.format(Locale.US, "%s%02d", Gnss.prefix(s.constellation), s.svid)
}
