package net.mspanc.twinsenradio.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.ClockColors
import net.mspanc.twinsenradio.data.ClockFace
import net.mspanc.twinsenradio.data.LineContent
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Builds the metadata that goes into the MediaSession, and from there to Android Auto
 * and on to the dashboard display.
 */
class MetadataFactory(private val context: Context, private val prefs: Prefs) {

    private val artworkBytesCache = HashMap<String, ByteArray?>()

    /** Metadata for a browse-list item (Android Auto renders a tile from it). */
    fun forBrowseItem(station: Station): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(station.name)
            .setSubtitle(station.genre)
            .setArtist(station.genre)
            .setStation(station.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setArtworkUri(logoUri(station))
            .build()

    fun forFolder(title: String, mediaType: Int, artwork: Uri? = null): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setArtworkUri(artwork)
            .build()

    /**
     * Metadata for the currently playing station.
     *
     * In diagnostic mode, every field gets its own Polish name - this is the
     * version used to figure out the layout on the AID in the Passat.
     */
    fun forPlayback(
        station: Station,
        now: NowPlaying?,
        coverArtUrl: String? = null,
        trackInfo: CoverArtLookup.TrackInfo? = null
    ): MediaMetadata {
        val b = MediaMetadata.Builder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)

        if (prefs.diagnosticMode) {
            val withApi = prefs.diagnosticShowApiName
            val clock = clockText()

            // Each field carries its own name AND the clock, e.g. "TYT.WYSW 16:44".
            // A single drive then answers two questions at once: which field
            // the head unit shows, and whether it refreshes it during playback
            // or freezes it at the value from when the track started.
            fun v(api: String): String {
                val field = DiagnosticFields.TEXT.first { it.api == api }
                return DiagnosticFields.value(field, withApi) + " " + clock
            }

            b.setTitle(v("title"))
                .setArtist(v("artist"))
                .setAlbumTitle(v("albumTitle"))
                .setAlbumArtist(v("albumArtist"))
                .setDisplayTitle(v("displayTitle"))
                .setSubtitle(v("subtitle"))
                .setDescription(v("description"))
                .setStation(v("station"))
                .setGenre(v("genre"))
                .setComposer(v("composer"))
                .setWriter(v("writer"))
                .setConductor(v("conductor"))
                .setCompilation(v("compilation"))
                .setTrackNumber(DiagnosticFields.TRACK_NUMBER)
                .setTotalTrackCount(DiagnosticFields.TOTAL_TRACKS)
                .setDiscNumber(DiagnosticFields.DISC_NUMBER)
                .setTotalDiscCount(DiagnosticFields.TOTAL_DISCS)
                .setRecordingYear(DiagnosticFields.RECORDING_YEAR)
                .setReleaseYear(DiagnosticFields.RELEASE_YEAR)
        } else {
            val p = prefs.presentation

            // We fix up the text once, in one place - all lines and semantic
            // fields then use the same strings. While at it, we also
            // straighten out the order when a station broadcasts "title - artist"
            // instead of the other way round (Jacaranda FM).
            val title = now?.let { displayTitle(it, trackInfo) }.orEmpty()
            val artist = now?.let { displayArtist(it, trackInfo) }.orEmpty()

            val top = lineText(p.top, station, now, trackInfo, title, artist, coverArtUrl)
            val bottom = lineText(p.bottom, station, now, trackInfo, title, artist, coverArtUrl)

            // The middle line is only visible on the AID - the one place where a
            // network warning can go without cluttering the central screen too.
            val middle = when (PlaybackStatusBus.status.value) {
                PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> context.getString(R.string.aid_waiting_network)
                else -> lineText(p.middle, station, now, trackInfo, title, artist, coverArtUrl)
            }

            // The fields the head unit actually renders. Measured in the Passat
            // (FINDINGS.md): the AID reads subtitle / description / displayTitle,
            // the central screen reads displayTitle as the large line and subtitle as the small one.
            b.setSubtitle(top)
                .setDescription(middle)
                .setDisplayTitle(bottom)
                // Semantic fields. They don't appear on any screen in the car,
                // but they describe what's actually playing - other systems may
                // reach for them, so we keep them honest rather than as copies of the lines.
                .setTitle(titleLine(now, title))
                .setArtist(artist)
                .setAlbumTitle(trackInfo?.album.orEmpty())
                .setStation(station.name)
                .setGenre(station.genre)
        }

        applyArtwork(b, station, coverArtUrl)
        return b.build()
    }

