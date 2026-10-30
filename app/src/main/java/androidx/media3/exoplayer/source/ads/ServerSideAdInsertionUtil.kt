package androidx.media3.exoplayer.source.ads

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util.msToUs
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import kotlin.math.max

object ServerSideAdInsertionUtil {
    fun addAdGroupToAdPlaybackState(
        adPlaybackState: AdPlaybackState,
        fromPositionUs: Long,
        contentResumeOffsetUs: Long,
        adDurationsUs: LongArray
    ): AdPlaybackState {
        var adPlaybackState = adPlaybackState
        val adGroupInsertionPositionUs =
            getMediaPeriodPositionUsForContent(
                fromPositionUs, C.INDEX_UNSET, adPlaybackState
            )
        var insertionIndex = adPlaybackState.removedAdGroupCount
        while (insertionIndex < adPlaybackState.adGroupCount && adPlaybackState.getAdGroup(
                insertionIndex
            ).timeUs != C.TIME_END_OF_SOURCE && adPlaybackState.getAdGroup(insertionIndex).timeUs <= adGroupInsertionPositionUs
        ) {
            insertionIndex++
        }
        adPlaybackState =
            adPlaybackState
                .withNewAdGroup(insertionIndex, adGroupInsertionPositionUs)
                .withIsServerSideInserted(insertionIndex, true)
                .withAdCount(insertionIndex, adDurationsUs.size)
                .withAdDurationsUs(insertionIndex, *adDurationsUs)
                .withContentResumeOffsetUs(insertionIndex, contentResumeOffsetUs)
        // Mark all ads as skipped that are before the first ad with a non-zero duration.
        var adIndex = 0
        while (adIndex < adDurationsUs.size && adDurationsUs[adIndex] == 0L) {
            adPlaybackState =
                adPlaybackState.withSkippedAd(insertionIndex, adIndex++)
        }
        return correctFollowingAdGroupTimes(
            adPlaybackState, insertionIndex, (adDurationsUs.sum()), contentResumeOffsetUs
        )
    }

