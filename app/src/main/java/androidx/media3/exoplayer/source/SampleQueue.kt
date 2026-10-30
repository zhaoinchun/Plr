package androidx.media3.exoplayer.source

import android.os.Looper
import androidx.annotation.CallSuper
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.allSamplesAreSyncSamples
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager.DrmSessionReference
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.TrackOutput
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class SampleQueue(
    allocator: Allocator,
    private val drmSessionManager: DrmSessionManager?,
    private val drmEventDispatcher: DrmSessionEventListener.EventDispatcher?
) : TrackOutput {
    interface UpstreamFormatChangedListener {
        fun onUpstreamFormatChanged(format: Format)
    }

    private val sampleDataQueue: SampleDataQueue = SampleDataQueue(allocator)
    private val extrasHolder: SampleExtrasHolder = SampleExtrasHolder()
    private val sharedSampleMetadata: SpannedData<SharedSampleMetadata> =
        SpannedData({ metadata: SharedSampleMetadata -> metadata.drmSessionReference.release() })
    private var upstreamFormatChangeListener: UpstreamFormatChangedListener? = null
    private var downstreamFormat: Format? = null
    private var currentDrmSession: DrmSession? = null
    private var capacity: Int = SAMPLE_CAPACITY_INCREMENT

    private var sourceIds: LongArray = LongArray(capacity)
    private var offsets: LongArray = LongArray(capacity)
    private var sizes: IntArray = IntArray(capacity)
    private var flags: IntArray = IntArray(capacity)
    private var timesUs: LongArray = LongArray(capacity)
    private var cryptoDatas: Array<TrackOutput.CryptoData?> = arrayOfNulls(capacity)
    private var length = 0
    var firstIndex: Int = 0
        private set
    private var relativeFirstIndex = 0
    private var readPosition = 0

    private var startTimeUs: Long = Long.MIN_VALUE
    private var readEndTimeUs: Long = C.TIME_END_OF_SOURCE
    private var largestDiscardedTimestampUs: Long = Long.MIN_VALUE

    @get:Synchronized
    var largestQueuedTimestampUs: Long = Long.MIN_VALUE
        private set
    private var readEndTimeAbsoluteIndex: Int = C.INDEX_UNSET
    private var readEndTimeAbsoluteIndexCandidate: Int = C.INDEX_UNSET

    @get:Synchronized
    var isLastSampleQueued: Boolean = false
        private set
    private var upstreamKeyframeRequired: Boolean = true
    private var upstreamFormatRequired: Boolean = true
    private var upstreamFormatAdjustmentRequired = false
    private var unadjustedUpstreamFormat: Format? = null
    private var upstreamFormat: Format? = null
    private var upstreamSourceId: Long = 0
    private var discardAllSamplesToStartTime: Boolean = true
    private var loggedUnexpectedNonSyncSample = false
    private var sampleOffsetUs: Long = 0
    private var pendingSplice = false

    // Called by the consuming thread when there is no loading thread.
    @CallSuper
    fun release() {
        reset( true)
        releaseDrmSessionReferences()
    }

    fun reset() {
        reset( false)
    }

    @CallSuper
    fun reset(resetUpstreamFormat: Boolean) {
        sampleDataQueue.reset()
        length = 0
        this.firstIndex = 0
        relativeFirstIndex = 0
        readPosition = 0
        readEndTimeAbsoluteIndex = C.INDEX_UNSET
        readEndTimeAbsoluteIndexCandidate = C.INDEX_UNSET
        upstreamKeyframeRequired = true
        startTimeUs = Long.MIN_VALUE
        largestDiscardedTimestampUs = Long.MIN_VALUE
        largestQueuedTimestampUs = Long.MIN_VALUE
        isLastSampleQueued = false
        sharedSampleMetadata.clear()
        if (resetUpstreamFormat) {
            unadjustedUpstreamFormat = null
            upstreamFormat = null
            upstreamFormatRequired = true
            discardAllSamplesToStartTime = true
        }
    }

    fun setStartTimeUs(startTimeUs: Long) {
        this.startTimeUs = startTimeUs
    }

    @Synchronized
    fun setReadEndTimeUs(readEndTimeUs: Long) {
        if (readEndTimeUs == this.readEndTimeUs) {
            return
        }
        this.readEndTimeUs = readEndTimeUs
        searchReadEndTimeAbsoluteIndex()
    }

    fun sourceId(sourceId: Long) {
        upstreamSourceId = sourceId
    }

    fun splice() {
        pendingSplice = true
    }

    val writeIndex: Int
        get() = this.firstIndex + length

    fun discardUpstreamSamples(discardFromIndex: Int) {
        sampleDataQueue.discardUpstreamSampleBytes(discardUpstreamSampleMetadata(discardFromIndex))
    }

    fun discardUpstreamFrom(timeUs: Long) {
        if (length == 0) {
            return
        }
        Preconditions.checkArgument(timeUs > this.largestReadTimestampUs)
        val retainCount = countUnreadSamplesBefore(timeUs)
        discardUpstreamSamples(this.firstIndex + retainCount)
    }

    // Called by the consuming thread.
    @CallSuper
    fun preRelease() {
        discardToEnd()
        releaseDrmSessionReferences()
    }

    @CallSuper
    @Throws(IOException::class)
    fun maybeThrowError() {
        // TODO: Avoid throwing if the DRM error is not preventing a read operation.
        if (currentDrmSession?.state == DrmSession.STATE_ERROR) {
            throw currentDrmSession!!.error!!
        }
    }

    val readIndex: Int
        get() = this.firstIndex + readPosition

    @Synchronized
    fun peekSourceId(): Long {
        val relativeReadIndex = getRelativeIndex(readPosition)
        return if (hasNextSample()) sourceIds[relativeReadIndex] else upstreamSourceId
    }

    @Synchronized
    fun getUpstreamFormat(): Format? {
        return if (upstreamFormatRequired) null else upstreamFormat
    }

    @Synchronized
    fun hasQueuedTimestampsUpToReadEndTimeUs(): Boolean {
        return readEndTimeAbsoluteIndex != C.INDEX_UNSET
    }

    @get:Synchronized
    val largestReadTimestampUs: Long
        get() = max(largestDiscardedTimestampUs, getLargestTimestamp(readPosition))

    @get:Synchronized
    val firstTimestampUs: Long
        get() = if (length == 0) Long.MIN_VALUE else timesUs[relativeFirstIndex]

    // See comments in setUpstreamFormat
    @CallSuper
    @Synchronized
    fun isReady(loadingFinished: Boolean): Boolean {
        val readIndex = this.readIndex
        if (readEndTimeAbsoluteIndex != C.INDEX_UNSET && readIndex >= readEndTimeAbsoluteIndex) {
            return true
        }
        if (!hasNextSample() || this.isReadEndTimeIndexMaybeReached) {
            return loadingFinished
                    || isLastSampleQueued
                    || (upstreamFormat != null && upstreamFormat !== downstreamFormat)
        }
        if (sharedSampleMetadata.get(readIndex)!!.format !== downstreamFormat) {
            // A format can be read.
            return true
        }
        return mayReadSample(getRelativeIndex(readPosition))
    }

    @CallSuper
    fun read(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: @ReadFlags Int,
        loadingFinished: Boolean
    ): Int {
        val result =
            peekSampleMetadata(
                formatHolder,
                buffer,  
                (readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0,
                loadingFinished,
                extrasHolder
            )
        if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) {
            val peek = (readFlags and SampleStream.FLAG_PEEK) != 0
            if ((readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0) {
                if (peek) {
                    sampleDataQueue.peekToBuffer(buffer, extrasHolder)
                } else {
                    sampleDataQueue.readToBuffer(buffer, extrasHolder)
                }
            }
            if (!peek) {
                readPosition++
            }
        }
        return result
    }

    @Synchronized
    fun seekTo(sampleIndex: Int): Boolean {
        rewind()
        if (sampleIndex < this.firstIndex || sampleIndex > this.firstIndex + length) {
            return false
        }
        if (readEndTimeAbsoluteIndex != C.INDEX_UNSET && sampleIndex >= readEndTimeAbsoluteIndex) {
            return false
        }
        if (readEndTimeAbsoluteIndexCandidate != C.INDEX_UNSET
            && sampleIndex >= readEndTimeAbsoluteIndexCandidate
        ) {
            return false
        }
        startTimeUs = Long.MIN_VALUE
        readPosition = sampleIndex - this.firstIndex
        return true
    }

    @Synchronized
    fun seekTo(timeUs: Long, allowTimeBeyondBuffer: Boolean): Boolean {
        rewind()
        val relativeReadIndex = getRelativeIndex(readPosition)
        val maxPossibleSeekTimeUs =
            if (readEndTimeUs != C.TIME_END_OF_SOURCE)
                min(largestQueuedTimestampUs, readEndTimeUs)
            else
                largestQueuedTimestampUs
        if (!hasNextSample() || timeUs < timesUs[relativeReadIndex] || (timeUs > maxPossibleSeekTimeUs && !allowTimeBeyondBuffer)) {
            return false
        }
        val offset =
            if (discardAllSamplesToStartTime)
                findSampleAfter(
                    relativeReadIndex, length - readPosition, timeUs, allowTimeBeyondBuffer
                )
            else
                findSampleBefore(
                    relativeReadIndex, length - readPosition, timeUs,  true
                )
        if (offset == -1) {
            return false
        }
        startTimeUs = timeUs
        readPosition += offset
        return true
    }

    @Synchronized
    fun getSkipCount(timeUs: Long, allowEndOfQueue: Boolean): Int {
        val relativeReadIndex = getRelativeIndex(readPosition)
        if (!hasNextSample() || timeUs < timesUs[relativeReadIndex]) {
            return 0
        }
        if (timeUs > largestQueuedTimestampUs && allowEndOfQueue) {
            return length - readPosition
        }
        val offset =
            findSampleBefore(relativeReadIndex, length - readPosition, timeUs,  true)
        if (offset == -1) {
            return 0
        }
        return offset
    }

    @Synchronized
    fun skip(count: Int) {
        Preconditions.checkArgument(count >= 0 && readPosition + count <= length)
        readPosition += count
    }

    fun discardTo(timeUs: Long, toKeyframe: Boolean, stopAtReadPosition: Boolean) {
        sampleDataQueue.discardDownstreamTo(
            discardSampleMetadataTo(timeUs, toKeyframe, stopAtReadPosition)
        )
    }

    fun discardToRead() {
        sampleDataQueue.discardDownstreamTo(discardSampleMetadataToRead())
    }

    fun discardToEnd() {
        sampleDataQueue.discardDownstreamTo(discardSampleMetadataToEnd())
    }

    // Called by the loading thread.
    fun setSampleOffsetUs(sampleOffsetUs: Long) {
        if (this.sampleOffsetUs != sampleOffsetUs) {
            this.sampleOffsetUs = sampleOffsetUs
            invalidateUpstreamFormatAdjustment()
        }
    }

    fun setUpstreamFormatChangeListener(
        listener: UpstreamFormatChangedListener?
    ) {
        upstreamFormatChangeListener = listener
    }

    // TrackOutput implementation. Called by the loading thread.
    override fun format(format: Format) {
        val adjustedUpstreamFormat = getAdjustedUpstreamFormat(format)
        upstreamFormatAdjustmentRequired = false
        unadjustedUpstreamFormat = format
        val upstreamFormatChanged = setUpstreamFormat(adjustedUpstreamFormat)
        if (upstreamFormatChangeListener != null && upstreamFormatChanged) {
            upstreamFormatChangeListener!!.onUpstreamFormatChanged(adjustedUpstreamFormat)
        }
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: @TrackOutput.SampleDataPart Int
    ): Int {
        return sampleDataQueue.sampleData(input, length, allowEndOfInput)
    }

    override fun sampleData(
        data: ParsableByteArray, length: Int, sampleDataPart: @TrackOutput.SampleDataPart Int
    ) {
        sampleDataQueue.sampleData(data, length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: @C.BufferFlags Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        var timeUs = timeUs
        var flags = flags
        if (upstreamFormatAdjustmentRequired) {
            format(unadjustedUpstreamFormat!!)
        }

        val isKeyframe = (flags and C.BUFFER_FLAG_KEY_FRAME) != 0
        if (upstreamKeyframeRequired) {
            if (!isKeyframe) {
                return
            }
            upstreamKeyframeRequired = false
        }

        timeUs += sampleOffsetUs
        if (discardAllSamplesToStartTime) {
            if (timeUs < startTimeUs) {
                return
            }
            if ((flags and C.BUFFER_FLAG_KEY_FRAME) == 0) {
                // The flag should always be set unless the source content has incorrect sample metadata.
                // Log a warning (once per format change, to avoid log spam) and override the flag.
                if (!loggedUnexpectedNonSyncSample) {
                    w(TAG, "Overriding unexpected non-sync sample for format: " + upstreamFormat)
                    loggedUnexpectedNonSyncSample = true
                }
                flags = flags or C.BUFFER_FLAG_KEY_FRAME
            }
        }
        if (pendingSplice) {
            if (!isKeyframe || !attemptSplice(timeUs)) {
                return
            }
            pendingSplice = false
        }

        val absoluteOffset = sampleDataQueue.totalBytesWritten - size - offset
        commitSample(timeUs, flags, absoluteOffset, size, cryptoData)
    }

    protected fun invalidateUpstreamFormatAdjustment() {
        upstreamFormatAdjustmentRequired = true
    }

    @CallSuper
    protected fun getAdjustedUpstreamFormat(format: Format): Format {
        var format = format
        if (sampleOffsetUs != 0L && format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE) {
            format =
                format
                    .buildUpon()
                    .setSubsampleOffsetUs(format.subsampleOffsetUs + sampleOffsetUs)
                    .build()
        }
        return format
    }

    // Internal methods.
    @Synchronized
    private fun rewind() {
        readPosition = 0
        sampleDataQueue.rewind()
    }

    @Synchronized  // See comments in setUpstreamFormat.
    private fun peekSampleMetadata(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        formatRequired: Boolean,
        loadingFinished: Boolean,
        extrasHolder: SampleExtrasHolder
    ): Int {
        buffer.waitingForKeys = false
        val readIndex = this.readIndex
        val readEndTimeReached =
            readEndTimeAbsoluteIndex != C.INDEX_UNSET && readIndex >= readEndTimeAbsoluteIndex
        if (!hasNextSample() || this.isReadEndTimeIndexMaybeReached || readEndTimeReached) {
            if (loadingFinished || isLastSampleQueued || readEndTimeReached) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                buffer.timeUs = C.TIME_END_OF_SOURCE
                return C.RESULT_BUFFER_READ
            } else if (upstreamFormat != null && (formatRequired || upstreamFormat !== downstreamFormat)) {
                onFormatResult(upstreamFormat!!, formatHolder)
                return C.RESULT_FORMAT_READ
            } else {
                return C.RESULT_NOTHING_READ
            }
        }

        val format = sharedSampleMetadata.get(readIndex)!!.format
        if (formatRequired || format !== downstreamFormat) {
            onFormatResult(format, formatHolder)
            return C.RESULT_FORMAT_READ
        }

        val relativeReadIndex = getRelativeIndex(readPosition)
        if (!mayReadSample(relativeReadIndex)) {
            buffer.waitingForKeys = true
            return C.RESULT_NOTHING_READ
        }

        buffer.setFlags(flags[relativeReadIndex])
        if (readPosition == (length - 1) && (loadingFinished || isLastSampleQueued)) {
            buffer.addFlag(C.BUFFER_FLAG_LAST_SAMPLE)
        }
        buffer.timeUs = timesUs[relativeReadIndex]
        extrasHolder.size = sizes[relativeReadIndex]
        extrasHolder.offset = offsets[relativeReadIndex]
        extrasHolder.cryptoData = cryptoDatas[relativeReadIndex]

        return C.RESULT_BUFFER_READ
    }

    @Synchronized
    private fun setUpstreamFormat(format: Format?): Boolean {
        upstreamFormatRequired = false
        if (format == upstreamFormat) {
            // The format is unchanged. If format and upstreamFormat are different objects, we keep the
            // current upstreamFormat so we can detect format changes on the read side using cheap
            // referential quality.
            return false
        }

        if (!sharedSampleMetadata.isEmpty
            && sharedSampleMetadata.endValue.format == format
        ) {
            // The format has changed back to the format of the last committed sample. If they are
            // different objects, we revert back to using upstreamCommittedFormat as the upstreamFormat
            // so we can detect format changes on the read side using cheap referential equality.
            upstreamFormat = sharedSampleMetadata.endValue.format
        } else {
            upstreamFormat = format
        }
        discardAllSamplesToStartTime = discardAllSamplesToStartTime and
                canDiscardAllSamplesToStartTime(
                    upstreamFormat!!.sampleMimeType,
                    upstreamFormat!!.codecs
                )
        loggedUnexpectedNonSyncSample = false
        return true
    }

    @Synchronized
    private fun discardSampleMetadataTo(
        timeUs: Long, toKeyframe: Boolean, stopAtReadPosition: Boolean
    ): Long {
        if (length == 0 || timeUs < timesUs[relativeFirstIndex]) {
            return C.INDEX_UNSET.toLong()
        }
        val searchLength =
            if (stopAtReadPosition && readPosition != length) readPosition + 1 else length
        val discardCount = findSampleBefore(relativeFirstIndex, searchLength, timeUs, toKeyframe)
        if (discardCount == -1) {
            return C.INDEX_UNSET.toLong()
        }
        return discardSamples(discardCount)
    }

    @Synchronized
    fun discardSampleMetadataToRead(): Long {
        if (readPosition == 0) {
            return C.INDEX_UNSET.toLong()
        }
        return discardSamples(readPosition)
    }

    @Synchronized
    private fun discardSampleMetadataToEnd(): Long {
        if (length == 0) {
            return C.INDEX_UNSET.toLong()
        }
        return discardSamples(length)
    }

    private fun releaseDrmSessionReferences() {
        currentDrmSession?.also {
            it.release(drmEventDispatcher)
            currentDrmSession = null
            // Clear downstream format to avoid violating the assumption that downstreamFormat.drmInitData
            // != null implies currentSession != null
            downstreamFormat = null
        }
    }

    @Synchronized
    private fun commitSample(
        timeUs: Long,
        sampleFlags: @C.BufferFlags Int,
        offset: Long,
        size: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (length > 0) {
            // Ensure sample data doesn't overlap.
            val previousSampleRelativeIndex = getRelativeIndex(length - 1)
            Preconditions.checkArgument(
                offsets[previousSampleRelativeIndex] + sizes[previousSampleRelativeIndex] <= offset
            )
        }

        isLastSampleQueued = (sampleFlags and C.BUFFER_FLAG_LAST_SAMPLE) != 0
        largestQueuedTimestampUs = max(largestQueuedTimestampUs, timeUs)
        updateReadEndTimeState( this.writeIndex, timeUs, sampleFlags)

        val relativeEndIndex = getRelativeIndex(length)
        timesUs[relativeEndIndex] = timeUs
        offsets[relativeEndIndex] = offset
        sizes[relativeEndIndex] = size
        flags[relativeEndIndex] = sampleFlags
        cryptoDatas[relativeEndIndex] = cryptoData
        sourceIds[relativeEndIndex] = upstreamSourceId

        if (sharedSampleMetadata.isEmpty
            || sharedSampleMetadata.endValue.format != upstreamFormat
        ) {
            val upstreamFormat = this.upstreamFormat!!
            val drmSessionReference =
                drmSessionManager?.preacquireSession(drmEventDispatcher, upstreamFormat)
                    ?: DrmSessionReference.EMPTY

            sharedSampleMetadata.appendSpan(
                this.writeIndex, SharedSampleMetadata(upstreamFormat, drmSessionReference)
            )
        }

        length++
        if (length == capacity) {
            // Increase the capacity.
            val newCapacity: Int = capacity + SAMPLE_CAPACITY_INCREMENT
            val newSourceIds = LongArray(newCapacity)
            val newOffsets = LongArray(newCapacity)
            val newTimesUs = LongArray(newCapacity)
            val newFlags = IntArray(newCapacity)
            val newSizes = IntArray(newCapacity)
            val newCryptoDatas = arrayOfNulls<TrackOutput.CryptoData>(newCapacity)
            val beforeWrap = capacity - relativeFirstIndex
            System.arraycopy(offsets, relativeFirstIndex, newOffsets, 0, beforeWrap)
            System.arraycopy(timesUs, relativeFirstIndex, newTimesUs, 0, beforeWrap)
            System.arraycopy(flags, relativeFirstIndex, newFlags, 0, beforeWrap)
            System.arraycopy(sizes, relativeFirstIndex, newSizes, 0, beforeWrap)
            System.arraycopy(cryptoDatas, relativeFirstIndex, newCryptoDatas, 0, beforeWrap)
            System.arraycopy(sourceIds, relativeFirstIndex, newSourceIds, 0, beforeWrap)
            val afterWrap = relativeFirstIndex
            System.arraycopy(offsets, 0, newOffsets, beforeWrap, afterWrap)
            System.arraycopy(timesUs, 0, newTimesUs, beforeWrap, afterWrap)
            System.arraycopy(flags, 0, newFlags, beforeWrap, afterWrap)
            System.arraycopy(sizes, 0, newSizes, beforeWrap, afterWrap)
            System.arraycopy(cryptoDatas, 0, newCryptoDatas, beforeWrap, afterWrap)
            System.arraycopy(sourceIds, 0, newSourceIds, beforeWrap, afterWrap)
            offsets = newOffsets
            timesUs = newTimesUs
            flags = newFlags
            sizes = newSizes
            cryptoDatas = newCryptoDatas
            sourceIds = newSourceIds
            relativeFirstIndex = 0
            capacity = newCapacity
        }
    }

    @Synchronized
    private fun attemptSplice(timeUs: Long): Boolean {
        if (length == 0) {
            return timeUs > largestDiscardedTimestampUs
        }
        if (this.largestReadTimestampUs >= timeUs) {
            return false
        }
        val retainCount = countUnreadSamplesBefore(timeUs)
        discardUpstreamSampleMetadata(this.firstIndex + retainCount)
        return true
    }

    private fun discardUpstreamSampleMetadata(discardFromIndex: Int): Long {
        val discardCount = this.writeIndex - discardFromIndex
        Preconditions.checkArgument(0 <= discardCount && discardCount <= (length - readPosition))
        length -= discardCount
        largestQueuedTimestampUs = max(largestDiscardedTimestampUs, getLargestTimestamp(length))
        isLastSampleQueued = discardCount == 0 && isLastSampleQueued
        if (readEndTimeAbsoluteIndex != C.INDEX_UNSET && discardFromIndex < readEndTimeAbsoluteIndex) {
            readEndTimeAbsoluteIndex = C.INDEX_UNSET
        }
        if (readEndTimeAbsoluteIndexCandidate != C.INDEX_UNSET
            && discardFromIndex < readEndTimeAbsoluteIndexCandidate
        ) {
            readEndTimeAbsoluteIndexCandidate = C.INDEX_UNSET
        }
        sharedSampleMetadata.discardFrom(discardFromIndex)
        if (length != 0) {
            val relativeLastWriteIndex = getRelativeIndex(length - 1)
            return offsets[relativeLastWriteIndex] + sizes[relativeLastWriteIndex]
        }
        return 0
    }

    private fun hasNextSample(): Boolean {
        return readPosition != length
    }

    private val isReadEndTimeIndexMaybeReached: Boolean
        get() = readEndTimeAbsoluteIndex == C.INDEX_UNSET && readEndTimeAbsoluteIndexCandidate != C.INDEX_UNSET && this.readIndex >= readEndTimeAbsoluteIndexCandidate

    private fun onFormatResult(newFormat: Format, outputFormatHolder: FormatHolder) {
        val isFirstFormat = downstreamFormat == null
        val oldDrmInitData = if (downstreamFormat == null) null else downstreamFormat!!.drmInitData
        downstreamFormat = newFormat
        val newDrmInitData = newFormat.drmInitData

        outputFormatHolder.format =
            if (drmSessionManager != null)
                newFormat.copyWithCryptoType(drmSessionManager.getCryptoType(newFormat))
            else
                newFormat
        outputFormatHolder.drmSession = currentDrmSession
        if (drmSessionManager == null) {
            // This sample queue is not expected to handle DRM. Nothing to do.
            return
        }
        if (!isFirstFormat && oldDrmInitData == newDrmInitData) {
            // Nothing to do.
            return
        }
        // Ensure we acquire the new session before releasing the previous one in case the same session
        // is being used for both DrmInitData.
        val previousSession = currentDrmSession
        currentDrmSession = drmSessionManager.acquireSession(drmEventDispatcher, newFormat)
        outputFormatHolder.drmSession = currentDrmSession
        previousSession?.release(drmEventDispatcher)
    }

    private fun mayReadSample(relativeReadIndex: Int): Boolean {
        return currentDrmSession == null
                || currentDrmSession!!.state == DrmSession.STATE_OPENED_WITH_KEYS
                || ((flags[relativeReadIndex] and C.BUFFER_FLAG_ENCRYPTED) == 0 && currentDrmSession!!.playClearSamplesWithoutKeys())
    }

    private fun findSampleBefore(
        relativeStartIndex: Int,
        length: Int,
        timeUs: Long,
        keyframe: Boolean
    ): Int {
        // This could be optimized to use a binary search, however in practice callers to this method
        // normally pass times near to the start of the search region. Hence it's unclear whether
        // switching to a binary search would yield any real benefit.
        var sampleCountToTarget = -1
        var searchIndex = relativeStartIndex
        var i = 0
        while (i < length && timesUs[searchIndex] <= timeUs) {
            if (!keyframe || (flags[searchIndex] and C.BUFFER_FLAG_KEY_FRAME) != 0) {
                // We've found a suitable sample.
                sampleCountToTarget = i
                if (timesUs[searchIndex] == timeUs) {
                    // Stop the search if we found a sample at the specified time to avoid returning a later
                    // sample with the same exactly matching timestamp.
                    break
                }
            }
            searchIndex++
            if (searchIndex == capacity) {
                searchIndex = 0
            }
            i++
        }
        return sampleCountToTarget
    }

    private fun findSampleAfter(
        relativeStartIndex: Int, length: Int, timeUs: Long, allowTimeBeyondBuffer: Boolean
    ): Int {
        var searchIndex = relativeStartIndex
        for (i in 0..<length) {
            if (timesUs[searchIndex] >= timeUs) {
                return i
            }
            searchIndex++
            if (searchIndex == capacity) {
                searchIndex = 0
            }
        }
        return if (allowTimeBeyondBuffer) length else -1
    }

    private fun searchReadEndTimeAbsoluteIndex() {
        readEndTimeAbsoluteIndex = C.INDEX_UNSET
        readEndTimeAbsoluteIndexCandidate = C.INDEX_UNSET
        if (readEndTimeUs == C.TIME_END_OF_SOURCE || readEndTimeUs > largestQueuedTimestampUs) {
            return
        }
        for (i in 0..<length) {
            val searchIndex = getRelativeIndex( i)
            updateReadEndTimeState(this.firstIndex + i, timesUs[searchIndex], flags[searchIndex])
            if (readEndTimeAbsoluteIndex != C.INDEX_UNSET) {
                return
            }
        }
    }

    private fun updateReadEndTimeState(
        absoluteSampleIndex: Int,
        sampleTimeUs: Long,
        sampleFlags: Int
    ) {
        if (readEndTimeUs == C.TIME_END_OF_SOURCE) {
            return
        }
        if (readEndTimeAbsoluteIndex != C.INDEX_UNSET) {
            return
        }
        if (sampleTimeUs < readEndTimeUs) {
            readEndTimeAbsoluteIndexCandidate = C.INDEX_UNSET
            return
        }
        if (readEndTimeAbsoluteIndexCandidate == C.INDEX_UNSET) {
            readEndTimeAbsoluteIndexCandidate = absoluteSampleIndex
        }
        val consecutiveSamplesAfterEndCount =
            absoluteSampleIndex - readEndTimeAbsoluteIndexCandidate + 1
        val isKeyFrame = (sampleFlags and C.BUFFER_FLAG_KEY_FRAME) != 0
        val isLastSample = (sampleFlags and C.BUFFER_FLAG_LAST_SAMPLE) != 0
        if ((consecutiveSamplesAfterEndCount >= this.upstreamMaxNumReorderSamples + 1)
            || isKeyFrame
            || isLastSample
        ) {
            readEndTimeAbsoluteIndex = readEndTimeAbsoluteIndexCandidate
            readEndTimeAbsoluteIndexCandidate = C.INDEX_UNSET
        }
    }

    private val upstreamMaxNumReorderSamples: Int
        get() = if (upstreamFormat != null && upstreamFormat!!.maxNumReorderSamples != Format.NO_VALUE)
            upstreamFormat!!.maxNumReorderSamples
        else
            DEFAULT_MAX_NUM_REORDER_SAMPLES

    private fun countUnreadSamplesBefore(timeUs: Long): Int {
        var count = length
        var relativeSampleIndex = getRelativeIndex(length - 1)
        while (count > readPosition && timesUs[relativeSampleIndex] >= timeUs) {
            count--
            relativeSampleIndex--
            if (relativeSampleIndex == -1) {
                relativeSampleIndex = capacity - 1
            }
        }
        return count
    }

    @GuardedBy("this")
    private fun discardSamples(discardCount: Int): Long {
        largestDiscardedTimestampUs =
            max(largestDiscardedTimestampUs, getLargestTimestamp(discardCount))
        length -= discardCount
        this.firstIndex += discardCount
        relativeFirstIndex += discardCount
        if (relativeFirstIndex >= capacity) {
            relativeFirstIndex -= capacity
        }
        readPosition -= discardCount
        if (readPosition < 0) {
            readPosition = 0
        }
        sharedSampleMetadata.discardTo(this.firstIndex)

        if (length == 0) {
            val relativeLastDiscardIndex =
                (if (relativeFirstIndex == 0) capacity else relativeFirstIndex) - 1
            return offsets[relativeLastDiscardIndex] + sizes[relativeLastDiscardIndex]
        } else {
            return offsets[relativeFirstIndex]
        }
    }

    private fun getLargestTimestamp(length: Int): Long {
        if (length == 0) {
            return Long.MIN_VALUE
        }
        var largestTimestampUs = Long.MIN_VALUE
        var relativeSampleIndex = getRelativeIndex(length - 1)
        for (i in 0..<length) {
            largestTimestampUs = max(largestTimestampUs, timesUs[relativeSampleIndex])
            if ((flags[relativeSampleIndex] and C.BUFFER_FLAG_KEY_FRAME) != 0) {
                break
            }
            relativeSampleIndex--
            if (relativeSampleIndex == -1) {
                relativeSampleIndex = capacity - 1
            }
        }
        return largestTimestampUs
    }

    private fun getRelativeIndex(offset: Int): Int {
        val relativeIndex = relativeFirstIndex + offset
        return if (relativeIndex < capacity) relativeIndex else relativeIndex - capacity
    }

    internal class SampleExtrasHolder {
        var size: Int = 0
        var offset: Long = 0
        var cryptoData: TrackOutput.CryptoData? = null
    }

    private class SharedSampleMetadata(
        val format: Format,
        val drmSessionReference: DrmSessionReference
    )

    companion object {
        @VisibleForTesting
        
        const val SAMPLE_CAPACITY_INCREMENT: Int = 1000
        private const val TAG = "SampleQueue"
        private const val DEFAULT_MAX_NUM_REORDER_SAMPLES = 16

        fun createWithoutDrm(allocator: Allocator): SampleQueue {
            return SampleQueue(
                allocator,  null,  null
            )
        }

        fun createWithDrm(
            allocator: Allocator,
            drmSessionManager: DrmSessionManager?,
            drmEventDispatcher: DrmSessionEventListener.EventDispatcher?
        ): SampleQueue {
            return SampleQueue(
                allocator,
                drmSessionManager!!,
                drmEventDispatcher!!
            )
        }

        @Deprecated(
            """Use {@link #createWithDrm(Allocator, DrmSessionManager, EventDispatcher)} instead.
        The {@code playbackLooper} should be configured on the {@link DrmSessionManager} with
        {@link DrmSessionManager#setPlayer(Looper, PlayerId)}."""
        )
        fun createWithDrm(
            allocator: Allocator,
            playbackLooper: Looper,
            drmSessionManager: DrmSessionManager,
            drmEventDispatcher: DrmSessionEventListener.EventDispatcher?
        ): SampleQueue {
            drmSessionManager.setPlayer(playbackLooper, PlayerId.UNSET)
            return SampleQueue(
                allocator,
                drmSessionManager,
                drmEventDispatcher!!
            )
        }

        private fun canDiscardAllSamplesToStartTime(
            mimeType: String?, codec: String?
        ): Boolean {
            // We can only discard up to the start time immediately if the samples are guaranteed to be all
            // sync samples. This optimization is also only possible for audio tracks where the inherent
            // duration of a sample is negligible, and it doesn't affect playback if a partial sample is
            // dropped.
            val trackType: @C.TrackType Int = getTrackType(mimeType)
            return trackType == C.TRACK_TYPE_AUDIO && allSamplesAreSyncSamples(mimeType, codec)
        }
    }
}
