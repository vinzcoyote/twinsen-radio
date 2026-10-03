package net.mspanc.twinsenradio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.StationRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Loads cover art (or, failing that, a station logo) into an ImageView.
 * Deliberately without an external library - there's only one need: fetch a
 * single image over HTTP and show it, with a simple cache.
 */
object ArtworkLoader {

    private val cache = LruCache<String, Bitmap>(8)

    /**
     * @param coverUri track cover art from session metadata; null or non-http(s)
     *   means we fall through to [logoUri] instead.
     * @param logoUri the station's own logo - a custom one (file://) is decoded
     *   immediately and shown as the resting image; a remote one (http(s)) is
     *   fetched the same way cover art is. This is what's shown whenever there's
     *   no cover art - previously callers only passed a static resource here,
     *   so a station with a custom or remote logo but no cover art showed the
     *   bare placeholder instead of its own logo.
     * @param fallbackRes shown when there's neither cover art nor any logo.
     */
    fun into(
        scope: LifecycleCoroutineScope,
        coverUri: Uri?,
        logoUri: Uri?,
        fallbackRes: Int,
        target: ImageView
    ) {
        // Invalidate any older asynchronous request tied to this ImageView.
        target.setTag(TAG_KEY, null)

        // The custom logo lives in the app's directory - decode it right away as
        // the resting image, without downloading.
        val localLogo = logoUri?.takeIf { it.scheme == "file" }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            ?.let(::prepareForDisplay)

        if (localLogo != null) {
            target.setImageBitmap(localLogo)
        } else {
            target.setImageResource(fallbackRes)
        }

        // Cover art wins when we have one; a remote station logo is the next best
        // thing to fetch and show instead of the bare placeholder.
        val url = coverUri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
            ?: logoUri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        if (url == null) return

        // Set the tag before consulting the cache so an older in-flight request
        // cannot overwrite a freshly selected cached image.
        target.setTag(TAG_KEY, url)

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                fetch(url)?.let(::prepareForDisplay)
            }
            // the track (or station) may have changed in the meantime
            if (bitmap != null && target.getTag(TAG_KEY) == url) {
                cache.put(url, bitmap)
                target.setImageBitmap(bitmap)
            }
        }
    }

    /**
     * Transparent logos are difficult to read because their appearance depends
     * entirely on whatever background the current screen happens to use.
     *
     * Only images with a meaningful transparent area are changed. We choose a
     * light or dark neutral backdrop according to the visible logo pixels, then
     * draw the original image slightly inset so transparent logos also get a
     * little breathing room. Ordinary opaque cover art is returned untouched.
     */
    private fun prepareForDisplay(source: Bitmap): Bitmap {
        val stats = transparencyStats(source)
        if (!stats.hasMeaningfulTransparency) return source

        val background = if (stats.visibleLuminance >= 0.5) {
            DARK_BACKDROP
        } else {
            LIGHT_BACKDROP
        }

        val output = Bitmap.createBitmap(
            source.width.coerceAtLeast(1),
            source.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)
        canvas.drawColor(background)

        val insetX = source.width * LOGO_INSET_FRACTION
        val insetY = source.height * LOGO_INSET_FRACTION
        val destination = RectF(
            insetX,
            insetY,
            source.width - insetX,
            source.height - insetY
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, destination, paint)

        return output
    }

    /**
     * Samples at most roughly 64 x 64 pixels. A few anti-aliased edge pixels do
     * not count as a transparent logo; at least 3% of the sampled image must be
     * significantly transparent before we add a backdrop.
     */
    private fun transparencyStats(bitmap: Bitmap): TransparencyStats {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return TransparencyStats(false, 0.5)
        }

        val stepX = max(1, bitmap.width / SAMPLE_GRID)
        val stepY = max(1, bitmap.height / SAMPLE_GRID)

        var sampled = 0
        var transparent = 0
        var luminanceSum = 0.0
        var luminanceWeight = 0.0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)

                sampled++
                if (alpha < TRANSPARENCY_ALPHA_THRESHOLD) {
                    transparent++
                }

                if (alpha >= VISIBLE_ALPHA_THRESHOLD) {
                    val r = Color.red(pixel) / 255.0
                    val g = Color.green(pixel) / 255.0
                    val b = Color.blue(pixel) / 255.0
                    val weight = alpha / 255.0

                    luminanceSum += (
                        0.2126 * r +
                            0.7152 * g +
                            0.0722 * b
                        ) * weight
                    luminanceWeight += weight
                }

                x += stepX
            }
            y += stepY
        }

        val transparentRatio =
            if (sampled > 0) transparent.toDouble() / sampled.toDouble() else 0.0

        val visibleLuminance =
            if (luminanceWeight > 0.0) luminanceSum / luminanceWeight else 0.5

        return TransparencyStats(
            hasMeaningfulTransparency = transparentRatio >= MIN_TRANSPARENT_RATIO,
            visibleLuminance = visibleLuminance
        )
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private data class TransparencyStats(
        val hasMeaningfulTransparency: Boolean,
        val visibleLuminance: Double
    )

    private const val SAMPLE_GRID = 64
    private const val TRANSPARENCY_ALPHA_THRESHOLD = 245
    private const val VISIBLE_ALPHA_THRESHOLD = 64
    private const val MIN_TRANSPARENT_RATIO = 0.03
    private const val LOGO_INSET_FRACTION = 0.055f

    // Neutral backdrops chosen to give good contrast without tinting the logo.
    private const val LIGHT_BACKDROP = 0xFFF4F4F4.toInt()
    private const val DARK_BACKDROP = 0xFF24272B.toInt()

    private val TAG_KEY = "artwork_url".hashCode()
}
