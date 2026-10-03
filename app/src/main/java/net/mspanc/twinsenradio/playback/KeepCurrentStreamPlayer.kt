package net.mspanc.twinsenradio.playback

import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * A wrapper that ignores a request to start a station that's already playing.
 *
 * Without this, selecting the same item from the list - whether in the app or in
 * Android Auto - triggered `setMediaItems` + `prepare`, i.e. disconnecting and
 * reconnecting to the stream. That's audible as a second-long gap, and for live
 * radio it makes no sense at all: it's the same, uninterrupted stream.
 *
 * The wrapper lives in the player layer on purpose - that way the guard applies
 * equally to the phone, the head unit, and voice control, without repeating the
 * condition in each of those places.
 */
@UnstableApi
class KeepCurrentStreamPlayer(player: Player) : ForwardingPlayer(player) {

    override fun setMediaItem(mediaItem: MediaItem) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    /**
     * A single item with the same mediaId as the one currently playing, with live
     * playback. State IDLE or ENDED means the stream has to be brought up again
     * anyway.
     */
    private fun isAlreadyPlaying(mediaItems: List<MediaItem>): Boolean {
        if (mediaItems.size != 1) return false
        val requested = mediaItems.first().mediaId
        val current = currentMediaItem?.mediaId ?: return false
        if (requested != current) return false
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return false

        Log.i(TAG, "station $requested is already playing - keeping current stream")
        if (!playWhenReady) play()
        return true
    }

    private companion object {
        const val TAG = "KeepCurrentStream"
    }
}
