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
 * disk everything Android Auto told us at connection time, so it can be
 * read after the fact.
 *
 * What this deliberately does NOT contain and never will: the content of
 * the handshake between Android Auto and the receiver in the car. That
 * conversation happens outside of us and no third-party app can see it.
 * What we do have are the root hints that Android Auto passes to apps -
 * and those reflect the head unit's limitations, for example the requested
 * artwork size or the action limit on list items.
 *
 * Reading it:
 *   adb shell run-as net.mspanc.twinsenradio cat files/polaczenia.log
 * or tools\pull-log.ps1
 */
object ConnectionLog {

    private const val FILE_NAME = "polaczenia.log"
    private const val MAX_BYTES = 512 * 1024
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

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

    /**
     * Hints passed along when the library root is requested. This is where
     * the head unit's limitations land - artwork size, item limit, action limit.
     */
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
