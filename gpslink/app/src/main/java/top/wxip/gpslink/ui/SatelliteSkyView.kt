package top.wxip.gpslink.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import top.wxip.gpslink.R
import top.wxip.gpslink.core.Gnss
import top.wxip.gpslink.core.SatInfo

/**
 * 极坐标天空图：圆心为天顶（仰角 90°），外圈为地平线（仰角 0°），
 * 正上方为北，顺时针方位角。每个卫星按方位角/仰角定位，颜色区分星座。
 */
class SatelliteSkyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var sats: List<SatInfo> = emptyList()

    private val d = resources.displayMetrics.density
    private val ringCount = 3

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = ContextCompat.getColor(context, R.color.sky_circle)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = ContextCompat.getColor(context, R.color.sky_cross)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sky_label)
        textSize = 11f * d
        textAlign = Paint.Align.CENTER
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card)
        textSize = 8f * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setSatellites(list: List<SatInfo>) {
        sats = list
        invalidate()
    }

    /**
     * 天空图必须保持正方形，但不能无视父容器的约束。
     *
     * 原实现直接 setMeasuredDimension(w, w)，横屏或车机上宽度可能上千 dp，
     * 星图就会被撑成巨大的正方形并超出屏幕。这里取宽、高两个约束的方向与
     * 上限，选可用空间的较小边作边长：父容器给固定高度时按高度，给 wrap_content
     * 时按宽度，且都不会超过任一方向的上限。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)

        val widthLimit = if (wMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else wSize
        val heightLimit = if (hMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else hSize

        val side = when {
            // 两个方向都有上限：取较小边，保证正方形不越界
            heightLimit != Int.MAX_VALUE -> minOf(widthLimit, heightLimit)
            // 只有宽度有上限：以此为准（竖屏常见，高度由内容决定）
            widthLimit != Int.MAX_VALUE -> widthLimit
            else -> (220 * resources.displayMetrics.density).toInt()
        }
        val finalSide = side.coerceAtLeast((80 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(finalSide, finalSide)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 22f * d

        // 同心圆
        for (i in 1..ringCount) {
            canvas.drawCircle(cx, cy, radius * i / ringCount, ringPaint)
        }
        // 十字与斜十字
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint)
        val diag = radius * 0.7071f
        canvas.drawLine(cx - diag, cy - diag, cx + diag, cy + diag, crossPaint)
        canvas.drawLine(cx - diag, cy + diag, cx + diag, cy - diag, crossPaint)

        // 方位标注
        val labelR = radius + 14f * d
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("北", cx, cy - labelR - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
        canvas.drawText("南", cx, cy + labelR - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("西", cx - labelR - labelPaint.measureText("西") / 2f,
            cy - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
        canvas.drawText("东", cx + labelR - labelPaint.measureText("东") / 2f,
            cy - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER

        if (sats.isEmpty()) {
            canvas.drawText(
                "等待卫星数据",
                cx,
                cy + 4f * d - (emptyPaint.descent() + emptyPaint.ascent()) / 2f,
                emptyPaint
            )
            return
        }

        val r = 9f * d
        for (s in sats) {
            // 仰角越高越靠圆心；低于地平线的夹到外圈
            val elev = s.elevation.coerceIn(0f, 90f)
            val dist = radius * (90f - elev) / 90f
            val az = Math.toRadians(s.azimuth.toDouble())
            val x = cx + (dist * Math.sin(az)).toFloat()
            val y = cy - (dist * Math.cos(az)).toFloat()

            dotPaint.color = colorOf(s)
            canvas.drawCircle(x, y, r, dotPaint)
            if (s.usedInFix) {
                canvas.drawCircle(x, y, r + 2f * d, ringPaint)
            }
            val number = s.svid.toString()
            canvas.drawText(number, x, y - (dotTextPaint.descent() + dotTextPaint.ascent()) / 2f, dotTextPaint)
        }
    }

    private fun colorOf(s: SatInfo): Int = Gnss.color(s.constellation)
}
