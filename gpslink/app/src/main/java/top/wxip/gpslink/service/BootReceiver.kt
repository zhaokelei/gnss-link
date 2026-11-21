package top.wxip.gpslink.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import top.wxip.gpslink.core.LinkBus
import top.wxip.gpslink.core.Prefs
import top.wxip.gpslink.core.Role

/**
 * 开机自启。
 *
 * 车机/平板熄火断电后整机重启，用户不会每次手动点「开始接收」，
 * 所以开机按上次的角色和参数把服务拉起来。
 *
 * 触发条件全部满足才启动：
 *  1. 设置里打开了「开机自动运行」；
 *  2. 上次用过发送或接收（否则不知道该起哪个）；
 *  3. 定位权限还在（用户可能在设置里撤销过）。
 *
 * 从 BOOT_COMPLETED 启动 location 类型前台服务属于系统豁免场景，
 * Android 14 的 FGS 后台启动限制不适用，但服务自己仍需先 startForeground。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!Prefs.loadAutoStart(context)) return
        if (!hasLocation(context)) {
            LinkBus.log("开机自启已跳过：缺少定位权限")
            return
        }
        // Android 14 起，后台创建 location 类型前台服务需要「始终允许」定位，
        // 否则系统会抛 SecurityException，这里先给出明确提示而不是静默失败
        if (Build.VERSION.SDK_INT >= 34 && !hasBackgroundLocation(context)) {
            LinkBus.log("开机自启可能被拒绝：请把定位权限改为「始终允许」")
        }

        val cfg = Prefs.loadSend(context)
        val useBt = Prefs.loadUseBt(context)
        try {
            when (Prefs.loadLastRole(context)) {
                Role.RECEIVER.name -> ReceiveService.start(
                    context, cfg.port, cfg.sid, Prefs.loadInject(context), useBt
                )
                Role.SENDER.name -> SendService.start(
                    context, cfg.host, cfg.port, cfg.sid, cfg.intervalMs,
                    useBt, Prefs.loadBtMac(context)
                )
                else -> return
            }
            LinkBus.log("开机自启：已按上次角色启动")
        } catch (t: Throwable) {
            LinkBus.log("开机自启失败：${t.message}")
        }
    }

    private fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
