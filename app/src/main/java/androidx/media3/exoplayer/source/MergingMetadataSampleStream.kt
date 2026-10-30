package androidx.media3.exoplayer.source

import android.util.LongSparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import java.io.IOException
import androidx.core.util.size

internal class MergingMetadataSampleStream(
    val primaryStream: SampleStream,
    val metadataStream: SampleStream, videoFormat: Format
) : SampleStream {
    private val metadataFormatHolder: FormatHolder = FormatHolder()
    private val metadataBuffer: DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val metadataCache: LongSparseArray<ByteArray> = LongSparseArray<ByteArray>()
    private val maxCacheSize: Int = getMaxCacheSize(videoFormat)
    private var lastMetadataPtsUs: Long = C.TIME_UNSET
    private var activeMetadata: ByteArray? = null
    private var metadataTrackInvalid = false
    private var metadataStreamEnded = false

    override val isReady: Boolean
        get() = primaryStream.isReady

    @Throws(IOException::class)
    override fun maybeThrowError() {
        primaryStream.maybeThrowError()
        if (!metadataTrackInvalid) {
            metadataStream.maybeThrowError()
        }
    }

    override fun readData(
        formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
    ): @ReadDataResult Int {
        val result: @ReadDataResult Int = primaryStream.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream && (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0 && (readFlags and SampleStream.FLAG_PEEK) == 0 && !metadataTrackInvalid) {
            populateMetadataCache(buffer.timeUs)
            val metadata = findActiveMetadata(buffer.timeUs)
            if (metadata != null) {
                attachSupplementalData(buffer, metadata)
            }
        }
        return result
    }

    override fun skipData(positionUs: Long): Int {
        return primaryStream.skipData(positionUs)
    }

    override val flags: @SampleStream.Flags Int
        get() = primaryStream.flags

    fun reset() {
        metadataCache.clear()
        activeMetadata = null
        lastMetadataPtsUs = C.TIME_UNSET
        metadataTrackInvalid = false
        metadataStreamEnded = false
        metadataBuffer.clear()
    }

    private fun populateMetadataCache(videoPtsUs: Long) {
        if (metadataStreamEnded) {
            return
        }
        while (true) {
            metadataBuffer.clear()
            val result: @ReadDataResult Int =
                metadataStream.readData(metadataFormatHolder, metadataBuffer,  0)
            if (result == C.RESULT_NOTHING_READ) {
                break
            }
            if (result == C.RESULT_FORMAT_READ) {
                // Format change in metadata stream; continue reading.
                continue
            }
            // RESULT_BUFFER_READ
            if (metadataBuffer.isEndOfStream) {
                metadataStreamEnded = true
                break
            }
            val metadataPtsUs = metadataBuffer.timeUs
            // Detect out-of-order metadata samples (e.g., after a backward seek).
            if (lastMetadataPtsUs != C.TIME_UNSET && metadataPtsUs < lastMetadataPtsUs) {
                metadataTrackInvalid = true
                metadataCache.clear()
                activeMetadata = null
            }
            lastMetadataPtsUs = metadataPtsUs
            val data = metadataBuffer.data
            if (!metadataTrackInvalid && data != null) {
                metadataBuffer.flip()
                val format = metadataFormatHolder.format
                var initializationDataSize = 0
                if (format != null) {
                    for (i in format.initializationData.indices) {
                        initializationDataSize += format.initializationData[i].size
                    }
                }
                val payload = ByteArray(initializationDataSize + data.remaining())
                var offset = 0
                if (format != null) {
                    for (i in format.initializationData.indices) {
                        val initData = format.initializationData[i]
                        System.arraycopy(initData, 0, payload, offset, initData.size)
                        offset += initData.size
                    }
                }
                data.get(payload, offset, data.remaining())
                metadataCache.put(metadataPtsUs, payload)
                evictCacheIfNeeded()
            }
            if (metadataPtsUs > videoPtsUs) {
                // We've read past the current video frame's PTS.
                break
            }
        }
    }

    private fun findActiveMetadata(videoPtsUs: Long): ByteArray? {
        // Search the cache for the closest metadata sample with PTS <= videoPtsUs.
        var bestMatch: ByteArray? = null
        for (i in metadataCache.size - 1 downTo 0) {
            if (metadataCache.keyAt(i) <= videoPtsUs) {
                bestMatch = metadataCache.valueAt(i)
                break
            }
        }
        if (bestMatch != null) {
            activeMetadata = bestMatch
        }
        return activeMetadata
    }

    private fun evictCacheIfNeeded() {
        while (metadataCache.size > maxCacheSize) {
            metadataCache.removeAt(0)
        }
    }

    companion object {
        private const val DEFAULT_MAX_NUM_REORDER_SAMPLES = 16

        private fun attachSupplementalData(buffer: DecoderInputBuffer, metadata: ByteArray) {
            buffer.resetSupplementalData(metadata.size)
            buffer.supplementalData!!.put(metadata)
            buffer.addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        }

        private fun getMaxCacheSize(videoFormat: Format): Int {
            // We need maxNumReorderSamples to account for B-frame reordering, +1 for the currently active
            // frame, and +1 to read ahead to the next metadata sample to know when the active one expires.
            if (videoFormat.maxNumReorderSamples != Format.NO_VALUE) {
                return videoFormat.maxNumReorderSamples + 2
            }
            return DEFAULT_MAX_NUM_REORDER_SAMPLES + 2
        }
    }
}
