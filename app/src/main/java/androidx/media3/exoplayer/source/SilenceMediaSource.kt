
package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.annotation.GuardedBy
import androidx.annotation.IntRange
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.getPcmFrameSize
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions
import kotlin.math.min

class SilenceMediaSource private constructor(private var durationUs: Long, mediaItem: MediaItem) :
    BaseMediaSource() {
    class Factory {
        private var durationUs: Long = 0
        private var tag: Any? = null
        fun setDurationUs(@IntRange(from = 1) durationUs: Long): Factory {
            this.durationUs = durationUs
            return this
        }

        fun setTag(tag: Any?): Factory {
            this.tag = tag
            return this
        }

        fun createMediaSource(): SilenceMediaSource {
            Preconditions.checkState(durationUs > 0)
            return SilenceMediaSource(durationUs, MEDIA_ITEM.buildUpon().setTag(tag).build())
        }
    }

    @GuardedBy("this")
    override var mediaItem: MediaItem = mediaItem

    constructor(durationUs: Long) : this(durationUs, MEDIA_ITEM)

    init {
        Preconditions.checkArgument(durationUs >= 0)
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        refreshSourceInfo(
            SinglePeriodTimeline(
                durationUs,  
                true,  
                false,  
                false,  
                null,
                mediaItem
            )
        )
    }

    override fun maybeThrowSourceInfoRefreshError() {}

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        return SilenceMediaPeriod(durationUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {}

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return true
    }

    @Synchronized
    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
    }

    override fun releaseSourceInternal() {}

    private class SilenceMediaPeriod(private val durationUs: Long) : MediaPeriod {
        private val sampleStreams: MutableList<SampleStream> = mutableListOf()

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            callback.onPrepared( this)
        }

        override fun maybeThrowPrepareError() {}

        override val trackGroups: TrackGroupArray=TRACKS

        override fun selectTracks(
            selections: Array<ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            var positionUs = positionUs
            positionUs = constrainSeekPosition(positionUs)
            for (i in selections.indices) {
                if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                    sampleStreams.remove(streams[i])
                    streams[i] = null
                }
                if (streams[i] == null && selections[i] != null) {
                    val stream = SilenceSampleStream(durationUs)
                    stream.seekTo(positionUs)
                    sampleStreams.add(stream)
                    streams[i] = stream
                    streamResetFlags[i] = true
                }
            }
            return positionUs
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {}

        override fun readDiscontinuity(): Long {
            return C.TIME_UNSET
        }

        override fun seekToUs(positionUs: Long): Long {
            var positionUs = positionUs
            positionUs = constrainSeekPosition(positionUs)
            for (i in sampleStreams.indices) {
                (sampleStreams[i] as SilenceSampleStream).seekTo(positionUs)
            }
            return positionUs
        }

        override fun getAdjustedSeekPositionUs(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long {
            return constrainSeekPosition(positionUs)
        }

        override val bufferedPositionUs: Long=C.TIME_END_OF_SOURCE
        override val nextLoadPositionUs: Long= C.TIME_END_OF_SOURCE

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            return false
        }

        override val isLoading: Boolean=false

        override fun reevaluateBuffer(positionUs: Long) {}

        fun constrainSeekPosition(positionUs: Long): Long {
            return constrainValue(positionUs, 0, durationUs)
        }

        companion object {
            private val TRACKS = TrackGroupArray(arrayOf(TrackGroup(listOf(FORMAT))))
        }
    }

    private class SilenceSampleStream(durationUs: Long) : SampleStream {
        private val durationBytes: Long = getAudioByteCount(durationUs)
        private var sentFormat = false
        private var positionBytes: Long = 0

        fun seekTo(positionUs: Long) {
            positionBytes = constrainValue(getAudioByteCount(positionUs), 0, durationBytes)
        }

        override val isReady: Boolean=true

        override fun maybeThrowError() {}

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): Int {
            if (!sentFormat || (readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0) {
                formatHolder.format = FORMAT
                sentFormat = true
                return C.RESULT_FORMAT_READ
            }

            val bytesRemaining = durationBytes - positionBytes
            if (bytesRemaining == 0L) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }

            buffer.timeUs = getAudioPositionUs(positionBytes)
            buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
            val bytesToWrite = min(SILENCE_SAMPLE.size.toLong(), bytesRemaining).toInt()
            if ((readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0) {
                buffer.ensureSpaceForWrite(bytesToWrite)
                buffer.data!!.put(SILENCE_SAMPLE,  0, bytesToWrite)
            }
            if ((readFlags and SampleStream.FLAG_PEEK) == 0) {
                positionBytes += bytesToWrite.toLong()
            }
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long): Int {
            val oldPositionBytes = positionBytes
            seekTo(positionUs)
            return ((positionBytes - oldPositionBytes) / SILENCE_SAMPLE.size).toInt()
        }
    }

    companion object {
        const val MEDIA_ID: String = "SilenceMediaSource"

        private const val SAMPLE_RATE_HZ = 44100
        private val PCM_ENCODING: @C.PcmEncoding Int = C.ENCODING_PCM_16BIT
        private const val CHANNEL_COUNT = 2
        private val FORMAT = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(CHANNEL_COUNT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setPcmEncoding(PCM_ENCODING)
            .build()
        private val MEDIA_ITEM = MediaItem.Builder()
            .setMediaId(MEDIA_ID)
            .setUri(Uri.EMPTY)
            .setMimeType(FORMAT.sampleMimeType)
            .build()
        private val SILENCE_SAMPLE = ByteArray(getPcmFrameSize(PCM_ENCODING, CHANNEL_COUNT) * 1024)

        private fun getAudioByteCount(durationUs: Long): Long {
            val audioSampleCount: Long = Util.durationUsToSampleCount(durationUs, SAMPLE_RATE_HZ)
            return getPcmFrameSize(PCM_ENCODING, CHANNEL_COUNT) * audioSampleCount
        }

        private fun getAudioPositionUs(bytes: Long): Long {
            val audioSampleCount = bytes / getPcmFrameSize(PCM_ENCODING, CHANNEL_COUNT)
            return Util.sampleCountToDurationUs(audioSampleCount, SAMPLE_RATE_HZ)
        }
    }
}
