package androidx.media3.common.util

import android.os.SystemClock
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.util.Util.scaleLargeTimestamp
import com.google.common.base.Preconditions
import java.util.concurrent.TimeoutException
import kotlin.math.abs

class TimestampAdjuster(firstSampleTimestampUs: Long) {
    @GuardedBy("this")
    private var firstSampleTimestampUs: Long = 0

    @get:Synchronized
    @GuardedBy("this")
    var timestampOffsetUs: Long = 0
        private set

    @GuardedBy("this")
    private var lastUnadjustedTimestampUs: Long = 0

    // incompatible type argument for type parameter T of ThreadLocal.
    private val nextSampleTimestampUs: ThreadLocal<Long?>

    // incompatible types in assignment.
    init {
        nextSampleTimestampUs = ThreadLocal<Long?>()
        reset(firstSampleTimestampUs)
    }

    @Synchronized
    @Throws(InterruptedException::class, TimeoutException::class)
    fun sharedInitializeOrWait(
        canInitialize: Boolean, nextSampleTimestampUs: Long, timeoutMs: Long
    ) {
        Preconditions.checkState(firstSampleTimestampUs == MODE_SHARED)
        if (this.isInitialized) {
            return
        } else if (canInitialize) {
            this.nextSampleTimestampUs.set(nextSampleTimestampUs)
        } else {
            // Wait for another calling thread to complete initialization.
            var totalWaitDurationMs: Long = 0
            var remainingTimeoutMs = timeoutMs
            while (!this.isInitialized) {
                if (timeoutMs == 0L) {
                    (this as Object).wait()
                } else {
                    Preconditions.checkState(remainingTimeoutMs > 0)
                    val waitStartingTimeMs = SystemClock.elapsedRealtime()
                    (this as Object).wait(remainingTimeoutMs)
                    totalWaitDurationMs += SystemClock.elapsedRealtime() - waitStartingTimeMs
                    if (totalWaitDurationMs >= timeoutMs && !this.isInitialized) {
                        val message =
                            "TimestampAdjuster failed to initialize in " + timeoutMs + " milliseconds"
                        throw TimeoutException(message)
                    }
                    remainingTimeoutMs = timeoutMs - totalWaitDurationMs
                }
            }
        }
    }

    @Synchronized
    fun getFirstSampleTimestampUs(): Long {
        return if (firstSampleTimestampUs == MODE_NO_OFFSET || firstSampleTimestampUs == MODE_SHARED)
            C.TIME_UNSET
        else
            firstSampleTimestampUs
    }

    @get:Synchronized
    val lastAdjustedTimestampUs: Long
        
        get() = if (lastUnadjustedTimestampUs != C.TIME_UNSET)
            lastUnadjustedTimestampUs + timestampOffsetUs
        else
            getFirstSampleTimestampUs()

    @Synchronized
    fun reset(firstSampleTimestampUs: Long) {
        this.firstSampleTimestampUs = firstSampleTimestampUs
        timestampOffsetUs = if (firstSampleTimestampUs == MODE_NO_OFFSET) 0 else C.TIME_UNSET
        lastUnadjustedTimestampUs = C.TIME_UNSET
    }

    @Synchronized
    fun adjustTsTimestamp(pts90Khz: Long): Long {
        var pts90Khz = pts90Khz
        if (pts90Khz == C.TIME_UNSET) {
            return C.TIME_UNSET
        }
        if (lastUnadjustedTimestampUs != C.TIME_UNSET) {
            // The wrap count for the current PTS may be closestWrapCount or (closestWrapCount - 1),
            // and we need to snap to the one closest to lastSampleTimestampUs.
            val lastPts: Long = usToNonWrappedPts(lastUnadjustedTimestampUs)
            val closestWrapCount: Long = (lastPts + (MAX_PTS_PLUS_ONE / 2)) / MAX_PTS_PLUS_ONE
            val ptsWrapBelow: Long = pts90Khz + (MAX_PTS_PLUS_ONE * (closestWrapCount - 1))
            val ptsWrapAbove: Long = pts90Khz + (MAX_PTS_PLUS_ONE * closestWrapCount)
            pts90Khz =
                if (abs(ptsWrapBelow - lastPts) < abs(ptsWrapAbove - lastPts))
                    ptsWrapBelow
                else
                    ptsWrapAbove
        }
        return adjustSampleTimestamp(ptsToUs(pts90Khz))
    }

    @Synchronized
    fun adjustTsTimestampGreaterThanPreviousTimestamp(pts90Khz: Long): Long {
        var pts90Khz = pts90Khz
        if (pts90Khz == C.TIME_UNSET) {
            return C.TIME_UNSET
        }
        if (lastUnadjustedTimestampUs != C.TIME_UNSET) {
            // The wrap count for the current PTS must be same or greater than the previous one.
            val lastPts: Long = usToNonWrappedPts(lastUnadjustedTimestampUs)
            val wrapCount: Long = lastPts / MAX_PTS_PLUS_ONE
            val ptsSameWrap: Long = pts90Khz + (MAX_PTS_PLUS_ONE * wrapCount)
            val ptsNextWrap: Long = pts90Khz + (MAX_PTS_PLUS_ONE * (wrapCount + 1))
            pts90Khz = if (ptsSameWrap >= lastPts) ptsSameWrap else ptsNextWrap
        }
        return adjustSampleTimestamp(ptsToUs(pts90Khz))
    }

    @Synchronized
    fun adjustSampleTimestamp(timeUs: Long): Long {
        if (timeUs == C.TIME_UNSET) {
            return C.TIME_UNSET
        }
        if (!this.isInitialized) {
            val desiredSampleTimestampUs =
                if (firstSampleTimestampUs == MODE_SHARED)
                    nextSampleTimestampUs.get()!!
                else
                    firstSampleTimestampUs
            timestampOffsetUs = desiredSampleTimestampUs - timeUs
            // Notify threads waiting for the timestamp offset to be determined.
            (this as Object).notifyAll()
        }
        lastUnadjustedTimestampUs = timeUs
        return timeUs + timestampOffsetUs
    }

    @get:Synchronized
    val isInitialized: Boolean
        
        get() = timestampOffsetUs != C.TIME_UNSET

    companion object {
        val MODE_NO_OFFSET: Long = Long.MAX_VALUE

        val MODE_SHARED: Long = Long.MAX_VALUE - 1

        private const val MAX_PTS_PLUS_ONE = 0x200000000L

        fun ptsToUs(pts: Long): Long {
            return scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, 90000)
        }

        fun usToWrappedPts(us: Long): Long {
            return usToNonWrappedPts(us) % MAX_PTS_PLUS_ONE
        }

        fun usToNonWrappedPts(us: Long): Long {
            return scaleLargeTimestamp(us, 90000, C.MICROS_PER_SECOND)
        }
    }
}