    /**
     * The content of a single description line.
     *
     * Lines containing the artist are intentionally empty when no track is playing -
     * duplicating the station name across several lines is exactly the flaw seen
     * in ReplaIO and in the official RNS app.
     */
    private fun lineText(
        content: LineContent,
        station: Station,
        now: NowPlaying?,
        info: CoverArtLookup.TrackInfo?,
        title: String,
        artist: String,
        coverArtUrl: String?
    ): String = when (content) {
        LineContent.CLOCK -> clockOrStationName(station, coverArtUrl)
        LineContent.STATION -> station.name
        LineContent.EMPTY -> ""
        LineContent.TITLE -> titleLine(now, title)
        LineContent.ARTIST -> if (now?.isRealSong == true) artist else ""
        LineContent.ARTIST_ALBUM ->
            if (now?.isRealSong == true) {
                composeArtistLine(now, info, prefs.enrichWithYear)
            } else {
                ""
            }
        LineContent.TITLE_ALBUM ->
            if (now?.isRealSong == true) {
                composeTitleLine(now, info, prefs.enrichWithYear)
            } else {
                titleLine(now, title)
            }
        LineContent.TRACK_FULL ->
            if (now?.isRealSong == true) {
                listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ")
            } else {
                // Outside of a track there's nothing to join - show the same thing as the title line
                titleLine(now, title)
            }
    }

    /**
     * What a line set to "Clock" actually shows.
     *
     * The graphic clock (drawn in place of the artwork, see [applyArtwork]) and a
     * text line both saying the time would be redundant - so a text line only
     * shows the time while the *real* cover art is on screen instead of the
     * clock graphic. The moment the graphic clock takes over (track's cover art
     * missing, or "always" mode), the text line switches to the station name,
     * which is otherwise nowhere to be seen at that point.
     */
    private fun clockOrStationName(station: Station, coverArtUrl: String?): String {
        val p = prefs.presentation
        val clockGraphicShowing = p.clockFace != ClockFace.NONE &&
            (prefs.clockCoverAlways || coverArtUrl == null)
        return if (clockGraphicShowing) station.name else clockText()
    }

    /**
     * The title line. Outside of a track we insert whatever we know at that
     * moment: the news, the station's slogan, or an ad label - anything but an empty screen.
     */
    private fun titleLine(now: NowPlaying?, title: String): String = when {
        now?.isRealSong == true -> title
        now?.isNews == true -> "${now.slogan} — serwis informacyjny"
        now?.slogan != null -> now.slogan!!
        now?.isAd == true -> adText(now)
        else -> ""
    }

    private fun adText(now: NowPlaying): String {
        val seconds = now.adDurationMs / 1000
        return if (seconds > 0) "$AD_LABEL · ${seconds}s" else AD_LABEL
    }

    private fun applyArtwork(b: MediaMetadata.Builder, station: Station, coverArtUrl: String?) {
        // Clock instead of cover art - drawn on the fly, so there's no URI and it
        // has to travel as bytes.
        val p = prefs.presentation
        if (p.clockFace != ClockFace.NONE) {
            val useClock = prefs.clockCoverAlways || coverArtUrl == null
            if (useClock) {
                val bg = ClockColors.background(prefs.clockBackground)
                val fg = ClockColors.foreground(prefs.clockForeground, bg)
                ClockArt.pngBytes(p.clockFace, bg, fg)?.let {
                    b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    return
                }
            }
        }
        // A found track cover art takes priority over the station logo.
        // Deliberately independent of diagnostic mode: that mode only swaps out
        // the text fields for labels, the artwork should always behave the same way.
        if (coverArtUrl != null) {
            b.setArtworkUri(Uri.parse(coverArtUrl))
            return
        }
        when (prefs.artworkMode) {
            ArtworkMode.NONE -> Unit
            ArtworkMode.EMBEDDED_BYTES -> {
                val bytes = artworkBytes(station)
                if (bytes != null) {
                    b.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                } else {
                    b.setArtworkUri(logoUri(station))
                }
            }
            else -> b.setArtworkUri(logoUri(station))
        }
    }

    /**
     * The logo URI: a bundled resource or remote artwork from the M3U list.
     *
     * The bundled logo goes through [LogoProvider] rather than as an android.resource://
     * with a resource number - see the comment in that class: the numbers change
     * between versions, and Android Auto caches artwork by URI.
     */
    fun logoUri(station: Station): Uri {
        // The user's own custom artwork beats everything else
        customLogoFile(station)?.let { return LogoProvider.customUriFor(context, station, it) }
        station.logoUrl?.let { return Uri.parse(it) }
        return LogoProvider.uriFor(context, station, logoResId(station))
    }

    /**
     * The logo URI to show on the phone. Unlike [logoUri] it may return a
     * `file://` URI - local screens read the file directly, without a middleman.
     */
    fun logoDisplayUri(station: Station): Uri? =
        customLogoFile(station)?.let { Uri.fromFile(it) }
            ?: station.logoUrl?.let { Uri.parse(it) }

