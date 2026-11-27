package top.wxip.gpslink.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import top.wxip.gpslink.MainActivity
import top.wxip.gpslink.core.LinkStatus
import top.wxip.gpslink.core.Role

/**
 * 后台保活用的悬浮小胶囊。
 *
 * 只有一枚小标签的大小（约 80×26dp），可拖到屏幕任意位置停住，
 * 点一下回到应用。只在「开关打开 + 服务在跑 + 应用不在前台」时出现，
 * 回到前台立刻收起，不会占用界面空间。
 *
 * 用 applicationContext 添加窗口，和 Activity 生命周期解耦 —— 界面被系统销毁时
 * 悬浮窗仍应继续存在，否则就失去了保活意义。
 */
object OverlayHud {

    private const val TAG = "OverlayHud"

    private var manager: WindowManager? = null
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 最近一次的运行状态，拖动结束或重建窗口时用它恢复文字 */
    private var lastStatus: LinkStatus? = null

    fun canDraw(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "canDrawOverlays failed", t)
                false
            }
        } else {
            true
        }

    fun isShowing(): Boolean = view != null

    fun show(ctx: Context) {
        if (view != null) return
        if (!canDraw(ctx)) {
            Log.i(TAG, "skip show: no overlay permission")
            return
        }
        val app = ctx.applicationContext
        try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tag = buildView(app)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // 不抢焦点，车机导航照常操作；保留触摸用于拖动与点击
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(app, 14f)
                y = dp(app, 70f)
            }
            attachDrag(app, wm, tag, lp)
            wm.addView(tag, lp)
            manager = wm
            view = tag
            params = lp
            lastStatus?.let { render(tag, it) }
            Log.i(TAG, "overlay shown")
        } catch (t: Throwable) {
            // 部分车机禁用了悬浮叠加层，失败不能影响主流程
            Log.w(TAG, "addView failed", t)
            view = null
            manager = null
            params = null
        }
    }

    fun hide() {
        val v = view ?: return
        try {
            manager?.removeView(v)
            Log.i(TAG, "overlay hidden")
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        } finally {
            view = null
            manager = null
            params = null
        }
    }

    fun update(status: LinkStatus) {
        lastStatus = status
        val v = view ?: return
        try {
            render(v, status)
            keepInsideScreen(v)
        } catch (t: Throwable) {
            Log.w(TAG, "update failed", t)
        }
    }

    /** 横竖屏切换后窗口可能停在可视区外，顺手把它拉回来 */
    private fun keepInsideScreen(v: View) {
        val lp = params ?: return
        val wm = manager ?: return
        if (v.width == 0 || v.height == 0) return
        val dm = v.resources.displayMetrics
        val maxX = (dm.widthPixels - v.width - dp(v.context, 4f)).coerceAtLeast(0)
        val maxY = (dm.heightPixels - v.height - dp(v.context, 4f)).coerceAtLeast(0)
        val nx = lp.x.coerceIn(0, maxX)
        val ny = lp.y.coerceIn(0, maxY)
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            try {
                wm.updateViewLayout(v, lp)
            } catch (t: Throwable) {
                Log.w(TAG, "keepInside failed", t)
            }
        }
    }

    /**
     * 拖动 + 单击。
     *
     * 位移超过系统触摸阈值才算拖，否则算点击 —— 悬浮窗停在屏幕边缘时很容易误触，
     * 这个阈值能避免"想拖却打开了应用"。
     */
    private fun attachDrag(
        app: Context,
        wm: WindowManager,
        v: View,
        lp: WindowManager.LayoutParams
    ) {
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var originX = 0
        var originY = 0
        var dragging = false

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    originX = lp.x
                    originY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        // gravity 是 TOP|END，x 表示距右边缘的距离，所以向右拖是减法
                        val dm = v.resources.displayMetrics
                        val maxX = (dm.widthPixels - v.width - dp(app, 4f)).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - v.height - dp(app, 4f)).coerceAtLeast(0)
                        lp.x = (originX - dx.toInt()).coerceIn(0, maxX)
                        lp.y = (originY + dy.toInt()).coerceIn(0, maxY)
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (t: Throwable) {
                            Log.w(TAG, "drag failed", t)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) openApp(app)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun openApp(app: Context) {
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "open activity failed", t)
        }
    }

    private fun buildView(ctx: Context): TextView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 9f), dp(ctx, 5f), dp(ctx, 9f), dp(ctx, 5f))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 99f).toFloat()
            setColor(Color.argb(206, 10, 18, 30))
            setStroke(dp(ctx, 1f), Color.argb(60, 255, 255, 255))
        }
        text = "● 运行中"
    }

    private fun render(v: TextView, s: LinkStatus) {
        v.text = label(s)
        v.setTextColor(
            when {
                !s.running -> Color.argb(150, 255, 255, 255)
                s.role == Role.RECEIVER && !s.online -> Color.argb(200, 255, 255, 255)
                else -> Color.WHITE
            }
        )
    }

    /** 尽量短：一枚胶囊里只放得下一个状态 + 一个数值 */
    private fun label(s: LinkStatus): String = when {
        !s.running -> "● 已停止"
        s.fixedName.isNotBlank() && s.fixedOverride -> "● 注入中"
        s.role == Role.RECEIVER && !s.online -> "● 等待"
        s.rateHz > 0f -> "● " + "%.1f Hz".format(s.rateHz)
        s.role == Role.SENDER -> "● 发送中"
        else -> "● 接收中"
    }

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
