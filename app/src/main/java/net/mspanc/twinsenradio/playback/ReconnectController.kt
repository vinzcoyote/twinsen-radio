package net.mspanc.twinsenradio.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Keeps playback alive when LTE drops in a tunnel or out in the countryside.
 *
 * Rule: the user should never see an error. If they wanted to listen, we
 * play - and if we can't, we wait and keep trying. Two independent triggers:
 *
 *  1. `ConnectivityManager` - when the phone regains a usable network, we
 *     resume immediately, without waiting for the next backoff step;
 *  2. a time-based backoff - in case the network is formally up, but the
 *     transmitter or CDN isn't responding.
 *
 * Most interruptions are already handled further down by
 * [InfiniteLoadErrorHandlingPolicy] - there, ExoPlayer retries the fetch
 * without ever reporting an error. This controller only catches what slips
 * through that layer.
 */
@UnstableApi
class ReconnectController(
    context: Context,
    private val player: Player,
    private val onStatusChanged: (Status) -> Unit
) : Player.Listener {

    /**
     * [STATION_UNREACHABLE] is not the same as [RECONNECTING]. It means: you have
     * a network, we've already tried several times, and the station stays
     * silent. This happens when a broadcaster decommissions a server but the
     * directory still lists the old address - which is exactly what happened
     * with Triple M Melbourne. We keep retrying, but we honestly report that
     * the problem is on the other end, not with signal coverage.
     */
    enum class Status { OK, RECONNECTING, WAITING_FOR_NETWORK, STATION_UNREACHABLE }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private var attempt = 0
    private var pendingRetry: Runnable? = null
    private var stuckCheck: Runnable? = null
    private var registered = false

    var status: Status = Status.OK
        private set(value) {
            if (field != value) {
                field = value
                onStatusChanged(value)
            }
        }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { if (needsRevive()) retryNow("siec wrocila") }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            handler.post { if (needsRevive()) retryNow("siec zwalidowana") }
        }
    }

    /**
     * Whether there's anything to rescue. We deliberately do NOT check `status`,
     * only the player's actual state.
     *
     * Reason: our retry policy is silent - ExoPlayer keeps trying to fetch the
     * stream indefinitely and never reports an error upward, so `onPlayerError`
     * may never fire at all. The player then sits stuck in BUFFERING with
     * status still OK, and the condition "status != OK" never let a returning
     * network trigger a resume. This is exactly how ReplaIO and TuneIn behave -
     * they can hang for an hour after you drive out of the garage before they
     * finally notice the network is back.
     */
    private fun needsRevive(): Boolean =
        player.playWhenReady && player.playbackState != Player.STATE_READY

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity?.registerNetworkCallback(request, networkCallback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "Failed to register network callback", it) }
        player.addListener(this)
    }

    fun stop() {
        cancelPending()
        cancelStuckCheck()
        player.removeListener(this)
        if (registered) {
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            registered = false
        }
    }

    // --- Player.Listener -----------------------------------------------------

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "Blad odtwarzania: ${error.errorCodeName}", error)
        if (!player.playWhenReady) {
            // The user didn't want to play anyway - there's nothing to rescue.
            status = Status.OK
            return
        }
        status = currentStatus()
        scheduleRetry()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                attempt = 0
                cancelPending()
                cancelStuckCheck()
                status = Status.OK
            }
            // Buffering by itself is normal. Buffering that doesn't end
            // within [STUCK_MS] means the silent retrier below is spinning
            // in circles - at that point we call it what it is and start
            // retrying ourselves.
            Player.STATE_BUFFERING -> scheduleStuckCheck()
            else -> cancelStuckCheck()
        }
    }

    // --- internal --------------------------------------------------------------

    private fun scheduleRetry() {
        cancelPending()
        val delay = BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.last() }
        attempt = (attempt + 1).coerceAtMost(BACKOFF_MS.size - 1)
        val runnable = Runnable { retryNow("backoff") }
        pendingRetry = runnable
        handler.postDelayed(runnable, delay)
        Log.i(TAG, "Ponowie za ${delay}ms (proba $attempt)")
    }

    private fun retryNow(reason: String) {
        cancelPending()
        if (!player.playWhenReady) return
        Log.i(TAG, "Resuming playback ($reason)")
        status = currentStatus()
        runCatching {
            player.prepare()
            player.play()
        }.onFailure { Log.w(TAG, "prepare() failed", it) }
        // If this fails, onPlayerError will schedule the next attempt.
        scheduleRetry()
    }

    private fun cancelPending() {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun scheduleStuckCheck() {
        cancelStuckCheck()
        if (!player.playWhenReady) return
        val runnable = Runnable {
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@Runnable
            status = currentStatus()
            Log.i(TAG, "Buforowanie ciagnie sie ponad ${STUCK_MS}ms - $status")
            scheduleRetry()
        }
        stuckCheck = runnable
        handler.postDelayed(runnable, STUCK_MS)
    }

    private fun cancelStuckCheck() {
        stuckCheck?.let { handler.removeCallbacks(it) }
        stuckCheck = null
    }

    /**
     * Without a network we wait for one. With a network we retry first, and
     * after [ATTEMPTS_BEFORE_UNREACHABLE] failed attempts we call it what it is.
     */
    private fun currentStatus(): Status = when {
        !hasUsableNetwork() -> Status.WAITING_FOR_NETWORK
        attempt >= ATTEMPTS_BEFORE_UNREACHABLE -> Status.STATION_UNREACHABLE
        else -> Status.RECONNECTING
    }

    private fun hasUsableNetwork(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "ReconnectController"
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

        /**
         * After this many milliseconds of uninterrupted buffering we conclude it's
         * not ordinary buffer filling but a lost connection. The value has margin
         * above the largest buffer profile's startup time (~4 s).
         */
        const val STUCK_MS = 12_000L

        /**
         * After this many failed attempts with a working network, we stop
         * pretending it's temporary. Four attempts amount to about 15 s of
         * increasing backoff.
         */
        const val ATTEMPTS_BEFORE_UNREACHABLE = 4
    }
}
