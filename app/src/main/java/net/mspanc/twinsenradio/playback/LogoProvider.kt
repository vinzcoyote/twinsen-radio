package net.mspanc.twinsenradio.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Station
import java.io.File

/**
 * Serves station logos under a **stable** `content://.../logo/<id>` address.
 *
 * Why this exists at all: logos used to go to Android Auto as
 * `android.resource://net.mspanc.twinsenradio/2131165359`. That kind of
 * address embeds a numeric resource ID, and that ID changes on almost every
 * app rebuild - adding a single file to res/drawable is enough. Android Auto
 * caches downloaded artwork by address, so after an update the same number
 * would already point to a different station, and the head unit would draw
 * the logo from its own cache - hence "RMF FM" appearing under Radio Nowy
 * Swiat's logo.
 *
 * Here the address describes the station, not the resource, so it's stable
 * across versions. The resource number is appended as a `?v=` parameter, so
 * that when the artwork itself is swapped (same station ID, different file)
 * the cache is invalidated exactly once.
 */
class LogoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    /**
     * We hand back the image from a cached file rather than a pipe: readers
     * on the other side (Android Auto, Glide) can query the size and seek,
     * which a pipe doesn't support.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val file = renderToCache(context, uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        val file = renderToCache(context, uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }.toTypedArray()
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    /**
     * Turns a resource into a PNG file. We render once - subsequent reads
     * come from disk, and since the file name contains the resource number,
     * a rebuild produces a new file.
     */
    private fun renderToCache(context: Context, uri: Uri): File? = runCatching {
        // The user's own custom artwork already sits ready in the app's
        // directory - there's nothing to render, just hand it back.
        if (uri.pathSegments.firstOrNull() == "custom") {
            val path = uri.getQueryParameter(PARAM_PATH) ?: return@runCatching null
            return@runCatching File(path).takeIf { it.exists() }
        }
        val resId = uri.getQueryParameter(PARAM_VERSION)?.toIntOrNull()
            ?: R.drawable.logo_placeholder
        val dir = File(context.cacheDir, "logo").apply { mkdirs() }
        val file = File(dir, "${uri.lastPathSegment}-$resId.png")
        if (file.exists() && file.length() > 0) return@runCatching file

        val drawable = ContextCompat.getDrawable(context, resId) ?: return@runCatching null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bmp ->
                drawable.setBounds(0, 0, SIZE, SIZE)
                drawable.draw(Canvas(bmp))
            }
        }
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        file
    }.onFailure { Log.w(TAG, "failed to prepare logo for $uri: ${it.message}") }
        .getOrNull()

    companion object {
        private const val TAG = "LogoProvider"
        private const val PARAM_VERSION = "v"
        private const val PARAM_PATH = "p"
        private const val SIZE = 512

        /** Must match android:authorities in the manifest. */
        fun authority(context: Context): String = "${context.packageName}.logos"

        /**
         * @param resId the resource number from this version of the app -
         *   included in the address solely to invalidate the cache when the
         *   artwork is swapped.
         */
        fun uriFor(context: Context, station: Station, resId: Int): Uri =
            build(context, "logo", station.id, resId)

        /**
         * Address of a button icon in Android Auto. The reason is the same as
         * for the logo, just the symptom differs: buttons used to get a
         * resource number, and the head unit remembers the icon it drew under
         * that number. After an app rebuild the numbers shift (adding a
         * single file to res/drawable is enough), and where a filled star
         * should be, whatever previously had that number would show up
         * instead - an empty star, or even a plain square.
         */
        fun iconUri(context: Context, name: String, resId: Int): Uri =
            build(context, "icon", name, resId)

        /**
         * Artwork uploaded by the user. The file's modification time is
         * included in the address, so that replacing the logo invalidates
         * what the head unit has cached.
         */
        fun customUriFor(context: Context, station: Station, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath("custom")
                .appendPath(station.id)
                .appendQueryParameter(PARAM_PATH, file.absolutePath)
                .appendQueryParameter(PARAM_VERSION, file.lastModified().toString())
                .build()

        private fun build(context: Context, kind: String, name: String, resId: Int): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(kind)
                .appendPath(name)
                .appendQueryParameter(PARAM_VERSION, resId.toString())
                .build()
    }
}
