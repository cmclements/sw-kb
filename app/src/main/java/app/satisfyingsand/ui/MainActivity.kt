package app.satisfyingsand.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.satisfyingsand.game.Pixelator
import app.satisfyingsand.game.SandGame
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : Activity() {

    /**
     * Difficulty presets: how big a block of sand each picture pixel becomes. The sand itself is
     * always [SAND_COLS] grains wide, so Coarse paints the picture in 10x10-grain blocks, Medium in
     * 4x4 blocks and Fine gives every grain its own picture pixel (colors scatter much more).
     */
    private enum class GrainSize(val label: String, val block: Int) {
        COARSE("Coarse", 10), MEDIUM("Medium", 4), FINE("Fine", 1);

        val pictureCols: Int get() = SAND_COLS / block
    }

    private lateinit var root: FrameLayout
    private lateinit var sfx: Sfx
    private var grainSize = GrainSize.MEDIUM
    private var colorCount = 8
    private var currentPicture: Bitmap? = null
    private var inGame = false
    private var gameSeed = 1

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sfx = Sfx(this)
        root = FrameLayout(this).apply { setBackgroundColor(GameView.BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        grainSize = GrainSize.entries[getPreferences(MODE_PRIVATE).getInt(PREF_SIZE, GrainSize.MEDIUM.ordinal)]
        colorCount = getPreferences(MODE_PRIVATE).getInt(PREF_COLORS, 8).takeIf { it in COLOR_CHOICES } ?: 8
        showMenu()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inGame) showMenu() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        inGame = false
        root.removeAllViews()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (28 * dp).toInt())
        }
        column.addView(label("Satisfying Sand", 32f, GameView.ACCENT, bold = true))
        column.addView(label("Turn any picture into sand, then drain every last grain.", 15f, Color.argb(200, 255, 255, 255)).apply {
            setPadding(0, (4 * dp).toInt(), 0, (20 * dp).toInt())
        })

        val sources = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sources.addView(bigButton("📷  Camera") { openCamera() }, weighted())
        sources.addView(View(this), LinearLayout.LayoutParams((12 * dp).toInt(), 1))
        sources.addView(bigButton("🖼  Gallery") { openGallery() }, weighted())
        column.addView(sources)

        column.addView(section("Difficulty (picture detail)"))
        val sizes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        GrainSize.entries.forEachIndexed { i, size ->
            val chip = chip(size.label, size == grainSize)
            chip.setOnClickListener {
                grainSize = size
                getPreferences(MODE_PRIVATE).edit().putInt(PREF_SIZE, size.ordinal).apply()
                chips.forEachIndexed { j, c -> styleChip(c, j == size.ordinal) }
            }
            chips += chip
            if (i > 0) sizes.addView(View(this), LinearLayout.LayoutParams((8 * dp).toInt(), 1))
            sizes.addView(chip, weighted())
        }
        column.addView(sizes)

        column.addView(section("Colors"))
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colorChips = ArrayList<TextView>()
        COLOR_CHOICES.forEachIndexed { i, n ->
            val chip = chip("$n colors", n == colorCount)
            chip.setOnClickListener {
                colorCount = n
                getPreferences(MODE_PRIVATE).edit().putInt(PREF_COLORS, n).apply()
                colorChips.forEachIndexed { j, c -> styleChip(c, COLOR_CHOICES[j] == n) }
            }
            colorChips += chip
            if (i > 0) colorRow.addView(View(this), LinearLayout.LayoutParams((8 * dp).toInt(), 1))
            colorRow.addView(chip, weighted())
        }
        column.addView(colorRow)

        column.addView(section("Picture library"))
        val grid = GridLayout(this).apply { columnCount = 2 }
        val cellW = ((resources.displayMetrics.widthPixels - 40 * dp - 12 * dp) / 2).toInt()
        for ((i, entry) in BuiltInImages.entries.withIndex()) {
            val bmp = BuiltInImages.render(entry)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.rgb(0x2A, 0x21, 0x40), 14f)
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { startGame(bmp) }
            }
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = rounded(Color.BLACK, 10f)
            }
            card.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (cellW * 0.9f).toInt()))
            card.addView(label(entry.name, 14f, Color.WHITE).apply {
                gravity = Gravity.CENTER; setPadding(0, (6 * dp).toInt(), 0, 0)
            })
            val lp = GridLayout.LayoutParams().apply {
                width = cellW
                setMargins(if (i % 2 == 1) (12 * dp).toInt() else 0, 0, 0, (12 * dp).toInt())
            }
            grid.addView(card, lp)
        }
        column.addView(grid)

        column.addView(label(
            "How to play: every box you'll need is already in the tray. Slide boxes around to clear a way for " +
                "the color you want, then bring it up to the picture: it sucks in sand of its own color from " +
                "around the notch on its top. Full boxes pop and the sand settles. Drain the whole picture to win.",
            13f, Color.argb(150, 255, 255, 255),
        ).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })

        root.addView(ScrollView(this).apply { addView(column) })
    }

    private fun label(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun section(s: String) = label(s.uppercase(), 12f, Color.argb(160, 255, 255, 255), bold = true).apply {
        letterSpacing = 0.12f
        setPadding(0, (24 * dp).toInt(), 0, (10 * dp).toInt())
    }

    private fun bigButton(s: String, onClick: () -> Unit) = label(s, 17f, Color.rgb(40, 25, 10), bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(GameView.ACCENT, 14f)
        setPadding(0, (18 * dp).toInt(), 0, (18 * dp).toInt())
        setOnClickListener { onClick() }
    }

    private fun chip(s: String, selected: Boolean) = label(s, 15f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        styleChip(this, selected)
    }

    private fun styleChip(v: TextView, selected: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            if (selected) setColor(Color.rgb(0x4A, 0x3D, 0x6B)) else setColor(Color.rgb(0x24, 0x1D, 0x33))
            setStroke((2 * dp).toInt(), if (selected) GameView.ACCENT else Color.TRANSPARENT)
        }
        v.setTextColor(if (selected) Color.WHITE else Color.argb(170, 255, 255, 255))
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * dp
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    // ------------------------------------------------------------------ picture sources

    private fun openCamera() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            toast("No camera app found")
        } catch (e: SecurityException) {
            toast("Camera not available")
        }
    }

    private fun openGallery() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_GALLERY)
        } catch (e: ActivityNotFoundException) {
            toast("No gallery app found")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_CAMERA -> {
                val bmp = if (Build.VERSION.SDK_INT >= 33) {
                    data.extras?.getParcelable("data", Bitmap::class.java)
                } else {
                    @Suppress("DEPRECATION") data.extras?.get("data") as? Bitmap
                }
                if (bmp != null) startGame(bmp) else data.data?.let { loadUri(it) } ?: toast("Couldn't get the photo")
            }
            REQ_GALLERY -> data.data?.let { loadUri(it) } ?: toast("Couldn't open that picture")
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        uri?.let { loadUri(it) }
    }

    private fun loadUri(uri: Uri) {
        thread {
            val bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    val scale = max(1, longest / MAX_DECODE)
                    decoder.setTargetSize(info.size.width / scale, info.size.height / scale)
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread { if (bmp != null) startGame(bmp) else toast("Couldn't open that picture") }
        }
    }

    // ------------------------------------------------------------------ game

    private fun startGame(picture: Bitmap) {
        currentPicture = picture
        val soft = if (picture.config == Bitmap.Config.HARDWARE) picture.copy(Bitmap.Config.ARGB_8888, false) else picture
        val w = soft.width
        val h = soft.height
        val px = IntArray(w * h)
        soft.getPixels(px, 0, w, 0, 0, w, h)

        val cols = grainSize.pictureCols
        val pic = Pixelator.pixelate(
            px, w, h, cols = cols,
            minRows = cols * 3 / 4, maxRows = cols * 4 / 3,
            buckets = colorCount, seed = gameSeed,
        )
        val sand = Pixelator.toSand(pic, SAND_COLS, seed = gameSeed)
        // More colors need more boxes, so the tray gets more (smaller) cells across.
        val trayCols = trayColsFor(colorCount)
        val game = SandGame(
            sand.cols, sand.rows, sand.grains, sand.bucket, pic.palette,
            trayCols = trayCols, trayRows = TRAY_ROWS, targetBoxes = trayCols * (TRAY_ROWS - 1) - TRAY_GAPS,
            seed = gameSeed++,
        )
        val view = GameView(this, game, sfx, object : GameView.Listener {
            override fun onExit() = showMenu()
            override fun onPlayAgain() { currentPicture?.let { startGame(it) } }
            override fun onNewPicture() = showMenu()
        })
        inGame = true
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_GALLERY = 2
        private const val MAX_DECODE = 768
        /** Sand grid width; divisible by every GrainSize.block. */
        private const val SAND_COLS = 120
        /** Tray height in cells, including the empty top row against the picture. */
        private const val TRAY_ROWS = 4
        /** Free cells left among the boxes at the start. */
        private const val TRAY_GAPS = 3
        private val COLOR_CHOICES = intArrayOf(4, 8, 16)

        /** Tray width in cells for a color count: 4 -> 6 (15 boxes), 8 -> 8 (21), 16 -> 12 (33). */
        private fun trayColsFor(colors: Int): Int = when {
            colors <= 4 -> 6
            colors <= 8 -> 8
            else -> 12
        }
        private const val PREF_SIZE = "grain_size"
        private const val PREF_COLORS = "colors"
    }
}
