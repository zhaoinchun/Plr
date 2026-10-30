package androidx.media3.exoplayer.image

import android.graphics.Bitmap
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import com.google.common.base.Preconditions

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

class ImageRenderer(private val decoderFactory: ImageDecoder.Factory, imageOutput: ImageOutput?) :
    BaseRenderer(C.TRACK_TYPE_IMAGE) {

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [REINITIALIZATION_STATE_NONE, REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM_THEN_WAIT, REINITIALIZATION_STATE_WAIT_END_OF_STREAM]
    )
    private annotation class ReinitializationState

    private val flagsOnlyBuffer = DecoderInputBuffer.newNoDataInstance()
    private val pendingOutputStreamChanges = ArrayDeque<OutputStreamInfo>()
    private var inputStreamEnded = false
    private var outputStreamEnded = false
    private var outputStreamInfo = OutputStreamInfo.UNSET
    private var lastProcessedOutputBufferTimeUs = C.TIME_UNSET
    private var largestQueuedPresentationTimeUs = C.TIME_UNSET
    private var decoderReinitializationState: @ReinitializationState Int =
        REINITIALIZATION_STATE_NONE
    private var firstFrameState: @C.FirstFrameState Int = C.FIRST_FRAME_NOT_RENDERED
    private var inputFormat: Format? = null
    private var decoder: ImageDecoder? = null
    private var inputBuffer: DecoderInputBuffer? = null
    private var imageOutput: ImageOutput = getImageOutput(imageOutput)
    private var imageMetadataListener: ImageMetadataListener? = null
    private var outputBitmap: Bitmap? = null
    private var readyToOutputTiles = false
    private var tileInfo: TileInfo? = null
    private var nextTileInfo: TileInfo? = null
    private var currentTileIndex = 0
    private var codecNeedsInitialization = false
    override val name: String = TAG

    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        return decoderFactory.supportsFormat(format)
    }

    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (outputStreamEnded) {
            return
        }
        if (inputFormat == null) {
            // We don't have a format yet, so try and read one.
            val formatHolder = getFormatHolder()
            flagsOnlyBuffer.clear()
            val result: @ReadDataResult Int =
                readSource(formatHolder, flagsOnlyBuffer, SampleStream.FLAG_REQUIRE_FORMAT)
            when (result) {
                C.RESULT_FORMAT_READ -> {
                    // Note that this works because we only expect to enter this if-condition once per playback.
                    inputFormat = formatHolder.format!!
                    codecNeedsInitialization = true
                }

                C.RESULT_BUFFER_READ -> {
                    // End of stream read having not read a format.
                    Preconditions.checkState(flagsOnlyBuffer.isEndOfStream)
                    inputStreamEnded = true
                    outputStreamEnded = true
                    return
                }

                else -> {
                    // We still don't have a format and can't make progress without one.
                    return
                }
            }
        }
        if (decoder == null && !maybeInitCodec()) {
            return
        }
        try {
            // Rendering loop.
            beginSection("drainAndFeedDecoder")
            while (drainOutput(positionUs, elapsedRealtimeUs)) {
            }
            while (feedInputBuffer(positionUs)) {
            }
            endSection()
        } catch (e: ImageDecoderException) {
            throw createRendererException(e, null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        }
    }

    override val isReady: Boolean
        get() = firstFrameState == C.FIRST_FRAME_RENDERED
                || (firstFrameState == C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED
                && readyToOutputTiles)

    override val isEnded: Boolean
        get() = outputStreamEnded

    @Throws(ExoPlaybackException::class)
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        firstFrameState =
            if (mayRenderStartOfStream)
                C.FIRST_FRAME_NOT_RENDERED
            else
                C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED
    }

    @Throws(ExoPlaybackException::class)
    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        if (outputStreamInfo.streamOffsetUs == C.TIME_UNSET
            || (pendingOutputStreamChanges.isEmpty()
                    && (largestQueuedPresentationTimeUs == C.TIME_UNSET
                    || (lastProcessedOutputBufferTimeUs != C.TIME_UNSET
                    && lastProcessedOutputBufferTimeUs >= largestQueuedPresentationTimeUs)))
        ) {
            // Either the first stream, or all previous streams have never queued any samples or have been
            // fully output already.
            outputStreamInfo =
                OutputStreamInfo(C.TIME_UNSET, offsetUs)
        } else {
            pendingOutputStreamChanges.add(
                OutputStreamInfo(largestQueuedPresentationTimeUs, offsetUs)
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED)
        outputStreamEnded = false
        inputStreamEnded = false
        outputBitmap = null
        tileInfo = null
        nextTileInfo = null
        readyToOutputTiles = false
        inputBuffer = null
        decoder?.flush()
        pendingOutputStreamChanges.clear()
    }

    override fun onDisabled() {
        inputFormat = null
        outputStreamInfo = OutputStreamInfo.UNSET
        pendingOutputStreamChanges.clear()
        releaseDecoderResources()
        imageOutput.onDisabled()
    }

    override fun onReset() {
        releaseDecoderResources()
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED)
    }

    override fun onRelease() {
        releaseDecoderResources()
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        when (messageType) {
            Renderer.MSG_SET_IMAGE_OUTPUT -> {
                val imageOutput = message as? ImageOutput
                setImageOutput(imageOutput)
            }

            Renderer.MSG_SET_IMAGE_METADATA_LISTENER -> imageMetadataListener =
                message as ImageMetadataListener?

            else -> super.handleMessage(messageType, message)
        }
    }

    @Throws(ImageDecoderException::class, ExoPlaybackException::class)
    private fun drainOutput(positionUs: Long, elapsedRealtimeUs: Long): Boolean {
        // If tileInfo and outputBitmap are both null, we must not return early. The EOS may have been
        // queued to the decoder, and we must stay in this method to deque it further down.
        if (outputBitmap != null && tileInfo == null) {
            return false
        }
        if (firstFrameState == C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED
            && state != Renderer.STATE_STARTED
        ) {
            return false
        }
        if (outputBitmap == null) {
            val outputBuffer = decoder!!.dequeueOutputBuffer() ?: return false
            if (outputBuffer.isEndOfStream) {
                if (decoderReinitializationState == REINITIALIZATION_STATE_WAIT_END_OF_STREAM) {
                    // We're waiting to re-initialize the decoder, and have now processed all final buffers.
                    releaseDecoderResources()
                    inputFormat!!
                    maybeInitCodec()
                } else {
                    outputBuffer.release()
                    if (pendingOutputStreamChanges.isEmpty()) {
                        outputStreamEnded = true
                    }
                }
                return false
            }
            outputBitmap = outputBuffer.bitmap!!
            outputBuffer.release()
        }

        if (readyToOutputTiles && outputBitmap != null && tileInfo != null) {
            val inputFormat = inputFormat!!
            val isThumbnailGrid =
                (inputFormat.tileCountHorizontal != 1 || inputFormat.tileCountVertical != 1)
                        && inputFormat.tileCountHorizontal != Format.NO_VALUE && inputFormat.tileCountVertical != Format.NO_VALUE
            // Lazily crop and store the bitmap to ensure we only have one tile in memory rather than
            // proactively storing a tile whenever creating TileInfos.
            if (!tileInfo!!.hasTileBitmap()) {
                tileInfo!!.tileBitmap = if (isThumbnailGrid)
                    cropTileFromImageGrid(tileInfo!!.tileIndex)
                else outputBitmap!!
            }
            if (!processOutputBuffer(
                    positionUs,
                    elapsedRealtimeUs,
                    tileInfo!!.tileBitmap!!,
                    tileInfo!!.presentationTimeUs
                )
            ) {
                return false
            }
            onProcessedOutputBuffer(tileInfo!!.presentationTimeUs)
            firstFrameState = C.FIRST_FRAME_RENDERED
            if (!isThumbnailGrid
                || tileInfo!!.tileIndex == inputFormat.tileCountVertical * inputFormat.tileCountHorizontal - 1
            ) {
                outputBitmap = null
            }
            tileInfo = nextTileInfo
            nextTileInfo = null
            return true
        }
        return false
    }

    private fun shouldForceRender(): Boolean {
        val isStarted = state == Renderer.STATE_STARTED
        return when (firstFrameState) {
            C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED -> isStarted
            C.FIRST_FRAME_NOT_RENDERED -> true
            C.FIRST_FRAME_RENDERED -> false
            else -> throw IllegalStateException()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        outputBitmap: Bitmap,
        bufferPresentationTimeUs: Long
    ): Boolean {
        val earlyUs = bufferPresentationTimeUs - positionUs
        if (shouldForceRender() || earlyUs < IMAGE_PRESENTATION_WINDOW_THRESHOLD_US) {
            if (imageMetadataListener != null) {
                imageMetadataListener!!.onImageAboutToBeAvailable(
                    bufferPresentationTimeUs - outputStreamInfo.streamOffsetUs,
                    inputFormat!!
                )
            }
            imageOutput.onImageAvailable(
                bufferPresentationTimeUs - outputStreamInfo.streamOffsetUs, outputBitmap
            )
            return true
        }
        return false
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeInitializeProcessingPipeline(): Boolean {
        // Do nothing.
        return true
    }

    private fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        lastProcessedOutputBufferTimeUs = presentationTimeUs
        while (!pendingOutputStreamChanges.isEmpty()
            && presentationTimeUs >= pendingOutputStreamChanges.peek()!!.previousStreamLastBufferTimeUs
        ) {
            outputStreamInfo = pendingOutputStreamChanges.removeFirst()
        }
    }

    @Throws(ImageDecoderException::class)
    private fun feedInputBuffer(positionUs: Long): Boolean {
        if (readyToOutputTiles && tileInfo != null) {
            return false
        }
        val formatHolder = getFormatHolder()
        if (decoder == null || decoderReinitializationState == REINITIALIZATION_STATE_WAIT_END_OF_STREAM || inputStreamEnded) {
            // We need to reinitialize the decoder or the input stream has ended.
            return false
        }
        if (inputBuffer == null) {
            inputBuffer = decoder!!.dequeueInputBuffer()
            if (inputBuffer == null) {
                return false
            }
        }
        if (decoderReinitializationState == REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM_THEN_WAIT) {
            inputBuffer!!.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
            decoder!!.queueInputBuffer(inputBuffer!!)
            inputBuffer = null
            decoderReinitializationState = REINITIALIZATION_STATE_WAIT_END_OF_STREAM
            return false
        }
        when (readSource(formatHolder, inputBuffer!!, 0)) {
            C.RESULT_NOTHING_READ -> return false
            C.RESULT_BUFFER_READ -> {
                inputBuffer!!.flip()
                // Input buffers with no data that are also non-EOS, only carry the timestamp for a grid
                // tile. These buffers are not queued.
                val shouldQueueBuffer =
                    (inputBuffer!!.data != null && inputBuffer!!.data!!.remaining() > 0)
                            || inputBuffer!!.isEndOfStream
                if (shouldQueueBuffer) {
                    inputBuffer!!.format = inputFormat
                    decoder!!.queueInputBuffer(inputBuffer!!)
                    currentTileIndex = 0
                }
                maybeAdvanceTileInfo(
                    positionUs,
                    inputBuffer!!
                )
                if (inputBuffer!!.isEndOfStream) {
                    inputStreamEnded = true
                    inputBuffer = null
                    return false
                } else {
                    largestQueuedPresentationTimeUs =
                        max(largestQueuedPresentationTimeUs, inputBuffer!!.timeUs)
                }
                // If inputBuffer was queued, the decoder already cleared it. Otherwise, inputBuffer is
                // cleared here.
                if (shouldQueueBuffer) {
                    inputBuffer = null
                } else {
                    inputBuffer!!.clear()
                }
                return !readyToOutputTiles
            }

            C.RESULT_FORMAT_READ -> {
                inputFormat = formatHolder.format!!
                codecNeedsInitialization = true
                decoderReinitializationState = REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM_THEN_WAIT
                return true
            }

            else -> throw IllegalStateException()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeInitCodec(): Boolean {
        if (!maybeInitializeProcessingPipeline()) {
            return false
        }
        if (!codecNeedsInitialization) {
            return true
        }
        if (canCreateDecoderForFormat(inputFormat!!)) {
            decoder?.release()
            decoder = decoderFactory.createImageDecoder()
        } else {
            throw createRendererException(
                ImageDecoderException("Provided decoder factory can't create decoder for format."),
                inputFormat,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            )
        }
        codecNeedsInitialization = false
        return true
    }

    private fun canCreateDecoderForFormat(format: Format): Boolean {
        val supportsFormat: @RendererCapabilities.Capabilities Int =
            decoderFactory.supportsFormat(format)
        return supportsFormat == RendererCapabilities.create(C.FORMAT_HANDLED)
                || supportsFormat == RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
    }

    private fun lowerFirstFrameState(firstFrameState: @C.FirstFrameState Int) {
        this.firstFrameState = min(this.firstFrameState, firstFrameState)
    }

    private fun releaseDecoderResources() {
        inputBuffer = null
        decoderReinitializationState = REINITIALIZATION_STATE_NONE
        largestQueuedPresentationTimeUs = C.TIME_UNSET
        decoder?.also {
            it.release()
            decoder = null
        }
    }

    private fun setImageOutput(imageOutput: ImageOutput?) {
        this.imageOutput = getImageOutput(imageOutput)
    }

    private fun maybeAdvanceTileInfo(positionUs: Long, inputBuffer: DecoderInputBuffer) {
        if (inputBuffer.isEndOfStream) {
            readyToOutputTiles = true
            return
        }
        nextTileInfo = TileInfo(currentTileIndex, inputBuffer.timeUs)
        currentTileIndex++
        if (!readyToOutputTiles) {
            val tilePresentationTimeUs = nextTileInfo!!.presentationTimeUs
            val isNextTileWithinPresentationThreshold =
                tilePresentationTimeUs - IMAGE_PRESENTATION_WINDOW_THRESHOLD_US <= positionUs
                        && positionUs <= tilePresentationTimeUs + IMAGE_PRESENTATION_WINDOW_THRESHOLD_US
            val isPositionBetweenTiles =
                tileInfo != null && tileInfo!!.presentationTimeUs <= positionUs && positionUs < tilePresentationTimeUs
            val isNextTileLastInGrid =
                isTileLastInGrid(nextTileInfo!!)
            readyToOutputTiles =
                isNextTileWithinPresentationThreshold || isPositionBetweenTiles || isNextTileLastInGrid
            if (isPositionBetweenTiles && !isNextTileWithinPresentationThreshold) {
                return
            }
        }
        tileInfo = nextTileInfo
        nextTileInfo = null
    }

    private fun isTileLastInGrid(tileInfo: TileInfo): Boolean {
        return inputFormat!!.tileCountHorizontal == Format.NO_VALUE || inputFormat!!.tileCountVertical == Format.NO_VALUE
                || (tileInfo.tileIndex == inputFormat!!.tileCountVertical * inputFormat!!.tileCountHorizontal - 1)
    }

    private fun cropTileFromImageGrid(tileIndex: Int): Bitmap {
        val outputBitmap = outputBitmap!!
        val tileWidth =
            outputBitmap.getWidth() / inputFormat!!.tileCountHorizontal
        val tileHeight =
            outputBitmap.getHeight() / inputFormat!!.tileCountVertical
        val tileStartXCoordinate = tileWidth * (tileIndex % inputFormat!!.tileCountHorizontal)
        val tileStartYCoordinate = tileHeight * (tileIndex / inputFormat!!.tileCountHorizontal)
        return Bitmap.createBitmap(
            outputBitmap, tileStartXCoordinate, tileStartYCoordinate, tileWidth, tileHeight
        )
    }

    private class TileInfo(val tileIndex: Int, val presentationTimeUs: Long) {
        var tileBitmap:Bitmap? = null

        fun hasTileBitmap(): Boolean {
            return tileBitmap != null
        }
    }

    private class OutputStreamInfo(
        val previousStreamLastBufferTimeUs: Long,
        val streamOffsetUs: Long
    ) {
        companion object {
            val UNSET: OutputStreamInfo = OutputStreamInfo(
                C.TIME_UNSET, C.TIME_UNSET
            )
        }
    }

    companion object {
        private const val TAG = "ImageRenderer"
        private const val REINITIALIZATION_STATE_NONE = 0
        private const val REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM_THEN_WAIT = 2
        private const val REINITIALIZATION_STATE_WAIT_END_OF_STREAM = 3
        private const val IMAGE_PRESENTATION_WINDOW_THRESHOLD_US: Long = 30000

        private fun getImageOutput(imageOutput: ImageOutput?): ImageOutput {
            return imageOutput ?: ImageOutput.NO_OP
        }
    }
}
