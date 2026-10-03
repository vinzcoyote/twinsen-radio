package net.mspanc.twinsenradio.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Source of the station list: the built-in assets/stations.json plus any M3U
 * lists provided by the user in Options. Keeps the result in memory, because
 * Android Auto can query the browsing tree frequently.
 */
class StationRepository private constructor(private val appContext: Context) {

    private val prefs = Prefs(appContext)

    @Volatile
    private var cache: List<Station> = emptyList()

    @Volatile
    private var userLoadedFor: List<String> = listOf("<never>")

    /** Built-in list - always available, doesn't require network access. */
    val builtIn: List<Station> by lazy { readBuiltIn() }

    /**
     * Full list: built-in + from M3U lists + stations added from the online catalog.
     *
     * We read the fetched ones on every call, not from `cache`: they live in a
     * stream in [Prefs] and can change at any moment, including from another
     * screen. There are at most a dozen or so of them, so there's nothing to optimize.
     */
    fun all(): List<Station> {
        if (cache.isEmpty()) cache = builtIn
        val extra = prefs.discovered
        val full = if (extra.isEmpty()) cache else cache + extra
        // We filter out hidden ones here, in one place - this way they disappear
        // everywhere at once: in the phone list, in search, and in the car's browsing tree.
        val hidden = prefs.hidden
        val visible = if (hidden.isEmpty()) full else full.filterNot { it.id in hidden }
        return sorted(visible.map(::resolve))
    }

    /**
     * Ordering of the list. By default, the order from the file - arranged
     * thematically and thought out - or, on request, alphabetically or by
     * what the user actually listens to.
     */
    private fun sorted(stations: List<Station>): List<Station> = when (prefs.stationSort) {
        StationSort.MOST_PLAYED ->
            stations.sortedWith(
                compareByDescending<Station> { prefs.playCount(it.id) }.thenBy { fold(it.name) }
            )
        StationSort.NAME -> stations.sortedBy { fold(it.name) }
        StationSort.GENRE -> stations.sortedWith(compareBy({ fold(it.genre) }, { fold(it.name) }))
        StationSort.DEFAULT -> stations
    }

    /** Full list including hidden stations - needed only for restoring them. */
    fun allIncludingHidden(): List<Station> {
        if (cache.isEmpty()) cache = builtIn
        return (cache + prefs.discovered).map(::resolve)
    }

    fun byId(id: String): Station? = all().firstOrNull { it.id == id }

    fun byMediaId(mediaId: String): Station? =
        Station.idFromMediaId(mediaId)?.let { byId(it) }

    fun favourites(): List<Station> {
        val fav = prefs.favourites
        return all().filter { it.id in fav }
    }

    fun recent(): List<Station> {
        val order = prefs.recent
        return order.mapNotNull { byId(it) }
    }

    fun genres(): List<String> = all().map { it.genre }.distinct().sorted()

    fun byGenre(genre: String): List<Station> = all().filter { it.genre == genre }

    /**
     * Search that's resistant to Polish characters and case - in the car, the
     * query comes from speech recognition and rarely hits diacritics correctly.
     */
    fun search(query: String): List<Station> {
        val q = fold(query)
        if (q.isBlank()) return all()
        return all().filter { fold(it.name).contains(q) || fold(it.genre).contains(q) }
    }

    /** Fetches the user's M3U lists. Safe to call multiple times. */
    suspend fun refreshUserLists(force: Boolean = false) = withContext(Dispatchers.IO) {
        val urls = prefs.userM3uUrls
        if (!force && urls == userLoadedFor) return@withContext
        val extra = mutableListOf<Station>()
        urls.forEachIndexed { index, url ->
            runCatching { fetchText(url) }
                .onSuccess { body -> extra += M3uParser.parse(body, index.toString()) }
                .onFailure { Log.w(TAG, "Failed to fetch list $url: ${it.message}") }
        }
        cache = builtIn + extra
        userLoadedFor = urls
    }

    private fun readBuiltIn(): List<Station> = runCatching {
        val json = appContext.assets.open("stations.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // A station provides either a single "stream" or a list of "streams".
            // Both forms are valid - most stations have just one address.
            val variants = o.optJSONArray("streams")?.let { list ->
                (0 until list.length()).map { j ->
                    val v = list.getJSONObject(j)
                    StreamVariant(
                        url = v.getString("url"),
                        label = v.optString("label").ifBlank { Station.DEFAULT_LABEL },
                        kbps = v.optInt("kbps", 0)
                    )
                }
            }.orEmpty()

            Station(
                id = o.getString("id"),
                name = o.getString("name"),
                genre = o.optString("genre", "Inne"),
                stream = variants.firstOrNull()?.url ?: o.getString("stream"),
                streams = variants,
                // "logo" is a built-in resource (for logo_placeholder etc.), "logoUrl"
                // is the station's remote artwork - we don't distribute stations'
                // own logos in the repo, we just link to their servers.
                logo = o.optString("logo").ifBlank { null },
                logoUrl = o.optString("logoUrl").ifBlank { null }
            )
        }
    }.getOrElse {
        Log.e(TAG, "Failed to read stations.json", it)
        emptyList()
    }

    /**
     * Replaces the address with the one the user actually wants to listen to.
     *
     * Order: a custom address entered manually, then the selected variant, and
     * if there was no selection - the variant with the highest bitrate. This
     * way the rest of the app (the player, Android Auto) still just sees
     * `station.stream` and doesn't need to know anything about variants.
     */
    private fun resolve(station: Station): Station {
        prefs.customStream(station.id)?.let { return station.copy(stream = it) }
        val variants = station.variants()
        val chosen = prefs.selectedStream(station.id)
        val variant = variants.firstOrNull { it.url == chosen }
            ?: variants.maxByOrNull { it.kbps }
            ?: return station
        return station.copy(stream = variant.url)
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ł", "l")

    companion object {
        private const val TAG = "StationRepository"
        const val USER_AGENT = "TwinsenRadio/0.1 (Android)"

        @Volatile
        private var instance: StationRepository? = null

        fun get(context: Context): StationRepository =
            instance ?: synchronized(this) {
                instance ?: StationRepository(context.applicationContext).also { instance = it }
            }
    }
}
