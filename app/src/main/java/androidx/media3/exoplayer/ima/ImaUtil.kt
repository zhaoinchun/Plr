package androidx.media3.exoplayer.ima

import android.content.Context
import android.os.Looper
import android.util.Pair
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CheckResult
import androidx.media3.common.AdOverlayInfo
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.ads.ServerSideAdInsertionUtil
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer
import com.google.ads.interactivemedia.v3.api.AdError
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.ads.interactivemedia.v3.api.AdEvent
import com.google.ads.interactivemedia.v3.api.AdPodInfo
import com.google.ads.interactivemedia.v3.api.AdSlot
import com.google.ads.interactivemedia.v3.api.AdsLoader
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.CompanionAdSlot
import com.google.ads.interactivemedia.v3.api.FriendlyObstruction
import com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings
import com.google.ads.interactivemedia.v3.api.UiElement
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.math.DoubleMath
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

object ImaUtil {
    val TIMEOUT_UNSET: Int = -1
    val BITRATE_UNSET: Int = -1

    fun getFriendlyObstructionPurpose(purpose: Int): FriendlyObstructionPurpose {
        when (purpose) {
            AdOverlayInfo.PURPOSE_CONTROLS -> return FriendlyObstructionPurpose.VIDEO_CONTROLS
            AdOverlayInfo.PURPOSE_CLOSE_AD -> return FriendlyObstructionPurpose.CLOSE_AD
            AdOverlayInfo.PURPOSE_NOT_VISIBLE -> return FriendlyObstructionPurpose.NOT_VISIBLE
            AdOverlayInfo.PURPOSE_OTHER -> return FriendlyObstructionPurpose.OTHER
            else -> return FriendlyObstructionPurpose.OTHER
        }
    }

    /**
     * Returns the microsecond ad group timestamps corresponding to the specified cue points.
     * 
     * @param cuePoints The cue points of the ads in seconds, provided by the IMA SDK.
     * @return The corresponding microsecond ad group timestamps.
     */
    fun getAdGroupTimesUsForCuePoints(cuePoints: MutableList<Float?>): LongArray {
        if (cuePoints.isEmpty()) {
            return longArrayOf(0L)
        }

        val count = cuePoints.size
        val adGroupTimesUs = LongArray(count)
        var adGroupIndex = 0
        for (i in 0..<count) {
            val cuePoint = cuePoints.get(i)!!.toDouble()
            if (cuePoint == -1.0) {
                adGroupTimesUs[count - 1] = C.TIME_END_OF_SOURCE
            } else {
                adGroupTimesUs[adGroupIndex++] = Math.round(C.MICROS_PER_SECOND * cuePoint)
            }
        }
        // Cue points may be out of order, so sort them.
        Arrays.sort(adGroupTimesUs, 0, adGroupIndex)
        return adGroupTimesUs
    }

    /** Returns an [AdsRequest] based on the specified ad tag [DataSpec].  */
    @Throws(IOException::class)
    fun getAdsRequestForAdTagDataSpec(
        imaFactory: ImaFactory, adTagDataSpec: DataSpec
    ): AdsRequest {
        val request: AdsRequest = imaFactory.createAdsRequest()
        if (DataSchemeDataSource.SCHEME_DATA == adTagDataSpec.uri.getScheme()) {
            val dataSchemeDataSource: DataSchemeDataSource = DataSchemeDataSource()
            try {
                dataSchemeDataSource.open(adTagDataSpec)
                request.setAdsResponse(
                    Util.fromUtf8Bytes(
                        DataSourceUtil.readToEnd(
                            dataSchemeDataSource
                        )
                    )
                )
            } finally {
                dataSchemeDataSource.close()
            }
        } else {
            request.setAdTagUrl(adTagDataSpec.uri.toString())
        }
        return request
    }

    /** Returns whether the ad error indicates that an entire ad group failed to load.  */
    fun isAdGroupLoadError(adError: AdError): Boolean {
        // TODO: Find out what other errors need to be handled (if any), and whether each one relates to
        // a single ad, ad group or the whole timeline.
        return adError.getErrorCode() === AdError.AdErrorCode.VAST_LINEAR_ASSET_MISMATCH
                || adError.getErrorCode() === AdError.AdErrorCode.UNKNOWN_ERROR
    }

    val imaLooper: Looper
        /** Returns the looper on which all IMA SDK interaction must occur.  */
        get() =// IMA SDK callbacks occur on the main thread. This method can be used to check that the player
            // is using the same looper, to ensure all interaction with this class is on the main thread.
            Looper.getMainLooper()

    /** Returns a human-readable representation of a video progress update.  */
    // VideoProgressUpdate.equals() is annotated as hidden.
    fun getStringForVideoProgressUpdate(videoProgressUpdate: VideoProgressUpdate): String {
        if (VideoProgressUpdate.VIDEO_TIME_NOT_READY.equals(videoProgressUpdate)) {
            return "not ready"
        } else {
            return Util.formatInvariant(
                "%d ms of %d ms",
                videoProgressUpdate.getCurrentTimeMs(), videoProgressUpdate.getDurationMs()
            )
        }
    }

    /**
     * Expands a placeholder ad group with a single ad to the requested number of ads and sets the
     * duration of the inserted ad.
     * 
     * 
     * The remaining ad group duration is propagated to the ad following the inserted ad. If the
     * inserted ad is the last ad, the remaining ad group duration is wrapped around to the first ad
     * in the group.
     * 
     * @param adGroupIndex The ad group index of the ad group to expand.
     * @param adIndexInAdGroup The ad index to set the duration.
     * @param adDurationUs The duration of the ad.
     * @param adGroupDurationUs The duration of the whole ad group.
     * @param adsInAdGroupCount The number of ads of the ad group.
     * @param adPlaybackState The ad playback state to modify.
     * @return The updated ad playback state.
     */
    @CheckResult
    fun expandAdGroupPlaceholder(
        adGroupIndex: Int,
        adGroupDurationUs: Long,
        adIndexInAdGroup: Int,
        adDurationUs: Long,
        adsInAdGroupCount: Int,
        adPlaybackState: AdPlaybackState
    ): AdPlaybackState {
        Preconditions.checkArgument(adIndexInAdGroup < adsInAdGroupCount)
        val adDurationsUs =
            updateAdDurationAndPropagate(
                LongArray(adsInAdGroupCount), adIndexInAdGroup, adDurationUs, adGroupDurationUs
            )
        return adPlaybackState
            .withAdCount(adGroupIndex, adDurationsUs.size)
            .withAdDurationsUs(adGroupIndex, *adDurationsUs)
    }

