package top.wxip.gpslink

import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动与切页的最小回归集。
 *
 * 之前 1.3.6 就是因为在样式中漏了 layout_width，导致 setContentView 直接抛
 * InflateException，用户侧表现是一打开就闪退；这类问题编译期发现不了，只能真跑一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivitySmokeTest {

    @Test
    fun launchesWithoutCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assert(controller.get() != null)
    }

    @Test
    fun switchesAllPages() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val docks = listOf(
            R.id.dock_link to R.id.page_link,
            R.id.dock_sat to R.id.page_sat,
            R.id.dock_access to R.id.page_access,
            R.id.dock_settings to R.id.page_settings,
            R.id.dock_about to R.id.page_about
        )
        for ((dock, page) in docks) {
            activity.findViewById<LinearLayout>(dock).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(page).visibility)
        }
    }
}
