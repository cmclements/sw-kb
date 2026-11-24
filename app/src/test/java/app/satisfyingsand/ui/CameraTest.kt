package app.satisfyingsand.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CameraTest {

    @org.junit.Before
    fun noPlayServices() { MainActivity.startPlayServices = false }

    private fun all(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @Test
    fun cameraPhotoIsLoadedAtFullResolutionAndUpright() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctl.get()
        Robolectric.setupContentProvider(CaptureProvider::class.java, CaptureProvider.authority(activity))

        // Tap "Camera": we must ask for a full-resolution photo written to our capture URI.
        val root = activity.window.decorView
        all(root).filterIsInstance<TextView>().first { it.text.contains("Camera") }.performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.intent.action)
        @Suppress("DEPRECATION")
        val target = started.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        assertTrue(started.intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)

        // Play the camera app: write a 1600x1200 landscape JPEG through the URI, tagged
        // "rotate 90" in EXIF like a phone held upright.
        val photo = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 800f, 1200f, Paint().apply { color = Color.RED })
        }
        activity.contentResolver.openOutputStream(target)!!.use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val file = CaptureProvider.captureFile(activity)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertTrue(file.length() > 10_000)

        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, null)

        // Decoding happens off the UI thread; wait for the level screen.
        var preview: ImageView? = null
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (all(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Your photo" }) {
                preview = all(activity.window.decorView).filterIsInstance<ImageView>().firstOrNull()
                return@repeat
            }
            Thread.sleep(50)
        }
        val bmp = (preview!!.drawable as BitmapDrawable).bitmap
        // Full resolution (downsampled to 1024 on the long side), not a ~200 px thumbnail,
        // and turned upright by its EXIF tag: portrait, 768 x 1024.
        assertEquals(768, bmp.width)
        assertEquals(1024, bmp.height)
    }
}
