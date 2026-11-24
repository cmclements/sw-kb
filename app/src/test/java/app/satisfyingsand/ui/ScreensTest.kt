package app.satisfyingsand.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Drives the real menu and level-select screens, and saves screenshots to build/screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {

    @org.junit.Before
    fun noPlayServices() { MainActivity.startPlayServices = false }

    private fun all(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun levelGridShowsCompletedLevels() {
        // Pretend Coarse / 8 colors of "Heart" has been beaten.
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Progress(app).record("lib:Heart", "coarse", 8, 83f, 41)
        Progress(app).record("lib:Heart", "medium", 4, 151f, 77)
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = ctl.get().window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1233, 2673)
        shot(root, "menu")

        val menuTexts = all(root).filterIsInstance<TextView>().map { it.text.toString() }
        val heart = all(root).filterIsInstance<TextView>().firstOrNull { it.text == "Heart" }
            ?: throw AssertionError("no Heart card; menu texts: $menuTexts; views: ${all(root).map { it.javaClass.simpleName + "(" + it.width + "x" + it.height + ")" }}; focus=${ctl.get().hasWindowFocus()} finishing=${ctl.get().isFinishing}")
        val badge = all(heart.parent as View).filterIsInstance<TextView>().firstOrNull { it.text.contains("/9") }
            ?: throw AssertionError("no badge")
        assertEquals("✓ 2/9", badge.text.toString())

        // Open the Heart card -> level select.
        var card: View = heart
        while (card.parent !is GridLayout) card = card.parent as View
        card.performClick()
        root.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1233, 2673)
        shot(root, "levels")

        val texts = all(root).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.containsAll(listOf("4 colors", "8 colors", "16 colors", "Coarse", "Medium", "Fine")))
        assertTrue("best times shown", texts.contains("1:23") && texts.contains("2:31"))
        assertEquals(2, texts.count { it == "✓" })
        assertEquals(7, texts.count { it == "Play" })
        assertNotNull(texts.firstOrNull { it.startsWith("2 of 9") })
        assertEquals("exactly one ranked level", 1, texts.count { it == "🏆 RANKED" })
    }

    @Test
    fun settingsDialogSavesVolumes() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = ctl.get().window.decorView
        val gear = all(root).filterIsInstance<TextView>().first { it.contentDescription == "Settings" }
        gear.performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val view = dialog.window!!.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shot(view, "settings")
        val dialogTexts = all(view).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("remove-ads offer shown", dialogTexts.any { it.startsWith("Remove ads forever") })
        assertTrue(dialogTexts.contains("Restore purchase"))
        val bars = all(view).filterIsInstance<android.widget.SeekBar>()
        assertEquals(2, bars.size)
        bars[0].progress = 35
        bars[1].progress = 10
        val saved = Settings(ctl.get())
        assertEquals(35, saved.sfxVolume)
        assertEquals(10, saved.musicVolume)
    }
}
