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
     * always [SAND_COLS] grains wide, so Coarse paints the picture in flat 10x10-grain blocks,
     * Medium in dithered 4x4 blocks and Fine gives every grain its own dithered picture pixel,
     * so colors are finely mixed and scattered through the picture.
     */
    private enum class GrainSize(val label: String, val hint: String, val block: Int, val dither: Float) {
        COARSE("Coarse", "big blocks", 10, 0f), MEDIUM("Medium", "small blocks", 4, 1f), FINE("Fine", "every grain", 1, 1f);

        /** Stable key for saved progress. */
        val id: String get() = name.lowercase()

        val pictureCols: Int get() = SAND_COLS / block
    }

    /** The picture being played: library pictures have an id, so progress can be saved. */
    private class Picture(val bitmap: Bitmap, val name: String, val id: String?)

    private enum class Screen { MENU, LEVELS, GAME }

    private lateinit var root: FrameLayout
    private lateinit var sfx: Sfx
    private lateinit var progress: Progress
    private var picture: Picture? = null
    private var grainSize = GrainSize.MEDIUM
    private var colorCount = 8
    private var screen = Screen.MENU
    private var gameSeed = 1

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sfx = Sfx(this)
        progress = Progress(this)
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
        when (screen) {
            Screen.GAME -> showLevels()
            Screen.LEVELS -> showMenu()
            Screen.MENU -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        screen = Screen.MENU
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

        column.addView(section("Picture library"))
        val grid = GridLayout(this).apply { columnCount = 2 }
        val cellW = ((resources.displayMetrics.widthPixels - 40 * dp - 12 * dp) / 2).toInt()
        for ((i, entry) in BuiltInImages.entries.withIndex()) {
            val bmp = BuiltInImages.render(entry)
            val id = libraryId(entry.name)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.rgb(0x2A, 0x21, 0x40), 14f)
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { picture = Picture(bmp, entry.name, id); showLevels() }
            }
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = rounded(Color.BLACK, 10f)
            }
            card.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (cellW * 0.9f).toInt()))
            val done = progress.completedCount(id, DIFFICULTY_IDS, COLOR_CHOICES)
            val total = DIFFICULTY_IDS.size * COLOR_CHOICES.size
            val caption = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt(), 0)
            }
            caption.addView(label(entry.name, 14f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            caption.addView(label(
                if (done == total) "★ $done/$total" else "✓ $done/$total", 12f,
                if (done > 0) DONE_GREEN else Color.argb(120, 255, 255, 255), bold = done > 0,
            ))
            card.addView(caption)
            val lp = GridLayout.LayoutParams().apply {
                width = cellW
                setMargins(if (i % 2 == 1) (12 * dp).toInt() else 0, 0, 0, (12 * dp).toInt())
            }
            grid.addView(card, lp)
        }
        column.addView(grid)

        column.addView(label(
            "How to play: pick a picture, then a level. Every box you'll need is already in the tray. " +
                "Slide boxes around to clear a way for the color you want, then bring it up to the picture: it " +
                "sucks in sand of its own color from around the notch on its top. Full boxes pop and the sand " +
                "settles. Drain the whole picture to win.",
            13f, Color.argb(150, 255, 255, 255),
        ).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })

        root.addView(ScrollView(this).apply { addView(column) })
    }

    // ------------------------------------------------------------------ level select

    /**
     * Level select for the chosen picture: a 3x3 grid of difficulty (rows) by colors (columns).
     * Completed levels of library pictures show a check and the best time.
     */
    private fun showLevels() {
        val pic = picture ?: return showMenu()
        screen = Screen.LEVELS
        root.removeAllViews()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }
        column.addView(label("‹  Pictures", 16f, Color.argb(220, 255, 255, 255), bold = true).apply {
            setPadding(0, (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { showMenu() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(label(pic.name, 26f, GameView.ACCENT, bold = true).apply {
            setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
        })

        val img = ImageView(this).apply {
            setImageBitmap(pic.bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = rounded(Color.BLACK, 14f)
        }
        val previewH = (resources.displayMetrics.heightPixels * 0.28f).toInt()
        column.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewH))

        column.addView(section("Choose a level"))
        val gap = (8 * dp).toInt()
        val labelW = (78 * dp).toInt()
        val tileW = ((resources.displayMetrics.widthPixels - 40 * dp - labelW - 3 * gap) / 3).toInt()

        // Header: colors.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(View(this), LinearLayout.LayoutParams(labelW, 1))
        for (n in COLOR_CHOICES) {
            header.addView(label("$n colors", 13f, Color.argb(190, 255, 255, 255), bold = true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = gap })
        }
        column.addView(header)

        for (size in GrainSize.entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, gap, 0, 0)
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(size.label, 15f, Color.WHITE, bold = true))
                addView(label(size.hint, 11f, Color.argb(140, 255, 255, 255)))
            }, LinearLayout.LayoutParams(labelW, ViewGroup.LayoutParams.WRAP_CONTENT))
            for (n in COLOR_CHOICES) {
                row.addView(levelTile(pic, size, n), LinearLayout.LayoutParams(tileW, tileW).apply { leftMargin = gap })
            }
            column.addView(row)
        }

        val note = if (pic.id != null) {
            val done = progress.completedCount(pic.id, DIFFICULTY_IDS, COLOR_CHOICES)
            "$done of ${DIFFICULTY_IDS.size * COLOR_CHOICES.size} levels complete"
        } else {
            "Progress is saved for pictures from the library."
        }
        column.addView(label(note, 13f, Color.argb(150, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, 0)
        })

        root.addView(ScrollView(this).apply { addView(column) })
    }

    private fun levelTile(pic: Picture, size: GrainSize, colors: Int): View {
        val best = pic.id?.let { progress.best(it, size.id, colors) }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(if (best != null) Color.rgb(0x1F, 0x3A, 0x2C) else Color.rgb(0x2A, 0x21, 0x40))
                setStroke((2 * dp).toInt(), if (best != null) DONE_GREEN else Color.rgb(0x4A, 0x3D, 0x6B))
            }
            setOnClickListener { grainSize = size; colorCount = colors; startGame() }
        }
        // Checkbox: a green box with a check when complete, an empty outlined box otherwise.
        val boxSize = (26 * dp).toInt()
        tile.addView(label(if (best != null) "✓" else "", 17f, Color.rgb(0x10, 0x2A, 0x1C), bold = true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = 6 * dp
                setColor(if (best != null) DONE_GREEN else Color.TRANSPARENT)
                setStroke((2 * dp).toInt(), if (best != null) DONE_GREEN else Color.argb(150, 255, 255, 255))
            }
        }, LinearLayout.LayoutParams(boxSize, boxSize).apply { bottomMargin = (6 * dp).toInt() })
        tile.addView(label(
            if (best != null) formatTime(best.seconds) else "Play", 13f,
            if (best != null) Color.WHITE else Color.argb(170, 255, 255, 255), bold = best != null,
        ).apply { gravity = Gravity.CENTER })
        return tile
    }

    private fun formatTime(s: Float): String {
        val total = s.toInt()
        return "%d:%02d".format(total / 60, total % 60)
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
                if (bmp != null) choosePicture(bmp, "Your photo") else data.data?.let { loadUri(it) } ?: toast("Couldn't get the photo")
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
            runOnUiThread { if (bmp != null) choosePicture(bmp, "Your picture") else toast("Couldn't open that picture") }
        }
    }

    // ------------------------------------------------------------------ game

    private fun choosePicture(bmp: Bitmap, name: String) {
        picture = Picture(bmp, name, null)
        showLevels()
    }

    /** Turns the current picture into sand for the chosen level (off the UI thread) and plays it. */
    private fun startGame() {
        val pic = picture ?: return
        val size = grainSize
        val colors = colorCount
        val seed = gameSeed++
        screen = Screen.GAME
        root.removeAllViews()
        root.addView(label("Pouring sand…", 18f, Color.argb(200, 255, 255, 255)).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        thread {
            val src = pic.bitmap
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val w = soft.width
            val h = soft.height
            val px = IntArray(w * h)
            soft.getPixels(px, 0, w, 0, 0, w, h)
            val cols = size.pictureCols
            val pix = Pixelator.pixelate(
                px, w, h, cols = cols,
                minRows = cols * 3 / 4, maxRows = cols * 4 / 3,
                buckets = colors, seed = seed, dither = size.dither,
            )
            val sand = Pixelator.toSand(pix, SAND_COLS, seed = seed)
            // More colors need more boxes, so the tray gets more (smaller) cells across.
            val trayCols = trayColsFor(colors)
            val game = SandGame(
                sand.cols, sand.rows, sand.grains, sand.bucket, pix.palette,
                trayCols = trayCols, trayRows = TRAY_ROWS, targetBoxes = trayCols * (TRAY_ROWS - 1) - TRAY_GAPS,
                seed = seed,
            )
            runOnUiThread {
                if (screen != Screen.GAME || picture !== pic) return@runOnUiThread // user backed out
                val view = GameView(this, game, sfx, object : GameView.Listener {
                    override fun onExit() = showLevels()
                    override fun onPlayAgain() = startGame()
                    override fun onLevels() = showLevels()
                    override fun onWin(moves: Int, seconds: Float) {
                        pic.id?.let { progress.record(it, size.id, colors, seconds, moves) }
                    }
                })
                root.removeAllViews()
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_GALLERY = 2
        private const val MAX_DECODE = 768
        /** Sand grid width; divisible by every GrainSize.block. */
        private const val SAND_COLS = 200
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
        private val DIFFICULTY_IDS = GrainSize.entries.map { it.id }
        private val DONE_GREEN = Color.rgb(0x5C, 0xD6, 0x8A)

        private fun libraryId(name: String) = "lib:$name"
    }
}