    /**
     * Updates the duration of an ad in and ad group.
     * 
     * 
     * The difference of the previous duration and the updated duration is propagated to the ad
     * following the updated ad. If the updated ad is the last ad, the remaining duration is wrapped
     * around to the first ad in the group.
     * 
     * 
     * The remaining ad duration is only propagated if the destination ad has a duration of 0.
     * 
     * @param adGroupIndex The ad group index of the ad group to expand.
     * @param adIndexInAdGroup The ad index to set the duration.
     * @param adDurationUs The duration of the ad.
     * @param adPlaybackState The ad playback state to modify.
     * @return The updated ad playback state.
     */
    @CheckResult
    fun updateAdDurationInAdGroup(
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        adDurationUs: Long,
        adPlaybackState: AdPlaybackState
    ): AdPlaybackState {
        val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
        Preconditions.checkArgument(adIndexInAdGroup < adGroup.durationsUs.size)
        val adDurationsUs =
            updateAdDurationAndPropagate(
                adGroup.durationsUs.copyOf(adGroup.durationsUs.size),
                adIndexInAdGroup,
                adDurationUs,
                adGroup.durationsUs[adIndexInAdGroup]
            )
        return adPlaybackState.withAdDurationsUs(adGroupIndex, *adDurationsUs)
    }

    /**
     * Updates the duration of the given ad in the array.
     * 
     * 
     * The remaining difference when subtracting `adDurationUs` from `remainingDurationUs` is used as the duration of the next ad after `adIndex`. If the
     * updated ad is the last ad, the remaining duration is wrapped around to the first ad of the
     * group.
     * 
     * 
     * The remaining ad duration is only propagated if the destination ad has a duration of 0.
     * 
     * @param adDurationsUs The array to edit.
     * @param adIndex The index of the ad in the durations array.
     * @param adDurationUs The new ad duration.
     * @param remainingDurationUs The remaining ad duration before updating the new ad duration.
     * @return The updated input array, for convenience.
     */
    private fun updateAdDurationAndPropagate(
        adDurationsUs: LongArray, adIndex: Int, adDurationUs: Long, remainingDurationUs: Long
    ): LongArray {
        adDurationsUs[adIndex] = adDurationUs
        val nextAdIndex = (adIndex + 1) % adDurationsUs.size
        if (adDurationsUs[nextAdIndex] == 0L) {
            // Propagate the remaining duration to the next ad.
            adDurationsUs[nextAdIndex] = max(0, remainingDurationUs - adDurationUs)
        }
        return adDurationsUs
    }

    /**
     * Gets the window start in microseconds since the Unix epoch for a window of a [ ] of the `DashMediaSource`.
     * 
     * @param windowStartTimeMs The window start time, in milliseconds.
     * @param positionInFirstPeriodUs The position of the window in the first period.
     * @return The window start time, in microseconds.
     */
    fun getWindowStartTimeUs(windowStartTimeMs: Long, positionInFirstPeriodUs: Long): Long {
        // Revert us/ms truncation introduced in `DashMediaSource.DashTimeline`.
        return Util.msToUs(windowStartTimeMs) + (positionInFirstPeriodUs % 1000)
    }

    /**
     * Splits an [AdPlaybackState] into a separate [AdPlaybackState] for each period of a
     * content timeline.
     * 
     * 
     * If a period is enclosed by an ad group, the period is considered an ad period. Splitting
     * results in a separate [ad playback state][AdPlaybackState] for each period that has either
     * no ads or a single ad. In the latter case, the duration of the single ad is set to the duration
     * of the period consuming the entire duration of the period. Accordingly an ad period does not
     * contribute to the duration of the containing window.
     * 
     * @param adPlaybackState The ad playback state to be split.
     * @param contentTimeline The content timeline for each period of which to create an [     ].
     * @return A map of ad playback states for each period UID in the content timeline.
     */
    fun splitAdPlaybackStateForPeriods(
        adPlaybackState: AdPlaybackState, contentTimeline: Timeline
    ): ImmutableMap<Any?, AdPlaybackState?> {
        Preconditions.checkArgument(!contentTimeline.isEmpty)
        val period = Timeline.Period()
        val window: Timeline.Window = contentTimeline.getWindow(0, Timeline.Window())
        var periodIndex = 0
        var totalElapsedContentDurationUs: Long = 0
        val adsId = Preconditions.checkNotNull<Any?>(adPlaybackState.adsId)
        var contentOnlyAdPlaybackState: AdPlaybackState = AdPlaybackState(adsId)
        if (window.isLive) {
            val windowStartTimeUs =
                getWindowStartTimeUs(window.windowStartTimeMs, window.positionInFirstPeriodUs)
            totalElapsedContentDurationUs = windowStartTimeUs - window.positionInFirstPeriodUs
            contentOnlyAdPlaybackState =
                contentOnlyAdPlaybackState.withLivePostrollPlaceholderAppended( /* isServerSideInserted= */
                    true
                )
        }
        val adPlaybackStates: MutableMap<Any?, AdPlaybackState?> = HashMap<Any?, AdPlaybackState?>()
        for (i in adPlaybackState.removedAdGroupCount..<adPlaybackState.adGroupCount) {
            val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup( /* adGroupIndex= */i)
            var mappedAdCount = 0
            if (adGroup.timeUs == C.TIME_END_OF_SOURCE) {
                Preconditions.checkState(i == adPlaybackState.adGroupCount - 1)
                // The last ad group is a placeholder for a potential post roll. We can just stop here.
                break
            }
            // The ad group start timeUs is in content position. We need to add the ad
            // duration before the ad group to translate the start time to the position in the period.
            val adGroupDurationUs: Long = adGroup.durationsUs.sum()
            var elapsedAdGroupAdDurationUs: Long = 0
            for (j in periodIndex..<contentTimeline.periodCount) {
                contentTimeline.getPeriod(j, period,  /* setIds= */true)
                if (totalElapsedContentDurationUs < adGroup.timeUs) {
                    // Period starts before the ad group, so it is a content period.
                    adPlaybackStates.put(
                        Preconditions.checkNotNull<Any?>(period.uid),
                        contentOnlyAdPlaybackState
                    )
                    totalElapsedContentDurationUs += period.durationUs
                    // Current period added as a content period. Advance and look at the next period.
                    periodIndex++
                } else {
                    val periodStartUs = totalElapsedContentDurationUs + elapsedAdGroupAdDurationUs
                    val periodDurationUs = period.durationUs
                    if ((periodDurationUs != C.TIME_UNSET
                                && periodStartUs + periodDurationUs <= adGroup.timeUs + adGroupDurationUs)
                        || (periodDurationUs == C.TIME_UNSET && elapsedAdGroupAdDurationUs < adGroupDurationUs && periodStartUs < adGroup.timeUs + adGroupDurationUs)
                    ) {
                        // Ad period found. The period either ends before the end of the ad group, or it is the
                        // last period of a live stream and it starts in the ad group.
                        adPlaybackStates.put(
                            Preconditions.checkNotNull<Any?>(period.uid),
                            splitAdGroupForPeriod(
                                adsId, adGroup, periodStartUs, periodDurationUs, window.isLive
                            )
                        )
                        // Current period added as an ad period. Advance and look at the next period.
                        periodIndex++
                        mappedAdCount++
                        elapsedAdGroupAdDurationUs += periodDurationUs
                        if ((adGroup.originalCount > adGroup.count && adGroup.count == mappedAdCount)
                            || periodStartUs + periodDurationUs == adGroup.timeUs + adGroupDurationUs
                        ) {
                            // Periods have consumed the ad group. We're at the end of the ad group.
                            if (window.isLive) {
                                // Add elapsed ad duration to elapsed content duration for live streams to account
                                // for the content resume offset (relevant because we above compare against
                                // `adGroup.timeUs`). Instead of `adGroup.contentResumeOffsetUs` we use
                                // `elapsedAdGroupAdDurationUs` that is the sum of the actual period durations.
                                totalElapsedContentDurationUs += elapsedAdGroupAdDurationUs
                            }
                            // Continue with next ad group.
                            break
                        }
                    } else {
                        // Period is after the current ad group. Continue with next ad group.
                        break
                    }
                }
            }
        }
        // The remaining periods end after the last ad group, so these are content periods.
        for (i in periodIndex..<contentTimeline.periodCount) {
            contentTimeline.getPeriod(i, period,  /* setIds= */true)
            adPlaybackStates.put(
                Preconditions.checkNotNull<Any?>(period.uid),
                contentOnlyAdPlaybackState
            )
        }
        return ImmutableMap.copyOf<Any?, AdPlaybackState?>(adPlaybackStates)
    }

