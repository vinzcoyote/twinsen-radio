package net.mspanc.twinsenradio.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.StationRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up cover art for the track currently playing.
 *
 * How we know this is the right approach: the ICY stream **carries no artwork
 * at all** - it only ever carries text (StreamTitle and optionally StreamUrl).
 * Stations bolt cover art on client-side by querying an external catalogue.
 * Radio Nowy Swiat's web player does exactly what's below - its
 * `neoplayer-min.js` contains:
 *
 *     $.ajax({ url: `https://itunes.apple.com/search?term=${title} ${artist}&media=music` })
 *
 * The iTunes catalogue is free, needs no key or registration, and returns the
 * artwork address in the artworkUrl100 field, which can be swapped for a
 * higher resolution.
 *
 * Fallbacks in case hits are poor: MusicBrainz + Cover Art Archive (free,
 * slower), Deezer API (free), Last.fm and Spotify (require a key).
 */
object CoverArtLookup {

    private const val TAG = "CoverArt"
    private const val ENDPOINT = "https://itunes.apple.com/search"
    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/recording"
    private const val COVER_ART = "https://coverartarchive.org"
    private const val MUSICBRAINZ_AGENT = "TwinsenRadio/0.1 (twinsen@mspanc.net)"

    /**
     * What we managed to find out about the track. Besides artwork, the
     * catalogue also knows the release name and year - which stations
     * usually don't provide.
     */
    data class TrackInfo(
        val artworkUrl: String?,
        val album: String?,
        val year: Int?,
        val isSingle: Boolean,
        /** Track duration from the catalogue; 0 when unknown. */
        val durationMs: Long = 0,
        /**
         * Title and artist **according to the catalogue**. Used to fix up the
         * text when a station shouts in all caps - see [TextCase.tidy].
         */
        val trackName: String? = null,
        val artistName: String? = null
    ) {
        /**
         * e.g. "Księga [2024]" or "singiel [2024]" (the literal produced below) -
         * or just "Księga" when [includeYear] is off.
         */
        fun albumLabel(includeYear: Boolean = true): String? {
            val name = when {
                isSingle -> "single"
                !album.isNullOrBlank() -> TextCase.tidy(album)
                else -> return null
            }
            return if (includeYear && year != null) "$name [$year]" else name
        }

        /**
         * Whether the tail provided by the station is the release name rather
         * than another artist.
         *
         * You can't tell this from the separator alone: RMF writes "Wiktoria
         * Kida / Księga" (artist and album), but it just as happily sends
         * "Shimza / AR/CO / Kasango" (three artists) or "Nico / Vinz" (a band
         * name that contains a slash). Only comparing against what the
         * catalogue knows about the track settles it.
         */
        fun tailIsAlbum(tail: String): Boolean {
            val a = album ?: return false
            return normalize(tail) == normalize(a)
        }

        /**
         * Whether the station gave the artist and title in reverse order.
         *
         * Not everyone sticks to the "Artist - Title" scheme. Jacaranda FM
         * broadcasts it backwards, as seen by sniffing the stream:
         *
         *   StreamTitle='THINKING ABOUT YOU - GOODLUCK'
         *   StreamTitle='WHAT'S LOVE GOT TO DO WITH IT - KYGO [+] TINA TURNER'
         *
         * You can't settle this from the text alone - "Nico / Vinz" could be
         * a band name or two words of a title. Only the catalogue can decide:
         * if what we took for the artist turns out to be the title there, and
         * what we took for the title turns out to be the artist, the fields
         * are swapped.
         *
         * This means stations don't need to be flagged by hand in
         * stations.json, nor guessed at - the fix follows from the data and
         * works for any station that broadcasts this way.
         */
        fun looksSwapped(artist: String?, title: String?): Boolean {
            val a = normalize(artist.orEmpty())
            val t = normalize(title.orEmpty())
            val catalogueTitle = normalize(trackName.orEmpty())
            if (a.isEmpty() || t.isEmpty() || catalogueTitle.isEmpty()) return false

            // A single condition decides it: whether what we took for the
            // artist is the track title in the catalogue. Comparing the
            // artist too doesn't work, because on collaborations the strings
            // diverge - the station gives "KYGO [+] TINA TURNER", while the
            // catalogue just gives "Tina Turner".
            return a == catalogueTitle && t != catalogueTitle
        }

        private fun normalize(s: String) = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    /** Small, bounded cache - in the car we cycle through just a few stations anyway. */
    private const val CACHE_LIMIT = 64
    private val cache = object : LinkedHashMap<String, TrackInfo?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackInfo?>) =
            size > CACHE_LIMIT
    }

    /**
     * @return track data, or null if nothing was found. Null is cached too,
     *   so we don't keep re-querying for tracks that aren't in the catalogue
     *   (and there are plenty of those among Polish stations).
     */
    suspend fun find(artist: String?, title: String?): TrackInfo? = withContext(Dispatchers.IO) {
        val a = artist?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return@withContext null

        val key = "$a|$t".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }

        var result = runCatching { query("$a $t".trim()) }
            .onFailure { Log.w(TAG, "iTunes did not respond: ${it.message}") }
            .getOrNull()

        // iTunes has poor coverage of older Polish repertoire - "Czesław
        // Niemen - Lipowa łyżka" isn't there at all, while MusicBrainz knows
        // both the track and the release. We only query it when Apple
        // doesn't know the track.
        if (result == null && a.isNotEmpty()) {
            result = runCatching { queryMusicBrainz(a, t) }
                .onFailure { Log.w(TAG, "MusicBrainz did not respond: ${it.message}") }
                .getOrNull()
        }

        synchronized(cache) { cache[key] = result }
        Log.i(TAG, "'$a - $t' -> artwork=${result?.artworkUrl != null} album='${result?.albumLabel()}'")
        result
    }

    /**
     * Fallback for tracks not found in iTunes. MusicBrainz is an open
     * database with much better coverage of Polish and older music; artwork
     * is taken from the linked Cover Art Archive.
     */
    private fun queryMusicBrainz(artist: String, title: String): TrackInfo? {
        val query = "artist:\"$artist\" AND recording:\"$title\""
        val url = "$MUSICBRAINZ?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=1"
        val body = httpGet(url) ?: return null

        val recordings = JSONObject(body).optJSONArray("recordings") ?: return null
        if (recordings.length() == 0) return null
        val recording = recordings.getJSONObject(0)
        // Spelling according to the database - useful when a station shouts in all caps
        val catalogueTitle = recording.optString("title").ifBlank { null }
        val catalogueArtist = recording.optJSONArray("artist-credit")
            ?.optJSONObject(0)?.optString("name")?.ifBlank { null }
        val releases = recording.optJSONArray("releases") ?: return null
        if (releases.length() == 0) return null
        val release = releases.getJSONObject(0)

        val album = release.optString("title").ifBlank { null }
        val year = release.optString("date").take(4).toIntOrNull()
        val mbid = release.optString("id").ifBlank { null }

        // Cover Art Archive doesn't have artwork for everything, so we check
        // whether the address returns anything at all - otherwise we'd be
        // sending a dead link to the car.
        val art = mbid?.let { id ->
            val candidate = "$COVER_ART/release/$id/front-500"
            if (headOk(candidate)) candidate else null
        }

        if (album == null && art == null) return null
        return TrackInfo(
            artworkUrl = art,
            album = album,
            year = year,
            isSingle = false,
            durationMs = 0,
            trackName = catalogueTitle,
            artistName = catalogueArtist
        )
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            // MusicBrainz requires a recognizable User-Agent with contact info
            setRequestProperty("User-Agent", MUSICBRAINZ_AGENT)
        }
        try {
            if (conn.responseCode != 200) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun headOk(url: String): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", MUSICBRAINZ_AGENT)
        }
        try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun query(term: String): TrackInfo? {
        val url = "$ENDPOINT?term=${URLEncoder.encode(term, "UTF-8")}&media=music&entity=song&limit=1"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", StationRepository.USER_AGENT)
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val row = results.getJSONObject(0)

            // iTunes returns a 100x100 thumbnail; swapping it in the URL gives full size
            val art = row.optString("artworkUrl100").ifBlank { null }
                ?.replace("100x100bb", "600x600bb")

            val rawAlbum = row.optString("collectionName").ifBlank { null }
            // Singles are named "Title - Single" in the catalogue; in that
            // case the release name adds nothing, and it's better to just
            // write "singiel" outright.
            val isSingle = rawAlbum?.endsWith(" - Single", ignoreCase = true) == true ||
                row.optInt("trackCount", 0) == 1
            val album = rawAlbum?.removeSuffix(" - Single")?.removeSuffix(" - EP")

            val year = row.optString("releaseDate").take(4).toIntOrNull()
            val duration = row.optLong("trackTimeMillis", 0L)

            return TrackInfo(
                artworkUrl = art,
                album = album,
                year = year,
                isSingle = isSingle,
                durationMs = duration,
                trackName = row.optString("trackName").ifBlank { null },
                artistName = row.optString("artistName").ifBlank { null }
            )
        } finally {
            conn.disconnect()
        }
    }
}
