package app.satisfyingsand.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal private provider that exposes exactly one file, the camera capture target, so the
 * camera app can write a full-resolution photo for us (via a temporary URI permission grant)
 * without the app needing storage or camera permissions, or an AndroidX dependency.
 */
class CaptureProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        if (uri.authority != authority(ctx) || uri.lastPathSegment != FILE_NAME) throw FileNotFoundException(uri.toString())
        return captureFile(ctx)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileFor(uri)
        file.parentFile?.mkdirs()
        val flags = ParcelFileDescriptor.parseMode(mode)
        if (flags and ParcelFileDescriptor.MODE_CREATE == 0 && !file.exists()) file.createNewFile()
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    /** Some camera apps ask for the display name and size before writing. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        cursor.addRow(cols.map { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> FILE_NAME
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val FILE_NAME = "capture.jpg"

        fun authority(ctx: Context) = "${ctx.packageName}.capture"

        fun captureFile(ctx: Context) = File(File(ctx.cacheDir, "camera"), FILE_NAME)

        /** A fresh, empty capture target and its content URI. */
        fun newCapture(ctx: Context): Uri {
            val f = captureFile(ctx)
            f.parentFile?.mkdirs()
            f.delete()
            return Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(FILE_NAME).build()
        }
    }
}