    private fun splitAdGroupForPeriod(
        adsId: Any?,
        adGroup: AdPlaybackState.AdGroup,
        periodStartUs: Long,
        periodDurationUs: Long,
        isLiveStream: Boolean
    ): AdPlaybackState {
        var adPlaybackState: AdPlaybackState =
            AdPlaybackState(adsId!!, listOf(AdPlaybackState.AdGroup(0)))
                .withIsServerSideInserted( /* adGroupIndex= */0, true)
                .withAdCount( /* adGroupIndex= */0,  /* adCount= */1)
        if (isLiveStream) {
            adPlaybackState =
                adPlaybackState.withLivePostrollPlaceholderAppended( /* isServerSideInserted= */true)
        }
        var adGroupDurationUs: Long = 0
        for (i in 0..<adGroup.count) {
            val sanitizedDurationUs: Long =
                (if (periodDurationUs != androidx.media3.common.C.TIME_UNSET) periodDurationUs else adGroup.durationsUs[i])!!
            val periodEndUs = periodStartUs + sanitizedDurationUs
            adGroupDurationUs += adGroup.durationsUs[i]
            // TODO(bachinger): Remove margin constant by making sure the VOD ad group times are adjusted
            //   to the actual DASH timeline periods.
            if (periodEndUs <= adGroup.timeUs + adGroupDurationUs + 10000) {
                adPlaybackState =
                    adPlaybackState
                        .withAdDurationsUs( /* adGroupIndex= */0, sanitizedDurationUs)
                        .withContentResumeOffsetUs( /* adGroupIndex= */
                            0, if (isLiveStream) sanitizedDurationUs else 0
                        )
                // Map the state of the global ad state to the period specific ad state.
                when (adGroup.states[i]) {
                    AdPlaybackState.Companion.AD_STATE_AVAILABLE -> adPlaybackState =
                        adPlaybackState.withAvailableAd( /* adGroupIndex= */0,  /* adIndexInAdGroup= */
                            0
                        )

                    AdPlaybackState.AD_STATE_PLAYED -> adPlaybackState =
                        adPlaybackState.withPlayedAd( /* adGroupIndex= */0,  /* adIndexInAdGroup= */
                            0
                        )

                    AdPlaybackState.AD_STATE_SKIPPED -> adPlaybackState =
                        adPlaybackState.withSkippedAd( /* adGroupIndex= */0,  /* adIndexInAdGroup= */
                            0
                        )

                    AdPlaybackState.AD_STATE_ERROR -> adPlaybackState =
                        adPlaybackState.withAdLoadError( /* adGroupIndex= */0,  /* adIndexInAdGroup= */
                            0
                        )

                    else -> {}
                }
                break
            }
        }
        return adPlaybackState
    }

    /**
     * Updates previously inserted ad durations with actual period durations from the timeline and
     * returns the updated [ad playback state][AdPlaybackState].
     * 
     * 
     * This method must only be called for multi period live streams and is useful in the case that
     * [a live ad has been][.addLiveAdBreak] while the duration of the corresponding period was still unknown. In this case the
     * [estimated ad duration][Ad.getDuration] was used which must be corrected as soon as
     * the live window of the `contentTimeline` advances and the previously unknown period
     * duration is available.
     * 
     * 
     * Roughly, the logic checks whether an ad group of the ad playback state fits in or overlaps
     * one or several periods in the content timeline. Starting at the first ad inside the window, the
     * ad duration is set to the duration of the corresponding period until a period with an unknown
     * duration or the end of the ad group is reached.
     * 
     * 
     * If the previously playing ad period isn't available in the content timeline anymore, no
     * correction is applied. The resulting position discontinuity of [ ][Player.DISCONTINUITY_REASON_REMOVE] needs to be handled accordingly elsewhere.
     * 
     * @param contentTimeline The live content timeline.
     * @param adPlaybackState The ad playback state.
     * @return The (potentially) updated ad playback state.
     */
    fun maybeCorrectPreviouslyUnknownAdDurations(
        contentTimeline: Timeline, adPlaybackState: AdPlaybackState
    ): AdPlaybackState {
        var adPlaybackState: AdPlaybackState = adPlaybackState
        val window: Timeline.Window =
            contentTimeline.getWindow( /* windowIndex= */0, Timeline.Window())
        if (window.firstPeriodIndex == window.lastPeriodIndex || adPlaybackState.adGroupCount < 2) {
            // Single period with unknown duration, or only a postroll placeholder.
            return adPlaybackState
        }
        val period = Timeline.Period()
        var lastPeriodIndex = window.lastPeriodIndex
        if (contentTimeline.getPeriod(lastPeriodIndex, period).durationUs == C.TIME_UNSET) {
            lastPeriodIndex--
            contentTimeline.getPeriod(lastPeriodIndex, period)
        }
        // Search for an unplayed ad group at or before the period start.
        val windowStartTimeUs =
            getWindowStartTimeUs(window.windowStartTimeMs, window.positionInFirstPeriodUs)
        val lastCompletePeriodStartTimeUs = windowStartTimeUs + period.positionInWindowUs
        val adGroupIndex: Int =
            adPlaybackState.getAdGroupIndexForPositionUs(
                lastCompletePeriodStartTimeUs,  /* periodDurationUs= */C.TIME_UNSET
            )
        if (adGroupIndex == C.INDEX_UNSET) {
            // No unplayed ads before the last period with a duration. Nothing to do.
            return adPlaybackState
        }
        val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)

