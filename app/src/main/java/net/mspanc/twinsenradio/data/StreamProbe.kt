package net.mspanc.twinsenradio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Checks right here, right now, whether a stream responds at all.
 *
 * Why bother, given the directory has a "working" flag: that flag comes from
 * the **last** check, which can be months old. Triple M Melbourne had
 * `lastcheckok = 1` dated 2026-01-15, while the server `wz3drp.scahw.com.au`
 * no longer even has a DNS record. Without our own probe, such an entry would
 * load into the list and buffer forever.
 *
 * The probe deliberately doesn't fetch the whole stream - the beginning is
 * enough to tell a working server from a dead one.
 */
object StreamProbe {

    enum class Result {
        /** The server responded and is returning data. */
        OK,

        /** The hostname doesn't exist - the server has been decommissioned. */
        NO_HOST,

        /** The server responded with an error, e.g. 403 for a regional block. */
        HTTP_ERROR,

        /** Nothing arrived within the given time. */
        TIMEOUT,

        /** Anything else - no network, TLS error. */
        FAILED
    }

    data class Report(val result: Result, val detail: String)

    suspend fun check(url: String): Report = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", StationRepository.USER_AGENT)
                // Ask for ICY metadata - as a side effect this checks whether the station provides it
                setRequestProperty("Icy-MetaData", "1")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val extra = if (code == 403) " (blocage régional ?)" else ""
                    return@withContext Report(Result.HTTP_ERROR, "HTTP $code$extra")
                }
                // Whether something is actually streaming - a bare 200 code can just be an error page
                val buffer = ByteArray(PROBE_BYTES)
                val read = conn.inputStream.use { it.read(buffer) }
                if (read <= 0) {
                    return@withContext Report(Result.FAILED, "le serveur n\'a renvoyé aucune donnée")
                }
                val type = conn.contentType.orEmpty()
                val bitrate = conn.getHeaderField("icy-br")
                val detail = buildString {
                    append(type.substringBefore(';').ifBlank { "format inconnu" })
                    if (!bitrate.isNullOrBlank()) append(" · $bitrate kb/s")
                }
                Report(Result.OK, detail)
            } finally {
                conn.disconnect()
            }
        } catch (e: UnknownHostException) {
            Report(Result.NO_HOST, "le serveur ${e.message} n\'existe pas")
        } catch (e: java.net.SocketTimeoutException) {
            Report(Result.TIMEOUT, "le serveur ne répond pas")
        } catch (e: Exception) {
            Report(Result.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private const val TIMEOUT_MS = 8_000
    private const val PROBE_BYTES = 1024
}
