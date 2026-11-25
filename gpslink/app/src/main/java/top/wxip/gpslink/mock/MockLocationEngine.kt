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

    /** 授权机制分档：Android 6.0 是分水岭，之前靠系统开关，之后靠 AppOps */
    enum class Tier { LEGACY, APPOPS }

    /**
     * 当前设备的授权全貌，一次性交给界面渲染。
     *
     * 4.4-5.1 与 6.0+ 是两套完全不同的机制：
     *  - 低版本：ACCESS_MOCK_LOCATION 是普通权限（装好就有），再加一个
     *    「允许模拟位置」的系统开关，两者都满足即可。
     *  - 高版本：权限被改成 signature，声明了也没用，改为 AppOps 里
     *    「选为模拟位置应用」这一条。
     */
    data class Access(
        val sdk: Int,
        val androidName: String,
        val tier: Tier,
        val granted: Boolean,
        /** 低版本的系统开关（高版本上该值无意义） */
        val switchOn: Boolean,
        /** 是否持有 ACCESS_MOCK_LOCATION，低版本必须，高版本恒为 false */
        val permission: Boolean,
        val devOptions: Boolean,
        /** 系统里还能不能打开那个设置页，false 说明被 ROM 裁掉了 */
        val settingsPage: Boolean,
        val adb: String,
        val adbCheck: String,
        /** 当前版本该走的具体步骤 */
        val steps: String,
        /** 没通过时的直接原因，已通过则为空 */
        val why: String
    )

    /** 系统版本号 → 人读的名字，界面直接显示，省得前端再维护一份映射 */
    private fun androidName(sdk: Int): String = when {
        sdk <= 19 -> "Android 4.4"
        sdk == 20 -> "Android 4.4W"
        sdk == 21 -> "Android 5.0"
        sdk == 22 -> "Android 5.1"
        sdk == 23 -> "Android 6.0"
        sdk == 24 -> "Android 7.0"
        sdk == 25 -> "Android 7.1"
        sdk == 26 -> "Android 8.0"
        sdk == 27 -> "Android 8.1"
        sdk == 28 -> "Android 9"
        sdk == 29 -> "Android 10"
        sdk == 30 -> "Android 11"
        sdk == 31 -> "Android 12"
        sdk == 32 -> "Android 12L"
        sdk == 33 -> "Android 13"
        sdk == 34 -> "Android 14"
        sdk == 35 -> "Android 15"
        else -> "Android ${sdk}"
    }

    /** 低版本的「允许模拟位置」系统开关。高版本上这个键已废弃，恒为 0 */
    private fun legacySwitchOn(): Boolean = try {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ALLOW_MOCK_LOCATION, 0
        ) == 1
    } catch (t: Throwable) {
        false
    }

    private fun holdsMockPermission(): Boolean = try {
        // 该常量在新版 SDK 里被标为 @hide，只能写字符串；低版本上它仍是普通权限
        context.checkCallingOrSelfPermission("android.permission.ACCESS_MOCK_LOCATION") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** AppOps 里是否放行 mock_location，仅 6.0+ 有意义 */
    private fun appOpsAllowed(): Boolean = try {
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

    /** 是否已获得注入所需的一切授权 */
    fun isSelectedAsMockApp(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            legacySwitchOn() && holdsMockPermission()
        } else {
            appOpsAllowed()
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

    /** 系统里是否还存在「开发者选项」这个页面，车机常把它整页裁掉 */
    fun canOpenMockSettings(): Boolean = try {
        context.packageManager.resolveActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0
        ) != null
    } catch (t: Throwable) {
        false
    }

    fun describe(): Access {
        val sdk = Build.VERSION.SDK_INT
        val legacy = sdk < Build.VERSION_CODES.M
        val sw = legacySwitchOn()
        val perm = holdsMockPermission()
        val ops = appOpsAllowed()
        val granted = if (legacy) (sw && perm) else ops
        val dev = isDeveloperOptionsEnabled()
        val page = canOpenMockSettings()
        val pkg = context.packageName

        val adb = if (legacy)
            "adb shell settings put secure mock_location 1"
        else
            "adb shell appops set $pkg android:mock_location allow"
        val adbCheck = if (legacy)
            "adb shell settings get secure mock_location"
        else
            "adb shell appops get $pkg android:mock_location"

        val steps = when {
            legacy ->
                "设置 → 开发者选项 → 勾选「允许模拟位置」。本应用已声明该权限，勾上即生效，不需要选择应用。"
            sdk <= Build.VERSION_CODES.R ->
                "设置 → 开发者选项 → 选择模拟位置信息应用 → 选中「${appLabel()}」。"
            else ->
                "设置 → 系统 → 开发者选项 → 选择模拟位置信息应用 → 选中「${appLabel()}」。"
        }

        val why = when {
            granted -> ""
            legacy && !perm -> "清单未声明 ACCESS_MOCK_LOCATION"
            legacy && !sw -> "系统未开启「允许模拟位置」"
            !legacy && !ops -> "未被选为模拟位置应用"
            else -> "授权状态未知"
        }

        return Access(
            sdk = sdk,
            androidName = androidName(sdk),
            tier = if (legacy) Tier.LEGACY else Tier.APPOPS,
            granted = granted,
            switchOn = sw,
            permission = perm,
            devOptions = dev,
            settingsPage = page,
            adb = adb,
            adbCheck = adbCheck,
            steps = steps,
            why = why
        )
    }

    /**
     * 打开模拟位置授权页面。
     *
     * 优先开发者选项；被裁掉时退到系统设置首页，再不行返回 false，
     * 由界面直接引导用户走 adb。
     */
    fun openMockSettings(): Boolean {
        val targets = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in targets) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "open settings failed: ${intent.action}", t)
            }
        }
        return false
    }

    @Deprecated("改用 openMockSettings()，它会带回退")
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
