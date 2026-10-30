package androidx.media3.common

import android.net.Uri
import android.os.Bundle
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.Util.intToStringMaxRadix
import androidx.media3.common.util.contentHashCode
import com.google.common.base.Preconditions
import com.google.common.math.LongMath
import java.util.Arrays
import java.util.Objects
import kotlin.math.max

class AdPlaybackState private constructor(
    val adsId: Any?,
    private val adGroups: List<AdGroup?>,
    val adResumePositionUs: Long,
    val contentDurationUs: Long,
    val removedAdGroupCount: Int
) {

    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [AD_STATE_UNAVAILABLE, AD_STATE_AVAILABLE, AD_STATE_SKIPPED, AD_STATE_PLAYED, AD_STATE_ERROR]
    )
    annotation class AdState

    class SkipInfo(skipOffsetUs: Long, skipDurationUs: Long, labelId: String?) {
        val skipOffsetUs: Long
        val skipDurationUs: Long
        val labelId: String?

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val skipInfo = o as SkipInfo
            return skipOffsetUs == skipInfo.skipOffsetUs && skipDurationUs == skipInfo.skipDurationUs && labelId == skipInfo.labelId
        }

        override fun hashCode(): Int {
            return Objects.hash(skipOffsetUs, skipDurationUs, labelId)
        }

        init {
            Preconditions.checkArgument(
                skipOffsetUs != C.TIME_UNSET || skipDurationUs != C.TIME_UNSET || labelId != null
            )
            this.skipOffsetUs = if (skipOffsetUs != C.TIME_UNSET) skipOffsetUs else 0
            this.skipDurationUs = skipDurationUs
            this.labelId = labelId
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

    val adGroupCount: Int

    constructor(adsId: Any?, vararg adGroupTimesUs: Long) : this(
        adsId,
        createEmptyAdGroups(adGroupTimesUs).toList(),  
        0L,  
        C.TIME_UNSET,  
        0
    )

    fun getAdGroup(@IntRange(from = 0) adGroupIndex: Int): AdGroup {
        return (if (adGroupIndex < removedAdGroupCount)
            AdPlaybackState.REMOVED_AD_GROUP
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

    fun isAdInErrorState(
        @IntRange(from = 0) adGroupIndex: Int, @IntRange(from = 0) adIndexInAdGroup: Int
    ): Boolean {
        if (adGroupIndex >= adGroupCount) {
            return false
        }
        val adGroup = getAdGroup(adGroupIndex)
        if (adGroup.count == C.LENGTH_UNSET || adIndexInAdGroup >= adGroup.count) {
            return false
        }
        return adGroup.states[adIndexInAdGroup] == AD_STATE_ERROR
    }

    fun withAdGroupTimeUs(
        @IntRange(from = 0) adGroupIndex: Int, adGroupTimeUs: Long
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = this.adGroups[adjustedIndex]!!.withTimeUs(adGroupTimeUs)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withNewAdGroup(
        @IntRange(from = 0) adGroupIndex: Int,
        adGroupTimeUs: Long
    ): AdPlaybackState {
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
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdCount(
        @IntRange(from = 0) adGroupIndex: Int, @IntRange(from = 1) adCount: Int
    ): AdPlaybackState {
        Preconditions.checkArgument(adCount > 0)
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.count == adCount) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = this.adGroups[adjustedIndex]!!.withAdCount(adCount)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun copy(): AdPlaybackState {
        val adGroups = arrayOfNulls<AdGroup>(this.adGroups.size)
        for (i in adGroups.indices) {
            adGroups[i] = this.adGroups[i]!!.copy()
        }
        return AdPlaybackState(
            adsId, adGroups.toList(), adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAvailableAdMediaItem(
        @IntRange(from = 0) adGroupIndex: Int,
        @IntRange(from = 0) adIndexInAdGroup: Int,
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
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdsId(adsId: Any?): AdPlaybackState {
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAvailableAd(
        @IntRange(from = 0) adGroupIndex: Int, @IntRange(from = 0) adIndexInAdGroup: Int
    ): AdPlaybackState {
        return withAvailableAdMediaItem(adGroupIndex, adIndexInAdGroup, MediaItem.fromUri(Uri.EMPTY))
    }

    fun withPlayedAd(
        @IntRange(from = 0) adGroupIndex: Int, @IntRange(from = 0) adIndexInAdGroup: Int
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdState(AD_STATE_PLAYED, adIndexInAdGroup)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withSkippedAd(
        @IntRange(from = 0) adGroupIndex: Int, @IntRange(from = 0) adIndexInAdGroup: Int
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdState(AD_STATE_SKIPPED, adIndexInAdGroup)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdLoadError(
        @IntRange(from = 0) adGroupIndex: Int, @IntRange(from = 0) adIndexInAdGroup: Int
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdState(AD_STATE_ERROR, adIndexInAdGroup)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdId(
        @IntRange(from = 0) adGroupIndex: Int,
        @IntRange(from = 0) adIndexInAdGroup: Int,
        adId: String?
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAdId(adId, adIndexInAdGroup)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdSkipInfo(
        @IntRange(from = 0) adGroupIndex: Int,
        @IntRange(from = 0) adIndexInAdGroup: Int,
        skipInfo: SkipInfo?
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withAdSkipInfo(skipInfo, adIndexInAdGroup)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withSkippedAdGroup(@IntRange(from = 0) adGroupIndex: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAllAdsSkipped()
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
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
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdDurationsUs(
        @IntRange(from = 0) adGroupIndex: Int, vararg adDurationsUs: Long
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAdDurationsUs(adDurationsUs)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withAdResumePositionUs(adResumePositionUs: Long): AdPlaybackState {
        if (this.adResumePositionUs == adResumePositionUs) {
            return this
        } else {
            return AdPlaybackState(
                adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
            )
        }
    }

    fun withContentDurationUs(contentDurationUs: Long): AdPlaybackState {
        if (this.contentDurationUs == contentDurationUs) {
            return this
        } else {
            return AdPlaybackState(
                adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
            )
        }
    }

    fun withRemovedAdGroupCount(@IntRange(from = 0) removedAdGroupCount: Int): AdPlaybackState {
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
            return AdPlaybackState(
                adsId, adGroups.toList(), adResumePositionUs, contentDurationUs, removedAdGroupCount
            )
        }
    }

    fun withRemovedAdGroupCountBefore(timeUs: Long): AdPlaybackState {
        var newRemovedAdGroupCount: Int
        newRemovedAdGroupCount = removedAdGroupCount
        while (newRemovedAdGroupCount < adGroupCount
        ) {
            val adGroup = getAdGroup(newRemovedAdGroupCount)
            if (timeUs <= adGroup.timeUs || adGroup.timeUs == C.TIME_END_OF_SOURCE) {
                break
            }
            newRemovedAdGroupCount++
        }
        return withRemovedAdGroupCount(newRemovedAdGroupCount)
    }

    fun withContentResumeOffsetUs(
        @IntRange(from = 0) adGroupIndex: Int, contentResumeOffsetUs: Long
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.contentResumeOffsetUs == contentResumeOffsetUs) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withContentResumeOffsetUs(contentResumeOffsetUs)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withOriginalAdCount(
        @IntRange(from = 0) adGroupIndex: Int, originalAdCount: Int
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.originalCount == originalAdCount) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withOriginalAdCount(originalAdCount)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withIsServerSideInserted(
        @IntRange(from = 0) adGroupIndex: Int, isServerSideInserted: Boolean
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        if (adGroups[adjustedIndex]!!.isServerSideInserted == isServerSideInserted) {
            return this
        }
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withIsServerSideInserted(isServerSideInserted)
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withResetAdGroup(@IntRange(from = 0) adGroupIndex: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]!!.withAllAdsReset()
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withRemovedAdsAfterIndex(
        @IntRange(from = 0) adGroupIndex: Int, @IntRange(from = 0) adIndexInAdGroup: Int
    ): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] =
            adGroups[adjustedIndex]!!.withRemovedAdsAfterIndex(adIndexInAdGroup)
        return AdPlaybackState(
            adsId,
            adGroups.toMutableList(),
            adResumePositionUs,
            contentDurationUs,
            removedAdGroupCount
        )
    }

    fun withUnavailableAdGroup(@IntRange(from = 0) adGroupIndex: Int): AdPlaybackState {
        val adjustedIndex = adGroupIndex - removedAdGroupCount
        val adGroups = this.adGroups.toMutableList()
        adGroups[adjustedIndex] = adGroups[adjustedIndex]?.withAllAdsUnavailable()
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
    }

    fun withLivePostrollPlaceholderAppended(isServerSideInserted: Boolean): AdPlaybackState {
        return withNewAdGroup(adGroupCount,  C.TIME_END_OF_SOURCE)
            .withIsPlaceholder(adGroupCount,  true, isServerSideInserted)
    }

    @VisibleForTesting
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
        return AdPlaybackState(
            adsId, adGroups, adResumePositionUs, contentDurationUs, removedAdGroupCount
        )
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

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val that = o as AdPlaybackState
        return adsId == that.adsId
                && adGroupCount == that.adGroupCount && adResumePositionUs == that.adResumePositionUs && contentDurationUs == that.contentDurationUs && removedAdGroupCount == that.removedAdGroupCount && adGroups == that.adGroups
    }

    override fun hashCode(): Int {
        var result = adGroupCount
        result = 31 * result + (if (adsId == null) 0 else adsId.hashCode())
        result = 31 * result + adResumePositionUs.toInt()
        result = 31 * result + contentDurationUs.toInt()
        result = 31 * result + removedAdGroupCount
        result = 31 * result + adGroups.contentHashCode()
        return result
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("AdPlaybackState(adsId=")
        sb.append(adsId)
        sb.append(", adResumePositionUs=")
        sb.append(adResumePositionUs)
        sb.append(", adGroups=[")
        for (i in adGroups.indices) {
            sb.append("adGroup(timeUs=")
            sb.append(adGroups[i]!!.timeUs)
            sb.append(", contentResumeOffsetUs=")
            sb.append(adGroups[i]!!.contentResumeOffsetUs)
            sb.append(", ads=[")
            for (j in adGroups[i]!!.states.indices) {
                sb.append("ad(state=")
                when (adGroups[i]!!.states[j]) {
                    AD_STATE_UNAVAILABLE -> sb.append('_')
                    AD_STATE_ERROR -> sb.append('!')
                    AD_STATE_AVAILABLE -> sb.append('R')
                    AD_STATE_PLAYED -> sb.append('P')
                    AD_STATE_SKIPPED -> sb.append('S')
                    else -> sb.append('?')
                }
                sb.append(", durationUs=")
                sb.append(adGroups[i]!!.durationsUs[j])
                sb.append(')')
                if (j < adGroups[i]!!.states.size - 1) {
                    sb.append(", ")
                }
            }
            sb.append("])")
            if (i < adGroups.size - 1) {
                sb.append(", ")
            }
        }
        sb.append("])")
        return sb.toString()
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

    init {
        adGroupCount = adGroups.size + removedAdGroupCount
    }

    // TODO(b/166765820): See if missing adsId would be okay and add adsId to the Bundle otherwise.
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

    class AdGroup internal constructor(
        timeUs: Long,
        count: Int,
        originalCount: Int,
        @AdState states: IntArray,
        mediaItems: Array<MediaItem?>,
        durationsUs: LongArray,
        contentResumeOffsetUs: Long,
        isServerSideInserted: Boolean,
        ids: Array<String?>,
        skipInfos: Array<SkipInfo?>,
        isPlaceholder: Boolean
    ) {
        val timeUs: Long
        val count: Int
        val originalCount: Int
        val mediaItems: Array<MediaItem?>

        @AdState
        val states: IntArray
        val durationsUs: LongArray
        val ids: Array<String?>
        val skipInfos: Array<SkipInfo?>
        val contentResumeOffsetUs: Long
        val isServerSideInserted: Boolean
        val isPlaceholder: Boolean

        constructor(timeUs: Long) : this(
            timeUs,
            C.LENGTH_UNSET,
            C.LENGTH_UNSET,
            IntArray(0),
            arrayOfNulls<MediaItem>(0),
            LongArray(0),
            0,
            false,
            arrayOfNulls<String>(0),
            arrayOfNulls<SkipInfo>(0),
            false
        )

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
            for (i in 0..<count) {
                if (states[i] == AD_STATE_UNAVAILABLE || states[i] == AD_STATE_AVAILABLE) {
                    return true
                }
            }
            return false
        }

        fun isLivePostrollPlaceholder(isServerSideInserted: Boolean): Boolean {
            return (this.isServerSideInserted == isServerSideInserted) && this.isLivePostrollPlaceholder
        }

        val isLivePostrollPlaceholder: Boolean
            get() = isPlaceholder && timeUs == C.TIME_END_OF_SOURCE && count == C.LENGTH_UNSET

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val adGroup = o as AdGroup
            return timeUs == adGroup.timeUs && count == adGroup.count && originalCount == adGroup.originalCount && mediaItems.contentEquals(
                adGroup.mediaItems
            ) && states.contentEquals(adGroup.states) && durationsUs.contentEquals(adGroup.durationsUs) && contentResumeOffsetUs == adGroup.contentResumeOffsetUs && isServerSideInserted == adGroup.isServerSideInserted && ids.contentEquals(
                adGroup.ids
            ) && skipInfos.contentEquals(adGroup.skipInfos) && isPlaceholder == adGroup.isPlaceholder
        }

        override fun hashCode(): Int {
            var result = count
            result = 31 * result + originalCount
            result = 31 * result + (timeUs xor (timeUs ushr 32)).toInt()
            result = 31 * result + mediaItems.contentHashCode()
            result = 31 * result + states.contentHashCode()
            result = 31 * result + durationsUs.contentHashCode()
            result =
                31 * result + (contentResumeOffsetUs xor (contentResumeOffsetUs ushr 32)).toInt()
            result = 31 * result + (if (isServerSideInserted) 1 else 0)
            result = 31 * result + ids.contentHashCode()
            result = 31 * result + skipInfos.contentHashCode()
            result = 31 * result + (if (isPlaceholder) 1 else 0)
            return result
        }

        fun withTimeUs(timeUs: Long): AdGroup {
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAdCount(count: Int): AdGroup {
            @AdState val states = copyStatesWithSpaceForAdCount(this.states, count)
            val durationsUs = copyDurationsUsWithSpaceForAdCount(this.durationsUs, count)
            val mediaItems = this.mediaItems.copyOf<MediaItem?>(count)
            val ids = this.ids.copyOf<String?>(count)
            val skipInfos = copySkipInfosWithSpaceForAdCount(this.skipInfos, count)
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAdMediaItem(mediaItem: MediaItem?, @IntRange(from = 0) index: Int): AdGroup {
            @AdState val states = copyStatesWithSpaceForAdCount(this.states, index + 1)
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
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAdState(@AdState state: @AdState Int, @IntRange(from = 0) index: Int): AdGroup {
            Preconditions.checkArgument(count == C.LENGTH_UNSET || index < count)
            @AdState val states = copyStatesWithSpaceForAdCount(this.states,  index + 1)
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
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAdDurationsUs(durationsUs: LongArray): AdGroup {
            var durationsUs = durationsUs
            if (durationsUs.size < mediaItems.size) {
                durationsUs = copyDurationsUsWithSpaceForAdCount(durationsUs, mediaItems.size)
            } else if (count != C.LENGTH_UNSET && durationsUs.size > mediaItems.size) {
                durationsUs = durationsUs.copyOf(mediaItems.size)
            }
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAdId(adId: String?, @IntRange(from = 0) index: Int): AdGroup {
            @AdState val states = copyStatesWithSpaceForAdCount(this.states, index + 1)
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
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAdSkipInfo(skipInfo: SkipInfo?, @IntRange(from = 0) index: Int): AdGroup {
            @AdState val states = copyStatesWithSpaceForAdCount(this.states, index + 1)
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
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withContentResumeOffsetUs(contentResumeOffsetUs: Long): AdGroup {
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withIsServerSideInserted(isServerSideInserted: Boolean): AdGroup {
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withOriginalAdCount(originalCount: Int): AdGroup {
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAllAdsSkipped(): AdGroup {
            if (count == C.LENGTH_UNSET) {
                return AdGroup(
                    timeUs,  
                    0,
                    originalCount,  
                    IntArray(0),  
                    arrayOfNulls<MediaItem>(0),  
                    LongArray(0),
                    contentResumeOffsetUs,
                    isServerSideInserted,
                    ids,
                    skipInfos,
                    isPlaceholder
                )
            }
            val count = this.states.size
            @AdState val states = this.states.copyOf(count)
            for (i in 0..<count) {
                if (states[i] == AD_STATE_AVAILABLE || states[i] == AD_STATE_UNAVAILABLE) {
                    states[i] = AD_STATE_SKIPPED
                }
            }
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withAllAdsReset(): AdGroup {
            if (count == C.LENGTH_UNSET) {
                return this
            }
            val count = this.states.size
            @AdState val states = this.states.copyOf(count)
            for (i in 0..<count) {
                if (states[i] == AD_STATE_PLAYED || states[i] == AD_STATE_SKIPPED || states[i] == AD_STATE_ERROR) {
                    states[i] =
                        if (mediaItems[i] == null) AD_STATE_UNAVAILABLE else AD_STATE_AVAILABLE
                }
            }
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun withRemovedAdsAfterIndex(adIndexInAdGroup: Int): AdGroup {
            Preconditions.checkArgument(adIndexInAdGroup >= 0)
            if (count == C.LENGTH_UNSET || adIndexInAdGroup >= count - 1) {
                return this
            }
            val newCount = adIndexInAdGroup + 1
            val newDurationsUs = durationsUs.copyOf(newCount)
            return AdGroup(
                timeUs,
                newCount,
                originalCount,
                states.copyOf(newCount),
                mediaItems.copyOf<MediaItem?>(newCount),
                newDurationsUs,
                if (contentResumeOffsetUs != 0L) sumOfDurations(*newDurationsUs) else 0,
                isServerSideInserted,
                ids.copyOf<String?>(newCount),
                skipInfos.copyOf<SkipInfo?>(newCount),
                isPlaceholder
            )
        }

        fun withAllAdsUnavailable(): AdGroup {
            if (count == C.LENGTH_UNSET) {
                return this
            }
            @AdState val states = IntArray(this.states.size)
            Arrays.fill(states, AD_STATE_UNAVAILABLE)
            val durationsUs = LongArray(states.size)
            Arrays.fill(durationsUs, C.TIME_UNSET)
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                arrayOfNulls<MediaItem>(count),
                durationsUs,  
                0,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        internal fun withIsPlaceholder(
            isPlaceholder: Boolean,
            isServerSideInserted: Boolean
        ): AdGroup {
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states,
                mediaItems,
                durationsUs,
                contentResumeOffsetUs,
                isServerSideInserted,
                ids,
                skipInfos,
                isPlaceholder
            )
        }

        fun getIndexOfAdId(adId: String?): Int {
            for (i in ids.indices) {
                if (ids[i] == adId) {
                    return i
                }
            }
            return C.INDEX_UNSET
        }

        fun copy(): AdGroup {
            return AdGroup(
                timeUs,
                count,
                originalCount,
                states.copyOf(states.size),
                mediaItems.copyOf<MediaItem?>(mediaItems.size),
                durationsUs.copyOf(durationsUs.size),
                contentResumeOffsetUs,
                isServerSideInserted,
                ids.copyOf<String?>(ids.size),
                skipInfos.copyOf<SkipInfo?>(skipInfos.size),
                isPlaceholder
            )
        }

        init {
            Preconditions.checkArgument(states.size == mediaItems.size)
            Preconditions.checkArgument(states.size == skipInfos.size)
            this.timeUs = timeUs
            this.count = count
            this.originalCount = originalCount
            this.states = states
            this.mediaItems = mediaItems
            this.durationsUs = durationsUs
            this.contentResumeOffsetUs = contentResumeOffsetUs
            this.isServerSideInserted = isServerSideInserted
            this.ids = ids
            this.skipInfos = skipInfos
            this.isPlaceholder = isPlaceholder
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
                java.util.ArrayList<String?>(Arrays.asList<String?>(*ids))
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
                    bundles.add(if (skipInfo == null) null else skipInfo.toBundle())
                }
                return bundles
            }

        private fun getMediaItemsArrayBundles(interfaceVersion: Int): java.util.ArrayList<Bundle?> {
            val bundles = java.util.ArrayList<Bundle?>()
            for (mediaItem in mediaItems) {
                bundles.add(
                    if (mediaItem == null)
                        null
                    else
                        mediaItem.toBundleIncludeLocalConfiguration(interfaceVersion)
                )
            }
            return bundles
        }

        companion object {
            
            @AdState
            private fun copyStatesWithSpaceForAdCount(
                @AdState states: IntArray,
                count: Int
            ): IntArray {
                var states = states
                val oldStateCount = states.size
                val newStateCount = max(count, oldStateCount)
                states = states.copyOf(newStateCount)
                Arrays.fill(states, oldStateCount, newStateCount, AD_STATE_UNAVAILABLE)
                return states
            }

            
            private fun copyDurationsUsWithSpaceForAdCount(
                durationsUs: LongArray,
                count: Int
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
                skipInfos = skipInfos.copyOf<SkipInfo?>(newSkipInfoCount)
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

            @VisibleForTesting
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
                @AdState val states = bundle.getIntArray(FIELD_STATES)
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
                    if (states == null) IntArray(0) else states,
                    getMediaItemsFromBundleArrays(mediaItemBundleList, uriList, interfaceVersion),
                    if (durationsUs == null) LongArray(0) else durationsUs,
                    contentResumeOffsetUs,
                    isServerSideInserted,
                    if (ids == null) arrayOfNulls<String>(0) else ids.toTypedArray<String?>(),
                    if (skipInfoBundleList == null)
                        arrayOfNulls<SkipInfo>(0)
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
                    return arrayOfNulls<MediaItem>(0)
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
}
