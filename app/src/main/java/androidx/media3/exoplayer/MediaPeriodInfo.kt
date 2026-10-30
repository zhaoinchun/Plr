
package androidx.media3.exoplayer

import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Preconditions

internal class MediaPeriodInfo(
    id: MediaPeriodId,
    startPositionUs: Long,
    liveStreamStartPositionProjectionUs: Long,
    requestedContentPositionUs: Long,
    durationUs: Long,
    isFollowedByTransitionToSameStream: Boolean,
    isLastInTimelinePeriod: Boolean,
    isLastInTimelineWindow: Boolean,
    isFinal: Boolean
) {
    val id: MediaPeriodId
    val startPositionUs: Long
    val liveStreamStartPositionProjectionUs: Long
    val requestedContentPositionUs: Long
    val durationUs: Long
    val isFollowedByTransitionToSameStream: Boolean
    val isLastInTimelinePeriod: Boolean
    val isLastInTimelineWindow: Boolean
    val isFinal: Boolean

    init {
        Preconditions.checkArgument(!isFinal || isLastInTimelinePeriod)
        Preconditions.checkArgument(!isLastInTimelineWindow || isLastInTimelinePeriod)
        Preconditions.checkArgument(
            !isFollowedByTransitionToSameStream
                    || (!isLastInTimelinePeriod && !isLastInTimelineWindow && !isFinal)
        )
        this.id = id
        this.startPositionUs = startPositionUs
        this.liveStreamStartPositionProjectionUs = liveStreamStartPositionProjectionUs
        this.requestedContentPositionUs = requestedContentPositionUs
        this.durationUs = durationUs
        this.isFollowedByTransitionToSameStream = isFollowedByTransitionToSameStream
        this.isLastInTimelinePeriod = isLastInTimelinePeriod
        this.isLastInTimelineWindow = isLastInTimelineWindow
        this.isFinal = isFinal
    }

    fun copyWithStartPositionUs(
        startPositionUs: Long, liveStreamStartPositionProjectionUs: Long
    ): MediaPeriodInfo {
        return if (startPositionUs == this.startPositionUs
            && liveStreamStartPositionProjectionUs == this.liveStreamStartPositionProjectionUs
        )
            this
        else
            MediaPeriodInfo(
                id,
                startPositionUs,
                liveStreamStartPositionProjectionUs,
                requestedContentPositionUs,
                durationUs,
                isFollowedByTransitionToSameStream,
                isLastInTimelinePeriod,
                isLastInTimelineWindow,
                isFinal
            )
    }

    fun copyWithRequestedContentPositionUs(requestedContentPositionUs: Long): MediaPeriodInfo {
        return if (requestedContentPositionUs == this.requestedContentPositionUs)
            this
        else
            MediaPeriodInfo(
                id,
                startPositionUs,
                liveStreamStartPositionProjectionUs,
                requestedContentPositionUs,
                durationUs,
                isFollowedByTransitionToSameStream,
                isLastInTimelinePeriod,
                isLastInTimelineWindow,
                isFinal
            )
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val that = o as MediaPeriodInfo
        return startPositionUs == that.startPositionUs && requestedContentPositionUs == that.requestedContentPositionUs && durationUs == that.durationUs && isFollowedByTransitionToSameStream == that.isFollowedByTransitionToSameStream && isLastInTimelinePeriod == that.isLastInTimelinePeriod && isLastInTimelineWindow == that.isLastInTimelineWindow && isFinal == that.isFinal && id == that.id
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + id.hashCode()
        result = 31 * result + startPositionUs.toInt()
        result = 31 * result + requestedContentPositionUs.toInt()
        result = 31 * result + durationUs.toInt()
        result = 31 * result + (if (isFollowedByTransitionToSameStream) 1 else 0)
        result = 31 * result + (if (isLastInTimelinePeriod) 1 else 0)
        result = 31 * result + (if (isLastInTimelineWindow) 1 else 0)
        result = 31 * result + (if (isFinal) 1 else 0)
        return result
    }
}
