package net.mspanc.twinsenradio.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ContentStyle
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.ui.MainActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Playback service that is also the browse tree source for Android Auto.
 *
 * Media3's MediaLibraryService exposes both the new session API and the old
 * MediaBrowserService, which Android Auto still uses - that's why the manifest
 * has two intent filters.
 */
@UnstableApi
class RadioService : MediaLibraryService() {

    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var reconnect: ReconnectController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastRawIcyTitle: String? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockTick: Runnable? = null
    private var lastIcyAtMs = 0L

    /**
     * Artwork currently exposed to the phone and Android Auto.
     * Priority: online track cover -> DAB MOT slideshow -> station logo (null here).
     */
    @Volatile
    private var coverArtUrl: String? = null

    /** What the catalog knows about the current track - release, year, cover art. */
    @Volatile
    private var trackInfo: CoverArtLookup.TrackInfo? = null

    /**
     * Last DAB MOT revision seen for the currently tuned station.
     * Kept across track changes so we can tell an old slide from a genuinely new one.
     */
    private var lastDabMotRevision: Long? = null

    /** Increments on every track change - filters out stale search results. */
    private var coverGeneration = 0
    private var coverRevertJob: Job? = null

    /**
     * The track that the cover art held in [coverArtUrl] belongs to. Used to
     * detect the moment when the cover art stops matching what's playing.
     */
    private var coverTrackKey: String? = null

    /** Format from the decoder and bitrate from the ICY header - together they give the quality description. */
    @Volatile
    private var audioFormat: androidx.media3.common.Format? = null

    @Volatile
    private var icyBitrateKbps = 0

    /** Waits after a control marker for whatever the station inserts next. */
    private var pendingMarkerJob: Job? = null

    /** Watches whether the track description has gone stale because the station announced nothing. */
    private var staleJob: Job? = null

    /** Fingerprint of the last metadata dump - filters out repeats in the log. */
    private var lastDump: String? = null

    /** Address we're playing from - used to detect a quality variant change. */
    private var currentStreamUrl: String? = null

    /** Last station slogan - shown when we don't know what's currently playing. */
    @Volatile
    private var lastSlogan: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_AA_FAVOURITES_GRID, Prefs.KEY_AA_ALL_GRID, Prefs.KEY_M3U -> {
                session.notifyChildrenChanged(NODE_ROOT, Int.MAX_VALUE, null)
                BROWSE_NODES.forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
            }
            Prefs.KEY_DIAG -> {
                refreshCurrentMetadata(force = true)
                // Stripping ICY happens when the data source is created, so for
                // the toggle to take effect immediately the stream must be re-prepared.
                if (player.playWhenReady && player.currentMediaItem != null) {
                    player.prepare()
                }
            }
            Prefs.KEY_DIAG_API, Prefs.KEY_ARTWORK,
            Prefs.KEY_LINE_TOP, Prefs.KEY_LINE_MIDDLE, Prefs.KEY_LINE_BOTTOM,
            Prefs.KEY_CLOCK_FACE, Prefs.KEY_CLOCK_ALWAYS, Prefs.KEY_CLOCK_BG,
            Prefs.KEY_CLOCK_FG, Prefs.KEY_ENRICH_ALBUM -> refreshCurrentMetadata(force = true)
            Prefs.KEY_BUFFER -> Log.i(TAG, "Buffer changed - applies after playback restart")
            // A quality change or a custom address change concerns a specific station.
            // The keys are prefixed with its identifier, so we check the beginning.
            else -> if (key?.startsWith("stream_") == true) reloadCurrentStation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = StationRepository.get(this)
        metadata = MetadataFactory(this, prefs)

        player = buildPlayer()
        player.addListener(PlayerEvents())
        player.addAnalyticsListener(
            LoadDiagnostics { format ->
                audioFormat = format
                publishQuality()
            }
        )

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaLibrarySession.Builder(this, KeepCurrentStreamPlayer(player), LibraryCallback())
            .setSessionActivity(sessionActivity)
            .setCustomLayout(customLayout())
            .build()

        reconnect = ReconnectController(this, player) { status ->
            PlaybackStatusBus.setStatus(
                when (status) {
                    ReconnectController.Status.RECONNECTING -> PlaybackStatusBus.Status.RECONNECTING
                    ReconnectController.Status.WAITING_FOR_NETWORK -> PlaybackStatusBus.Status.WAITING_FOR_NETWORK
                    ReconnectController.Status.STATION_UNREACHABLE -> PlaybackStatusBus.Status.STATION_UNREACHABLE
                    ReconnectController.Status.OK -> PlaybackStatusBus.Status.PLAYING
                }
            )
            // No network hits the middle line on the dashboard, so the metadata
            // must be pushed again - otherwise the message would only appear at
            // the next track, which in practice means never.
            refreshCurrentMetadata(force = true)
        }
        reconnect.start()
        prefs.registerListener(prefsListener)
        scheduleClockTick()

        scope.launch { repo.refreshUserLists() }

        // A favourites change - wherever it came from - must immediately translate
        // into the star next to the player and into the lists that show it.
        scope.launch {
            Prefs.favouritesFlow.collect { favourites ->
                if (!this@RadioService::session.isInitialized) return@collect
                Log.i(TAG, "Favourites changed (${favourites.size}) - refreshing buttons and nodes")
                session.setCustomLayout(customLayout())
                notifyBrowseNodesChanged(NODE_ROOT, NODE_ALL)
            }
        }

