package androidx.media3.exoplayer.metadata

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.Util.createHandler
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.extractor.metadata.MetadataDecoder
import androidx.media3.extractor.metadata.MetadataInputBuffer
import com.google.common.base.Preconditions
import org.checkerframework.dataflow.qual.SideEffectFree

class MetadataRenderer constructor(
    private val output: MetadataOutput,
    outputLooper: Looper?,
    private val decoderFactory: MetadataDecoderFactory = MetadataDecoderFactory.DEFAULT,
    private val outputMetadataEarly: Boolean = false
) : BaseRenderer(
    C.TRACK_TYPE_METADATA
), Handler.Callback {
    private val outputHandler: Handler? =
        if (outputLooper == null) null else createHandler(outputLooper, this)
    private val buffer = MetadataInputBuffer()
    private var decoder: MetadataDecoder? = null
    private var inputStreamEnded = false
    private var outputStreamEnded = false
    private var subsampleOffsetUs: Long = 0
    private var pendingMetadata: Metadata? = null
    private var outputStreamOffsetUs = C.TIME_UNSET
    override val name: String = TAG

    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        return if (decoderFactory.supportsFormat(format)) {
            RendererCapabilities.create(
                if (format.cryptoType == C.CRYPTO_TYPE_NONE) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_DRM
            )
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        decoder = decoderFactory.createDecoder(formats[0])
        if (pendingMetadata != null) {
            pendingMetadata =
                pendingMetadata!!.copyWithPresentationTimeUs(
                    pendingMetadata!!.presentationTimeUs + outputStreamOffsetUs - offsetUs
                )
        }
        outputStreamOffsetUs = offsetUs
    }

    override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        pendingMetadata = null
        inputStreamEnded = false
        outputStreamEnded = false
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        var working = true
        while (working) {
            readMetadata(positionUs)
            working = outputMetadata(positionUs)
        }
    }

    private fun decodeWrappedMetadata(
        metadata: Metadata,
        decodedEntries: MutableList<Metadata.Entry>
    ) {
        for (i in 0..<metadata.length()) {
            val wrappedMetadataFormat = metadata.get(i).wrappedMetadataFormat
            if (wrappedMetadataFormat != null && decoderFactory.supportsFormat(wrappedMetadataFormat)) {
                val wrappedMetadataDecoder = decoderFactory.createDecoder(wrappedMetadataFormat)
                // wrappedMetadataFormat != null so wrappedMetadataBytes must be non-null too.
                val wrappedMetadataBytes = metadata.get(i).wrappedMetadataBytes!!
                buffer.clear()
                buffer.ensureSpaceForWrite(wrappedMetadataBytes.size)
                buffer.data!!.put(wrappedMetadataBytes)
                buffer.flip()
                val innerMetadata = wrappedMetadataDecoder.decode(buffer)
                if (innerMetadata != null) {
                    // The decoding succeeded, so we'll try another level of unwrapping.
                    decodeWrappedMetadata(innerMetadata, decodedEntries)
                }
            } else {
                // Entry doesn't contain any wrapped metadata, so output it directly.
                decodedEntries.add(metadata.get(i))
            }
        }
    }

    override fun onDisabled() {
        pendingMetadata = null
        decoder = null
        outputStreamOffsetUs = C.TIME_UNSET
    }

    override val isEnded: Boolean
        get() = outputStreamEnded
    override val isReady: Boolean
        get() = true

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_INVOKE_RENDERER -> {
                invokeRendererInternal(msg.obj as Metadata)
                return true
            }

            else ->         // Should never happen.
                throw IllegalStateException()
        }
    }

    private fun readMetadata(positionUs: Long) {
        if (!inputStreamEnded && pendingMetadata == null) {
            buffer.clear()
            val formatHolder = getFormatHolder()
            var result: @ReadDataResult Int =
                readSource(
                    formatHolder,
                    buffer,
                    SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA
                )
            if (result == C.RESULT_NOTHING_READ && !buffer.isEndOfStream) {
                // Nothing to consume yet.
                return
            } else if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) {
                // New buffer available, only consume if close enough to the current position.
                if (!outputMetadataEarly && positionUs < buffer.timeUs - MAX_READ_AHEAD_DURATION_US) {
                    return
                }
            } else if (result == C.RESULT_BUFFER_READ || result == C.RESULT_NOTHING_READ) {
                // EOS signal available, only consume if close enough to the stream duration.
                val streamEndPositionUs = streamEndPositionUs
                val positionInPeriodUs = positionUs - streamOffsetUs
                if (streamEndPositionUs == C.TIME_UNSET
                    || positionInPeriodUs < streamEndPositionUs - MAX_READ_AHEAD_DURATION_US
                ) {
                    return
                }
            }
            result = readSource(formatHolder, buffer, 0)
            if (result == C.RESULT_BUFFER_READ) {
                if (buffer.isEndOfStream) {
                    inputStreamEnded = true
                } else if (buffer.timeUs >= lastResetPositionUs) {
                    // Ignore metadata before start position.
                    buffer.subsampleOffsetUs = subsampleOffsetUs
                    buffer.flip()
                    val metadata: Metadata? = decoder!!.decode(buffer)
                    if (metadata != null) {
                        val entries = mutableListOf<Metadata.Entry>()
                        decodeWrappedMetadata(metadata, entries)
                        if (!entries.isEmpty()) {
                            val expandedMetadata =
                                Metadata(getPresentationTimeUs(buffer.timeUs), entries)
                            pendingMetadata = expandedMetadata
                        }
                    }
                }
            } else if (result == C.RESULT_FORMAT_READ) {
                subsampleOffsetUs = formatHolder.format!!.subsampleOffsetUs
            }
        }
    }

    private fun outputMetadata(positionUs: Long): Boolean {
        var didOutput = false
        if (pendingMetadata != null
            && (outputMetadataEarly
                    || pendingMetadata!!.presentationTimeUs <= getPresentationTimeUs(positionUs))
        ) {
            invokeRenderer(pendingMetadata!!)
            pendingMetadata = null
            didOutput = true
        }
        if (inputStreamEnded && pendingMetadata == null) {
            outputStreamEnded = true
        }
        return didOutput
    }

    private fun invokeRenderer(metadata: Metadata) {
        if (outputHandler != null) {
            outputHandler.obtainMessage(MSG_INVOKE_RENDERER, metadata).sendToTarget()
        } else {
            invokeRendererInternal(metadata)
        }
    }

    private fun invokeRendererInternal(metadata: Metadata) {
        output.onMetadata(metadata)
    }

    @SideEffectFree
    private fun getPresentationTimeUs(positionUs: Long): Long {
        Preconditions.checkState(positionUs != C.TIME_UNSET)
        Preconditions.checkState(outputStreamOffsetUs != C.TIME_UNSET)
        return positionUs - outputStreamOffsetUs
    }

    companion object {
        private const val TAG = "MetadataRenderer"
        private const val MSG_INVOKE_RENDERER = 1
        private const val MAX_READ_AHEAD_DURATION_US = C.MICROS_PER_SECOND
    }
}
