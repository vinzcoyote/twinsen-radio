package net.mspanc.twinsenradio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * App settings. Deliberately backed by SharedPreferences - the playback
 * service reads from it too, and writes are rare.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE)

    /**
     * A mode where every metadata field gets its Polish field name shown
     * instead of its value.
     *
     * OFF by default. It was on until we knew which fields the head unit
     * actually renders - measured on 2026-08-11 (FINDINGS.md), so from now on
     * it's a tool for further experiments, not the default state.
     */
    var diagnosticMode: Boolean
        get() = sp.getBoolean(KEY_DIAG, false)
        set(v) = sp.edit { putBoolean(KEY_DIAG, v) }

    /**
     * Whether to strip ICY metadata while in diagnostic mode.
     *
     * OFF by default. Stripping gives fully deterministic labels, but takes
     * away the ability to see what and how often a station actually
     * broadcasts - which is its own interesting topic. ICY only overwrites
     * `title`, `station`, and `genre`; the fields Android Auto actually shows
     * (`displayTitle`, `subtitle`) stay ours.
     */
    var stripIcyInDiagnostic: Boolean
        get() = sp.getBoolean(KEY_STRIP_ICY, false)
        set(v) = sp.edit { putBoolean(KEY_STRIP_ICY, v) }

    /** Whether to append the API field name to the Polish label, e.g. "TYTUL<title>". */
    var diagnosticShowApiName: Boolean
        get() = sp.getBoolean(KEY_DIAG_API, false)
        set(v) = sp.edit { putBoolean(KEY_DIAG_API, v) }

    /** Presentation scheme for folders in Android Auto (see [ContentStyle]). */
    var browsableStyle: Int
        get() = sp.getInt(KEY_STYLE_BROWSABLE, ContentStyle.CATEGORY_LIST)
        set(v) = sp.edit { putInt(KEY_STYLE_BROWSABLE, v) }

    /** Presentation scheme for stations in Android Auto. */
    var playableStyle: Int
        get() = sp.getInt(KEY_STYLE_PLAYABLE, ContentStyle.LIST)
        set(v) = sp.edit { putInt(KEY_STYLE_PLAYABLE, v) }

    /**
     * Content of the successive description lines. Numbered like on the AID,
     * top to bottom - see the comment in [Presentation], which explains which
     * line goes into which metadata field and why they can't be split between
     * the AID and the head unit screen.
     */
    var lineTop: Int
        get() = sp.getInt(KEY_LINE_TOP, Presentation.DEFAULT.top.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_TOP, v) }

    var lineMiddle: Int
        get() = sp.getInt(KEY_LINE_MIDDLE, Presentation.DEFAULT.middle.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_MIDDLE, v) }

    var lineBottom: Int
        get() = sp.getInt(KEY_LINE_BOTTOM, Presentation.DEFAULT.bottom.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_BOTTOM, v) }

    /** Whether to draw a clock instead of cover art, see [ClockFace]. */
    var clockFace: Int
        get() = sp.getInt(KEY_CLOCK_FACE, Presentation.DEFAULT.clockFace.ordinal)
        set(v) = sp.edit { putInt(KEY_CLOCK_FACE, v) }

    /** The full set of description settings, assembled from the above. */
    val presentation: Presentation
        get() = Presentation(
            top = LineContent.at(lineTop),
            middle = LineContent.at(lineMiddle),
            bottom = LineContent.at(lineBottom),
            clockFace = ClockFace.at(clockFace)
        )

    /**
     * With a clock instead of cover art: whether it should always be visible
     * (true), or only when we'd have shown the station logo anyway because no
     * cover art was found.
     */
    var clockCoverAlways: Boolean
        get() = sp.getBoolean(KEY_CLOCK_ALWAYS, false)
        set(v) = sp.edit { putBoolean(KEY_CLOCK_ALWAYS, v) }

    /** Background color of the clock drawn instead of cover art. */
    var clockBackground: Int
        get() = sp.getInt(KEY_CLOCK_BG, 0)
        set(v) = sp.edit { putInt(KEY_CLOCK_BG, v) }

    /** Color of the clock digits and hands; index 0 means automatic selection. */
    var clockForeground: Int
        get() = sp.getInt(KEY_CLOCK_FG, 0)
        set(v) = sp.edit { putInt(KEY_CLOCK_FG, v) }

    /**
     * Whether to append the year to the album in the "artist/title · album"
     * lines, when the catalog knows it. The album itself always shows when
     * available - this only controls the year suffix.
     */
    var enrichWithYear: Boolean
        get() = sp.getBoolean(KEY_ENRICH_ALBUM, true)
        set(v) = sp.edit { putBoolean(KEY_ENRICH_ALBUM, v) }

    /** Buffer profile index, see [BufferProfile.ALL]. */
    var bufferProfile: Int
        get() = sp.getInt(KEY_BUFFER, 1)
        set(v) = sp.edit { putInt(KEY_BUFFER, v) }

    /** How cover art is delivered to Android Auto, see [ArtworkMode]. */
    var artworkMode: Int
        get() = sp.getInt(KEY_ARTWORK, ArtworkMode.RESOURCE_URI)
        set(v) = sp.edit { putInt(KEY_ARTWORK, v) }

    var userM3uUrls: List<String>
        get() = sp.getString(KEY_M3U, "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        set(v) = sp.edit { putString(KEY_M3U, v.joinToString("\n")) }

    init {
        // A single source of truth for all screens, seeded on first use
        synchronized(FAV_LOCK) {
            if (!favouritesSeeded) {
                _favourites.value = sp.getStringSet(KEY_FAV, emptySet()).orEmpty()
                favouritesSeeded = true
            }
        }
        synchronized(DISCOVERED_LOCK) {
            if (!discoveredSeeded) {
                _discovered.value = readDiscovered()
                discoveredSeeded = true
            }
        }
        synchronized(HIDDEN_LOCK) {
            if (!hiddenSeeded) {
                _hidden.value = sp.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
                hiddenSeeded = true
            }
        }
    }

    var favourites: Set<String>
        get() = _favourites.value
        set(v) {
            sp.edit { putStringSet(KEY_FAV, v) }
            _favourites.value = v
        }

    /** @return true if the station was added to favourites, false if it was removed. */
    fun toggleFavourite(stationId: String): Boolean {
        val cur = favourites.toMutableSet()
        val added = if (cur.contains(stationId)) {
            cur.remove(stationId)
            false
        } else {
            cur.add(stationId)
            true
        }
        favourites = cur
        return added
    }

    /**
     * Stations added manually from the online directory. We keep the full
     * data, not just the identifier: the directory can stop responding or
     * delete an entry, and a station once added should keep working in the
     * car even without access to the directory.
     */
    val discovered: List<Station> get() = _discovered.value

    fun addDiscovered(station: Station) {
        if (discovered.any { it.id == station.id }) return
        saveDiscovered(discovered + station)
    }

    fun removeDiscovered(stationId: String) {
        saveDiscovered(discovered.filterNot { it.id == stationId })
    }

    fun isDiscovered(stationId: String): Boolean = discovered.any { it.id == stationId }

    private fun saveDiscovered(list: List<Station>) {
        val arr = JSONArray()
        list.forEach { s ->
            val variants = JSONArray()
            s.streams.forEach { v ->
                variants.put(
                    JSONObject().put("url", v.url).put("label", v.label).put("kbps", v.kbps)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("genre", s.genre)
                    .put("stream", s.stream)
                    .put("streams", variants)
                    .put("logoUrl", s.logoUrl ?: "")
            )
        }
        sp.edit { putString(KEY_DISCOVERED, arr.toString()) }
        _discovered.value = list
    }

    private fun readDiscovered(): List<Station> = runCatching {
        val raw = sp.getString(KEY_DISCOVERED, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
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
                genre = o.optString("genre", "Z sieci"),
                stream = o.getString("stream"),
                streams = variants,
                logoUrl = o.optString("logoUrl").ifBlank { null },
                source = Station.Source.DISCOVERED
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Search history - separate for the local list and for the online
     * directory, because they're two different worlds: here you search for
     * "rmf", there for "jazz radio paris". Newest first, no duplicates.
     */
    var localSearchHistory: List<String>
        get() = readHistory(KEY_HISTORY_LOCAL)
        set(v) = writeHistory(KEY_HISTORY_LOCAL, v)

    var webSearchHistory: List<String>
        get() = readHistory(KEY_HISTORY_WEB)
        set(v) = writeHistory(KEY_HISTORY_WEB, v)

    fun pushLocalSearch(query: String) = pushHistory(KEY_HISTORY_LOCAL, query)

    fun pushWebSearch(query: String) = pushHistory(KEY_HISTORY_WEB, query)

    private fun pushHistory(key: String, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val current = readHistory(key).filterNot { it.equals(q, ignoreCase = true) }
        writeHistory(key, listOf(q) + current)
    }

    private fun readHistory(key: String): List<String> =
        sp.getString(key, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun writeHistory(key: String, values: List<String>) =
        sp.edit { putString(key, values.take(HISTORY_LIMIT).joinToString("\n")) }

    /**
     * Built-in stations that the user has removed from the list.
     *
     * Built-in stations can't actually be deleted - they live in assets - so
     * instead we keep a set of hidden ones and skip them when building the
     * list. That makes removal reversible, and the list file stays untouched.
     */
    val hidden: Set<String> get() = _hidden.value

    fun hide(stationId: String) {
        saveHidden(hidden + stationId)
    }

    fun unhide(stationId: String) {
        saveHidden(hidden - stationId)
    }

    fun restoreAllHidden() = saveHidden(emptySet())

    fun isHidden(stationId: String) = stationId in hidden

    private fun saveHidden(value: Set<String>) {
        sp.edit { putStringSet(KEY_HIDDEN, value) }
        _hidden.value = value
    }

    /**
     * The chosen stream variant for a station. No entry = we take the best one
     * the station offers. We store the address, not the position index - the
     * variant list can change when the app is updated.
     */
    fun selectedStream(stationId: String): String? =
        sp.getString(KEY_STREAM_PREFIX + stationId, null)

    fun setSelectedStream(stationId: String, url: String?) = sp.edit {
        if (url == null) remove(KEY_STREAM_PREFIX + stationId) else putString(KEY_STREAM_PREFIX + stationId, url)
    }

    /**
     * A manually entered address. Takes priority over everything - even for
     * built-in stations, whose list file can't be edited.
     */
    fun customStream(stationId: String): String? =
        sp.getString(KEY_CUSTOM_STREAM_PREFIX + stationId, null)?.takeIf { it.isNotBlank() }

    fun setCustomStream(stationId: String, url: String?) = sp.edit {
        if (url.isNullOrBlank()) {
            remove(KEY_CUSTOM_STREAM_PREFIX + stationId)
        } else {
            putString(KEY_CUSTOM_STREAM_PREFIX + stationId, url.trim())
        }
    }

    /** Custom logo uploaded by the user - a path to a file in the app's directory. */
    fun customLogo(stationId: String): String? =
        sp.getString(KEY_CUSTOM_LOGO_PREFIX + stationId, null)?.takeIf { it.isNotBlank() }

    fun setCustomLogo(stationId: String, path: String?) = sp.edit {
        if (path.isNullOrBlank()) {
            remove(KEY_CUSTOM_LOGO_PREFIX + stationId)
        } else {
            putString(KEY_CUSTOM_LOGO_PREFIX + stationId, path)
        }
    }

    /**
     * How many times the station has been turned on. Used to sort by "most
     * frequently listened to" - after a few weeks of driving this is the best
     * ordering we can offer.
     */
    fun playCount(stationId: String): Int = sp.getInt(KEY_PLAYS_PREFIX + stationId, 0)

    fun bumpPlayCount(stationId: String) =
        sp.edit { putInt(KEY_PLAYS_PREFIX + stationId, playCount(stationId) + 1) }

    /** Station list order, see [StationSort]. */
    var stationSort: StationSort
        get() = StationSort.at(sp.getInt(KEY_SORT_STATIONS, 0))
        set(v) = sp.edit { putInt(KEY_SORT_STATIONS, v.ordinal) }

    /** Search results order in the directory, see [DiscoverSort]. */
    var discoverSort: DiscoverSort
        get() = DiscoverSort.at(sp.getInt(KEY_SORT_DISCOVER, 0))
        set(v) = sp.edit { putInt(KEY_SORT_DISCOVER, v.ordinal) }

    /** Recently listened to, newest first. */
    var recent: List<String>
        get() = sp.getString(KEY_RECENT, "").orEmpty().lines().filter { it.isNotBlank() }
        set(v) = sp.edit { putString(KEY_RECENT, v.take(20).joinToString("\n")) }

    fun pushRecent(stationId: String) {
        recent = listOf(stationId) + recent.filter { it != stationId }
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        /**
         * Favourites as a stream, rather than polling preferences on demand.
         *
         * Previously each screen read them at its own moment - the list when
         * coming to the foreground, the playback screen when rendering,
         * Android Auto when building buttons - and the states would drift
         * apart from each other. Now there's a single source of truth that
         * announces changes, and everyone interested observes it.
         */
        private val _favourites = MutableStateFlow<Set<String>>(emptySet())
        val favouritesFlow: StateFlow<Set<String>> = _favourites

        private var favouritesSeeded = false
        private val FAV_LOCK = Any()

        /**
         * Stations pulled in from the directory - just like favourites, a
         * single source of truth with a change stream, so the list on the
         * phone and the tree in the car rebuild at the same moment.
         */
        private val _discovered = MutableStateFlow<List<Station>>(emptyList())
        val discoveredFlow: StateFlow<List<Station>> = _discovered

        private var discoveredSeeded = false
        private val DISCOVERED_LOCK = Any()

        /** Hidden built-in stations - observable just like favourites. */
        private val _hidden = MutableStateFlow<Set<String>>(emptySet())
        val hiddenFlow: StateFlow<Set<String>> = _hidden

        private var hiddenSeeded = false
        private val HIDDEN_LOCK = Any()

        const val KEY_DIAG = "diagnostic_mode"
        const val KEY_DIAG_API = "diagnostic_api_names"
        const val KEY_STRIP_ICY = "strip_icy_in_diagnostic"
        const val KEY_LINE_TOP = "line_top"
        const val KEY_LINE_MIDDLE = "line_middle"
        const val KEY_LINE_BOTTOM = "line_bottom"
        const val KEY_CLOCK_FACE = "clock_face"
        const val KEY_CLOCK_ALWAYS = "clock_cover_always"
        const val KEY_CLOCK_BG = "clock_background"
        const val KEY_CLOCK_FG = "clock_foreground"
        const val KEY_ENRICH_ALBUM = "enrich_with_album"
        const val KEY_SWAP = "swap_title_artist"
        const val KEY_STYLE_BROWSABLE = "aa_style_browsable"
        const val KEY_STYLE_PLAYABLE = "aa_style_playable"
        const val KEY_BUFFER = "buffer_profile"
        const val KEY_ARTWORK = "artwork_mode"
        const val KEY_M3U = "user_m3u"
        const val KEY_FAV = "favourites"
        const val KEY_RECENT = "recent"
        const val KEY_DISCOVERED = "discovered_stations"
        const val KEY_HIDDEN = "hidden_stations"
        const val KEY_SORT_STATIONS = "sort_stations"
        const val KEY_SORT_DISCOVER = "sort_discover"

        // Station-dependent keys - the full key is the prefix plus its identifier
        private const val KEY_STREAM_PREFIX = "stream_choice_"
        private const val KEY_CUSTOM_STREAM_PREFIX = "stream_custom_"
        private const val KEY_CUSTOM_LOGO_PREFIX = "logo_custom_"
        private const val KEY_PLAYS_PREFIX = "plays_"
        const val KEY_HISTORY_LOCAL = "search_history_local"
        const val KEY_HISTORY_WEB = "search_history_web"

        /** This many entries is enough - a longer list wouldn't fit on screen anyway. */
        private const val HISTORY_LIMIT = 10
    }
}

/**
 * The four presentation schemes that Android Auto actually exposes to media
 * apps (the CONTENT_STYLE_* keys in the MediaBrowser extensions).
 */

object ContentStyle {
    const val LIST = 1
    const val GRID = 2
    const val CATEGORY_LIST = 3
    const val CATEGORY_GRID = 4

    const val EXTRA_SUPPORTED =
        "android.media.browse.CONTENT_STYLE_SUPPORTED"

    const val EXTRA_BROWSABLE_HINT =
        "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"

    const val EXTRA_PLAYABLE_HINT =
        "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    val LABELS = listOf(
        "Liste",
        "Grille",
        "Liste de catégories",
        "Grille de catégories"
    )

    fun indexToValue(i: Int) = i + 1

    fun valueToIndex(v: Int) =
        (v - 1).coerceIn(0, 3)
}

object ArtworkMode {

    /** android.resource:// - Android Auto fetches the logo directly from APK resources. */
    const val RESOURCE_URI = 0

    /** PNG bytes in the artworkData field. */
    const val EMBEDDED_BYTES = 1

    /** No cover art. */
    const val NONE = 2

    val LABELS = listOf(
        "URI de ressource (android.resource://)",
        "Données intégrées (artworkData)",
        "Sans pochette"
    )
}

/** Buffering profiles. Values in ms. */
data class BufferProfile(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val forPlaybackMs: Int,
    val afterRebufferMs: Int
) {

    companion object {

        val ALL = listOf(
            BufferProfile(
                "Petit — démarrage ~1 s, réserve 20 s",
                20_000,
                30_000,
                1_000,
                3_000
            ),

            BufferProfile(
                "Moyen — démarrage ~2 s, réserve 45 s (par défaut)",
                45_000,
                75_000,
                2_000,
                6_000
            ),

            BufferProfile(
                "Grand — démarrage ~4 s, réserve 120 s",
                120_000,
                180_000,
                4_000,
                12_000
            )
        )

        fun at(index: Int) =
            ALL.getOrElse(index) { ALL[1] }
    }
}