    private fun customLogoFile(station: Station): java.io.File? =
        prefs.customLogo(station.id)?.let { java.io.File(it) }?.takeIf { it.exists() }

    fun logoResId(station: Station): Int {
        val name = station.logo ?: return R.drawable.logo_placeholder
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else R.drawable.logo_placeholder
    }

    private fun artworkBytes(station: Station): ByteArray? =
        artworkBytesCache.getOrPut(station.id) {
            runCatching {
                val drawable = ContextCompat.getDrawable(context, logoResId(station)) ?: return@runCatching null
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
                    val size = ART_SIZE
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                        val c = Canvas(bmp)
                        drawable.setBounds(0, 0, size, size)
                        drawable.draw(c)
                    }
                }
                val scaled = if (bitmap.width > ART_SIZE || bitmap.height > ART_SIZE) {
                    Bitmap.createScaledBitmap(bitmap, ART_SIZE, ART_SIZE, true)
                } else {
                    bitmap
                }
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }.getOrNull()
        }

    companion object {
        /** 512 px is a reasonable compromise: the HU gets a sharp image, and the Binder doesn't bloat. */
        private const val ART_SIZE = 512

        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        const val AD_LABEL = "Publicité"

        /**
         * The artist line, supplemented with the release when needed.
         *
         * Shared between the car metadata and the phone's playback screen -
         * otherwise the phone would be missing the album name visible in the car.
         *
         * RMF already provides a ready-made "Wiktoria Kida / Księga" - we don't
         * touch that kind of text. For stations that give just the artist, we
         * append what the catalog knows: "Kaeyra · single [2024]".
         */
        /**
         * Title and artist in a form fit for the screen: with corrected
         * order (when the station broadcasts it reversed) and tidied-up text.
         * Shared between the car and the phone so they don't drift apart from each other.
         */
        fun displayTitle(now: NowPlaying, info: CoverArtLookup.TrackInfo?): String {
            val swapped = info?.looksSwapped(now.artist, now.songTitle) == true
            return TextCase.tidy(if (swapped) now.artist else now.songTitle, info?.trackName)
        }

        fun displayArtist(now: NowPlaying, info: CoverArtLookup.TrackInfo?): String {
            val swapped = info?.looksSwapped(now.artist, now.songTitle) == true
            return TextCase.tidy(if (swapped) now.songTitle else now.artist, info?.artistName)
        }

        /**
         * The artist alone, without the album - for use where the album should go
         * on a separate line (the phone's playback screen), not after a dot.
         */
        fun composeArtistOnly(now: NowPlaying, info: CoverArtLookup.TrackInfo?): String {
            val artist = displayArtist(now, info)
            if (artist.isBlank() || info == null) return artist

            // Only trim the tail when the catalog confirms it's an album
            // name. Otherwise "Shimza / AR/CO / Kasango" would lose its third artist.
            val parts = artist.split(Regex("\\s+[/,]\\s+"))
            return if (parts.size > 1 && info.tailIsAlbum(parts.last())) {
                parts.dropLast(1).joinToString(" / ")
            } else {
                artist
            }
        }

        /**
         * @param includeYear whether the album gets its year appended, when the
         *   catalog knows it - see [Prefs.enrichWithYear]. The album itself
         *   always shows when we have one; this only controls the year suffix.
         */
        fun composeArtistLine(
            now: NowPlaying,
            info: CoverArtLookup.TrackInfo?,
            includeYear: Boolean
        ): String {
            val artistsOnly = composeArtistOnly(now, info)
            if (artistsOnly.isBlank() || info == null) return artistsOnly

            val album = info.albumLabel(includeYear) ?: return artistsOnly
            // We separate the release with a middle dot, not a slash - with several
            // artists separated by slashes there'd be no way to tell them apart from the album.
            return "$artistsOnly · $album"
        }

        /** Same idea as [composeArtistLine], but for the title instead of the artist. */
        fun composeTitleLine(
            now: NowPlaying,
            info: CoverArtLookup.TrackInfo?,
            includeYear: Boolean
        ): String {
            val titleOnly = displayTitle(now, info)
            if (titleOnly.isBlank() || info == null) return titleOnly

            val album = info.albumLabel(includeYear) ?: return titleOnly
            return "$titleOnly · $album"
        }

        /** Always a two-digit hour and minute, e.g. "09:07". */
        fun clockText(): String = LocalTime.now().format(CLOCK_FORMAT)
    }
}

/**
 * What came in the ICY (Icecast/SHOUTcast) metadata for the current stream.
 *
 * [isStationSelfTitle] denotes the case where the station put not a track but
 * its own name and slogan, or a show name, into that same field - e.g.
 * "Radio Nowy Świat - Pion i poziom!". A naive split on " - " then makes the
 * "artist" equal to the station name, and the name ends up on screen twice.
 */