        var periodStartTimeUs = windowStartTimeUs - window.positionInFirstPeriodUs
        if (adGroup.timeUs + adGroup.contentResumeOffsetUs <= periodStartTimeUs) {
            // Ad group ends before first period in window. Discontinuity of reason REMOVE.
            return adPlaybackState
        }
        // The ads at the start of the ad group may be out of the window already. Skip them.
        var firstAdIndexInWindow = 0
        var adStartTimeUs: Long = adGroup.timeUs
        while (adStartTimeUs < periodStartTimeUs) {
            if (adGroup.states[firstAdIndexInWindow] == AdPlaybackState.Companion.AD_STATE_AVAILABLE) {
                // The previously available ad is not in the timeline anymore. Discontinuity of reason
                // `DISCONTINUITY_REASON_REMOVE`.
                return adPlaybackState
            }
            // Skip ad before first period of window.
            adStartTimeUs += adGroup.durationsUs[firstAdIndexInWindow++]
        }
        var firstPeriodIndexInAdGroup = C.INDEX_UNSET
        for (i in window.firstPeriodIndex..lastPeriodIndex) {
            if (adGroup.timeUs <= periodStartTimeUs) {
                firstPeriodIndexInAdGroup = i
                break
            }
            periodStartTimeUs += contentTimeline.getPeriod( /* periodIndex= */i, period).durationUs
        }
        Preconditions.checkState(firstPeriodIndexInAdGroup != C.INDEX_UNSET)