    fun getStreamPositionUs(player: Player, adsId: Any?): Long {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) {
            return C.TIME_UNSET
        }
        val period =
            timeline.getPeriod(player.currentPeriodIndex, Timeline.Period())
        if (period.adsId != adsId) {
            return C.TIME_UNSET
        }
        if (player.isPlayingAd) {
            val adGroupIndex = player.currentAdGroupIndex
            val adIndexInAdGroup = player.currentAdIndexInAdGroup
            val adPositionUs = msToUs(player.currentPosition)
            return getStreamPositionUsForAd(
                adPositionUs, adGroupIndex, adIndexInAdGroup, period.adPlaybackState
            )
        }
        val periodPositionUs =
            msToUs(player.currentPosition) - period.positionInWindowUs
        return getStreamPositionUsForContent(
            periodPositionUs, C.INDEX_UNSET, period.adPlaybackState
        )
    }

    fun getStreamPositionUs(
        positionUs: Long, mediaPeriodId: MediaPeriodId, adPlaybackState: AdPlaybackState
    ): Long {
        return if (mediaPeriodId.isAd)
            getStreamPositionUsForAd(
                positionUs,
                mediaPeriodId.adGroupIndex,
                mediaPeriodId.adIndexInAdGroup,
                adPlaybackState
            )
        else
            getStreamPositionUsForContent(
                positionUs, mediaPeriodId.nextAdGroupIndex, adPlaybackState
            )
    }

    fun getMediaPeriodPositionUs(
        positionUs: Long, mediaPeriodId: MediaPeriodId, adPlaybackState: AdPlaybackState
    ): Long {
        return if (mediaPeriodId.isAd)
            getMediaPeriodPositionUsForAd(
                positionUs,
                mediaPeriodId.adGroupIndex,
                mediaPeriodId.adIndexInAdGroup,
                adPlaybackState
            )
        else
            getMediaPeriodPositionUsForContent(
                positionUs, mediaPeriodId.nextAdGroupIndex, adPlaybackState
            )
    }

    fun getStreamPositionUsForAd(
        positionUs: Long, adGroupIndex: Int, adIndexInAdGroup: Int, adPlaybackState: AdPlaybackState
    ): Long {
        var positionUs = positionUs
        val currentAdGroup = adPlaybackState.getAdGroup(adGroupIndex)
        positionUs += currentAdGroup.timeUs
        for (i in adPlaybackState.removedAdGroupCount..<adGroupIndex) {
            val adGroup = adPlaybackState.getAdGroup(i)
            for (j in 0..<getAdCountInGroup(adPlaybackState, i)) {
                positionUs += adGroup.durationsUs[j]
            }
            positionUs -= adGroup.contentResumeOffsetUs
        }
        if (adIndexInAdGroup < getAdCountInGroup(adPlaybackState, adGroupIndex)) {
            for (i in 0..<adIndexInAdGroup) {
                positionUs += currentAdGroup.durationsUs[i]
            }
        }
        return positionUs
    }

    fun getMediaPeriodPositionUsForAd(
        positionUs: Long, adGroupIndex: Int, adIndexInAdGroup: Int, adPlaybackState: AdPlaybackState
    ): Long {
        var positionUs = positionUs
        val currentAdGroup = adPlaybackState.getAdGroup(adGroupIndex)
        positionUs -= currentAdGroup.timeUs
        for (i in adPlaybackState.removedAdGroupCount..<adGroupIndex) {
            val adGroup = adPlaybackState.getAdGroup(i)
            for (j in 0..<getAdCountInGroup(adPlaybackState, i)) {
                positionUs -= adGroup.durationsUs[j]
            }
            positionUs += adGroup.contentResumeOffsetUs
        }
        if (adIndexInAdGroup < getAdCountInGroup(adPlaybackState, adGroupIndex)) {
            for (i in 0..<adIndexInAdGroup) {
                positionUs -= currentAdGroup.durationsUs[i]
            }
        }
        return positionUs
    }

    fun getStreamPositionUsForContent(
        positionUs: Long, nextAdGroupIndex: Int, adPlaybackState: AdPlaybackState
    ): Long {
        var nextAdGroupIndex = nextAdGroupIndex
        var totalAdDurationBeforePositionUs: Long = 0
        if (nextAdGroupIndex == C.INDEX_UNSET) {
            nextAdGroupIndex = adPlaybackState.adGroupCount
        }
        for (i in adPlaybackState.removedAdGroupCount..<nextAdGroupIndex) {
            val adGroup = adPlaybackState.getAdGroup(i)
            if (adGroup.timeUs == C.TIME_END_OF_SOURCE || adGroup.timeUs > positionUs) {
                break
            }
            val adGroupStreamStartPositionUs = adGroup.timeUs + totalAdDurationBeforePositionUs
            for (j in 0..<getAdCountInGroup(adPlaybackState, i)) {
                totalAdDurationBeforePositionUs += adGroup.durationsUs[j]
            }
            totalAdDurationBeforePositionUs -= adGroup.contentResumeOffsetUs
            val adGroupResumePositionUs = adGroup.timeUs + adGroup.contentResumeOffsetUs
            if (adGroupResumePositionUs > positionUs) {
                // The position is inside the ad group.
                return max(
                    adGroupStreamStartPositionUs,
                    positionUs + totalAdDurationBeforePositionUs
                )
            }
        }
        return positionUs + totalAdDurationBeforePositionUs
    }

    fun getMediaPeriodPositionUsForContent(
        positionUs: Long, nextAdGroupIndex: Int, adPlaybackState: AdPlaybackState
    ): Long {
        var nextAdGroupIndex = nextAdGroupIndex
        var totalAdDurationBeforePositionUs: Long = 0
        if (nextAdGroupIndex == C.INDEX_UNSET) {
            nextAdGroupIndex = adPlaybackState.adGroupCount
        }
        for (i in adPlaybackState.removedAdGroupCount..<nextAdGroupIndex) {
            val adGroup = adPlaybackState.getAdGroup(i)
            if (adGroup.timeUs == C.TIME_END_OF_SOURCE
                || adGroup.timeUs > positionUs - totalAdDurationBeforePositionUs
            ) {
                break
            }
            for (j in 0..<getAdCountInGroup(adPlaybackState, i)) {
                totalAdDurationBeforePositionUs += adGroup.durationsUs[j]
            }
            totalAdDurationBeforePositionUs -= adGroup.contentResumeOffsetUs
            val adGroupResumePositionUs = adGroup.timeUs + adGroup.contentResumeOffsetUs
            if (adGroupResumePositionUs > positionUs - totalAdDurationBeforePositionUs) {
                // The position is inside the ad group.
                return max(adGroup.timeUs, positionUs - totalAdDurationBeforePositionUs)
            }
        }
        return positionUs - totalAdDurationBeforePositionUs
    }

    fun getAdCountInGroup(adPlaybackState: AdPlaybackState, adGroupIndex: Int): Int {
        val adGroup = adPlaybackState.getAdGroup(adGroupIndex)
        return if (adGroup.count == C.LENGTH_UNSET) 0 else adGroup.count
    }

    private fun correctFollowingAdGroupTimes(
        adPlaybackState: AdPlaybackState,
        adGroupInsertionIndex: Int,
        insertedAdDurationUs: Long,
        addedContentResumeOffsetUs: Long
    ): AdPlaybackState {
        var adPlaybackState = adPlaybackState
        val followingAdGroupTimeUsOffset = -insertedAdDurationUs + addedContentResumeOffsetUs
        for (i in adGroupInsertionIndex + 1..<adPlaybackState.adGroupCount) {
            val adGroupTimeUs = adPlaybackState.getAdGroup(i).timeUs
            if (adGroupTimeUs != C.TIME_END_OF_SOURCE) {
                adPlaybackState =
                    adPlaybackState.withAdGroupTimeUs(
                        i, adGroupTimeUs + followingAdGroupTimeUsOffset
                    )
            }
        }
        return adPlaybackState
    }
}