        // A station added from the catalog should appear in the car without restarting the app.
        // A new genre may also show up, so we refresh their list as well.
        scope.launch {
            Prefs.discoveredFlow.collect { stations ->
                if (!this@RadioService::session.isInitialized) return@collect
                Log.i(TAG, "Online stations changed (${stations.size}) - refreshing nodes")
                notifyBrowseNodesChanged(NODE_ALL)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        prefs.unregisterListener(prefsListener)
        reconnect.stop()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // --- player construction ----------------------------------------------------

    private fun buildPlayer(): ExoPlayer {
        val profile = BufferProfile.at(prefs.bufferProfile)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.forPlaybackMs,
                profile.afterRebufferMs
            )
            // For a live stream we care about time, not buffer size.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(StationRepository.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(false)

        val filtered = IcyFilteringDataSource.Factory(
            DefaultDataSource.Factory(this, httpFactory)
        ) { prefs.diagnosticMode && prefs.stripIcyInDiagnostic }

        val mediaSourceFactory = DefaultMediaSourceFactory(filtered)
            .setLoadErrorHandlingPolicy(InfiniteLoadErrorHandlingPolicy())

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    // --- reactions to player events --------------------------------------------

    private inner class PlayerEvents : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastRawIcyTitle = null
            lastSlogan = null
            // A station change starts everything from scratch. Without this, the cover art
            // from the previous station would stay in the `coverArtUrl` field and come back
            // on screen at the next metadata refresh - after switching from RMF to RNS
            // the RMF cover art would briefly show instead of the Nowy Swiat logo.
            resetCoverArt()
            audioFormat = null
            icyBitrateKbps = 0
            PlaybackStatusBus.setQuality(null)
            val id = mediaItem?.mediaId?.let { Station.idFromMediaId(it) }
            PlaybackStatusBus.setStation(id)
            id?.let {
                prefs.pushRecent(it)
                // Play count - the only sensible source for a "most listened"
                // ordering, which after a few weeks of driving arranges the list
                // better than anything we could come up with upfront.
                prefs.bumpPlayCount(it)
            }

            // The star belongs to a specific station, so on change the button
            // layout needs to be rebuilt. Without this, entering a station showed
            // the previous one's state and the first tap looked like it did nothing.
            if (this@RadioService::session.isInitialized) {
                session.setCustomLayout(customLayout())
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            PlaybackStatusBus.setStatus(
                when (state) {
                    Player.STATE_BUFFERING -> PlaybackStatusBus.Status.BUFFERING
                    Player.STATE_READY -> if (player.playWhenReady) {
                        PlaybackStatusBus.Status.PLAYING
                    } else {
                        PlaybackStatusBus.Status.IDLE
                    }
                    else -> PlaybackStatusBus.Status.IDLE
                }
            )
        }

        override fun onMetadata(meta: Metadata) {
            for (i in 0 until meta.length()) {
                when (val entry = meta.get(i)) {
                    is IcyHeaders -> logIcyHeaders(entry)
                    is IcyInfo -> {
                        val raw = logIcyInfo(entry)
                        handleIcyTitle(entry.title, raw)
                    }
                    else -> Log.i(TAG_ICY, "inny typ metadanych: ${entry.javaClass.simpleName} | $entry")
                }
            }
        }
    }

    /**
     * New title from Icecast. We update the item's metadata, not the stream itself -
     * `replaceMediaItem` with the same source configuration doesn't interrupt playback.
     */
    /** icy-* headers from the HTTP response - constant for the whole stream. */
    private fun logIcyHeaders(h: IcyHeaders) {
        Log.i(TAG_ICY, "== naglowki ICY strumienia ==")
        h.name?.let { Log.i(TAG_ICY, "icy-name = $it") }
        h.genre?.let { Log.i(TAG_ICY, "icy-genre = $it") }
        h.url?.let { Log.i(TAG_ICY, "icy-url = $it") }
        if (h.bitrate > 0) {
            Log.i(TAG_ICY, "icy-br = ${h.bitrate}")
            // Fallback in case the decoder doesn't report a bitrate - with AAC
            // in ADTS this is the rule, since the stream itself carries none at all.
            icyBitrateKbps = h.bitrate
            publishQuality()
        }
        if (h.metadataInterval > 0) Log.i(TAG_ICY, "icy-metaint = ${h.metadataInterval}")
        Log.i(TAG_ICY, "icy-pub = ${h.isPublic}")
    }

    /**
     * The metadata block injected into the stream. ExoPlayer only parses
     * StreamTitle and StreamUrl out of it, but stations push in more key=value
     * pairs (RMF, for instance, marks ads via adw_ad and durationMilliseconds). So
     * alongside the recognized fields we also log the whole raw content.
     */
    private fun logIcyInfo(info: IcyInfo): String {
        val raw = runCatching { String(info.rawMetadata, Charsets.UTF_8).trim(Char(0), ' ') }
            .getOrDefault("")
        Log.i(TAG_ICY, "surowy blok: $raw")
        info.url?.let { Log.i(TAG_ICY, "StreamUrl = $it") }

        // split all key='value' pairs so unusual fields are visible
        Regex("""(\w+)='([^']*)'""").findAll(raw).forEach { m ->
            Log.i(TAG_ICY, "  ${m.groupValues[1]} = ${m.groupValues[2]}")
        }
        return raw
    }

    private fun handleIcyTitle(rawTitle: String?, rawBlock: String? = null) {
        val title = rawTitle?.trim().orEmpty()
        // The title alone isn't enough to detect a repeat: ads have an empty
        // title, and different insertions only differ in their adId fields.
        val fingerprint = title + "|" + rawBlock.orEmpty()
        if (fingerprint == lastRawIcyTitle) return
        lastRawIcyTitle = fingerprint

        // The gap between ICY blocks is something we don't know about stations:
        // whether metadata arrives once per track or periodically. We log it so
        // it can be measured from the outside.
        val nowMs = System.currentTimeMillis()
        val sinceLast = if (lastIcyAtMs == 0L) -1 else (nowMs - lastIcyAtMs) / 1000
        lastIcyAtMs = nowMs
        Log.i(TAG_ICY, "po ${sinceLast}s | StreamTitle='$title'")

        val stationName = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it)?.name }
        val now = NowPlaying.parse(title, stationName, rawBlock)
        Log.i(
            TAG_ICY,
            "  -> artist='${now?.artist}' title='${now?.songTitle}' " +
                "slogan='${now?.slogan}' ad=${now?.isAd} " +
                "marker=${now?.isControlMarker} track=${now?.isRealSong}"
        )
        publish(now)
    }

    /**
     * Decides whether an event from ICY should immediately change what's shown on screen.
     *
     * A control marker (e.g. STOP_AD_BREAK) by itself doesn't mean the music is
     * back - RMF can insert another ad right after it. If we switched back to the
     * station view immediately, the screen would flicker between "Ad" and the
     * station name on every insertion. So the marker only arms a timer: if
     * something concrete arrives within [MARKER_GRACE_MS] - a track or another
     * ad - it wins, and if nothing arrives, only then do we fall back to just
     * the station.
     */
    private fun publish(now: NowPlaying?) {
        pendingMarkerJob?.cancel()

        if (now?.isControlMarker == true && now.isAd != true) {
            Log.i(TAG_ICY, "marker '${now.raw}' - waiting ${MARKER_GRACE_MS}ms for next metadata")
            pendingMarkerJob = scope.launch {
                delay(MARKER_GRACE_MS)
                Log.i(TAG_ICY, "nothing arrived after marker - falling back to station")
                apply(null)
            }
            return
        }

        apply(now)
    }

    private fun apply(now: NowPlaying?) {
        now?.slogan?.let { lastSlogan = it }

        // Some stations re-announce the very same song's ICY metadata mid-track
        // (a periodic StreamTitle repeat, not an actual track change) - the raw
        // block differs just enough to dodge the fingerprint check in
        // handleIcyTitle, so we still land here. Comparing against the track the
        // cover art already belongs to catches that case and skips the
        // clear-and-relookup cycle, which is what showed up as the station logo
        // flashing back on for an instant in the middle of a song.
        val key = if (now?.isRealSong == true) "${now.artist}|${now.songTitle}" else null
        val sameTrack = key != null && key == coverTrackKey

        if (!sameTrack) {
            // Catalog data belongs to the PREVIOUS track, so we clear it before drawing
            // anything. Otherwise the new artist briefly shows up glued to the old
            // record - "Taylor Swift - Black Gold: The Best of Soul Asylum [1992]".
            // The cover art is cleared by updateCoverArt, also immediately.
            trackInfo = null
            PlaybackStatusBus.setTrackInfo(null)
        }

        PlaybackStatusBus.setNowPlaying(now)

        // Order matters: we clear the cover art first, only then send the
        // metadata. The other way around, the car would get a new description
        // with the old artwork - after the song, the studio would come on and
        // "Pion i poziom!" would still show the previous track's cover.
        updateCoverArt(now, key, sameTrack)
        refreshCurrentMetadata(force = true, now = now)
        scheduleStaleCheck(now)
    }

    /**
     * Cleans up after a track whose end was never announced.
     *
     * RMF can enter an ad block without any ICY event - the screen would then
     * keep a title from several minutes ago, because we had no signal that
     * anything had changed. Instead of guessing with a fixed limit, we use the
     * track length from the catalog: if a song is 3:20 long, by 4:20 it's
     * certainly no longer playing. When we don't know the length, we assume
     * [FALLBACK_TRACK_MS].
     */
    private fun scheduleStaleCheck(now: NowPlaying?) {
        staleJob?.cancel()
        if (now?.isRealSong != true) return

        val known = trackInfo?.durationMs ?: 0
        val timeout = (if (known > 0) known else FALLBACK_TRACK_MS) + STALE_GRACE_MS
        staleJob = scope.launch {
            delay(timeout)
            if (PlaybackStatusBus.nowPlaying.value?.raw != now.raw) return@launch
            Log.i(
                TAG_ICY,
                "track '${now.raw}' should have ended after ${timeout / 1000}s - " +
                    "station sent no update, clearing description"
            )
            // If the station has ever given its slogan, it's better to show it
            // than an empty line - RNS has "Pion i poziom!", RMF "FAKTY" during the news.
            val slogan = lastSlogan
            if (slogan != null) {
                apply(
                    NowPlaying(
                        raw = slogan,
                        artist = null,
                        songTitle = slogan,
                        isStationSelfTitle = true
                    )
                )
            } else {
                apply(null)
            }
        }
    }

    /**
     * Clears everything we knew about the previous track. Called on a station
     * change, where no trace of the previous one is allowed to remain.
     */
    private fun resetCoverArt() {
        coverGeneration++
        coverRevertJob?.cancel()
        staleJob?.cancel()
        coverTrackKey = null
        coverArtUrl = null
        trackInfo = null
        lastDabMotRevision = null
        PlaybackStatusBus.setCoverArt(null)
        PlaybackStatusBus.setTrackInfo(null)
    }

    /**
     * Swaps the cover art on a track change.
     *
     * There's one rule: **the cover art never outlives the track it belongs to**.
     * Previously it was the opposite - the old artwork stayed until a new one was
     * found, so the station logo wouldn't flicker between tracks. In practice that
     * gave a worse result than a flicker: for a fraction of a second (and after
     * the description expired, even for [COVER_GRACE_MS]) the previous record's
     * cover hung next to the new artist's name, which simply looks like a bug.
     *
     * So on every track change we immediately fall back to the station logo, and
     * only show the cover art once the catalog actually finds it. When a station
     * sends metadata right at connection time, the lookup usually takes
     * ~200 ms and the logo barely has time to appear.
     */
    private fun updateCoverArt(now: NowPlaying?, key: String?, sameTrack: Boolean) {
        // Same track re-announced - we already have its art (or a lookup for it
        // already in flight), nothing to clear or re-fetch.
        if (sameTrack) return

        val generation = ++coverGeneration
        coverRevertJob?.cancel()

        fun applyIfCurrent(info: CoverArtLookup.TrackInfo?, artworkUrl: String?) {
            if (generation != coverGeneration) return
            if (coverArtUrl == artworkUrl && trackInfo == info) return
            coverArtUrl = artworkUrl
            trackInfo = info
            PlaybackStatusBus.setCoverArt(artworkUrl)
            PlaybackStatusBus.setTrackInfo(info)
            refreshCurrentMetadata(force = true, now = PlaybackStatusBus.nowPlaying.value)
            // We now know the track length - recompute the moment the description goes stale
            scheduleStaleCheck(PlaybackStatusBus.nowPlaying.value)
        }

        coverTrackKey = key
        if (coverArtUrl != null || trackInfo != null) {
            coverArtUrl = null
            trackInfo = null
            PlaybackStatusBus.setCoverArt(null)
            PlaybackStatusBus.setTrackInfo(null)
        }

        val station = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it) }

        // No real song means there is nothing sensible to query in iTunes/MusicBrainz,
        // but a DAB slideshow may still carry a useful programme image.
        if (key == null) {
            scope.launch {
                monitorDabMot(station?.dabMotUrl, generation) { mot ->
                    applyIfCurrent(null, mot)
                }
            }
            return
        }

        scope.launch {
            val info = CoverArtLookup.find(now!!.artist, now.songTitle)
            if (generation != coverGeneration) return@launch

            // Internet artwork always wins. Keep TrackInfo even when it only contains
            // album/year data but no image.
            val onlineArt = info?.artworkUrl
            applyIfCurrent(info, onlineArt)
            if (onlineArt != null) return@launch

            // No catalogue artwork: keep watching the DAB slideshow for the whole
            // track. welle.io often still exposes the previous slide for a few
            // seconds after StreamTitle changes; mot.lastchange tells us exactly
            // when a genuinely new slide arrives.
            monitorDabMot(station?.dabMotUrl, generation) { mot ->
                applyIfCurrent(info, mot)
            }
        }
    }

    /**
     * Watches welle.io's mux metadata while DAB MOT is the active artwork fallback.
     *
     * /mux.json exposes service.mot.lastchange. Polling that small JSON document is
     * much cheaper and more reliable than downloading /slide/<sid> repeatedly.
     * Whenever the revision changes we publish a cache-busted slide URL, which
     * forces both the phone UI and Android Auto to fetch the new image.
     */
    private suspend fun monitorDabMot(
        url: String?,
        generation: Int,
        onChanged: (String) -> Unit
    ) {
        val base = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return

        val startedAt = System.currentTimeMillis()
        var appliedForThisGeneration = false

        while (generation == coverGeneration) {
            val revision = dabMotRevision(base)

            if (revision != null) {
                val genuinelyNew = lastDabMotRevision == null || revision != lastDabMotRevision

                if (genuinelyNew) {
                    lastDabMotRevision = revision
                    onChanged(cacheBustedMotUrl(base, revision))
                    appliedForThisGeneration = true
                } else if (
                    !appliedForThisGeneration &&
                    System.currentTimeMillis() - startedAt >= MOT_UNCHANGED_FALLBACK_MS
                ) {
                    // Some stations use a static MOT logo for long periods. After a
                    // short grace period, reuse it rather than falling back forever
                    // to the separately configured station logo.
                    onChanged(cacheBustedMotUrl(base, revision))
                    appliedForThisGeneration = true
                }
            }

            delay(MOT_POLL_MS)
        }
    }

    private fun cacheBustedMotUrl(base: String, revision: Long): String {
        val separator = if ('?' in base) '&' else '?'
        return "$base${separator}v=$revision"
    }

    /**
     * Reads the current MOT revision for the service represented by /slide/<sid>.
     */
    private suspend fun dabMotRevision(motUrl: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val slideUrl = URL(motUrl)
            val sid = slideUrl.path.substringAfterLast('/').trim()
            if (sid.isBlank()) return@runCatching null

            val muxUrl = URL(slideUrl.protocol, slideUrl.host, slideUrl.port, "/mux.json")
            val conn = (muxUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 3_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", StationRepository.USER_AGENT)
            }

            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val services = root.optJSONArray("services") ?: return@runCatching null

                for (i in 0 until services.length()) {
                    val service = services.optJSONObject(i) ?: continue
                    if (!service.optString("sid").equals(sid, ignoreCase = true)) continue

                    val mot = service.optJSONObject("mot") ?: return@runCatching null
                    val lastChange = mot.optLong("lastchange", 0L)
                    val time = mot.optLong("time", 0L)

                    // lastchange only moves when the MOT image itself changes.
                    // mot.time can be refreshed even while the same image remains active,
                    // so using maxOf(lastchange, time) caused needless reloads/flicker.
                    return@runCatching (if (lastChange > 0L) lastChange else time)
                        .takeIf { it > 0L }
                }
                null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Reloads the current station at a new address.
     *
     * Used after a quality variant change. We go straight to ExoPlayer, bypassing
     * [KeepCurrentStreamPlayer] - it deliberately ignores setting the same
     * station so that tapping the currently playing item doesn't drop the
     * connection, but here dropping it is exactly the point.
     */
    private fun reloadCurrentStation() {
        val id = PlaybackStatusBus.stationId.value ?: return
        val station = repo.byId(id) ?: return
        if (station.stream == currentStreamUrl) return
        currentStreamUrl = station.stream
        Log.i(TAG, "station stream changed: ${station.name} -> ${station.stream}")
        val wasPlaying = player.playWhenReady
        player.setMediaItem(playableItem(station))
        player.prepare()
        if (wasPlaying) player.play()
    }

    /** Builds the quality description from the decoder format and the icy-br header. */
    private fun publishQuality() {
        val format = audioFormat
        if (format == null) {
            PlaybackStatusBus.setQuality(null)
            return
        }
        val quality = StreamQuality.of(format, icyBitrateKbps)
        PlaybackStatusBus.setQuality(
            quality.label().ifBlank { null },
            quality.bitrateKbps.takeIf { it > 0 }
        )
    }

    /**
     * Refreshes metadata exactly on the minute boundary, not every 60s from
     * start - otherwise the clock in the subtitle would drift against the car's clock.
     */
    private fun scheduleClockTick() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        val delayToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
        val runnable = Runnable {
            // We only refresh the clock when there's somewhere to show it - always
            // in diagnostic mode, otherwise only in layouts that have a clock.
            val needed = prefs.diagnosticMode || prefs.presentation.needsClock
            if (needed) refreshCurrentMetadata(force = true)
            scheduleClockTick()
        }
        clockTick = runnable
        clockHandler.postDelayed(runnable, delayToNextMinute + 100)
    }

    private fun refreshCurrentMetadata(force: Boolean, now: NowPlaying? = PlaybackStatusBus.nowPlaying.value) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val station = repo.byMediaId(item.mediaId) ?: return
        if (!force && prefs.diagnosticMode) return
        val fresh = metadata.forPlayback(station, now, coverArtUrl, trackInfo)
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(fresh).build())
        dumpMetadata(station, fresh)
    }

    /**
     * Prints out the full set of fields sent to the session. Used by the Windows
     * preview window (tools/meta-watch.ps1), which reads it via `adb logcat -s MetaDump`.
     */
    private fun dumpMetadata(station: Station, m: MediaMetadata) {
        // A dump identical to the previous one carries no information, and it
        // can repeat every minute (the clock tick) or on every button refresh.
        // The preview window would otherwise flood with duplicates.
        val fingerprint = listOf(
            m.title, m.artist, m.albumTitle, m.displayTitle, m.subtitle,
            m.description, m.station, m.genre, m.artworkUri
        ).joinToString("|")
        if (fingerprint == lastDump) return
        lastDump = fingerprint

        Log.i(TAG_DUMP, "--- ${station.name} @ ${MetadataFactory.clockText()} ---")
        listOf(
            "title" to m.title,
            "artist" to m.artist,
            "albumTitle" to m.albumTitle,
            "albumArtist" to m.albumArtist,
            "displayTitle" to m.displayTitle,
            "subtitle" to m.subtitle,
            "description" to m.description,
            "station" to m.station,
            "genre" to m.genre,
            "composer" to m.composer,
            "writer" to m.writer,
            "conductor" to m.conductor,
            "compilation" to m.compilation
        ).forEach { (name, value) ->
            if (value != null) Log.i(TAG_DUMP, "$name = $value")
        }
        m.trackNumber?.let { Log.i(TAG_DUMP, "trackNumber = $it") }
        m.recordingYear?.let { Log.i(TAG_DUMP, "recordingYear = $it") }
        Log.i(TAG_DUMP, "artworkUri = ${m.artworkUri}")
        m.artworkData?.let { Log.i(TAG_DUMP, "artworkData = ${it.size} B (grafika w metadanych)") }
    }

    // --- browse tree for Android Auto -------------------------------------------

    /**
     * A button in the Android Auto player template. It's the only way to give
     * the user any custom control at all - AA doesn't allow drawing your own UI,
     * but it renders custom actions in its own layout (ReplaIO does the same
     * with its star and heart).
     *
     * Why this one specifically: it lets you toggle diagnostic mode right from
     * the car screen, without reaching for the phone while driving.
     */
    /**
     * Announces a change in browse node contents.
     *
     * The version without a specified recipient only reaches controllers
     * registered as subscribers on the Media3 side. Android Auto connects via
     * the old API and its subscriptions are tracked elsewhere, so with the
     * favourites list open it received nothing and the list stayed stale. So we
     * notify each connected controller by address instead.
     */
    private fun notifyBrowseNodesChanged(vararg nodes: String) {
        val controllers = session.connectedControllers
        for (node in nodes) {
            // Use the style that belongs to the node being refreshed.
            val params = contentStyleParams(node)
            val count = browseNodeChildCount(node)
            session.notifyChildrenChanged(node, count, params)
            session.notifyChildrenChanged(node, count, null)
            for (controller in controllers) {
                session.notifyChildrenChanged(controller, node, count, params)
                session.notifyChildrenChanged(controller, node, count, null)
            }
        }
        Log.i(TAG, "notified ${controllers.size} controller(s) about ${nodes.joinToString()}")
    }

    private fun browseNodeChildCount(parentId: String): Int = when {
        // Home = favourite stations + the single "All stations" entry.
        parentId == NODE_ROOT -> repo.favourites().size + 1
        parentId == NODE_FAVOURITES -> repo.favourites().size
        parentId == NODE_ALL -> repo.all().size
        parentId == NODE_RECENT -> repo.recent().size
        parentId == NODE_GENRES -> repo.genres().size
        parentId.startsWith(NODE_GENRE_PREFIX) ->
            repo.byGenre(parentId.removePrefix(NODE_GENRE_PREFIX)).size
        else -> 0
    }

    /**
     * Buttons in the Android Auto player template - the same set also shows up
     * in the phone's playback notification, since both read the session's
     * custom layout.
     *
     * The diagnostic toggle only appears once diagnostic mode is actually on -
     * it's a developer convenience for turning it back off without leaving the
     * car screen or the notification, not the way to turn it on in the first
     * place (that's the switch in Options). Showing it all the time, for
     * everyone, made no sense for a mode that's off by default.
     */
    private fun customLayout(): ImmutableList<CommandButton> =
        if (prefs.diagnosticMode) {
            ImmutableList.of(favouriteButton(), diagnosticButton())
        } else {
            ImmutableList.of(favouriteButton())
        }

    /**
     * The favourites star. Without it, a station playing in the car couldn't
     * be added to favourites at all - and it's exactly in the car that a person
     * decides they want it within reach.
     */
    private fun favouriteButton(): CommandButton {
        val id = PlaybackStatusBus.stationId.value
        val isFav = id != null && id in prefs.favourites
        Log.i(TAG, "building favourite button for '$id': favourite=$isFav")
        // The icon goes through TWO channels at once, and that's not redundancy.
        //
        // The proper channel is extras: Android's documentation for cars says
        // explicitly that if an icon matches one of the CommandButton.ICON_
        // constants, its value should be put under the
        // EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT key, because that "overrides the
        // icon resource passed to CustomAction.Builder and lets the system draw
        // the action consistently with the others". In other words, the head
        // unit then draws its OWN star and doesn't look at our resources at all.
        //
        // The fallback channel is the resource id - for systems that don't know
        // that key. And only there does it matter that the vector has no
        // android:tint: a reference to @color/... would have to be resolved by
        // the head unit in our package while inflating in its own process, and
        // that's exactly where it used to fail.
        //
        // What NOT to do: pass the semantic constant to the
        // CommandButton.Builder(ICON_STAR_FILLED) constructor and assume that's
        // enough. Media3 then translates it for the old API into the resource id
        // of its own bundled resource, and the Desktop Head Unit rendered from
        // that a music note and the text "1.8X".
        val icon = if (isFav) {
            CommandButton.ICON_STAR_FILLED
        } else {
            CommandButton.ICON_STAR_UNFILLED
        }
        @Suppress("DEPRECATION")
        return CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_FAV)
            .setDisplayName(
                getString(
                    if (isFav) net.mspanc.twinsenradio.R.string.fav_remove
                    else net.mspanc.twinsenradio.R.string.fav_add
                )
            )
            .setIconResId(
                if (isFav) net.mspanc.twinsenradio.R.drawable.ic_star_filled_aa
                else net.mspanc.twinsenradio.R.drawable.ic_star_outline_aa
            )
            .setExtras(
                Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT, icon)
                }
            )
            .build()
    }

    /** Same as the star - our own vector, resource id hardcoded. */
    private fun diagnosticButton(): CommandButton =
        @Suppress("DEPRECATION")
        CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_DIAG)
            .setDisplayName(
                if (prefs.diagnosticMode) "Diagnostyka: WL" else "Diagnostyka: WYL"
            )
            .setIconResId(net.mspanc.twinsenradio.R.drawable.ic_diag_aa)
            .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            ConnectionLog.connected(
                this@RadioService,
                controller.packageName,
                controller.uid,
                controller.controllerVersion,
                controller.interfaceVersion,
                controller.connectionHints
            )
            VehicleDiagnosticManager.recordConnection(
                this@RadioService,
                controller.packageName,
                controller.uid,
                controller.controllerVersion,
                controller.interfaceVersion,
                controller.connectionHints
            )
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(CMD_TOGGLE_DIAG)
                .add(CMD_TOGGLE_FAV)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                // A freshly connected controller must get the current star state
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            VehicleDiagnosticManager.controllerDisconnected(controller.packageName)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_TOGGLE_DIAG.customAction -> {
                    prefs.diagnosticMode = !prefs.diagnosticMode
                    Log.i(TAG, "przelaczono tryb diagnostyczny na ${prefs.diagnosticMode}")
                    // prefsListener will take care of refreshing the metadata
                    session.setCustomLayout(customLayout())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_VIEW_GRID, ACTION_VIEW_LIST -> {
                    val requestedNode = args.getString(KEY_ACTION_MEDIA_ITEM_ID)
                    val node = if (requestedNode == NODE_ALL) NODE_ALL else NODE_ROOT
                    val grid = customCommand.customAction == ACTION_VIEW_GRID

                    if (node == NODE_ALL) {
                        prefs.aaAllStationsGrid = grid
                    } else {
                        prefs.aaFavouritesGrid = grid
                    }

                    notifyBrowseNodesChanged(node)

                    val result = Bundle().apply {
                        // Re-enter the current node so Android Auto immediately
                        // asks for its children with the new content-style hint.
                        putString(KEY_ACTION_RESULT_BROWSE_NODE, node)
                        putString(KEY_ACTION_RESULT_REFRESH_ITEM, node)
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, result)
                    )
                }

                // An action from the list item's menu - the head unit attaches the item id
                ACTION_FAVOURITE, ACTION_UNFAVOURITE -> {
                    val mediaId = args.getString(KEY_ACTION_MEDIA_ITEM_ID)
                    val station = mediaId?.let { repo.byMediaId(it) }
                    val result = Bundle()
                    if (station != null) {
                        val added = prefs.toggleFavourite(station.id)
                        Log.i(TAG, "from list: ${station.name} ${if (added) "added to" else "removed from"} favourites")
                        // Tell the head unit to refresh this item so the icon switches
                        result.putString(KEY_ACTION_RESULT_REFRESH_ITEM, mediaId)
                        result.putString(
                            KEY_ACTION_RESULT_MESSAGE,
                            getString(
                                if (added) net.mspanc.twinsenradio.R.string.fav_added_toast
                                else net.mspanc.twinsenradio.R.string.fav_removed_toast,
                                station.name
                            )
                        )
                        this@RadioService.session
                            .notifyChildrenChanged(NODE_FAVOURITES, Int.MAX_VALUE, null)
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, result)
                    )
                }
                CMD_TOGGLE_FAV.customAction -> {
                    val id = PlaybackStatusBus.stationId.value
                    if (id != null) {
                        val added = prefs.toggleFavourite(id)
                        Log.i(TAG, "station $id ${if (added) "added to" else "removed from"} favourites")
                        session.setCustomLayout(customLayout())
                        // notifyChildrenChanged only exists on MediaLibrarySession,
                        // and here `session` has the wider MediaSession type
                        this@RadioService.session
                            .notifyChildrenChanged(NODE_FAVOURITES, Int.MAX_VALUE, null)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            ConnectionLog.libraryRoot(this@RadioService, browser.packageName, params?.extras)
            VehicleDiagnosticManager.recordRoot(
                this@RadioService,
                browser.packageName,
                params?.extras
            )
            Log.i(
                TAG,
                "onGetLibraryRoot od ${browser.packageName} (uid=${browser.uid}), " +
                    "isRecent=${params?.isRecent}, isSuggested=${params?.isSuggested}, " +
                    "ostatnio sluchane=${repo.recent().size}"
            )
            // The system (and the head unit) asks separately for the "resume"
            // root - in that case it expects a short recently-played list, not the whole tree.
            if (params?.isRecent == true) {
                if (repo.recent().isEmpty()) {
                    return Futures.immediateFuture(
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                    )
                }
                val recentRoot = MediaItem.Builder()
                    .setMediaId(NODE_RECENT)
                    .setMediaMetadata(
                        metadata.forFolder(
                            getString(net.mspanc.twinsenradio.R.string.node_recent),
                            MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                        )
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(recentRoot, params))
            }

            val root = browseNode(
                NODE_ROOT,
                getString(net.mspanc.twinsenradio.R.string.root_title),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
            return Futures.immediateFuture(
                LibraryResult.ofItem(root, contentStyleParams(NODE_ROOT))
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            VehicleDiagnosticManager.recordChildren(
                this@RadioService,
                browser.packageName,
                parentId,
                page,
                pageSize,
                params?.extras
            )

            val children: List<MediaItem> = when {
                // Android Auto home: favourite stations immediately, plus one
                // secondary entry giving access to the complete station list.
                parentId == NODE_ROOT ->
                    repo.favourites().map(::browseItem) +
                        browseNode(
                            NODE_ALL,
                            getString(net.mspanc.twinsenradio.R.string.node_all),
                            MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                        )

                // Kept for compatibility with an old cached Android Auto tree.
                parentId == NODE_FAVOURITES -> repo.favourites().map(::browseItem)
                parentId == NODE_ALL -> repo.all().map(::browseItem)
                parentId == NODE_RECENT -> repo.recent().map(::browseItem)

                parentId == NODE_GENRES -> repo.genres().map { genre ->
                    MediaItem.Builder()
                        .setMediaId("$NODE_GENRE_PREFIX$genre")
                        .setMediaMetadata(
                            metadata.forFolder(genre, MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                        )
                        .build()
                }

                parentId.startsWith(NODE_GENRE_PREFIX) ->
                    repo.byGenre(parentId.removePrefix(NODE_GENRE_PREFIX)).map(::browseItem)

                else -> emptyList()
            }
            Log.i(TAG, "onGetChildren($parentId) od ${browser.packageName} -> ${children.size} pozycji")
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    ImmutableList.copyOf(children),
                    contentStyleParams(parentId)
                )
            )
        }

        /**
         * Resuming playback after connecting to the car or from the system panel.
         *
         * The rule: **it must play exactly the same station as before** -
         * regardless of whether it was playing from the phone or via Android
         * Auto. There's one shared state (one service, one session), and the
         * history in [Prefs.recent] is updated on every item change, so both
         * paths record it the same way.
         *
         * Order: the station currently loaded in the player (if something was
         * already playing, it must not be swapped out), then the most recently
         * listened one from history, then the first favourite, and finally the
         * first from the list.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val current = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it) }
            val station = current
                ?: repo.recent().firstOrNull()
                ?: repo.favourites().firstOrNull()
                ?: repo.all().firstOrNull()

            if (station == null) {
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("no station available for resume")
                )
            }

            val skad = if (current != null) "juz zaladowana" else "z historii"
            Log.i(
                TAG,
                "resuming after connection: ${station.name} ($skad, requested by ${controller.packageName})"
            )
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(playableItem(station)),
                    0,
                    // Live radio - start position doesn't matter
                    C.TIME_UNSET
                )
            )
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            VehicleDiagnosticManager.recordSubscription(
                this@RadioService,
                browser.packageName,
                parentId,
                params?.extras
            )
            Log.i(TAG, "SUBSKRYPCJA $parentId od ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String
        ): ListenableFuture<LibraryResult<Void>> {
            VehicleDiagnosticManager.recordUnsubscribe(browser.packageName, parentId)
            Log.i(TAG, "KONIEC SUBSKRYPCJI $parentId od ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val node = when (mediaId) {
                NODE_ROOT -> browseNode(
                    NODE_ROOT,
                    getString(net.mspanc.twinsenradio.R.string.root_title),
                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
                NODE_ALL -> browseNode(
                    NODE_ALL,
                    getString(net.mspanc.twinsenradio.R.string.node_all),
                    MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                )
                else -> null
            }
            if (node != null) {
                return Futures.immediateFuture(
                    LibraryResult.ofItem(node, contentStyleParams(mediaId))
                )
            }

            val station = repo.byMediaId(mediaId)
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            return Futures.immediateFuture(LibraryResult.ofItem(browseItem(station), null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            VehicleDiagnosticManager.recordSearch(
                this@RadioService,
                browser.packageName,
                query,
                null,
                null,
                params?.extras
            )
            val hits = repo.search(query).size
            session.notifySearchResultChanged(browser, query, hits, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            VehicleDiagnosticManager.recordSearch(
                this@RadioService,
                browser.packageName,
                query,
                page,
                pageSize,
                params?.extras
            )
            val hits = repo.search(query).map(::browseItem)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    ImmutableList.copyOf(hits),
                    contentStyleParams(NODE_ALL)
                )
            )
        }

        /**
         * Android Auto sends items with just a mediaId. Here we add the stream
         * address and full metadata - without this there'd be nothing to play.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                repo.byMediaId(item.mediaId)?.let(::playableItem)
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Voice command "play X" arrives here via search. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.mapNotNull { item ->
                repo.byMediaId(item.mediaId)
                    ?: item.requestMetadata.searchQuery?.let { repo.search(it).firstOrNull() }
            }.map(::playableItem).toMutableList()

            val safeIndex = if (startIndex in resolved.indices) startIndex else 0
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, safeIndex, startPositionMs)
            )
        }
    }

    private fun folder(id: String, titleRes: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                metadata.forFolder(
                    getString(titleRes),
                    MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                )
            )
            .build()

    /**
     * Browsable node carrying its own grid/list toggle action. Android Auto
     * renders actions attached to a browse node in the secondary toolbar.
     */
    private fun browseNode(id: String, title: String, mediaType: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                metadata.forFolder(title, mediaType)
                    .buildUpon()
                    .setExtras(
                        Bundle().apply {
                            putStringArrayList(
                                KEY_ACTION_ID_LIST,
                                arrayListOf(
                                    if (nodeUsesGrid(id)) ACTION_VIEW_LIST
                                    else ACTION_VIEW_GRID
                                )
                            )
                        }
                    )
                    .build()
            )
            .build()

    private fun nodeUsesGrid(nodeId: String): Boolean =
        if (nodeId == NODE_ALL) prefs.aaAllStationsGrid else prefs.aaFavouritesGrid

    /**
     * A list item with a "favourite" action available directly from the context
     * menu in Android Auto. Without this, a station could only be added to
     * favourites while it was already playing - but marking it while browsing
     * the list is the natural thing to do.
     */
    private fun browseItem(station: Station): MediaItem {
        val isFav = station.id in prefs.favourites
        val extras = Bundle().apply {
            putStringArrayList(
                KEY_ACTION_ID_LIST,
                arrayListOf(if (isFav) ACTION_UNFAVOURITE else ACTION_FAVOURITE)
            )
        }
        return MediaItem.Builder()
            .setMediaId(station.mediaId)
            .setMediaMetadata(
                metadata.forBrowseItem(station).buildUpon().setExtras(extras).build()
            )
            .build()
    }

    /** Definitions of actions the head unit shows on list items. */
    private fun browseActionsRootList(): ArrayList<Bundle> {
        // Here the icon can only be given as a URI, so instead of
        // android.resource:// with a resource id (unstable across versions
        // and cached by head units), we use a stable address from LogoProvider.
        fun action(id: String, labelRes: Int, iconName: String, iconRes: Int) = Bundle().apply {
            putString(KEY_ACTION_ID, id)
            putString(KEY_ACTION_LABEL, getString(labelRes))
            putString(
                KEY_ACTION_ICON_URI,
                LogoProvider.iconUri(this@RadioService, iconName, iconRes).toString()
            )
        }
        return arrayListOf(
            action(
                ACTION_FAVOURITE,
                net.mspanc.twinsenradio.R.string.fav_add,
                "star_outline",
                net.mspanc.twinsenradio.R.drawable.ic_star_outline_aa
            ),
            action(
                ACTION_UNFAVOURITE,
                net.mspanc.twinsenradio.R.string.fav_remove,
                "star_filled",
                net.mspanc.twinsenradio.R.drawable.ic_star_filled_aa
            ),
            action(
                ACTION_VIEW_GRID,
                net.mspanc.twinsenradio.R.string.aa_view_grid,
                "view_grid",
                net.mspanc.twinsenradio.R.drawable.ic_view_grid_aa
            ),
            action(
                ACTION_VIEW_LIST,
                net.mspanc.twinsenradio.R.string.aa_view_list,
                "view_list",
                net.mspanc.twinsenradio.R.drawable.ic_view_list_aa
            )
        )
    }

    private fun playableItem(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.mediaId)
            .setUri(station.stream)
            .setMediaMetadata(metadata.forPlayback(station, null))
            .build()

    /**
     * The presentation scheme chosen in Options. These are the four layouts
     * Android Auto actually offers media apps.
     */
    private fun contentStyleParams(
        parentId: String = NODE_ROOT
    ): MediaLibraryService.LibraryParams {
        val extras = Bundle().apply {
            putBoolean(ContentStyle.EXTRA_SUPPORTED, true)

            // The only browsable item on the home screen is "All stations".
            // Keeping browsable nodes as a simple list makes that entry discreet,
            // while playable radio stations use the user's grid/list choice.
            putInt(ContentStyle.EXTRA_BROWSABLE_HINT, ContentStyle.LIST)
            putInt(
                ContentStyle.EXTRA_PLAYABLE_HINT,
                if (nodeUsesGrid(parentId)) ContentStyle.GRID else ContentStyle.LIST
            )
            putParcelableArrayList(KEY_ACTION_ROOT_LIST, browseActionsRootList())
        }
        return MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()
    }

    companion object {
        private const val TAG = "RadioService"
        private const val TAG_ICY = "IcyMeta"
        private const val TAG_DUMP = "MetaDump"

        private val CMD_TOGGLE_DIAG =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_DIAG", Bundle.EMPTY)
        private val CMD_TOGGLE_FAV =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_FAV", Bundle.EMPTY)

        // List item actions in Android Auto. The keys come from
        // androidx.media.utils.MediaConstants - hardcoded here because Media3
        // doesn't expose them in its own MediaConstants.
        private const val ACTION_FAVOURITE = "net.mspanc.twinsenradio.FAVOURITE"
        private const val ACTION_UNFAVOURITE = "net.mspanc.twinsenradio.UNFAVOURITE"
        private const val ACTION_VIEW_GRID = "net.mspanc.twinsenradio.VIEW_GRID"
        private const val ACTION_VIEW_LIST = "net.mspanc.twinsenradio.VIEW_LIST"

        private const val KEY_ACTION_ROOT_LIST =
            "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ROOT_LIST"
        private const val KEY_ACTION_ID_LIST =
            "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ID_LIST"
        private const val KEY_ACTION_ID =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ID"
        private const val KEY_ACTION_LABEL =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_LABEL"
        private const val KEY_ACTION_ICON_URI =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ICON_URI"
        private const val KEY_ACTION_MEDIA_ITEM_ID =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID"
        private const val KEY_ACTION_RESULT_REFRESH_ITEM =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM"
        private const val KEY_ACTION_RESULT_MESSAGE =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE"
        private const val KEY_ACTION_RESULT_BROWSE_NODE =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_BROWSE_NODE"

        /**
         * How long we wait after a control marker before deciding the station
         * has nothing more to tell us. Insertions in RMF follow one another a
         * few seconds apart, so 15s comfortably covers them.
         */
        private const val MARKER_GRACE_MS = 15_000L

        /** Poll interval for the small welle.io /mux.json document. */
        private const val MOT_POLL_MS = 2_000L

        /**
         * If the MOT revision has not changed after a track switch, reuse the current
         * slide after this delay. This preserves stations that intentionally keep a
         * static logo in MOT while still giving dynamic slides time to update first.
         */
        private const val MOT_UNCHANGED_FALLBACK_MS = 8_000L

        /** Assumed track length when the catalog doesn't know it. */
        private const val FALLBACK_TRACK_MS = 5 * 60_000L

        /**
         * Margin added to the track length before we consider the description stale.
         *
         * Stations shorten tracks, talk over endings, and play radio edits
         * shorter than the catalog version, so waiting long past the nominal
         * time gains nothing. Half a minute covers the natural drift while not
         * leaving a stale title on screen for too long.
         */
        private const val STALE_GRACE_MS = 30_000L

        const val NODE_ROOT = "/"
        const val NODE_FAVOURITES = "/fav"
        const val NODE_ALL = "/all"
        const val NODE_RECENT = "/recent"
        const val NODE_GENRES = "/genres"
        const val NODE_GENRE_PREFIX = "/genre/"

        private val BROWSE_NODES = listOf(NODE_FAVOURITES, NODE_ALL, NODE_RECENT, NODE_GENRES)
    }
}