data class NowPlaying(
    val raw: String,
    val artist: String?,
    val songTitle: String?,
    val isStationSelfTitle: Boolean = false,
    val isAd: Boolean = false,
    val adDurationMs: Long = 0,
    /** A station control marker, e.g. STOP_AD_BREAK - not content. */
    val isControlMarker: Boolean = false
) {
    /** Slogan or show name - what the station put in instead of a track. */
    val slogan: String? get() = if (isStationSelfTitle) songTitle else null

    /**
     * A news broadcast. RMF marks it as "RMF FM - FAKTY", other stations
     * use the words "wiadomosci", "informacje", "serwis". This isn't a track, but
     * it's worth calling it by name instead of showing an empty line.
     */
    val isNews: Boolean
        get() = slogan?.let { s -> NEWS_WORDS.any { s.contains(it, ignoreCase = true) } } == true

    /** Whether a track is really playing, as opposed to an ad, a marker, or the station's own announcement. */
    val isRealSong: Boolean
        get() = !isAd && !isStationSelfTitle && !isControlMarker && !songTitle.isNullOrBlank()

    companion object {
        /**
         * A typical StreamTitle is "Artist - Title".
         *
         * @param stationName the station name, needed to detect its own slogan
         * @param rawBlock the whole ICY block - this is how we detect ads. RMF sends
         *   an empty StreamTitle along with adw_ad='true', adId, and durationMilliseconds.
         */
        fun parse(
            streamTitle: String?,
            stationName: String? = null,
            rawBlock: String? = null
        ): NowPlaying? {
            val block = rawBlock.orEmpty()
            val isAd = Regex("""adw_ad='true'""").containsMatchIn(block) ||
                Regex("""adId='[^']+'""").containsMatchIn(block)
            val adMs = Regex("""durationMilliseconds='(\d+)'""")
                .find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0

            val raw = streamTitle?.trim().orEmpty()

            // Stations mark ads in two ways and can switch between them:
            // RMF used to send a block with adw_ad='true', and now just sends
            // StreamTitle='Reklama'. Without this list, the word "Reklama" would end up in
            // the cover art search and come back with the cover of an Uzbek song by that
            // title.
            if (raw.isNotEmpty() && AD_WORDS.any { it.equals(raw, ignoreCase = true) }) {
                return NowPlaying(raw, null, null, isAd = true, adDurationMs = adMs)
            }

            if (raw.isEmpty()) {
                // An empty title on its own carries nothing, but if it's accompanied
                // by an ad marker, that's concrete information worth showing.
                return if (isAd) NowPlaying("", null, null, isAd = true, adDurationMs = adMs) else null
            }

            // Control markers: RMF sends things like STOP_AD_BREAK in StreamTitle
            // to mark the end of an ad block. This isn't a track title -
            // taken literally it used to stay on screen together with the
            // previous song's cover art.
            if (CONTROL_MARKER.matches(raw)) {
                val upper = raw.uppercase()
                val breakStarts = upper.contains("START") && upper.contains("AD")
                return NowPlaying(
                    raw = raw,
                    artist = null,
                    songTitle = null,
                    isAd = isAd || breakStarts,
                    adDurationMs = adMs,
                    isControlMarker = true
                )
            }

            val dash = raw.indexOf(" - ")
            if (dash <= 0) {
                val selfTitled = stationName != null && similar(raw, stationName)
                return NowPlaying(raw, null, raw, selfTitled, isAd, adMs)
            }

            val left = raw.substring(0, dash).trim()
            val right = raw.substring(dash + 3).trim()
            val selfTitled = stationName != null && similar(left, stationName)
            return NowPlaying(raw, left, right, selfTitled, isAd, adMs)
        }

        /**
         * Only uppercase letters, digits, and underscores, no spaces - that's what
         * station control markers look like, not track titles.
         */
        private val CONTROL_MARKER = Regex("^[A-Z0-9][A-Z0-9_]{3,}$")

        /** Words used to recognize a news broadcast. */
        private val NEWS_WORDS = listOf(
            "fakty", "wiadomosci", "wiadomości", "informacje", "serwis", "news"
        )

        /** Titles that actually denote an ad block, not a track. */
        private val AD_WORDS = listOf(
            "reklama", "reklamy", "reklamа",
            "spot reklamowy", "blok reklamowy",
            "advertisement", "advert", "commercial", "ad break", "ads"
        )

        /** A comparison resilient to diacritics, letter case, and the word "radio". */
        private fun similar(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase()
                .replace("ł", "l")
                .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]"), "")
                .replace("radio", "")
            val na = norm(a)
            val nb = norm(b)
            return na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
        }
    }
}
