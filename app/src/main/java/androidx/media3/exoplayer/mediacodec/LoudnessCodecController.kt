package androidx.media3.exoplayer.mediacodec

import android.media.LoudnessCodecController
import android.media.LoudnessCodecController.OnLoudnessCodecUpdateListener
import android.media.MediaCodec
import android.os.Bundle
import androidx.annotation.RequiresApi

import com.google.common.base.Preconditions
import com.google.common.util.concurrent.MoreExecutors

@RequiresApi(35)
class LoudnessCodecController  constructor(private val updateListener: LoudnessParameterUpdateListener = LoudnessParameterUpdateListener.DEFAULT) {
    fun interface LoudnessParameterUpdateListener {
        fun onLoudnessParameterUpdate(parameters: Bundle): Bundle

        companion object {
            val DEFAULT: LoudnessParameterUpdateListener =
                LoudnessParameterUpdateListener { bundle: Bundle -> bundle }
        }
    }

    private val mediaCodecs = HashSet<MediaCodec?>()
    private var loudnessCodecController: LoudnessCodecController? = null

    fun setAudioSessionId(audioSessionId: Int) {
        if (loudnessCodecController != null) {
            loudnessCodecController!!.close()
            loudnessCodecController = null
        }
        val loudnessCodecController =
            LoudnessCodecController.create(
                audioSessionId,
                MoreExecutors.directExecutor(),
                object : OnLoudnessCodecUpdateListener {
                    override fun onLoudnessCodecUpdate(
                        codec: MediaCodec,
                        parameters: Bundle
                    ): Bundle {
                        return updateListener.onLoudnessParameterUpdate(parameters)
                    }
                })
        this.loudnessCodecController = loudnessCodecController
        val it = mediaCodecs.iterator()
        while (it.hasNext()) {
            val registered = loudnessCodecController.addMediaCodec(it.next()!!)
            if (!registered) {
                it.remove()
            }
        }
    }

    fun addMediaCodec(mediaCodec: MediaCodec) {
        if (loudnessCodecController?.addMediaCodec(mediaCodec) != true) {
            // Don't add codec if the existing loudness controller can't handle it.
            return
        }
        Preconditions.checkState(mediaCodecs.add(mediaCodec))
    }

    fun removeMediaCodec(mediaCodec: MediaCodec) {
        val removedCodec = mediaCodecs.remove(mediaCodec)
        if (removedCodec && loudnessCodecController != null) {
            loudnessCodecController!!.removeMediaCodec(mediaCodec)
        }
    }

    fun release() {
        mediaCodecs.clear()
        loudnessCodecController?.close()
    }
}
