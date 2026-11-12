package androidx.media3.common

import android.net.Uri
import android.os.Bundle
import androidx.annotation.IntRange
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions
import com.google.common.math.LongMath
import java.util.Arrays
import kotlin.math.max

data class AdPlaybackState(
    val adsId: Any?,
    private val adGroups: List<AdGroup?>,
    val adResumePositionUs: Long = 0L,
    val contentDurationUs: Long = C.TIME_UNSET,
    val removedAdGroupCount: Int = 0
) {
    val adGroupCount = adGroups.size + removedAdGroupCount

    fun getAdGroup(adGroupIndex: Int): AdGroup {
        return (if (adGroupIndex < removedAdGroupCount)
            REMOVED_AD_GROUP
        else
            adGroups[adGroupIndex - removedAdGroupCount])!!
    }

    fun getAdGroupIndexForPositionUs(positionUs: Long, periodDurationUs: Long): Int {
        // Use a linear search as the array elements may not be increasing due to TIME_END_OF_SOURCE.
        // In practice we expect there to be few ad groups so the search shouldn't be expensive.
        var index = adGroupCount - 1
        index -= if (isLivePostrollPlaceholder(index)) 1 else 0
        while (index >= 0 && isPositionBeforeAdGroup(positionUs, periodDurationUs, index)) {
            index--
        }
        return if (index >= 0 && getAdGroup(index).hasUnplayedAds()) index else C.INDEX_UNSET
    }

    fun getAdGroupIndexAfterPositionUs(positionUs: Long, periodDurationUs: Long): Int {
        if (positionUs == C.TIME_END_OF_SOURCE
            || (periodDurationUs != C.TIME_UNSET && positionUs >= periodDurationUs)
        ) {
            return C.INDEX_UNSET
        }
        // Use a linear search as the array elements may not be increasing due to TIME_END_OF_SOURCE.
        // In practice we expect there to be few ad groups so the search shouldn't be expensive.
        var index = removedAdGroupCount
        while (index < adGroupCount
            && ((getAdGroup(index).timeUs != C.TIME_END_OF_SOURCE
                    && getAdGroup(index).timeUs <= positionUs)
                    || !getAdGroup(index).shouldPlayAdGroup())
        ) {
            index++
        }
        return if (index < adGroupCount
            && (periodDurationUs == C.TIME_UNSET || getAdGroup(index).timeUs <= periodDurationUs)
        )
            index
        else
            C.INDEX_UNSET
    }

    fun isAdInErrorState(adGroupIndex: Int, adIndexInAdGroup: Int): Boolean {
        if (adGroupIndex >= adGroupCount) {
            return false
        }
        val adGroup = getAdGroup(adGroupIndex)
        if (adGroup.count == C.LENGTH_UNSET || adIndexInAdGroup >= adGroup.count) {
            return false
        }
        return adGroup.states[adIndexInAdGroup] == AD_STATE_ERROR
    }

    fun withAdGroupTimeUs(adGroupIndex: Int, adGroupTimeUs: Long): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = this.adGroups[adjustedIndex]!!.withTimeUs(adGroupTimeUs)
        return copy(adGroups = adGroups)
    }

    fun withNewAdGroup(adGroupIndex: Int, adGroupTimeUs: Long): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val newAdGroup = AdGroup(adGroupTimeUs)
        val adGroups = this.adGroups.toMutableList()
        adGroups.add(newAdGroup)
        System.arraycopy(
            adGroups,
            adjustedIndex,
            adGroups,
            adjustedIndex + 1,
            this.adGroups.size - adjustedIndex
        )
        adGroups[adjustedIndex] = newAdGroup
        return copy(adGroups = adGroups)
    }

    fun withAdCount(adGroupIndex: Int, @IntRange(from = 1) adCount: Int): AdPlaybackState {
        Preconditions.checkArgument(adCount > 0)
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.count == adCount) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = this.adGroups[adjustedIndex]!!.withAdCount(adCount)
        return copy(adGroups = adGroups)
    }

    fun copy(): AdPlaybackState {
        val adGroups = arrayOfNulls<AdGroup>(this.adGroups.size)
        for (i in adGroups.indices) {
            adGroups[i] = this.adGroups[i]!!.copy()
        }
        return copy(adGroups = adGroups.toList())
    }

    fun withAvailableAdMediaItem(
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        mediaItem: MediaItem
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        Preconditions.checkState(
            adGroups[adjustedIndex]!!.isServerSideInserted
                    || (mediaItem.localConfiguration != null
                    && mediaItem.localConfiguration.uri != Uri.EMPTY)
        )
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdMediaItem(mediaItem, adIndexInAdGroup)
        return copy(adGroups = adGroups)
    }

    fun withAvailableAd(adGroupIndex: Int, adIndexInAdGroup: Int): AdPlaybackState {
        return withAvailableAdMediaItem(
            adGroupIndex,
            adIndexInAdGroup,
            MediaItem.fromUri(Uri.EMPTY)
        )
    }

    fun withPlayedAd(adGroupIndex: Int, adIndexInAdGroup: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdState(AD_STATE_PLAYED, adIndexInAdGroup)
        return copy(adGroups = adGroups)
    }

    fun withSkippedAd(adGroupIndex: Int, adIndexInAdGroup: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdState(AD_STATE_SKIPPED, adIndexInAdGroup)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdLoadError(adGroupIndex: Int, adIndexInAdGroup: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdState(AD_STATE_ERROR, adIndexInAdGroup)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdId(adGroupIndex: Int, adIndexInAdGroup: Int, adId: String?): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAdId(adId, adIndexInAdGroup)
        return copy(adGroups = adGroups)
    }

    fun withAdSkipInfo(
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        skipInfo: SkipInfo?
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdSkipInfo(skipInfo, adIndexInAdGroup)
        return copy(adGroups = adGroups)
    }

    fun withSkippedAdGroup(adGroupIndex: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAllAdsSkipped()
        return copy(adGroups = adGroups)
    }

    fun withAdDurationsUs(adDurationUs: Array<LongArray?>): AdPlaybackState {
        Preconditions.checkArgument(adDurationUs.size == adGroupCount)
        val adGroups = this.adGroups.toMutableList()
        for (correctedAdGroupIndex in 0..<adGroupCount - removedAdGroupCount) {
            adGroups[correctedAdGroupIndex] =
                adGroups[correctedAdGroupIndex]!!.withAdDurationsUs(
                    adDurationUs[removedAdGroupCount + correctedAdGroupIndex]!!
                )
        }
        return copy(adGroups = adGroups)
    }

    fun withAdDurationsUs(adGroupIndex: Int, vararg adDurationsUs: Long): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAdDurationsUs(adDurationsUs)
        return copy(adGroups = adGroups)
    }

    fun withAdResumePositionUs(adResumePositionUs: Long): AdPlaybackState {
        if (this.adResumePositionUs == adResumePositionUs) {
            return this
        } else {
            return copy(adResumePositionUs = adResumePositionUs)
        }
    }

    fun withContentDurationUs(contentDurationUs: Long): AdPlaybackState {
        if (this.contentDurationUs == contentDurationUs) {
            return this
        } else {
            return copy(contentDurationUs = contentDurationUs)
        }
    }

    fun withRemovedAdGroupCount(removedAdGroupCount: Int): AdPlaybackState {
        if (this.removedAdGroupCount == removedAdGroupCount) {
            return this
        } else {
            Preconditions.checkArgument(removedAdGroupCount > this.removedAdGroupCount)
            val adGroups = arrayOfNulls<AdGroup>(adGroupCount - removedAdGroupCount)
            System.arraycopy(
                this.adGroups,
                removedAdGroupCount - this.removedAdGroupCount,
                adGroups,
                0,
                adGroups.size
            )
            return copy(adGroups=adGroups.toList())
        }
    }

    fun withRemovedAdGroupCountBefore(timeUs: Long): AdPlaybackState {
        var newRemovedAdGroupCount = removedAdGroupCount
        while (newRemovedAdGroupCount < adGroupCount) {
            val adGroup = getAdGroup(newRemovedAdGroupCount)
            if (timeUs <= adGroup.timeUs || adGroup.timeUs == C.TIME_END_OF_SOURCE) {
                break
            }
            newRemovedAdGroupCount++
        }
        return withRemovedAdGroupCount(newRemovedAdGroupCount)
    }

    fun withContentResumeOffsetUs(adGroupIndex: Int, contentResumeOffsetUs: Long): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.contentResumeOffsetUs == contentResumeOffsetUs) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.copy(contentResumeOffsetUs = contentResumeOffsetUs)
        return copy(adGroups=adGroups)
    }

    fun withOriginalAdCount(adGroupIndex: Int, originalAdCount: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.originalCount == originalAdCount) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.copy(originalCount = originalAdCount)
        return copy(adGroups=adGroups)
    }

    fun withIsServerSideInserted(
        adGroupIndex: Int,
        isServerSideInserted: Boolean
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.isServerSideInserted == isServerSideInserted) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.copy(isServerSideInserted = isServerSideInserted)
        return copy(adGroups=adGroups)
    }

    fun withResetAdGroup(adGroupIndex: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAllAdsReset()
        return copy(adGroups=adGroups)
    }

    fun withRemovedAdsAfterIndex(adGroupIndex: Int, adIndexInAdGroup: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withRemovedAdsAfterIndex(adIndexInAdGroup)
        return copy(adGroups=adGroups)
    }

    fun withUnavailableAdGroup(adGroupIndex: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]?.withAllAdsUnavailable()
        return copy(adGroups=adGroups)
    }

    fun withLivePostrollPlaceholderAppended(isServerSideInserted: Boolean): AdPlaybackState {
        return withNewAdGroup(adGroupCount, C.TIME_END_OF_SOURCE)
            .withIsPlaceholder(adGroupCount, true, isServerSideInserted)
    }

    fun withIsPlaceholder(
        adGroupIndex: Int, isPlaceholder: Boolean, isServerSideInserted: Boolean
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]?.isPlaceholder == isPlaceholder
            && adGroups[adjustedIndex]?.isServerSideInserted == isServerSideInserted
        ) {
            return this
        }
        val adGroups = this.adGroups.toList().toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]?.withIsPlaceholder(isPlaceholder, isServerSideInserted)
        return copy(adGroups=adGroups)
    }

    fun endsWithLivePostrollPlaceHolder(): Boolean {
        val adGroupIndex = adGroupCount - 1
        return adGroupIndex >= 0 && isLivePostrollPlaceholder(adGroupIndex)
    }

    fun endsWithLivePostrollPlaceHolder(isServerSideInserted: Boolean): Boolean {
        val adGroupIndex = adGroupCount - 1
        return adGroupIndex >= 0 && isLivePostrollPlaceholder(adGroupIndex, isServerSideInserted)
    }

    fun isLivePostrollPlaceholder(adGroupIndex: Int): Boolean {
        return adGroupIndex == adGroupCount - 1 && getAdGroup(adGroupIndex).isLivePostrollPlaceholder
    }

    fun isLivePostrollPlaceholder(adGroupIndex: Int, isServerSideInserted: Boolean): Boolean {
        return adGroupIndex == adGroupCount - 1
                && getAdGroup(adGroupIndex).isLivePostrollPlaceholder(isServerSideInserted)
    }

    fun getAdIndexOfAdId(adGroupIndex: Int, adId: String?): Int {
        return getAdGroup(adGroupIndex).getIndexOfAdId(adId)
    }

    private fun isPositionBeforeAdGroup(
        positionUs: Long, periodDurationUs: Long, adGroupIndex: Int
    ): Boolean {
        if (positionUs == C.TIME_END_OF_SOURCE) {
            // The end of the content is at (but not before) any postroll ad, and after any other ad.
            return false
        }
        val adGroup = getAdGroup(adGroupIndex)
        val adGroupPositionUs = adGroup.timeUs
        if (adGroupPositionUs == C.TIME_END_OF_SOURCE) {
            // Handling postroll: The requested position is considered before a postroll when a)
            // the period duration is unknown (last period in a live stream), or when b) the postroll is a
            // placeholder in a period of a multi-period live window, or when c) the position actually is
            // before the given period duration.
            return periodDurationUs == C.TIME_UNSET || adGroup.isLivePostrollPlaceholder
                    || positionUs < periodDurationUs
        }
        return positionUs < adGroupPositionUs
    }

    fun toBundle(interfaceVersion: Int): Bundle {
        val bundle = Bundle()
        val adGroupBundleList = java.util.ArrayList<Bundle?>()
        for (adGroup in adGroups) {
            adGroupBundleList.add(adGroup!!.toBundle(interfaceVersion))
        }
        if (!adGroupBundleList.isEmpty()) {
            bundle.putParcelableArrayList(FIELD_AD_GROUPS, adGroupBundleList)
        }
        if (adResumePositionUs != NONE.adResumePositionUs) {
            bundle.putLong(FIELD_AD_RESUME_POSITION_US, adResumePositionUs)
        }
        if (contentDurationUs != NONE.contentDurationUs) {
            bundle.putLong(FIELD_CONTENT_DURATION_US, contentDurationUs)
        }
        if (removedAdGroupCount != NONE.removedAdGroupCount) {
            bundle.putInt(FIELD_REMOVED_AD_GROUP_COUNT, removedAdGroupCount)
        }
        return bundle
    }

    data class SkipInfo(var skipOffsetUs: Long, val skipDurationUs: Long, val labelId: String?) {
        init {
            Preconditions.checkArgument(
                skipOffsetUs != C.TIME_UNSET || skipDurationUs != C.TIME_UNSET || labelId != null
            )
            this.skipOffsetUs = if (skipOffsetUs != C.TIME_UNSET) skipOffsetUs else 0
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putLong(FIELD_SKIP_OFFSET_US, skipOffsetUs)
            bundle.putLong(FIELD_SKIP_DURATION_US, skipDurationUs)
            bundle.putString(FIELD_LABEL_ID, labelId)
            return bundle
        }

        companion object {
            private val FIELD_SKIP_OFFSET_US = intToStringMaxRadix(0)
            private val FIELD_SKIP_DURATION_US = intToStringMaxRadix(1)
            private val FIELD_LABEL_ID = intToStringMaxRadix(2)

            fun fromBundle(bundle: Bundle): SkipInfo {
                return SkipInfo(
                    bundle.getLong(FIELD_SKIP_OFFSET_US),
                    bundle.getLong(FIELD_SKIP_DURATION_US),
                    bundle.getString(FIELD_LABEL_ID)
                )
            }
        }
    }

    data class AdGroup(
        val timeUs: Long,
        val count: Int = C.LENGTH_UNSET,
        val originalCount: Int = C.LENGTH_UNSET,
        val states: IntArray = IntArray(0),
        val mediaItems: Array<MediaItem?> = arrayOfNulls(0),
        val durationsUs: LongArray = LongArray(0),
        val contentResumeOffsetUs: Long = 0,
        val isServerSideInserted: Boolean = false,
        val ids: Array<String?> = arrayOfNulls(0),
        val skipInfos: Array<SkipInfo?> = arrayOfNulls(0),
        val isPlaceholder: Boolean = false
    ) {
        init {
            Preconditions.checkArgument(states.size == mediaItems.size)
            Preconditions.checkArgument(states.size == skipInfos.size)
        }

        val firstAdIndexToPlay: Int
            get() = getNextAdIndexToPlay(-1)

        fun getNextAdIndexToPlay(@IntRange(from = -1) lastPlayedAdIndex: Int): Int {
            var nextAdIndexToPlay = lastPlayedAdIndex + 1
            if (isServerSideInserted) {
                return nextAdIndexToPlay
            }
            while (nextAdIndexToPlay < states.size) {
                if (states[nextAdIndexToPlay] == AD_STATE_UNAVAILABLE || states[nextAdIndexToPlay] == AD_STATE_AVAILABLE) {
                    break
                }
                nextAdIndexToPlay++
            }
            return nextAdIndexToPlay
        }

        fun shouldPlayAdGroup(): Boolean {
            return count == C.LENGTH_UNSET || this.firstAdIndexToPlay < count
        }

        fun hasUnplayedAds(): Boolean {
            if (count == C.LENGTH_UNSET) {
                return true
            }
            return states.find { it == AD_STATE_UNAVAILABLE || it == AD_STATE_AVAILABLE } != null
        }

        fun isLivePostrollPlaceholder(isServerSideInserted: Boolean): Boolean {
            return (this.isServerSideInserted == isServerSideInserted) && this.isLivePostrollPlaceholder
        }

        val isLivePostrollPlaceholder: Boolean
            get() = isPlaceholder && timeUs == C.TIME_END_OF_SOURCE && count == C.LENGTH_UNSET

        fun withTimeUs(timeUs: Long): AdGroup {
            return copy(timeUs = timeUs)
        }

        fun withAdCount(count: Int): AdGroup {
            val states = copyStatesWithSpaceForAdCount(this.states, count)
            val durationsUs = copyDurationsUsWithSpaceForAdCount(this.durationsUs, count)
            val mediaItems = this.mediaItems.copyOf<MediaItem?>(count)
            val ids = this.ids.copyOf<String?>(count)
            val skipInfos = copySkipInfosWithSpaceForAdCount(this.skipInfos, count)
            return copy(
                count = count,
                states = states,
                mediaItems = mediaItems,
                durationsUs = durationsUs,
                ids = ids,
                skipInfos = skipInfos,
            )
        }

        fun withAdMediaItem(mediaItem: MediaItem?, index: Int): AdGroup {
            val states = copyStatesWithSpaceForAdCount(this.states, index + 1)
            val durationsUs =
                if (this.durationsUs.size == states.size)
                    this.durationsUs
                else
                    copyDurationsUsWithSpaceForAdCount(this.durationsUs, states.size)
            val mediaItems = this.mediaItems.copyOf<MediaItem?>(states.size)
            mediaItems[index] = mediaItem
            states[index] = AD_STATE_AVAILABLE
            val ids =
                if (this.ids.size == states.size) this.ids else this.ids.copyOf<String?>(
                    states.size
                )
            val skipInfos =
                if (this.skipInfos.size == states.size)
                    this.skipInfos
                else
                    copySkipInfosWithSpaceForAdCount(this.skipInfos, states.size)
            return copy(
                states = states,
                mediaItems = mediaItems,
                durationsUs = durationsUs,
                ids = ids,
                skipInfos = skipInfos,
            )
        }

        fun withAdState(state: Int, index: Int): AdGroup {
            Preconditions.checkArgument(count == C.LENGTH_UNSET || index < count)
            val states = copyStatesWithSpaceForAdCount(this.states, index + 1)
            Preconditions.checkArgument(
                states[index] == AD_STATE_UNAVAILABLE || states[index] == AD_STATE_AVAILABLE || states[index] == state
            )
            val durationsUs =
                if (this.durationsUs.size == states.size)
                    this.durationsUs
                else
                    copyDurationsUsWithSpaceForAdCount(this.durationsUs, states.size)
            val mediaItems =
                if (this.mediaItems.size == states.size)
                    this.mediaItems
                else this.mediaItems.copyOf<MediaItem?>(states.size)
            val ids =
                if (this.ids.size == states.size) this.ids else this.ids.copyOf<String?>(
                    states.size
                )
            states[index] = state
            val skipInfos =
                if (this.skipInfos.size == states.size)
                    this.skipInfos
                else
                    copySkipInfosWithSpaceForAdCount(this.skipInfos, states.size)
            return copy(
                states = states,
                mediaItems = mediaItems,
                durationsUs = durationsUs,
                ids = ids,
                skipInfos = skipInfos,
            )
        }

        fun withAdDurationsUs(durationsUs: LongArray): AdGroup {
            var durationsUs = durationsUs
            if (durationsUs.size < mediaItems.size) {
                durationsUs = copyDurationsUsWithSpaceForAdCount(durationsUs, mediaItems.size)
            } else if (count != C.LENGTH_UNSET && durationsUs.size > mediaItems.size) {
                durationsUs = durationsUs.copyOf(mediaItems.size)
            }
            return copy(durationsUs = durationsUs)
        }

        fun withAdId(adId: String?, index: Int): AdGroup {
            val states = copyStatesWithSpaceForAdCount(this.states, index + 1)
            val durationsUs =
                if (this.durationsUs.size == states.size)
                    this.durationsUs
                else
                    copyDurationsUsWithSpaceForAdCount(this.durationsUs, states.size)
            val mediaItems =
                if (this.mediaItems.size == states.size)
                    this.mediaItems
                else this.mediaItems.copyOf<MediaItem?>(states.size)
            val ids = this.ids.copyOf<String?>(states.size)
            ids[index] = adId
            return copy(
                states = states,
                mediaItems = mediaItems,
                durationsUs = durationsUs,
                ids = ids,
            )
        }

        fun withAdSkipInfo(skipInfo: SkipInfo?, index: Int): AdGroup {
            val states = copyStatesWithSpaceForAdCount(this.states, index + 1)
            val durationsUs =
                if (this.durationsUs.size == states.size)
                    this.durationsUs
                else
                    copyDurationsUsWithSpaceForAdCount(this.durationsUs, states.size)
            val mediaItems =
                if (this.mediaItems.size == states.size)
                    this.mediaItems
                else this.mediaItems.copyOf<MediaItem?>(states.size)
            val ids =
                if (this.ids.size == states.size) this.ids else this.ids.copyOf<String?>(
                    states.size
                )
            val skipInfos = copySkipInfosWithSpaceForAdCount(this.skipInfos, states.size)
            skipInfos[index] = skipInfo
            return copy(
                states = states,
                mediaItems = mediaItems,
                durationsUs = durationsUs,
                ids = ids,
                skipInfos = skipInfos,
            )
        }

        fun withAllAdsSkipped(): AdGroup {
            if (count == C.LENGTH_UNSET) {
                return copy(
                    count = 0,
                    states = IntArray(0),
                    mediaItems = arrayOfNulls(0),
                    durationsUs = LongArray(0),
                )
            }
            val count = this.states.size
            val states = this.states.copyOf(count)
            for (i in 0..<count) {
                if (states[i] == AD_STATE_AVAILABLE || states[i] == AD_STATE_UNAVAILABLE) {
                    states[i] = AD_STATE_SKIPPED
                }
            }
            return copy(count = count, states = states)
        }

        fun withAllAdsReset(): AdGroup {
            if (count == C.LENGTH_UNSET) {
                return this
            }
            val count = this.states.size
            val states = this.states.copyOf(count)
            for (i in 0..<count) {
                if (states[i] == AD_STATE_PLAYED || states[i] == AD_STATE_SKIPPED || states[i] == AD_STATE_ERROR) {
                    states[i] =
                        if (mediaItems[i] == null) AD_STATE_UNAVAILABLE else AD_STATE_AVAILABLE
                }
            }
            return copy(count = count, states = states)
        }

        fun withRemovedAdsAfterIndex(adIndexInAdGroup: Int): AdGroup {
            Preconditions.checkArgument(adIndexInAdGroup >= 0)
            if (count == C.LENGTH_UNSET || adIndexInAdGroup >= count - 1) {
                return this
            }
            val newCount = adIndexInAdGroup + 1
            val newDurationsUs = durationsUs.copyOf(newCount)
            return copy(
                count = newCount,
                durationsUs = newDurationsUs,
                contentResumeOffsetUs = if (contentResumeOffsetUs != 0L) sumOfDurations(*newDurationsUs) else 0,
            )
        }

        fun withAllAdsUnavailable(): AdGroup {
            if (count == C.LENGTH_UNSET) {
                return this
            }
            val states = IntArray(this.states.size)
            Arrays.fill(states, AD_STATE_UNAVAILABLE)
            val durationsUs = LongArray(states.size)
            Arrays.fill(durationsUs, C.TIME_UNSET)
            return copy(
                states = states,
                mediaItems = arrayOfNulls(count),
                durationsUs = durationsUs,
                contentResumeOffsetUs = 0,
            )
        }

        internal fun withIsPlaceholder(
            isPlaceholder: Boolean, isServerSideInserted: Boolean
        ): AdGroup {
            return copy(
                isServerSideInserted = isServerSideInserted,
                isPlaceholder = isPlaceholder
            )
        }

        fun getIndexOfAdId(adId: String?): Int {
            return ids.indexOfFirst { it == adId }
        }

        // putParcelableArrayList actually supports null elements.
        fun toBundle(interfaceVersion: Int): Bundle {
            val bundle = Bundle()
            bundle.putLong(FIELD_TIME_US, timeUs)
            bundle.putInt(FIELD_COUNT, count)
            bundle.putInt(FIELD_ORIGINAL_COUNT, originalCount)
            bundle.putParcelableArrayList(
                FIELD_MEDIA_ITEMS,
                getMediaItemsArrayBundles(interfaceVersion)
            )
            bundle.putIntArray(FIELD_STATES, states)
            bundle.putLongArray(FIELD_DURATIONS_US, durationsUs)
            bundle.putLong(FIELD_CONTENT_RESUME_OFFSET_US, contentResumeOffsetUs)
            bundle.putBoolean(FIELD_IS_SERVER_SIDE_INSERTED, isServerSideInserted)
            bundle.putStringArrayList(
                FIELD_IDS,
                java.util.ArrayList(Arrays.asList<String?>(*ids))
            )
            bundle.putParcelableArrayList(
                FIELD_SKIP_INFOS,
                this.skipInfoArrayBundles
            )
            bundle.putBoolean(FIELD_IS_PLACEHOLDER, isPlaceholder)
            return bundle
        }

        private val skipInfoArrayBundles: ArrayList<Bundle?>
            get() {
                val bundles =
                    java.util.ArrayList<Bundle?>()
                for (skipInfo in skipInfos) {
                    bundles.add(skipInfo?.toBundle())
                }
                return bundles
            }

        private fun getMediaItemsArrayBundles(interfaceVersion: Int): java.util.ArrayList<Bundle?> {
            val bundles = java.util.ArrayList<Bundle?>()
            for (mediaItem in mediaItems) {
                bundles.add(mediaItem?.toBundleIncludeLocalConfiguration(interfaceVersion))
            }
            return bundles
        }

        companion object {
            private fun copyStatesWithSpaceForAdCount(
                states: IntArray, count: Int
            ): IntArray {
                var states = states
                val oldStateCount = states.size
                val newStateCount = max(count, oldStateCount)
                states = states.copyOf(newStateCount)
                Arrays.fill(states, oldStateCount, newStateCount, AD_STATE_UNAVAILABLE)
                return states
            }

            private fun copyDurationsUsWithSpaceForAdCount(
                durationsUs: LongArray, count: Int
            ): LongArray {
                var durationsUs = durationsUs
                val oldDurationsUsCount = durationsUs.size
                val newDurationsUsCount = max(count, oldDurationsUsCount)
                durationsUs = durationsUs.copyOf(newDurationsUsCount)
                Arrays.fill(durationsUs, oldDurationsUsCount, newDurationsUsCount, C.TIME_UNSET)
                return durationsUs
            }

            private fun copySkipInfosWithSpaceForAdCount(
                skipInfos: Array<SkipInfo?>, count: Int
            ): Array<SkipInfo?> {
                var skipInfos = skipInfos
                val oldSkipInfoCount = skipInfos.size
                val newSkipInfoCount = max(count, oldSkipInfoCount)
                skipInfos = skipInfos.copyOf(newSkipInfoCount)
                return skipInfos
            }

            private val FIELD_TIME_US = intToStringMaxRadix(0)
            private val FIELD_COUNT = intToStringMaxRadix(1)
            private val FIELD_URIS = intToStringMaxRadix(2)
            private val FIELD_STATES = intToStringMaxRadix(3)
            private val FIELD_DURATIONS_US = intToStringMaxRadix(4)
            private val FIELD_CONTENT_RESUME_OFFSET_US = intToStringMaxRadix(5)
            private val FIELD_IS_SERVER_SIDE_INSERTED = intToStringMaxRadix(6)
            private val FIELD_ORIGINAL_COUNT = intToStringMaxRadix(7)

            val FIELD_MEDIA_ITEMS: String = intToStringMaxRadix(8)
            val FIELD_IDS: String = intToStringMaxRadix(9)
            val FIELD_IS_PLACEHOLDER: String = intToStringMaxRadix(10)
            private val FIELD_SKIP_INFOS = intToStringMaxRadix(11)

            // getParcelableArrayList may have null elements.
            fun fromBundle(bundle: Bundle, interfaceVersion: Int): AdGroup {
                val timeUs = bundle.getLong(FIELD_TIME_US)
                val count = bundle.getInt(FIELD_COUNT)
                val originalCount = bundle.getInt(FIELD_ORIGINAL_COUNT)
                val uriList = bundle.getParcelableArrayList<Uri?>(FIELD_URIS)
                val mediaItemBundleList =
                    bundle.getParcelableArrayList<Bundle?>(FIELD_MEDIA_ITEMS)
                val states = bundle.getIntArray(FIELD_STATES)
                val durationsUs = bundle.getLongArray(FIELD_DURATIONS_US)
                val contentResumeOffsetUs = bundle.getLong(FIELD_CONTENT_RESUME_OFFSET_US)
                val isServerSideInserted = bundle.getBoolean(FIELD_IS_SERVER_SIDE_INSERTED)
                val ids = bundle.getStringArrayList(FIELD_IDS)
                val skipInfoBundleList =
                    bundle.getParcelableArrayList<Bundle?>(FIELD_SKIP_INFOS)
                val isPlaceholder = bundle.getBoolean(FIELD_IS_PLACEHOLDER)
                return AdGroup(
                    timeUs,
                    count,
                    originalCount,
                    states ?: IntArray(0),
                    getMediaItemsFromBundleArrays(mediaItemBundleList, uriList, interfaceVersion),
                    durationsUs ?: LongArray(0),
                    contentResumeOffsetUs,
                    isServerSideInserted,
                    ids?.toTypedArray<String?>() ?: arrayOfNulls<String>(0),
                    if (skipInfoBundleList == null)
                        arrayOfNulls(0)
                    else
                        getSkipInfosFromBundleArrays(skipInfoBundleList),
                    isPlaceholder
                )
            }

            private fun getMediaItemsFromBundleArrays(
                mediaItemBundleList: java.util.ArrayList<Bundle?>?,
                uriList: java.util.ArrayList<Uri?>?,
                interfaceVersion: Int
            ): Array<MediaItem?> {
                if (mediaItemBundleList != null) {
                    val mediaItems = arrayOfNulls<MediaItem>(mediaItemBundleList.size)
                    for (i in mediaItemBundleList.indices) {
                        val mediaItemBundle = mediaItemBundleList[i]
                        mediaItems[i] =
                            if (mediaItemBundle == null)
                                null
                            else
                                MediaItem.fromBundle(mediaItemBundle, interfaceVersion)
                    }
                    return mediaItems
                } else if (uriList != null) {
                    val mediaItems = arrayOfNulls<MediaItem>(uriList.size)
                    for (i in uriList.indices) {
                        val uri = uriList[i]
                        mediaItems[i] = if (uri == null) null else MediaItem.fromUri(uri)
                    }
                    return mediaItems
                } else {
                    return arrayOfNulls(0)
                }
            }

            private fun getSkipInfosFromBundleArrays(
                skipInfoBundleList: MutableList<Bundle?>
            ): Array<SkipInfo?> {
                val skipInfos = arrayOfNulls<SkipInfo>(skipInfoBundleList.size)
                for (i in skipInfoBundleList.indices) {
                    val skipInfoBundle = skipInfoBundleList[i]
                    skipInfos[i] =
                        if (skipInfoBundle == null) null else SkipInfo.fromBundle(skipInfoBundle)
                }
                return skipInfos
            }
        }
    }

    companion object {
        const val AD_STATE_UNAVAILABLE: Int = 0
        const val AD_STATE_AVAILABLE: Int = 1
        const val AD_STATE_SKIPPED: Int = 2
        const val AD_STATE_PLAYED: Int = 3
        const val AD_STATE_ERROR: Int = 4

        val NONE: AdPlaybackState = AdPlaybackState(
            null,
            listOf<AdGroup>(),
            0L,
            C.TIME_UNSET,
            0
        )
        private val REMOVED_AD_GROUP: AdGroup = AdGroup(0).withAdCount(0)

        fun sumOfDurations(vararg durations: Long): Long {
            var sum: Long = 0
            for (duration in durations) {
                if (duration != C.TIME_UNSET) {
                    sum = LongMath.saturatedAdd(sum, duration)
                }
            }
            return sum
        }

        fun fromAdPlaybackState(adsId: Any?, adPlaybackState: AdPlaybackState): AdPlaybackState {
            val adGroups =
                arrayOfNulls<AdGroup>(adPlaybackState.adGroupCount - adPlaybackState.removedAdGroupCount)
            for (i in adGroups.indices) {
                val adGroup = adPlaybackState.adGroups[i]!!
                adGroups[i] =
                    AdGroup(
                        adGroup.timeUs,
                        adGroup.count,
                        adGroup.originalCount,
                        adGroup.states.copyOf(adGroup.states.size),
                        adGroup.mediaItems.copyOf<MediaItem?>(adGroup.mediaItems.size),
                        adGroup.durationsUs.copyOf(adGroup.durationsUs.size),
                        adGroup.contentResumeOffsetUs,
                        adGroup.isServerSideInserted,
                        adGroup.ids,
                        adGroup.skipInfos,
                        adGroup.isPlaceholder
                    )
            }
            return AdPlaybackState(
                adsId,
                adGroups.toList(),
                adPlaybackState.adResumePositionUs,
                adPlaybackState.contentDurationUs,
                adPlaybackState.removedAdGroupCount
            )
        }

        private const val FIELD_AD_GROUPS = "1"
        private const val FIELD_AD_RESUME_POSITION_US = "2"
        private const val FIELD_CONTENT_DURATION_US = "3"
        private const val FIELD_REMOVED_AD_GROUP_COUNT = "4"

        fun fromBundle(bundle: Bundle, interfaceVersion: Int): AdPlaybackState {
            val adGroupBundleList = bundle.getParcelableArrayList<Bundle?>(FIELD_AD_GROUPS)
            val adGroups: Array<AdGroup?>?
            if (adGroupBundleList == null) {
                adGroups = arrayOfNulls<AdGroup>(0)
            } else {
                adGroups = arrayOfNulls<AdGroup>(adGroupBundleList.size)
                for (i in adGroupBundleList.indices) {
                    adGroups[i] =
                        AdGroup.fromBundle(adGroupBundleList[i]!!, interfaceVersion)
                }
            }
            val adResumePositionUs =
                bundle.getLong(
                    FIELD_AD_RESUME_POSITION_US,
                    NONE.adResumePositionUs
                )
            val contentDurationUs =
                bundle.getLong(
                    FIELD_CONTENT_DURATION_US,
                    NONE.contentDurationUs
                )
            val removedAdGroupCount =
                bundle.getInt(
                    FIELD_REMOVED_AD_GROUP_COUNT,
                    NONE.removedAdGroupCount
                )
            return AdPlaybackState(
                null, adGroups.toList(), adResumePositionUs, contentDurationUs, removedAdGroupCount
            )
        }

        private fun createEmptyAdGroups(adGroupTimesUs: LongArray): Array<AdGroup?> {
            val adGroups = Array<AdGroup?>(adGroupTimesUs.size) { null }
            for (i in adGroups.indices) {
                adGroups[i] = AdGroup(adGroupTimesUs[i])
            }
            return adGroups
        }
    }
}
