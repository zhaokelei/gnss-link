package top.wxip.gpslink.mock

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import top.wxip.gpslink.core.LocationFrame
import top.wxip.gpslink.core.SatStats

/**
 * 模拟位置注入器。
 *
 * 原理：调用 LocationManager.addTestProvider() 用一个"测试提供者"顶替系统真实的
 * GPS / NETWORK 提供者，然后不断 setTestProviderLocation() 灌入坐标。
 * 此后所有通过 LocationManager 或 FusedLocationProvider 取位置的应用
 * （高德、百度、Google 地图、导航软件……）拿到的都是我们灌进去的点。
 *
 * 前提条件（无法绕过）：
 *  1. 打开开发者选项；
 *  2. 在「选择模拟位置信息应用」里选中本应用；
 *  3. 本应用持有 ACCESS_FINE_LOCATION 运行时权限。
 */
class MockLocationEngine(private val context: Context) {

    companion object {
        private const val TAG = "MockEngine"

        /** 同时顶替 gps 和 network，兼容只读 network 的应用（部分车机/平板） */
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val active = mutableListOf<String>()

    /** 是否已在开发者选项中被选为模拟位置应用 */
    fun isSelectedAsMockApp(): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        Log.w(TAG, "check mock op failed", t)
        false
    }

    /** 开发者选项总开关是否打开，用于给出更准确的提示 */
    fun isDeveloperOptionsEnabled(): Boolean = try {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
    } catch (t: Throwable) {
        false
    }

    fun developerOptionsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 建立测试提供者。失败时抛异常，由调用方决定如何提示用户。
     */
    @Suppress("DEPRECATION")
    fun prepare() {
        if (!isSelectedAsMockApp()) {
            throw SecurityException(
                if (isDeveloperOptionsEnabled())
                    "请在 开发者选项 → 选择模拟位置信息应用 中选择「${appLabel()}」"
                else
                    "请先开启开发者选项，再把本应用设为模拟位置信息应用"
            )
        }

        active.clear()
        for (name in PROVIDERS) {
            try {
                // requiresNetwork / requiresSatellite 都传 false：测试提供者不依赖真实硬件，
                // 没有 GPS 模块的平板/车机也能注入。
                lm.addTestProvider(
                    name,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                Log.i(TAG, "addTestProvider($name) ok")
            } catch (e: IllegalArgumentException) {
                // 已经存在（可能是上次没清理干净），直接复用
                Log.i(TAG, "provider $name already exists, reuse")
            }

            try {
                lm.setTestProviderEnabled(name, true)
                active.add(name)
            } catch (t: Throwable) {
                Log.w(TAG, "enable provider $name failed", t)
            }
        }

        if (active.isEmpty()) {
            throw IllegalStateException("无法启用模拟位置提供者，请确认已授权模拟位置")
        }
        Log.i(TAG, "mock ready: $active")
    }

    /** 注入一帧；stats 会作为 extras 挂到 Location 上，供下游应用读取卫星数 */
    fun inject(frame: LocationFrame, stats: SatStats? = null): Boolean {
        var ok = false
        for (name in active) {
            try {
                lm.setTestProviderLocation(name, frame.toLocation(name, stats))
                ok = true
            } catch (t: Throwable) {
                Log.w(TAG, "inject into $name failed: ${t.message}")
            }
        }
        return ok
    }

    data class ReadBack(
        val lat: Double,
        val lon: Double,
        val provider: String,
        val isMock: Boolean,
        val ageMs: Long
    )

    /**
     * 从系统读回当前位置 —— 这是"注入到底有没有生效"的硬证据。
     *
     * 读的是 LocationManager 对外提供的值，也就是任何第三方 App（高德、Google 地图）
     * 拿到的同一个值。它和我们注入的坐标一致、且 isMock 为 true，就说明系统确实在
     * 用模拟位置对外服务，而不是在用本机真实 GPS。
     */
    fun readBack(): ReadBack? {
        for (name in PROVIDERS) {
            try {
                val loc = lm.getLastKnownLocation(name) ?: continue
                val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    loc.isMock
                } else {
                    @Suppress("DEPRECATION")
                    loc.isFromMockProvider
                }
                return ReadBack(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    provider = name,
                    isMock = mock,
                    ageMs = System.currentTimeMillis() - loc.time
                )
            } catch (t: Throwable) {
                Log.w(TAG, "readBack($name) failed: ${t.message}")
            }
        }
        return null
    }

    /**
     * 撤销测试提供者，恢复系统真实定位。
     *
     * 这里不依赖 active 列表，而是对两个 provider 都尝试移除 ——
     * 因为进程被 ROM 杀掉时 onDestroy 可能不会执行，下次启动时 active 是空的，
     * 但系统里还挂着上次的测试 provider，会导致设备一直卡在假位置上。
     */
    fun release() {
        for (name in PROVIDERS) {
            try {
                lm.setTestProviderEnabled(name, false)
            } catch (_: Throwable) {
            }
            try {
                lm.removeTestProvider(name)
            } catch (_: Throwable) {
            }
        }
        active.clear()
    }

    private fun appLabel(): String = try {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    } catch (t: Throwable) {
        "本应用"
    }
}
