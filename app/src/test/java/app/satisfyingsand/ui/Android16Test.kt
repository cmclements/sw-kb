package app.satisfyingsand.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Behavior that changes when targeting Android 16 (API 36), checked on an API 36 runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Android16Test {

    @Before
    fun noPlayServices() { MainActivity.startPlayServices = false }

    private fun all(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun texts(a: MainActivity) = all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    @Test
    fun backStepsThroughScreensThenLeavesFromTheMenu() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse("menu: Back must fall through to the system (close the app)", activity.navigateBack())

        // Open a library picture -> level select.
        var card: View = all(activity.window.decorView).filterIsInstance<TextView>().first { it.text == "Heart" }
        while (card.parent !is GridLayout) card = card.parent as View
        card.performClick()
        assertTrue(texts(activity).contains("Choose a level".uppercase()))

        assertTrue("levels: Back is handled by the app", activity.navigateBack())
        assertTrue("back on the menu", texts(activity).contains("Satisfying Sand"))
        assertFalse(activity.navigateBack())
    }

    /** Back goes through the AndroidX dispatcher, exactly as a Back gesture/button does. */
    @Test
    fun backGestureStepsBackThenClosesFromTheMenu() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctl.get()
        assertFalse("menu: the app doesn't intercept Back, so it closes the app",
            activity.onBackPressedDispatcher.hasEnabledCallbacks())

        var card: View = all(activity.window.decorView).filterIsInstance<TextView>().first { it.text == "Heart" }
        while (card.parent !is GridLayout) card = card.parent as View
        card.performClick()
        assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())

        activity.onBackPressedDispatcher.onBackPressed() // the gesture
        assertTrue("back on the menu", texts(activity).contains("Satisfying Sand"))
        assertFalse(activity.isFinishing)
        assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())

        activity.onBackPressedDispatcher.onBackPressed() // Back on the menu closes the app
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun menuFitsALandscapeTabletAndRebuildsOnResize() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val dp = ctl.get().resources.displayMetrics.density
        fun columnWidthDp(): Float {
            val scroll = all(ctl.get().window.decorView).filterIsInstance<ScrollView>().first()
            return scroll.getChildAt(0).layoutParams.width / dp
        }
        assertEquals("content is capped on a wide screen", 560f, columnWidthDp(), 1f)

        // Shrink to a narrow window (split screen): the menu must rebuild for the new width.
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-port")
        ctl.configurationChange(Configuration(ctl.get().resources.configuration))
        assertEquals(400f, columnWidthDp(), 1f)
        assertTrue(texts(ctl.get()).contains("Satisfying Sand"))
    }
}
