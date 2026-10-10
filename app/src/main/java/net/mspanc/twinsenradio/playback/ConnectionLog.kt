package net.mspanc.twinsenradio.playback

import android.content.Context
import android.os.Bundle
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persistent log of connections to the media session.
 *
 * Why: logcat is a ring buffer - after an hour of driving, the most
 * interesting entries have long since fallen out of it. Here we write to
 * disk everything Android Auto tells us about the connection and browse
 * requests, so it can be read after the fact.
 *
 * Android Auto exposes some head-unit layout limits in the options bundle
 * passed while loading children. In particular it may report the maximum
 * number of GRID items per row. There is no current API key for the number
 * of visible grid rows, so we log page/pageSize and every option as well
 * rather than guessing it.
 *
 * What this deliberately does NOT contain and never will: the content of
 * the handshake between Android Auto and the receiver in the car. That
 * conversation happens outside of us and no third-party app can see it.
 *
 * Reading it:
 *   adb shell run-as net.mspanc.twinsenradio cat files/polaczenia.log
 * or tools\pull-log.ps1
 */
object ConnectionLog {

    private const val FILE_NAME = "polaczenia.log"
    private const val MAX_BYTES = 512 * 1024
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Documented Android Auto / MediaBrowserExtras layout hints.
    // Literal keys avoid adding the whole androidx.car.app artifact just for diagnostics.
    private const val KEY_MAX_GRID =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_GRID_ITEMS_COUNT_PER_ROW"
    private const val KEY_MAX_CATEGORY_GRID =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_CATEGORY_GRID_ITEMS_COUNT_PER_ROW"
    private const val KEY_MAX_ITEMS_RESTRICTED =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_ITEMS_WHILE_RESTRICTED"

    /** Do not fill the persistent log with identical browse requests. */
    private val seenBrowseRequests = mutableSetOf<String>()

    fun connected(
        context: Context,
        packageName: String,
        uid: Int,
        controllerVersion: Int,
        interfaceVersion: Int,
        connectionHints: Bundle
    ) {
        write(context, buildString {
            appendLine("== podlaczenie: $packageName (uid=$uid)")
            appendLine("   wersja kontrolera=$controllerVersion, interfejsu=$interfaceVersion")
            appendBundle(connectionHints, "   hint: ")
        })
    }

    /** Hints supplied when the library root is requested. */
    fun libraryRoot(context: Context, packageName: String, rootHints: Bundle?) {
        write(context, buildString {
            appendLine("== korzen biblioteki dla $packageName")
            if (rootHints == null || rootHints.isEmpty) {
                appendLine("   (brak podpowiedzi)")
            } else {
                appendBundle(rootHints, "   ")
            }
        })
    }

    /**
     * Options sent by Android Auto when it asks for a node's children.
     * The documented GRID column limit is delivered here, not in the root hints.
     */
    fun childrenRequest(
        context: Context,
        packageName: String,
        parentId: String,
        page: Int,
        pageSize: Int,
        options: Bundle?
    ) {
        val signature = buildString {
            append(packageName)
            append('|')
            append(parentId)
            append('|')
            append(page)
            append('|')
            append(pageSize)
            append('|')
            append(options?.fingerprint().orEmpty())
        }

        synchronized(seenBrowseRequests) {
            if (!seenBrowseRequests.add(signature)) return
        }

        write(context, buildString {
            appendLine("== browse '$parentId' dla $packageName")
            appendLine("   page=$page, pageSize=$pageSize")

            val gridColumns = options?.intOrNull(KEY_MAX_GRID)
            val categoryGridColumns = options?.intOrNull(KEY_MAX_CATEGORY_GRID)
            val maxItemsRestricted = options?.intOrNull(KEY_MAX_ITEMS_RESTRICTED)

            if (gridColumns != null) appendLine("   GRID: max kolumn=$gridColumns")
            if (categoryGridColumns != null) {
                appendLine("   CATEGORY_GRID: max kolumn=$categoryGridColumns")
            }
            if (maxItemsRestricted != null) {
                appendLine("   max elementow przy ograniczeniu=$maxItemsRestricted")
            }

            // AndroidX exposes a maximum column count, but no current key for
            // the number of visible grid rows. Keep this explicit instead of guessing.
            appendLine("   GRID: liczba widocznych wierszy = brak oficjalnego hintu")

            if (options == null || options.isEmpty) {
                appendLine("   (brak opcji onLoadChildren)")
            } else {
                appendBundle(options, "   opcja: ")
            }
        })
    }

    private fun Bundle.intOrNull(key: String): Int? =
        if (containsKey(key)) getInt(key) else null

    private fun Bundle.fingerprint(): String =
        keySet().sorted().joinToString("|") { key ->
            @Suppress("DEPRECATION")
            "$key=${get(key)}"
        }

    private fun StringBuilder.appendBundle(bundle: Bundle, prefix: String) {
        for (key in bundle.keySet().sorted()) {
            @Suppress("DEPRECATION")
            val value = bundle.get(key)
            appendLine("$prefix$key = $value")
        }
    }

    private fun write(context: Context, text: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            // A simple cap so the log doesn't grow forever over the months
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText("[${LocalDateTime.now().format(STAMP)}] $text")
        }.onFailure { Log.w("ConnectionLog", "failed to save: ${it.message}") }
    }
}
