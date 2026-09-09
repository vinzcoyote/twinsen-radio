package net.mspanc.twinsenradio.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivityNowPlayingBinding
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.playback.RadioService
import net.mspanc.twinsenradio.playback.TextCase

/**
 * Full-screen player on the phone: large cover art, metadata, controls, and
 * a back arrow to the station list. Opens by tapping the playback bar on the
 * home screen.
 */
@UnstableApi
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var b: ActivityNowPlayingBinding
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var prefs: Prefs
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        b = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                maxOf(systemBars.bottom, ime.bottom)
            )

            insets
        }

        repo = StationRepository.get(this)
        prefs = Prefs(this)
        metadata = MetadataFactory(this, prefs)

        b.toolbar.setNavigationOnClickListener { finish() }

        b.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }

        b.prev.setOnClickListener { step(-1) }
        b.next.setOnClickListener { step(+1) }

        b.favourite.setOnClickListener {
            val id = PlaybackStatusBus.stationId.value ?: return@setOnClickListener
            prefs.toggleFavourite(id)
            render()
        }

        listOf(
            PlaybackStatusBus.stationId,
            PlaybackStatusBus.nowPlaying,
            PlaybackStatusBus.status,
            PlaybackStatusBus.coverArtUrl,
            PlaybackStatusBus.trackInfo,
            PlaybackStatusBus.quality,
            Prefs.favouritesFlow
        ).forEach { flow ->
            lifecycleScope.launch {
                flow.collect {
                    render()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        val token = SessionToken(
            this,
            ComponentName(this, RadioService::class.java)
        )

        val future = MediaController.Builder(this, token).buildAsync()

        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {

                    override fun onIsPlayingChanged(isPlaying: Boolean) = render()

                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int
                    ) = render()

                    override fun onMediaMetadataChanged(
                        mediaMetadata: MediaMetadata
                    ) = render()
                })
            }

            render()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    /**
     * Quality button for the currently playing station.
     *
     * It's the same setting as in the station card - we save it in [Prefs], so
     * a change made here is visible there and vice versa. The service reloads
     * the stream on its own, since it observes this preference.
     *
     * Only visible when we actually know the bitrate - from the directory or,
     * for "bare" URLs without that declaration, from the live decoder. Without
     * it there's nothing to show, so the button disappears instead of showing
     * a placeholder.
     */
    private fun renderBitrate(station: Station?) {
        val variants = station?.variants().orEmpty()

        val chosen = station?.let {
            prefs.selectedStream(it.id)
        } ?: variants.maxByOrNull {
            it.kbps
        }?.url

        val current = variants.firstOrNull {
            it.url == chosen
        } ?: variants.firstOrNull()

        val label = current?.kbpsLabel(
            PlaybackStatusBus.qualityKbps.value
        )

        if (station == null || label == null) {
            b.bitrate.visibility = android.view.View.GONE
            return
        }

        b.bitrate.visibility = android.view.View.VISIBLE
        b.bitrate.text = label

        b.bitrate.setOnClickListener {
            StreamPicker.show(this, station, prefs) {
                renderBitrate(station)
            }
        }
    }

    /** Jump to the neighbouring station in the same list, with wraparound. */
    private fun step(delta: Int) {
        val all = repo.all()

        if (all.isEmpty()) return

        val currentId = PlaybackStatusBus.stationId.value

        val index = all.indexOfFirst {
            it.id == currentId
        }

        val target = if (index < 0) {
            0
        } else {
            ((index + delta) % all.size + all.size) % all.size
        }

        play(all[target])
    }

    private fun play(station: Station) {
        val c = controller ?: return

        c.setMediaItem(
            MediaItem.Builder()
                .setMediaId(station.mediaId)
                .build()
        )

        c.prepare()
        c.play()
    }

    private fun render() {
        val station = PlaybackStatusBus.stationId.value?.let {
            repo.byId(it)
        }

        val now = PlaybackStatusBus.nowPlaying.value

        b.stationName.text =
            station?.name ?: getString(R.string.nothing_playing)

        when {
            now?.isRealSong == true -> {
                val info = PlaybackStatusBus.trackInfo.value

                b.songArtist.text =
                    MetadataFactory.composeArtistOnly(now, info)

                val album =
                    info?.albumLabel(prefs.enrichWithYear).orEmpty()

                b.songAlbum.text = album

                b.songAlbum.visibility =
                    if (album.isBlank()) {
                        android.view.View.GONE
                    } else {
                        android.view.View.VISIBLE
                    }

                b.songTitle.text =
                    MetadataFactory.displayTitle(now, info)
            }

            now?.slogan != null -> {
                b.songTitle.text = now.slogan
                b.songArtist.text = ""
                b.songAlbum.visibility = android.view.View.GONE
            }

            now?.isAd == true -> {
                val seconds = now.adDurationMs / 1000

                b.songTitle.text =
                    if (seconds > 0) {
                        getString(R.string.ad_with_length, seconds)
                    } else {
                        getString(R.string.ad)
                    }

                b.songArtist.text = ""
                b.songAlbum.visibility = android.view.View.GONE
            }

            else -> {
                b.songTitle.text = ""
                b.songArtist.text = ""
                b.songAlbum.visibility = android.view.View.GONE
            }
        }

        val isFav =
            station != null && station.id in prefs.favourites

        b.favourite.setImageResource(
            if (isFav) {
                R.drawable.ic_star_filled
            } else {
                R.drawable.ic_star_outline
            }
        )

        b.favourite.contentDescription =
            getString(
                if (isFav) {
                    R.string.fav_remove
                } else {
                    R.string.fav_add
                }
            )

        b.diagnosticBanner.visibility =
            if (prefs.diagnosticMode) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }

        ArtworkLoader.into(
            lifecycleScope,
            PlaybackStatusBus.coverArtUrl.value?.let {
                android.net.Uri.parse(it)
            },
            station?.let {
                metadata.logoDisplayUri(it)
            },
            station?.let {
                metadata.logoResId(it)
            } ?: R.drawable.logo_placeholder,
            b.art
        )

        val statusText = getString(
            when (PlaybackStatusBus.status.value) {
                PlaybackStatusBus.Status.CONNECTING ->
                    R.string.status_connecting

                PlaybackStatusBus.Status.BUFFERING ->
                    R.string.status_buffering

                PlaybackStatusBus.Status.PLAYING ->
                    R.string.status_playing

                PlaybackStatusBus.Status.RECONNECTING ->
                    R.string.status_reconnecting

                PlaybackStatusBus.Status.WAITING_FOR_NETWORK ->
                    R.string.status_waiting_network

                PlaybackStatusBus.Status.STATION_UNREACHABLE ->
                    R.string.status_unreachable

                PlaybackStatusBus.Status.IDLE ->
                    R.string.status_idle
            }
        )

        val quality = PlaybackStatusBus.quality.value

        b.status.text =
            if (quality.isNullOrBlank()) {
                statusText
            } else {
                "$statusText · $quality"
            }

        renderBitrate(station)

        b.playPause.setImageResource(
            if (controller?.isPlaying == true) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        )
    }
}
