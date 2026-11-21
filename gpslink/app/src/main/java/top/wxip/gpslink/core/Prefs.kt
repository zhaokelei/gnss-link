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
