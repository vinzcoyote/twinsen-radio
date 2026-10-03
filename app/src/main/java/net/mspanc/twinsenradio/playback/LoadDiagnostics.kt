package net.mspanc.twinsenradio.playback

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * The retry policy deliberately doesn't propagate network errors upward, so
 * error messages don't flash on screen in the car. The side effect is that a
 * dropped stream looks exactly like slow buffering. This listener logs what
 * is actually happening - otherwise diagnosing issues in the field is
 * guesswork.
 *
 *   adb logcat -s LoadDiag
 */
@UnstableApi
class LoadDiagnostics(
    /**
     * The format as determined by the decoder. This is the only place where
     * we learn the real codec and sample rate - station headers can disagree
     * with them.
     */
    private val onAudioFormat: (Format) -> Unit = {}
) : AnalyticsListener {

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        Log.i(
            TAG,
            "audio format: ${format.sampleMimeType} codecs=${format.codecs} " +
                "bitrate=${format.bitrate} sr=${format.sampleRate} ch=${format.channelCount}"
        )
        onAudioFormat(format)
    }

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(TAG, "start pobierania: ${loadEventInfo.dataSpec.uri}")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(
            TAG,
            "zakonczone: ${loadEventInfo.bytesLoaded} B w ${loadEventInfo.loadDurationMs} ms " +
                "(${loadEventInfo.uri})"
        )
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        Log.w(
            TAG,
            "LOAD ERROR (${loadEventInfo.uri}) after ${loadEventInfo.loadDurationMs} ms, " +
                "pobrano ${loadEventInfo.bytesLoaded} B, anulowane=$wasCanceled: " +
                "${error.javaClass.simpleName}: ${error.message}"
        )
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(TAG, "anulowano pobieranie: ${loadEventInfo.uri}")
    }

    private companion object {
        const val TAG = "LoadDiag"
    }
}