        // Update all ad durations that we know and are not yet correct.
        for (i in firstAdIndexInWindow..<adGroup.durationsUs.size) {
            val adPeriodIndex = firstPeriodIndexInAdGroup + (i - firstAdIndexInWindow)
            if (adPeriodIndex > lastPeriodIndex) {
                break
            }
            contentTimeline.getPeriod(adPeriodIndex, period)
            if (period.durationUs != adGroup.durationsUs[i]) {
                // Set the ad duration to the period duration.
                adPlaybackState =
                    updateAdDurationInAdGroup(
                        adGroupIndex,  /* adIndexInAdGroup= */i, period.durationUs, adPlaybackState
                    )
            }
        }
        // Get the ad group again and set the new content resume offset after update.
        val adGroupDurationUs: Long = adPlaybackState.getAdGroup(adGroupIndex).durationsUs.sum()
        return adPlaybackState.withContentResumeOffsetUs(adGroupIndex, adGroupDurationUs)
    }

    /**
     * Returns the sum of the durations of all the ads in an ad pod, in microseconds.
     * 
     * 
     * If all periods are contained in the current window and have a known duration, the sum of the
     * period durations is returned. If this is not possible, [the][AdPodInfo.getMaxDuration] is used instead.
     * 
     * 
     * Do not use this method with public timelines of VOD multi-period streams that have the ad
     * duration subtracted from period durations.
     * 
     * @param timeline The timeline.
     * @param adPodInfo The [AdPodInfo] from the SDK event.
     * @param adPeriodIndex The period index of the period corresponding to the [     ][AdPodInfo.getAdPosition].
     * @param window The window to be populated as the window containing the ad period.
     * @param period The period to be populated as the ad period.
     * @return The duration of the ad group, in microseconds.
     */
    fun getAdGroupDurationUsForLiveAdPeriodIndex(
        timeline: Timeline,
        adPodInfo: AdPodInfo,
        adPeriodIndex: Int,
        window: Timeline.Window,
        period: Timeline.Period
    ): Long {
        timeline.getPeriod(adPeriodIndex, period)
        timeline.getWindow(period.windowIndex, window)
        Preconditions.checkArgument(window.isLive)
        val adIndexInAdGroup: Int = adPodInfo.getAdPosition() - 1
        val firstAdPeriodIndex = adPeriodIndex - adIndexInAdGroup
        val lastAdPeriodIndex: Int =
            adPeriodIndex + (adPodInfo.getTotalAds() - adIndexInAdGroup - 1)
        var adGroupDurationUs = C.TIME_UNSET
        if (window.firstPeriodIndex <= firstAdPeriodIndex
            && lastAdPeriodIndex < window.lastPeriodIndex
        ) {
            adGroupDurationUs = 0L
            val currentPeriod = Timeline.Period()
            for (periodIndex in firstAdPeriodIndex..lastAdPeriodIndex) {
                val durationUs: Long = timeline.getPeriod(periodIndex, currentPeriod).durationUs
                if (durationUs == C.TIME_UNSET) {
                    adGroupDurationUs = C.TIME_UNSET
                    break
                }
                adGroupDurationUs += durationUs
            }
        }
        return if (adGroupDurationUs != C.TIME_UNSET)
            adGroupDurationUs
        else
            secToUsRounded(adPodInfo.getMaxDuration())
    }

    /**
     * Handles the case when an ad period has been removed from the timeline while being played.
     * 
     * 
     * This makes sure no unplayed ads are left in the ad playback state after buffering or after
     * the player has been paused in a live stream. In such a case periods may be removed from the
     * timeline before playback transitions to the next period automatically.
     * 
     * @param currentPeriodIndex The index of the [current][Player.getCurrentPeriodIndex].
     * @param timeline The timeline containing the current period.
     * @param adPlaybackState The ad playback state to query.
     * @return The updated ad playback state.
     */
    @CheckResult
    fun handleAdPeriodRemovedFromTimeline(
        currentPeriodIndex: Int, timeline: Timeline, adPlaybackState: AdPlaybackState
    ): AdPlaybackState {
        var adPlaybackState: AdPlaybackState = adPlaybackState
        val currentPeriod: Timeline.Period =
            timeline.getPeriod(currentPeriodIndex, Timeline.Period())
        val window: Timeline.Window =
            timeline.getWindow(currentPeriod.windowIndex, Timeline.Window())
        val currentPeriodStartTimeUs =
            (getWindowStartTimeUs(window.windowStartTimeMs, window.positionInFirstPeriodUs)
                    + currentPeriod.positionInWindowUs)
        val adGroupIndex: Int =
            adPlaybackState.getAdGroupIndexForPositionUs(currentPeriodStartTimeUs, C.TIME_UNSET)
        if (adGroupIndex != C.INDEX_UNSET) {
            var adGroup = adPlaybackState.getAdGroup(adGroupIndex)
            if (adGroup.timeUs + adGroup.contentResumeOffsetUs <= currentPeriodStartTimeUs) {
                // current period starts after ad group.
                return markAdGroupAsPlayed(
                    adGroupIndex,  /* preserveDurations= */
                    true,
                    adPlaybackState
                )
            }
            var lastAvailableAdIndex = C.INDEX_UNSET
            var adGroupDurationUs: Long = 0
            var i = 0
            while (i < adGroup.states.size) {
                val state: Int = adGroup.states[i]
                if (state == AdPlaybackState.Companion.AD_STATE_AVAILABLE) {
                    lastAvailableAdIndex = i
                }
                if (currentPeriodStartTimeUs <= adGroup.timeUs + adGroupDurationUs) {
                    if (currentPeriodStartTimeUs == adGroup.timeUs + adGroupDurationUs) {
                        // Found the ad matching the current period.
                        if (state == AdPlaybackState.Companion.AD_STATE_AVAILABLE || state == AdPlaybackState.Companion.AD_STATE_PLAYED) {
                            return adPlaybackState
                        }
                        if (state == AdPlaybackState.Companion.AD_STATE_UNAVAILABLE && lastAvailableAdIndex == i - 1) {
                            // First unavailable ad in the group.
                            if (currentPeriod.durationUs == C.TIME_UNSET) {
                                // All ad data available except the last period. Will be corrected as normal.
                                return adPlaybackState
                            }
                            // Else we can set the duration in the ad group as if we received the ad event.
                            adPlaybackState =
                                updateAdDurationInAdGroup(
                                    adGroupIndex,  /* adIndexInAdGroup= */
                                    i,  /* adDurationUs= */
                                    currentPeriod.durationUs,
                                    adPlaybackState
                                )
                            adGroup = adPlaybackState.getAdGroup(adGroupIndex)
                            return adPlaybackState.withContentResumeOffsetUs(
                                adGroupIndex, adGroup.durationsUs.sum()
                            )
                        }
                    }
                    // We can't calculate the index position of the current period within the ad group after
                    // an ad period has been removed from the timeline. Give up.
                    adPlaybackState =
                        markAdGroupAsPlayed(
                            adGroupIndex,  /* preserveDurations= */
                            false,
                            adPlaybackState
                        )
                    // Future SDK events belonging to the skipped ad group are handled in #addLiveAdBreak()
                    // like when joining the live stream while an ad is playing and will insert a new separate
                    // partial ad group.
                    if (currentPeriod.durationUs != C.TIME_UNSET) {
                        // Insert the current ad as a single ad group instead of the skipped ad group.
                        return addLiveAdBreak(
                            currentPeriodStartTimeUs,  /* adDurationUs= */
                            currentPeriod.durationUs,  /* adPositionInAdPod= */
                            1,  /* totalAdDurationUs= */
                            currentPeriod.durationUs,  /* totalAdsInAdPod= */
                            1,
                            adPlaybackState
                        )
                    }
                    return adPlaybackState
                }
                if (state == AdPlaybackState.Companion.AD_STATE_AVAILABLE || state == AdPlaybackState.Companion.AD_STATE_UNAVAILABLE) {
                    adPlaybackState =
                        adPlaybackState.withSkippedAd(adGroupIndex,  /* adIndexInAdGroup= */i)
                }
                adGroupDurationUs += adGroup.durationsUs[i]
                i++
            }
        }
        return adPlaybackState
    }

    private fun markAdGroupAsPlayed(
        adGroupIndex: Int, preserveDurations: Boolean, adPlaybackState: AdPlaybackState
    ): AdPlaybackState {
        var adPlaybackState: AdPlaybackState = adPlaybackState
        val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
        val newDurationsUs = LongArray(adGroup.durationsUs.size)
        for (adIndex in newDurationsUs.indices) {
            newDurationsUs[adIndex] = if (preserveDurations) adGroup.durationsUs[adIndex] else 0
            if (adGroup.states[adIndex] == AdPlaybackState.Companion.AD_STATE_AVAILABLE
                || adGroup.states[adIndex] == AdPlaybackState.Companion.AD_STATE_UNAVAILABLE
            ) {
                adPlaybackState =
                    adPlaybackState.withSkippedAd(adGroupIndex,  /* adIndexInAdGroup= */adIndex)
            }
        }
        return adPlaybackState
            .withAdDurationsUs(adGroupIndex, *newDurationsUs)
            .withContentResumeOffsetUs(adGroupIndex, newDurationsUs.sum())
    }

    /**
     * Returns the `adGroupIndex` in the ad playback state corresponding to the given period
     * index in the timeline and the `adIndexInAdGroup` of the first unplayed ad in that ad
     * group.
     * 
     * @param adPeriodIndex The index of the ad period in the timeline.
     * @param adPlaybackState The ad playback state that holds the ad group and ad information.
     * @param timeline The single-window timeline that contains the period for the ad period index.
     * @return A pair with the ad group index (first) and the ad index in that ad group (second).
     * @exception IllegalStateException If no unplayed ad is found before or at the start time of the
     * ad period.
     */
    fun getAdGroupAndIndexInLiveMultiPeriodTimeline(
        adPeriodIndex: Int, adPlaybackState: AdPlaybackState, timeline: Timeline
    ): Pair<Int?, Int?> {
        val window: Timeline.Window = timeline.getWindow( /* windowIndex= */0, Timeline.Window())
        Preconditions.checkArgument(window.isLive)
        val period = Timeline.Period()
        timeline.getPeriod(adPeriodIndex, period,  /* setIds= */true)
        val adPeriodStartTimeUs =
            (getWindowStartTimeUs(window.windowStartTimeMs, window.positionInFirstPeriodUs)
                    + period.positionInWindowUs)
        val adGroupIndex: Int =
            adPlaybackState.getAdGroupIndexForPositionUs(adPeriodStartTimeUs, C.TIME_UNSET)
        if (adGroupIndex != C.INDEX_UNSET) {
            val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
            for (k in adGroup.states.indices) {
                if (adGroup.states[k] == AdPlaybackState.Companion.AD_STATE_AVAILABLE || adGroup.states[k] == AdPlaybackState.Companion.AD_STATE_UNAVAILABLE) {
                    return Pair<Int?, Int?>( /* adGroupIndex= */adGroupIndex,  /* adIndexInAdGroup= */
                        k
                    )
                }
            }
        }
        throw IllegalStateException(
            String.format(
                "No unplayed ad group found before or at the start time us %d of the period with index"
                        + " %d",
                adPeriodStartTimeUs, adPeriodIndex
            )
        )
    }

    /**
     * Returns the `adGroupIndex` and the `adIndexInAdGroup` for the given period index of
     * an ad period in a VOD multi-period timeline.
     * 
     * @param adPeriodIndex The period index of the ad period.
     * @param adPlaybackState The ad playback state that holds the ad group and ad information.
     * @param timeline The single-window timeline that contains the ad period.
     * @return A pair with the ad group index (first) and the ad index in that ad group (second).
     */
    fun getAdGroupAndIndexInVodMultiPeriodTimeline(
        adPeriodIndex: Int, adPlaybackState: AdPlaybackState, timeline: Timeline
    ): Pair<Int?, Int?> {
        val window: Timeline.Window = timeline.getWindow( /* windowIndex= */0, Timeline.Window())
        Preconditions.checkArgument(timeline.windowCount == 1)
        var periodIndex = 0
        var totalElapsedContentDurationUs: Long = 0
        if (window.isLive) {
            val windowStartTimeUs =
                getWindowStartTimeUs(window.windowStartTimeMs, window.positionInFirstPeriodUs)
            totalElapsedContentDurationUs = windowStartTimeUs - window.positionInFirstPeriodUs
        }
        val period = Timeline.Period()
        for (i in adPlaybackState.removedAdGroupCount..<adPlaybackState.adGroupCount) {
            var adIndexInAdGroup = 0
            val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup( /* adGroupIndex= */i)
            val adGroupDurationUs: Long = adGroup.durationsUs.sum()
            var elapsedAdGroupAdDurationUs: Long = 0
            for (j in periodIndex..<min(timeline.periodCount, adPeriodIndex + 1)) {
                timeline.getPeriod(j, period,  /* setIds= */true)
                if (totalElapsedContentDurationUs < adGroup.timeUs) {
                    // Period starts before the ad group, so it is a content period.
                    totalElapsedContentDurationUs += period.durationUs
                } else {
                    val periodStartUs = totalElapsedContentDurationUs + elapsedAdGroupAdDurationUs
                    if (periodStartUs + period.durationUs <= adGroup.timeUs + adGroupDurationUs) {
                        // The period ends before the end of the ad group, so it is an ad period.
                        if (j == adPeriodIndex) {
                            return Pair<Int?, Int?>( /* adGroupIndex= */i, adIndexInAdGroup)
                        }
                        elapsedAdGroupAdDurationUs += period.durationUs
                        adIndexInAdGroup++
                    } else {
                        // Period is after the current ad group. Continue with next ad group.
                        totalElapsedContentDurationUs +=
                            min(elapsedAdGroupAdDurationUs, adGroup.contentResumeOffsetUs)
                        break
                    }
                }
                // Increment the period index to the next unclassified period.
                periodIndex++
            }
        }
        throw IllegalStateException()
    }

    /**
     * Called when the SDK emits a `LOADED` event of an IMA SSAI live stream.
     * 
     * 
     * For each ad, the SDK emits a `LOADED` event at the start of the ad. The `LOADED`
     * event provides the information of a certain ad (index and duration) and its ad pod (number of
     * ads and total ad duration) that is mapped to an ad in an [ad group][AdGroup] of an
     * [ad playback state][AdPlaybackState] to reflect ads in the ExoPlayer media structure.
     * 
     * 
     * In the normal case (when all ad information is available completely and in time), the
     * life-cycle of a live ad group and its ads has these phases:
     * 
     * 
     *  1. When playing content and a `LOADED` event arrives, an ad group is inserted at the
     * current position with the number of ads reported by the ad pod. The duration of the first
     * ad is set and its state is set to [AdPlaybackState.AD_STATE_AVAILABLE]. The
     * duration of the 2nd ad is set to the remaining duration of the total ad group duration.
     * This pads out the duration of the ad group, so it doesn't end before the next ad event
     * arrives. When inserting the ad group at the current position, the player immediately
     * advances to play the inserted ad period.
     *  1. When playing an ad group and a further `LOADED` event arrives, the ad state is
     * inspected to find the [ad group currently being played][AdPlaybackState.getAdGroupIndexForPositionUs]. We query for the first [       ][AdPlaybackState.AD_STATE_UNAVAILABLE] of that ad group, override its
     * placeholder duration, mark it [available][AdPlaybackState.AD_STATE_AVAILABLE]
     * and propagate the remainder of the placeholder duration to the next ad. Repeating this
     * step until all ads are configured and marked as available.
     *  1. When playing an ad and a `LOADED` event arrives but no more ads are in [       ][AdPlaybackState.AD_STATE_UNAVAILABLE], the group is expanded by inserting a new ad at the
     * end of the ad group.
     *  1. After playing an ad: When playback exits from an ad period to the next ad or back to
     * content, [ImaServerSideAdInsertionMediaSource] detects [       ][Player.Listener.onPositionDiscontinuity], identifies [the][Player.PositionInfo.adIndexInAdGroup] and [marks the ad as played][AdPlaybackState.AD_STATE_PLAYED].
     * 
     * 
     * 
     * Some edge-cases need consideration. When a user joins a live stream during an ad being
     * played, ad information previous to the first received `LOADED` event is missing. Only ads
     * starting from the first ad with full information are inserted into the group (back to happy
     * path step 2).
     * 
     * 
     * There is further a chance, that a (pre-fetch) event arrives after the ad group has already
     * ended. In such a case, the pre-fetch ad starts a new ad group with the remaining ads in the
     * same way as the during-ad-joiner case that can afterwards be expanded again (back to end of
     * happy path step 2).
     * 
     * @param currentContentPeriodPositionUs The current public content position, in microseconds.
     * @param adDurationUs The duration of the ad to be inserted, in microseconds.
     * @param adPositionInAdPod The ad position in the ad pod (Note: starts with index 1).
     * @param totalAdDurationUs The total duration of all ads as declared by the ad pod.
     * @param totalAdsInAdPod The total number of ads declared by the ad pod.
     * @param adPlaybackState The ad playback state with the current ad information.
     * @return The updated [AdPlaybackState].
     */
    @CheckResult
    fun addLiveAdBreak(
        currentContentPeriodPositionUs: Long,
        adDurationUs: Long,
        adPositionInAdPod: Int,
        totalAdDurationUs: Long,
        totalAdsInAdPod: Int,
        adPlaybackState: AdPlaybackState
    ): AdPlaybackState {
        var adPlaybackState: AdPlaybackState = adPlaybackState
        Preconditions.checkArgument(adPositionInAdPod > 0)
        val mediaPeriodPositionUs: Long =
            ServerSideAdInsertionUtil.getMediaPeriodPositionUsForContent(
                currentContentPeriodPositionUs,  /* nextAdGroupIndex= */
                C.INDEX_UNSET,
                adPlaybackState
            )
        // TODO(b/217187518) Support seeking backwards.
        var adGroupIndex: Int =
            adPlaybackState.getAdGroupIndexForPositionUs(
                mediaPeriodPositionUs,  /* periodDurationUs= */C.TIME_UNSET
            )
        if (adGroupIndex == C.INDEX_UNSET) {
            val adIndexInAdGroup = adPositionInAdPod - 1
            val adDurationsUs =
                updateAdDurationAndPropagate(
                    LongArray(totalAdsInAdPod - adIndexInAdGroup),  /* adIndex= */
                    0,
                    adDurationUs,
                    totalAdDurationUs
                )
            adPlaybackState =
                ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState(
                    adPlaybackState,  /* fromPositionUs= */
                    currentContentPeriodPositionUs,  /* contentResumeOffsetUs= */
                    adDurationsUs.sum(),  /* adDurationsUs...= */
                    adDurationsUs
                )
            adGroupIndex =
                adPlaybackState.getAdGroupIndexForPositionUs(
                    mediaPeriodPositionUs,  /* periodDurationUs= */C.TIME_UNSET
                )
            if (adGroupIndex != C.INDEX_UNSET) {
                adPlaybackState =
                    adPlaybackState
                        .withAvailableAd(adGroupIndex,  /* adIndexInAdGroup= */0)
                        .withOriginalAdCount(adGroupIndex,  /* originalAdCount= */totalAdsInAdPod)
            }
        } else {
            val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
            var newDurationsUs: LongArray = adGroup.durationsUs.copyOf(adGroup.count)
            val nextUnavailableAdIndex = getNextUnavailableAdIndex(adGroup)
            if (adGroup.originalCount < totalAdsInAdPod || nextUnavailableAdIndex == adGroup.count) {
                val adInAdGroupCount = max(totalAdsInAdPod, nextUnavailableAdIndex + 1)
                adPlaybackState =
                    adPlaybackState
                        .withAdCount(adGroupIndex, adInAdGroupCount)
                        .withOriginalAdCount(adGroupIndex,  /* originalAdCount= */adInAdGroupCount)
                newDurationsUs = newDurationsUs.copyOf(adInAdGroupCount)
                newDurationsUs[nextUnavailableAdIndex] = totalAdDurationUs
                Arrays.fill(
                    newDurationsUs,  /* fromIndex= */
                    nextUnavailableAdIndex + 1,  /* toIndex= */
                    adInAdGroupCount,  /* val= */
                    0L
                )
            }
            val remainingDurationUs = max(adDurationUs, newDurationsUs[nextUnavailableAdIndex])
            updateAdDurationAndPropagate(
                newDurationsUs, nextUnavailableAdIndex, adDurationUs, remainingDurationUs
            )
            adPlaybackState =
                adPlaybackState
                    .withAdDurationsUs(adGroupIndex, *newDurationsUs)
                    .withAvailableAd(adGroupIndex, nextUnavailableAdIndex)
                    .withContentResumeOffsetUs(adGroupIndex, newDurationsUs.sum())
        }
        return adPlaybackState
    }

    /**
     * Splits the ad group at an available ad at a given split index.
     * 
     * 
     * When splitting, the ads from and after the split index are removed from the existing ad
     * group. Then the ad events of all removed available ads are replicated to get the exact same
     * result as if the new ad group was created by SDK ad events.
     * 
     * @param adGroup The ad group to split.
     * @param adGroupIndex The index of the ad group in the ad playback state.
     * @param splitIndexExclusive The first index that should be part of the newly created ad group.
     * @param adPlaybackState The ad playback state to modify.
     * @return The ad playback state with the split ad group.
     */
    @CheckResult
    fun splitAdGroup(
        adGroup: AdPlaybackState.AdGroup,
        adGroupIndex: Int,
        splitIndexExclusive: Int,
        adPlaybackState: AdPlaybackState
    ): AdPlaybackState {
        var adPlaybackState: AdPlaybackState = adPlaybackState
        Preconditions.checkArgument(splitIndexExclusive > 0 && splitIndexExclusive < adGroup.count)
        // Remove the ads from the ad group.
        adPlaybackState =
            adPlaybackState.withRemovedAdsAfterIndex(
                adGroupIndex,  /* adIndexInAdGroup= */splitIndexExclusive - 1
            )
        val previousAdGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
        val newAdGroupTimeUs: Long = previousAdGroup.timeUs + previousAdGroup.contentResumeOffsetUs
        // Replicate ad events for each available ad that has been removed.
        val removedStates: IntArray =
            Arrays.copyOfRange(adGroup.states, splitIndexExclusive, adGroup.count)
        val removedDurationsUs: LongArray =
            Arrays.copyOfRange(adGroup.durationsUs, splitIndexExclusive, adGroup.count)
        var remainingAdDurationUs: Long = removedDurationsUs.sum()
        var i = 0
        while (i < removedStates.size && removedStates[i] == AdPlaybackState.Companion.AD_STATE_AVAILABLE) {
            adPlaybackState =
                addLiveAdBreak(
                    newAdGroupTimeUs,  /* adDurationUs= */
                    removedDurationsUs[i],  /* adPositionInAdPod= */
                    i + 1,  /* totalAdDurationUs= */
                    remainingAdDurationUs,  /* totalAdsInAdPod= */
                    removedDurationsUs.size,
                    adPlaybackState
                )
            remainingAdDurationUs -= removedDurationsUs[i]
            i++
        }
        return adPlaybackState
    }

    private fun getNextUnavailableAdIndex(adGroup: AdPlaybackState.AdGroup): Int {
        for (i in adGroup.states.indices) {
            if (adGroup.states[i] == AdPlaybackState.Companion.AD_STATE_UNAVAILABLE) {
                return i
            }
        }
        return adGroup.states.size
    }

    /**
     * Converts a time in seconds to the corresponding time in microseconds.
     * 
     * 
     * Fractional values are rounded to the nearest microsecond using [RoundingMode.HALF_UP].
     * 
     * @param timeSec The time in seconds.
     * @return The corresponding time in microseconds.
     */
    fun secToUsRounded(timeSec: Double): Long {
        return DoubleMath.roundToLong(
            BigDecimal.valueOf(timeSec).scaleByPowerOfTen(6).toDouble(), RoundingMode.HALF_UP
        )
    }

    /**
     * Converts a time in seconds to the corresponding time in milliseconds.
     * 
     * 
     * Fractional values are rounded to the nearest millisecond using [RoundingMode.HALF_UP].
     * 
     * @param timeSec The time in seconds.
     * @return The corresponding time in milliseconds.
     */
    fun secToMsRounded(timeSec: Double): Long {
        return DoubleMath.roundToLong(
            BigDecimal.valueOf(timeSec).scaleByPowerOfTen(3).toDouble(), RoundingMode.HALF_UP
        )
    }

    /** Factory for objects provided by the IMA SDK.  */
    interface ImaFactory {
        /** Creates [ImaSdkSettings] for configuring the IMA SDK.  */
        fun createImaSdkSettings(): ImaSdkSettings

        /**
         * Creates [AdsRenderingSettings] for giving the [AdsManager] parameters that
         * control rendering of ads.
         */
        fun createAdsRenderingSettings(): AdsRenderingSettings

        /**
         * Creates an [AdDisplayContainer] to hold the player for video ads, a container for
         * non-linear ads, and slots for companion ads.
         */
        fun createAdDisplayContainer(
            container: ViewGroup,
            player: VideoAdPlayer
        ): AdDisplayContainer

        /** Creates an [AdDisplayContainer] to hold the player for audio ads.  */
        fun createAudioAdDisplayContainer(
            context: Context,
            player: VideoAdPlayer
        ): AdDisplayContainer

        /**
         * Creates a [FriendlyObstruction] to describe an obstruction considered "friendly" for
         * viewability measurement purposes.
         */
        fun createFriendlyObstruction(
            view: View,
            friendlyObstructionPurpose: FriendlyObstructionPurpose,
            reasonDetail: String?
        ): FriendlyObstruction

        /** Creates an [AdsRequest] to contain the data used to request ads.  */
        fun createAdsRequest(): AdsRequest

        /** Creates an [AdsLoader] for requesting ads using the specified settings.  */
        fun createAdsLoader(
            context: Context,
            imaSdkSettings: ImaSdkSettings,
            adDisplayContainer: AdDisplayContainer
        ): AdsLoader
    }

    /** Stores configuration for ad loading and playback.  */
    class Configuration(
        val adPreloadTimeoutMs: Long,
        val vastLoadTimeoutMs: Int,
        val mediaLoadTimeoutMs: Int,
        val focusSkipButtonWhenAvailable: Boolean,
        val playAdBeforeStartPosition: Boolean,
        enableCustomTabs: Boolean,
        val mediaBitrate: Int,
        val enableContinuousPlayback: Boolean?,
        val adMediaMimeTypes: MutableList<String?>?,
        adUiElements: MutableSet<UiElement?>?,
        companionAdSlots: MutableCollection<CompanionAdSlot?>?,
        applicationAdErrorListener: AdErrorEvent.AdErrorListener?,
        applicationAdEventListener: AdEvent.AdEventListener?,
        applicationVideoAdPlayerCallback: VideoAdPlayer.VideoAdPlayerCallback?,
        imaSdkSettings: ImaSdkSettings?,
        debugModeEnabled: Boolean
    ) {
        val enableCustomTabs: Boolean
        val adUiElements: MutableSet<UiElement?>?
        val companionAdSlots: MutableCollection<CompanionAdSlot?>?
        val applicationAdErrorListener: AdErrorEvent.AdErrorListener?
        val applicationAdEventListener: AdEvent.AdEventListener?
        val applicationVideoAdPlayerCallback: VideoAdPlayer.VideoAdPlayerCallback?
        val imaSdkSettings: ImaSdkSettings?
        val debugModeEnabled: Boolean

        init {
            this.adUiElements = adUiElements
            this.companionAdSlots = companionAdSlots
            this.applicationAdErrorListener = applicationAdErrorListener
            this.applicationAdEventListener = applicationAdEventListener
            this.applicationVideoAdPlayerCallback = applicationVideoAdPlayerCallback
            this.imaSdkSettings = imaSdkSettings
            this.debugModeEnabled = debugModeEnabled
            this.enableCustomTabs = enableCustomTabs
        }
    }

    /** Stores configuration for playing server side ad insertion content.  */
    class ServerSideAdInsertionConfiguration(
        adViewProvider: AdViewProvider,
        imaSdkSettings: ImaSdkSettings,
        streamEventListener: ImaServerSideAdInsertionMediaSource.StreamEventListener,
        applicationAdEventListener: AdEvent.AdEventListener?,
        applicationAdErrorListener: AdErrorEvent.AdErrorListener?,
        companionAdSlots: MutableList<CompanionAdSlot?>,
        pauseAdSlot: AdSlot?,
        focusSkipButtonWhenAvailable: Boolean,
        enableCustomTabs: Boolean,
        debugModeEnabled: Boolean
    ) {
        val adViewProvider: AdViewProvider
        val imaSdkSettings: ImaSdkSettings
        val streamEventListener: ImaServerSideAdInsertionMediaSource.StreamEventListener
        val applicationAdEventListener: AdEvent.AdEventListener?
        val applicationAdErrorListener: AdErrorEvent.AdErrorListener?
        val companionAdSlots: ImmutableList<CompanionAdSlot?>
        val pauseAdSlot: AdSlot?
        val focusSkipButtonWhenAvailable: Boolean
        val enableCustomTabs: Boolean
        val debugModeEnabled: Boolean

        init {
            this.imaSdkSettings = imaSdkSettings
            this.adViewProvider = adViewProvider
            this.streamEventListener = streamEventListener
            this.applicationAdEventListener = applicationAdEventListener
            this.applicationAdErrorListener = applicationAdErrorListener
            this.companionAdSlots = ImmutableList.copyOf<CompanionAdSlot?>(companionAdSlots)
            this.pauseAdSlot = pauseAdSlot
            this.focusSkipButtonWhenAvailable = focusSkipButtonWhenAvailable
            this.enableCustomTabs = enableCustomTabs
            this.debugModeEnabled = debugModeEnabled
        }
    }
}
