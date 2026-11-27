package top.wxip.gpslink.core

import android.content.Context

/** 极简偏好存储：服务被系统重启后要能恢复参数 */
object Prefs {

    private const val FILE = "gpslink"

    private const val K_HOST = "send_host"
    private const val K_PORT = "port"
    private const val K_SID = "sid"
    private const val K_RATE = "sample_interval"
    private const val K_INJECT = "inject_interval"
    private const val K_USE_BT = "use_bt"
    private const val K_BT_MAC = "bt_mac"
    private const val K_AUTO_START = "auto_start"
    private const val K_LAST_ROLE = "last_role"
    private const val K_TRACK_ENABLED = "track_enabled"
    private const val K_NMEA_ENABLED = "nmea_enabled"
    private const val K_NMEA_PORT = "nmea_port"
    private const val K_ORIENTATION = "orientation"
    private const val K_THEME = "theme"
    private const val K_KEEP_ALIVE = "keep_alive"

    /** NMEA over TCP 输出 */
    fun saveNmea(ctx: Context, enabled: Boolean, port: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_NMEA_ENABLED, enabled)
            .putInt(K_NMEA_PORT, port)
            .apply()
    }

    fun loadNmeaEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(K_NMEA_ENABLED, false)

    fun loadNmeaPort(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(K_NMEA_PORT, Protocol.DEFAULT_NMEA_PORT)

    /** 开机自启：下次开机按上次角色自动拉起服务 */
    fun saveAutoStart(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_AUTO_START, enabled)
            .apply()
    }

    fun loadAutoStart(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(K_AUTO_START, false)

    /** 最近一次运行的角色，供开机自启使用 */
    fun saveLastRole(ctx: Context, role: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_LAST_ROLE, role)
            .apply()
    }

    fun loadLastRole(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(K_LAST_ROLE, "") ?: ""

    /** 收到定位帧时是否自动落盘为轨迹 */
    fun saveTrackEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_TRACK_ENABLED, enabled)
            .apply()
    }

    fun loadTrackEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(K_TRACK_ENABLED, false)

    /** 传输方式 */
    fun saveUseBt(ctx: Context, useBt: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_USE_BT, useBt)
            .apply()
    }

    fun loadUseBt(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(K_USE_BT, false)

    /**
     * 后台保活：开启后应用退到后台时用悬浮窗 + 常驻通知维持进程优先级。
     * 默认关闭，保持原有行为。
     */
    fun saveKeepAlive(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_KEEP_ALIVE, enabled)
            .apply()
    }

    fun loadKeepAlive(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(K_KEEP_ALIVE, false)

    /** 屏幕方向：auto 跟随系统，landscape / portrait 强制指定，车机常需要手动锁横屏 */
    fun saveOrientation(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_ORIENTATION, value)
            .apply()
    }

    fun loadOrientation(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(K_ORIENTATION, "auto") ?: "auto"

    /** 主题：system 跟随系统，light / dark 强制指定 */
    fun saveTheme(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_THEME, value)
            .apply()
    }

    fun loadTheme(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(K_THEME, "system") ?: "system"

    fun saveBtMac(ctx: Context, mac: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_BT_MAC, mac)
            .apply()
    }

    fun loadBtMac(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(K_BT_MAC, "") ?: ""

    data class SendConfig(
        val host: String,
        val port: Int,
        val sid: String,
        val intervalMs: Int
    )

    fun saveSend(ctx: Context, host: String, port: Int, sid: String, intervalMs: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_HOST, host)
            .putInt(K_PORT, port)
            .putString(K_SID, sid)
            .putInt(K_RATE, intervalMs)
            .apply()
    }

    fun saveInterval(ctx: Context, intervalMs: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(K_RATE, intervalMs)
            .apply()
    }

    fun saveInject(ctx: Context, intervalMs: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(K_INJECT, intervalMs)
            .apply()
    }

    fun loadInject(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(K_INJECT, Protocol.DEFAULT_INJECT_MS)

    fun loadSend(ctx: Context): SendConfig {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return SendConfig(
            host = sp.getString(K_HOST, "") ?: "",
            port = sp.getInt(K_PORT, Protocol.DEFAULT_PORT),
            sid = sp.getString(K_SID, "8888") ?: "8888",
            intervalMs = sp.getInt(K_RATE, Protocol.DEFAULT_INTERVAL_MS)
        )
    }

    fun saveReceive(ctx: Context, port: Int, sid: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(K_PORT, port)
            .putString(K_SID, sid)
            .apply()
    }
}
