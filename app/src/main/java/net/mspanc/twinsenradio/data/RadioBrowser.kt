package net.mspanc.twinsenradio.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Search for stations outside the built-in list.
 *
 * Source: **radio-browser.info** - an open, community-run directory of roughly
 * 50k stations. Chosen because it's the only sensible option that meets the
 * full set of requirements: it's free, needs no key or registration, has a
 * clear data license, a public API, and - most importantly - filters out dead
 * streams itself (`hidebroken`) by checking them periodically. Alternatives
 * fell short: TuneIn and iHeartRadio have no open API, Shoutcast requires a
 * manually issued key, and nobody maintains M3U lists found around the web.
 *
 * Watch the field order: `url` can be a redirect or a .pls file, while
 * `url_resolved` is already a concrete stream - so that's what we use.
 */
object RadioBrowser {

    private const val TAG = "RadioBrowser"

    /**
     * Directory servers. `all.api` spreads traffic across live nodes, but it can
     * fail to respond - in that case we fall back to specific mirrors one by
     * one. The API's terms require a recognizable User-Agent, which we honor.
     */
    private val MIRRORS = listOf(
        "https://all.api.radio-browser.info",
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    /** A single entry from the directory, not yet added to the user's list. */
    data class Found(
        val uuid: String,
        val name: String,
        val stream: String,
        val faviconUrl: String?,
        val country: String?,
        val tags: String?,
        val codec: String?,
        val bitrate: Int,
        /** Fields for the details screen only - they don't fit in the results list. */
        val countryName: String? = null,
        val language: String? = null,
        val homepage: String? = null,
        val votes: Int = 0,
        /**
         * When the directory last confirmed the stream was working (UTC).
         * More important than it looks - see [isCheckStale].
         */
        val lastCheckOk: String? = null
    ) {
        /**
         * Whether the confirmation is old enough that it no longer means anything.
         *
         * The directory checks live stations roughly once a day, so a date from
         * months ago means the checker gave up long ago, and the "working" flag
         * is just left over from the last successful test. That's exactly what
         * Triple M Melbourne looked like: lastcheckok = 1 with a date seven
         * months back, on a server with no DNS record left.
         */
        fun isCheckStale(): Boolean = daysSinceCheck()?.let { it > STALE_AFTER_DAYS } ?: false

        fun daysSinceCheck(): Long? {
            val raw = lastCheckOk?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val checked = java.time.LocalDateTime.parse(raw.replace(' ', 'T'))
                java.time.Duration.between(checked, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .toDays()
            }.getOrNull()
        }
        /** Second line in the results list: "PL · MP3 128 kb/s · rock". */
        fun describe(): String = listOfNotNull(
            country?.takeIf { it.isNotBlank() },
            listOfNotNull(
                codec?.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", true) },
                bitrate.takeIf { it > 0 }?.let { "$it kb/s" }
            ).joinToString(" ").takeIf { it.isNotBlank() },
            tags?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        /**
         * The other addresses of the same station, found in the directory under
         * the same name. Filled in while merging results - see [merge].
         */
        var alternates: List<StreamVariant> = emptyList()

        fun toStation(): Station {
            val own = StreamVariant(stream, variantLabel(), bitrate)
            return Station(
                id = "$DISCOVERED_PREFIX$uuid",
                name = name,
                genre = tags?.split(',')?.firstOrNull()?.trim()?.replaceFirstChar { it.uppercase() }
                    ?.takeIf { it.isNotBlank() } ?: "Internet",
                stream = stream,
                streams = (listOf(own) + alternates).sortedByDescending { it.kbps },
                logoUrl = faviconUrl,
                source = Station.Source.DISCOVERED
            )
        }

        /** e.g. "MP3 128 kb/s". The directory doesn't provide labels, so we build them ourselves. */
        fun variantLabel(): String = listOfNotNull(
            codec?.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", true) }?.uppercase(),
            bitrate.takeIf { it > 0 }?.let { "$it kb/s" }
        ).joinToString(" ").ifBlank { Station.DEFAULT_LABEL }
    }

    /**
     * @param query fragment of the station name; an empty query makes no sense,
     *   since the directory would then hand back a random 50 thousand entries.
     */
    suspend fun search(query: String, limit: Int = 40): List<Found> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        val path = "/json/stations/search?limit=$limit&hidebroken=true" +
            "&order=votes&reverse=true&name=${URLEncoder.encode(q, "UTF-8")}"

        for (mirror in MIRRORS) {
            val body = runCatching { get(mirror + path) }
                .onFailure { Log.w(TAG, "$mirror did not respond: ${it.message}") }
                .getOrNull() ?: continue
            val parsed = runCatching { parse(body) }.getOrNull() ?: continue
            Log.i(TAG, "'$q' -> ${parsed.size} wynikow z $mirror")
            return@withContext parsed
        }
        Log.w(TAG, "no catalog server responded")
        emptyList()
    }

    /**
     * Merges entries that are actually the same station broadcasting under
     * several addresses.
     *
     * The directory keeps each stream as a separate entry, so "Jazz Radio" can
     * show up four times - once in MP3 128, once in AAC 64, and so on. Instead
     * of flooding the user with duplicates, we keep a single entry (the one
     * with the best bitrate) and attach the rest as selectable variants.
     */
    private fun merge(found: List<Found>): List<Found> {
        val groups = LinkedHashMap<String, MutableList<Found>>()
        found.forEach { f ->
            val key = f.name.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "") + "|" + f.country
            groups.getOrPut(key) { mutableListOf() }.add(f)
        }
        return groups.values.map { group ->
            val best = group.maxByOrNull { it.bitrate } ?: group.first()
            best.alternates = group.filter { it !== best }
                .map { StreamVariant(it.stream, it.variantLabel(), it.bitrate) }
            best
        }
    }

    private fun parse(body: String): List<Found> {
        val arr = JSONArray(body)
        val seen = HashSet<String>()
        val found = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val stream = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (stream.isBlank() || name.isBlank()) return@mapNotNull null
            // No reason for the same ADDRESS to appear twice; different addresses
            // of the same station only get merged by merge() below.
            if (!seen.add(stream)) return@mapNotNull null
            Found(
                uuid = o.optString("stationuuid").ifBlank { stream.hashCode().toString() },
                name = name,
                stream = stream,
                faviconUrl = o.optString("favicon").ifBlank { null },
                country = o.optString("countrycode").ifBlank { null },
                tags = o.optString("tags").ifBlank { null },
                codec = o.optString("codec").ifBlank { null },
                bitrate = o.optInt("bitrate", 0),
                countryName = o.optString("country").ifBlank { null },
                language = o.optString("language").ifBlank { null },
                homepage = o.optString("homepage").ifBlank { null },
                votes = o.optInt("votes", 0),
                lastCheckOk = o.optString("lastcheckoktime").ifBlank { null }
            )
        }
        return merge(found)
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    const val DISCOVERED_PREFIX = "rb:"

    /**
     * Beyond this many days since the last successful check, we treat the
     * directory's confirmation as worthless. Live stations are checked roughly
     * once a day, so a month is already a very generous margin.
     */
    private const val STALE_AFTER_DAYS = 30L
}
