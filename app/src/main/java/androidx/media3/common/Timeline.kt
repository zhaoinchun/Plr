package androidx.media3.common

import android.net.Uri
import android.os.Bundle
import android.util.Pair
import androidx.media3.common.MediaItem.LiveConfiguration
import androidx.media3.common.Player.RepeatMode
import androidx.media3.common.util.Util.getNowUnixTimeMs
import androidx.media3.common.util.Util.intToStringMaxRadix
import androidx.media3.common.util.Util.usToMs
import com.google.common.base.Preconditions
import com.google.common.collect.Maps
import kotlin.math.max
import kotlin.math.min

abstract class Timeline protected constructor() {
    class Window {
        var uid: Any
        var mediaItem: MediaItem
        var manifest: Any? = null
        var presentationStartTimeMs: Long = 0
        var windowStartTimeMs: Long = 0
        var elapsedRealtimeEpochOffsetMs: Long = 0
        var isSeekable: Boolean = false
        var isDynamic: Boolean = false
        var liveConfiguration: LiveConfiguration? = null
        var isPlaceholder: Boolean = false
        var defaultPositionUs: Long = 0
        var durationUs: Long = 0
        var firstPeriodIndex: Int = 0
        var lastPeriodIndex: Int = 0
        var positionInFirstPeriodUs: Long = 0
        var tag: Any? = null

        fun set(
            uid: Any,
            mediaItem: MediaItem?,
            manifest: Any?,
            presentationStartTimeMs: Long,
            windowStartTimeMs: Long,
            elapsedRealtimeEpochOffsetMs: Long,
            isSeekable: Boolean,
            isDynamic: Boolean,
            liveConfiguration: LiveConfiguration?,
            defaultPositionUs: Long,
            durationUs: Long,
            firstPeriodIndex: Int,
            lastPeriodIndex: Int,
            positionInFirstPeriodUs: Long
        ): Window {
            this.uid = uid
            this.mediaItem = mediaItem ?: PLACEHOLDER_MEDIA_ITEM
            tag = mediaItem?.localConfiguration?.tag
            this.manifest = manifest
            this.presentationStartTimeMs = presentationStartTimeMs
            this.windowStartTimeMs = windowStartTimeMs
            this.elapsedRealtimeEpochOffsetMs = elapsedRealtimeEpochOffsetMs
            this.isSeekable = isSeekable
            this.isDynamic = isDynamic
            this.liveConfiguration = liveConfiguration
            this.defaultPositionUs = defaultPositionUs
            this.durationUs = durationUs
            this.firstPeriodIndex = firstPeriodIndex
            this.lastPeriodIndex = lastPeriodIndex
            this.positionInFirstPeriodUs = positionInFirstPeriodUs
            this.isPlaceholder = false
            return this
        }

        val defaultPositionMs: Long
            get() = usToMs(defaultPositionUs)
        val durationMs: Long
            get() = usToMs(durationUs)
        val positionInFirstPeriodMs: Long
            get() = usToMs(positionInFirstPeriodUs)
        val currentUnixTimeMs: Long
            get() = getNowUnixTimeMs(elapsedRealtimeEpochOffsetMs)
        val isLive: Boolean
            get() = liveConfiguration != null

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null || javaClass != other.javaClass) {
                return false
            }
            val that = other as Window
            return uid == that.uid
                    && mediaItem == that.mediaItem
                    && manifest == that.manifest
                    && liveConfiguration == that.liveConfiguration
                    && presentationStartTimeMs == that.presentationStartTimeMs && windowStartTimeMs == that.windowStartTimeMs && elapsedRealtimeEpochOffsetMs == that.elapsedRealtimeEpochOffsetMs && isSeekable == that.isSeekable && isDynamic == that.isDynamic && isPlaceholder == that.isPlaceholder && defaultPositionUs == that.defaultPositionUs && durationUs == that.durationUs && firstPeriodIndex == that.firstPeriodIndex && lastPeriodIndex == that.lastPeriodIndex && positionInFirstPeriodUs == that.positionInFirstPeriodUs
        }

        override fun hashCode(): Int {
            var result = 7
            result = 31 * result + uid.hashCode()
            result = 31 * result + mediaItem.hashCode()
            result = 31 * result + (manifest?.hashCode() ?: 0)
            result =
                31 * result + (liveConfiguration?.hashCode() ?: 0)
            result =
                31 * result + (presentationStartTimeMs xor (presentationStartTimeMs ushr 32)).toInt()
            result = 31 * result + (windowStartTimeMs xor (windowStartTimeMs ushr 32)).toInt()
            result =
                (31 * result
                        + (elapsedRealtimeEpochOffsetMs xor (elapsedRealtimeEpochOffsetMs ushr 32)).toInt())
            result = 31 * result + (if (isSeekable) 1 else 0)
            result = 31 * result + (if (isDynamic) 1 else 0)
            result = 31 * result + (if (isPlaceholder) 1 else 0)
            result = 31 * result + (defaultPositionUs xor (defaultPositionUs ushr 32)).toInt()
            result = 31 * result + (durationUs xor (durationUs ushr 32)).toInt()
            result = 31 * result + firstPeriodIndex
            result = 31 * result + lastPeriodIndex
            result =
                31 * result + (positionInFirstPeriodUs xor (positionInFirstPeriodUs ushr 32)).toInt()
            return result
        }

        init {
            uid = SINGLE_WINDOW_UID
            mediaItem = PLACEHOLDER_MEDIA_ITEM
        }

        fun toBundle(interfaceVersion: Int): Bundle {
            val bundle = Bundle()
            if (MediaItem.EMPTY != mediaItem) {
                bundle.putBundle(FIELD_MEDIA_ITEM, mediaItem.toBundle(interfaceVersion))
            }
            if (presentationStartTimeMs != C.TIME_UNSET) {
                bundle.putLong(FIELD_PRESENTATION_START_TIME_MS, presentationStartTimeMs)
            }
            if (windowStartTimeMs != C.TIME_UNSET) {
                bundle.putLong(FIELD_WINDOW_START_TIME_MS, windowStartTimeMs)
            }
            if (elapsedRealtimeEpochOffsetMs != C.TIME_UNSET) {
                bundle.putLong(FIELD_ELAPSED_REALTIME_EPOCH_OFFSET_MS, elapsedRealtimeEpochOffsetMs)
            }
            if (isSeekable) {
                bundle.putBoolean(FIELD_IS_SEEKABLE, isSeekable)
            }
            if (isDynamic) {
                bundle.putBoolean(FIELD_IS_DYNAMIC, isDynamic)
            }

            val liveConfiguration = this.liveConfiguration
            if (liveConfiguration != null) {
                bundle.putBundle(FIELD_LIVE_CONFIGURATION, liveConfiguration.toBundle())
            }
            if (isPlaceholder) {
                bundle.putBoolean(FIELD_IS_PLACEHOLDER, isPlaceholder)
            }
            if (defaultPositionUs != 0L) {
                bundle.putLong(FIELD_DEFAULT_POSITION_US, defaultPositionUs)
            }
            if (durationUs != C.TIME_UNSET) {
                bundle.putLong(FIELD_DURATION_US, durationUs)
            }
            if (firstPeriodIndex != 0) {
                bundle.putInt(FIELD_FIRST_PERIOD_INDEX, firstPeriodIndex)
            }
            if (lastPeriodIndex != 0) {
                bundle.putInt(FIELD_LAST_PERIOD_INDEX, lastPeriodIndex)
            }
            if (positionInFirstPeriodUs != 0L) {
                bundle.putLong(FIELD_POSITION_IN_FIRST_PERIOD_US, positionInFirstPeriodUs)
            }
            if (uid is String) {
                bundle.putString(FIELD_UID, uid as String)
            }
            return bundle
        }

        companion object {
            val SINGLE_WINDOW_UID: Any = Any()

            private val PLACEHOLDER_MEDIA_ITEM = MediaItem.Builder()
                .setMediaId("androidx.media3.common.Timeline")
                .setUri(Uri.EMPTY)
                .build()

            private val FIELD_MEDIA_ITEM = intToStringMaxRadix(1)
            private val FIELD_PRESENTATION_START_TIME_MS = intToStringMaxRadix(2)
            private val FIELD_WINDOW_START_TIME_MS = intToStringMaxRadix(3)
            private val FIELD_ELAPSED_REALTIME_EPOCH_OFFSET_MS = intToStringMaxRadix(4)
            private val FIELD_IS_SEEKABLE = intToStringMaxRadix(5)
            private val FIELD_IS_DYNAMIC = intToStringMaxRadix(6)
            private val FIELD_LIVE_CONFIGURATION = intToStringMaxRadix(7)
            private val FIELD_IS_PLACEHOLDER = intToStringMaxRadix(8)
            private val FIELD_DEFAULT_POSITION_US = intToStringMaxRadix(9)
            private val FIELD_DURATION_US = intToStringMaxRadix(10)
            private val FIELD_FIRST_PERIOD_INDEX = intToStringMaxRadix(11)
            private val FIELD_LAST_PERIOD_INDEX = intToStringMaxRadix(12)
            private val FIELD_POSITION_IN_FIRST_PERIOD_US = intToStringMaxRadix(13)
            private val FIELD_UID = intToStringMaxRadix(14)

            fun fromBundle(bundle: Bundle, interfaceVersion: Int): Window {
                val mediaItemBundle = bundle.getBundle(FIELD_MEDIA_ITEM)
                val mediaItem: MediaItem? =
                    if (mediaItemBundle != null)
                        MediaItem.fromBundle(mediaItemBundle, interfaceVersion)
                    else
                        MediaItem.EMPTY
                val presentationStartTimeMs =
                    bundle.getLong(FIELD_PRESENTATION_START_TIME_MS, C.TIME_UNSET)
                val windowStartTimeMs =
                    bundle.getLong(FIELD_WINDOW_START_TIME_MS, C.TIME_UNSET)
                val elapsedRealtimeEpochOffsetMs =
                    bundle.getLong(FIELD_ELAPSED_REALTIME_EPOCH_OFFSET_MS, C.TIME_UNSET)
                val isSeekable = bundle.getBoolean(FIELD_IS_SEEKABLE, false)
                val isDynamic = bundle.getBoolean(FIELD_IS_DYNAMIC, false)
                val liveConfigurationBundle = bundle.getBundle(FIELD_LIVE_CONFIGURATION)
                val liveConfiguration =
                    if (liveConfigurationBundle != null)
                        LiveConfiguration.fromBundle(liveConfigurationBundle)
                    else
                        null
                val isPlaceHolder =
                    bundle.getBoolean(FIELD_IS_PLACEHOLDER, false)
                val defaultPositionUs =
                    bundle.getLong(FIELD_DEFAULT_POSITION_US, 0)
                val durationUs = bundle.getLong(FIELD_DURATION_US, C.TIME_UNSET)
                val firstPeriodIndex =
                    bundle.getInt(FIELD_FIRST_PERIOD_INDEX, 0)
                val lastPeriodIndex = bundle.getInt(FIELD_LAST_PERIOD_INDEX, 0)
                val positionInFirstPeriodUs =
                    bundle.getLong(FIELD_POSITION_IN_FIRST_PERIOD_US, 0)
                val uid = bundle.getString(FIELD_UID) ?: SINGLE_WINDOW_UID

                val window = Window()
                window.set(
                    uid,
                    mediaItem,
                    null,
                    presentationStartTimeMs,
                    windowStartTimeMs,
                    elapsedRealtimeEpochOffsetMs,
                    isSeekable,
                    isDynamic,
                    liveConfiguration,
                    defaultPositionUs,
                    durationUs,
                    firstPeriodIndex,
                    lastPeriodIndex,
                    positionInFirstPeriodUs
                )
                window.isPlaceholder = isPlaceHolder
                return window
            }
        }
    }

    class Period {
        var id: Any? = null
        var uid: Any? = null

        var windowIndex: Int = 0
        var durationUs: Long = 0
        var positionInWindowUs: Long = 0
        var isPlaceholder: Boolean = false
        var adPlaybackState: AdPlaybackState

        fun set(
            id: Any?,
            uid: Any?,
            windowIndex: Int,
            durationUs: Long,
            positionInWindowUs: Long,
            adPlaybackState: AdPlaybackState,
            isPlaceholder: Boolean
        ): Period {
            this.id = id
            this.uid = uid
            this.windowIndex = windowIndex
            this.durationUs = durationUs
            this.positionInWindowUs = positionInWindowUs
            this.adPlaybackState = adPlaybackState
            this.isPlaceholder = isPlaceholder
            return this
        }

        val durationMs: Long
            get() = usToMs(durationUs)
        val positionInWindowMs: Long
            get() = usToMs(positionInWindowUs)
        val adsId: Any?
            get() = adPlaybackState.adsId
        val adGroupCount: Int
            get() = adPlaybackState.adGroupCount
        val removedAdGroupCount: Int
            get() = adPlaybackState.removedAdGroupCount

        fun getAdGroupTimeUs(adGroupIndex: Int): Long {
            return adPlaybackState.getAdGroup(adGroupIndex).timeUs
        }

        fun getFirstAdIndexToPlay(adGroupIndex: Int): Int {
            return adPlaybackState.getAdGroup(adGroupIndex).firstAdIndexToPlay
        }

        fun getNextAdIndexToPlay(adGroupIndex: Int, lastPlayedAdIndex: Int): Int {
            return adPlaybackState.getAdGroup(adGroupIndex).getNextAdIndexToPlay(lastPlayedAdIndex)
        }

        fun hasPlayedAdGroup(adGroupIndex: Int): Boolean {
            return !adPlaybackState.getAdGroup(adGroupIndex).hasUnplayedAds()
        }

        fun getAdGroupIndexForPositionUs(positionUs: Long): Int {
            return adPlaybackState.getAdGroupIndexForPositionUs(positionUs, durationUs)
        }

        fun getAdGroupIndexAfterPositionUs(positionUs: Long): Int {
            return adPlaybackState.getAdGroupIndexAfterPositionUs(positionUs, durationUs)
        }

        fun getAdCountInAdGroup(adGroupIndex: Int): Int {
            return adPlaybackState.getAdGroup(adGroupIndex).count
        }

        fun getAdDurationUs(adGroupIndex: Int, adIndexInAdGroup: Int): Long {
            val adGroup = adPlaybackState.getAdGroup(adGroupIndex)
            return if (adGroup.count != C.LENGTH_UNSET) adGroup.durationsUs[adIndexInAdGroup] else C.TIME_UNSET
        }

        fun getAdState(adGroupIndex: Int, adIndexInAdGroup: Int): Int {
            val adGroup = adPlaybackState.getAdGroup(adGroupIndex)
            return if (adGroup.count != C.LENGTH_UNSET) {
                adGroup.states[adIndexInAdGroup]
            } else {
                AdPlaybackState.AD_STATE_UNAVAILABLE
            }
        }

        fun isLivePostrollPlaceholder(adGroupIndex: Int): Boolean {
            return adGroupIndex == this.adGroupCount - 1
                    && adPlaybackState.isLivePostrollPlaceholder(adGroupIndex)
        }

        val adResumePositionUs: Long
            get() = adPlaybackState.adResumePositionUs

        fun isServerSideInsertedAdGroup(adGroupIndex: Int): Boolean {
            return adPlaybackState.getAdGroup(adGroupIndex).isServerSideInserted
        }

        fun getContentResumeOffsetUs(adGroupIndex: Int): Long {
            return adPlaybackState.getAdGroup(adGroupIndex).contentResumeOffsetUs
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null || javaClass != other.javaClass) {
                return false
            }
            val that = other as Period
            return id == that.id
                    && uid == that.uid
                    && windowIndex == that.windowIndex && durationUs == that.durationUs && positionInWindowUs == that.positionInWindowUs && isPlaceholder == that.isPlaceholder && adPlaybackState == that.adPlaybackState
        }

        override fun hashCode(): Int {
            var result = 7
            result = 31 * result + (id?.hashCode() ?: 0)
            result = 31 * result + (uid?.hashCode() ?: 0)
            result = 31 * result + windowIndex
            result = 31 * result + (durationUs xor (durationUs ushr 32)).toInt()
            result = 31 * result + (positionInWindowUs xor (positionInWindowUs ushr 32)).toInt()
            result = 31 * result + (if (isPlaceholder) 1 else 0)
            result = 31 * result + adPlaybackState.hashCode()
            return result
        }

        init {
            adPlaybackState = AdPlaybackState.NONE
        }

        fun toBundle(interfaceVersion: Int): Bundle {
            val bundle = Bundle()
            if (windowIndex != 0) {
                bundle.putInt(FIELD_WINDOW_INDEX, windowIndex)
            }
            if (durationUs != C.TIME_UNSET) {
                bundle.putLong(FIELD_DURATION_US, durationUs)
            }
            if (positionInWindowUs != 0L) {
                bundle.putLong(FIELD_POSITION_IN_WINDOW_US, positionInWindowUs)
            }
            if (isPlaceholder) {
                bundle.putBoolean(FIELD_PLACEHOLDER, isPlaceholder)
            }
            if (adPlaybackState != AdPlaybackState.NONE) {
                bundle.putBundle(
                    FIELD_AD_PLAYBACK_STATE,
                    adPlaybackState.toBundle(interfaceVersion)
                )
            }
            if (id is String) {
                bundle.putString(FIELD_ID, id as String)
            }
            if (uid is String) {
                bundle.putString(FIELD_UID, uid as String)
            }
            return bundle
        }

        companion object {
            private val FIELD_WINDOW_INDEX = intToStringMaxRadix(0)
            private val FIELD_DURATION_US = intToStringMaxRadix(1)
            private val FIELD_POSITION_IN_WINDOW_US = intToStringMaxRadix(2)
            private val FIELD_PLACEHOLDER = intToStringMaxRadix(3)
            private val FIELD_AD_PLAYBACK_STATE = intToStringMaxRadix(4)
            private val FIELD_ID = intToStringMaxRadix(5)
            private val FIELD_UID = intToStringMaxRadix(6)

            fun fromBundle(bundle: Bundle, interfaceVersion: Int): Period {
                val windowIndex = bundle.getInt(FIELD_WINDOW_INDEX, 0)
                val durationUs = bundle.getLong(FIELD_DURATION_US, C.TIME_UNSET)
                val positionInWindowUs =
                    bundle.getLong(FIELD_POSITION_IN_WINDOW_US, 0)
                val isPlaceholder = bundle.getBoolean(FIELD_PLACEHOLDER, false)
                val adPlaybackStateBundle = bundle.getBundle(FIELD_AD_PLAYBACK_STATE)
                val adPlaybackState =
                    if (adPlaybackStateBundle != null) {
                        AdPlaybackState.fromBundle(adPlaybackStateBundle, interfaceVersion)
                    } else {
                        AdPlaybackState.NONE
                    }

                val id: Any? = bundle.getString(FIELD_ID)
                val uid: Any? = bundle.getString(FIELD_UID)

                val period = Period()
                period.set(
                    id,
                    uid,
                    windowIndex,
                    durationUs,
                    positionInWindowUs,
                    adPlaybackState,
                    isPlaceholder
                )
                return period
            }
        }
    }

    val isEmpty: Boolean
        get() = this.windowCount == 0
    abstract val windowCount: Int

    open fun getNextWindowIndex(
        windowIndex: Int, @RepeatMode repeatMode: @RepeatMode Int, shuffleModeEnabled: Boolean
    ): Int {
        when (repeatMode) {
            Player.REPEAT_MODE_OFF ->
                return if (windowIndex == getLastWindowIndex(shuffleModeEnabled)
                ) {
                    C.INDEX_UNSET
                } else {
                    windowIndex + 1
                }

            Player.REPEAT_MODE_ONE -> return windowIndex
            Player.REPEAT_MODE_ALL ->
                return if (windowIndex == getLastWindowIndex(shuffleModeEnabled)) {
                    getFirstWindowIndex(shuffleModeEnabled)
                } else {
                    windowIndex + 1
                }

            else -> throw IllegalStateException()
        }
    }

    open fun getPreviousWindowIndex(
        windowIndex: Int, @RepeatMode repeatMode: @RepeatMode Int, shuffleModeEnabled: Boolean
    ): Int {
        when (repeatMode) {
            Player.REPEAT_MODE_OFF ->
                return if (windowIndex == getFirstWindowIndex(shuffleModeEnabled)) {
                    C.INDEX_UNSET
                } else {
                    windowIndex - 1
                }

            Player.REPEAT_MODE_ONE -> return windowIndex
            Player.REPEAT_MODE_ALL ->
                return if (windowIndex == getFirstWindowIndex(shuffleModeEnabled)) {
                    getLastWindowIndex(shuffleModeEnabled)
                } else {
                    windowIndex - 1
                }

            else -> throw IllegalStateException()
        }
    }

    open fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int {
        return if (this.isEmpty) C.INDEX_UNSET else this.windowCount - 1
    }

    open fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int {
        return if (this.isEmpty) C.INDEX_UNSET else 0
    }

    fun getWindow(windowIndex: Int, window: Window): Window {
        return getWindow(windowIndex, window, 0)
    }

    abstract fun getWindow(
        windowIndex: Int, window: Window, defaultPositionProjectionUs: Long
    ): Window

    abstract val periodCount: Int

    fun getNextPeriodIndex(
        periodIndex: Int,
        period: Period?,
        window: Window,
        @RepeatMode repeatMode: @RepeatMode Int,
        shuffleModeEnabled: Boolean
    ): Int {
        val windowIndex = getPeriod(periodIndex, period!!).windowIndex
        if (getWindow(windowIndex, window).lastPeriodIndex == periodIndex) {
            val nextWindowIndex = getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
            if (nextWindowIndex == C.INDEX_UNSET) {
                return C.INDEX_UNSET
            }
            return getWindow(nextWindowIndex, window).firstPeriodIndex
        }
        return periodIndex + 1
    }

    fun isLastPeriod(
        periodIndex: Int,
        period: Period?,
        window: Window,
        @RepeatMode repeatMode: @RepeatMode Int,
        shuffleModeEnabled: Boolean
    ): Boolean {
        return (getNextPeriodIndex(periodIndex, period, window, repeatMode, shuffleModeEnabled)
                == C.INDEX_UNSET)
    }

    fun getPeriodPositionUs(
        window: Window, period: Period, windowIndex: Int, windowPositionUs: Long
    ): Pair<Any, Long> {
        return getPeriodPositionUs(
            window, period, windowIndex, windowPositionUs, 0
        )!!
    }

    fun getPeriodPositionUs(
        window: Window,
        period: Period,
        windowIndex: Int,
        windowPositionUs: Long,
        defaultPositionProjectionUs: Long
    ): Pair<Any, Long>? {
        var windowPositionUs = windowPositionUs
        Preconditions.checkElementIndex(windowIndex, this.windowCount)
        getWindow(windowIndex, window, defaultPositionProjectionUs)
        if (windowPositionUs == C.TIME_UNSET) {
            windowPositionUs = window.defaultPositionUs
            if (windowPositionUs == C.TIME_UNSET) {
                return null
            }
        }
        var periodIndex = window.firstPeriodIndex
        getPeriod(periodIndex, period)
        while (periodIndex < window.lastPeriodIndex && period.positionInWindowUs != windowPositionUs && getPeriod(
                periodIndex + 1,
                period
            ).positionInWindowUs <= windowPositionUs
        ) {
            periodIndex++
        }
        getPeriod(periodIndex, period, true)
        var periodPositionUs = windowPositionUs - period.positionInWindowUs
        // The period positions must be less than the period duration, if it is known.
        if (period.durationUs != C.TIME_UNSET) {
            periodPositionUs = min(periodPositionUs, period.durationUs - 1)
        }
        // Period positions cannot be negative.
        periodPositionUs = max(0, periodPositionUs)
        return Pair.create<Any, Long>(period.uid!!, periodPositionUs)
    }

    open fun getPeriodByUid(periodUid: Any, period: Period): Period {
        return getPeriod(getIndexOfPeriod(periodUid), period, true)
    }

    fun getPeriod(periodIndex: Int, period: Period): Period {
        return getPeriod(periodIndex, period, false)
    }

    abstract fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period

    abstract fun getIndexOfPeriod(uid: Any): Int

    abstract fun getUidOfPeriod(periodIndex: Int): Any

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Timeline) {
            return false
        }
        if (other.windowCount != this.windowCount || other.periodCount != this.periodCount) {
            return false
        }
        val window = Window()
        val period = Period()
        val otherWindow = Window()
        val otherPeriod = Period()
        for (i in 0..<this.windowCount) {
            if (getWindow(i, window) != other.getWindow(i, otherWindow)) {
                return false
            }
        }
        for (i in 0..<this.periodCount) {
            if (getPeriod(i, period, true)
                != other.getPeriod(i, otherPeriod, true)
            ) {
                return false
            }
        }

        // Check shuffled order
        var windowIndex = getFirstWindowIndex(true)
        if (windowIndex != other.getFirstWindowIndex(true)) {
            return false
        }
        val lastWindowIndex = getLastWindowIndex(true)
        if (lastWindowIndex != other.getLastWindowIndex(true)) {
            return false
        }
        while (windowIndex != lastWindowIndex) {
            val nextWindowIndex =
                getNextWindowIndex(
                    windowIndex,
                    Player.REPEAT_MODE_OFF,
                    true
                )
            if (nextWindowIndex
                != other.getNextWindowIndex(
                    windowIndex, Player.REPEAT_MODE_OFF, true
                )
            ) {
                return false
            }
            windowIndex = nextWindowIndex
        }

        return true
    }

    override fun hashCode(): Int {
        val window = Window()
        val period = Period()
        var result = 7
        result = 31 * result + this.windowCount
        for (i in 0..<this.windowCount) {
            result = 31 * result + getWindow(i, window).hashCode()
        }
        result = 31 * result + this.periodCount
        for (i in 0..<this.periodCount) {
            result = 31 * result + getPeriod(i, period, true).hashCode()
        }

        var windowIndex = getFirstWindowIndex(true)
        while (windowIndex != C.INDEX_UNSET) {
            result = 31 * result + windowIndex
            windowIndex = getNextWindowIndex(windowIndex, Player.REPEAT_MODE_OFF, true)
        }

        return result
    }

    fun toBundle(interfaceVersion: Int): Bundle {
        val windowBundles = mutableListOf<Bundle>()
        val windowCount = this.windowCount
        val window = Window()
        for (i in 0..<windowCount) {
            windowBundles.add(
                getWindow(
                    i,
                    window,
                    0
                ).toBundle(interfaceVersion)
            )
        }

        val periodBundles = mutableListOf<Bundle>()
        val periodCount = this.periodCount
        val period = Period()
        for (i in 0..<periodCount) {
            periodBundles.add(getPeriod(i, period, true).toBundle(interfaceVersion))
        }

        val shuffledWindowIndices = IntArray(windowCount)
        if (windowCount > 0) {
            shuffledWindowIndices[0] = getFirstWindowIndex(true)
        }
        for (i in 1..<windowCount) {
            shuffledWindowIndices[i] =
                getNextWindowIndex(
                    shuffledWindowIndices[i - 1],
                    Player.REPEAT_MODE_OFF,
                    true
                )
        }

        val bundle = Bundle()
        bundle.putBinder(FIELD_WINDOWS, BundleListRetriever(windowBundles))
        bundle.putBinder(FIELD_PERIODS, BundleListRetriever(periodBundles))
        bundle.putIntArray(FIELD_SHUFFLED_WINDOW_INDICES, shuffledWindowIndices)
        return bundle
    }

    fun copyWithSingleWindow(windowIndex: Int): Timeline {
        if (this.windowCount == 1) {
            return this
        }
        val window = getWindow(windowIndex, Window(), 0)
        val periods = mutableListOf<Period>()
        for (i in window.firstPeriodIndex..window.lastPeriodIndex) {
            val period = getPeriod(i, Period(), true)
            period.windowIndex = 0
            periods.add(period)
        }
        window.lastPeriodIndex -= window.firstPeriodIndex
        window.firstPeriodIndex = 0
        return RemotableTimeline(
            listOf(window),
            periods.toList(),
            intArrayOf(0)
        )
    }

    class RemotableTimeline(
        windows: List<Window>,
        periods: List<Period>,
        shuffledWindowIndices: IntArray
    ) : Timeline() {
        private val windows: List<Window>
        private val periods: List<Period>
        private val shuffledWindowIndices: IntArray
        private val windowIndicesInShuffled: IntArray
        private val uidToIndexMap: MutableMap<Any, Int?>

        init {
            Preconditions.checkArgument(windows.size == shuffledWindowIndices.size)
            this.shuffledWindowIndices = shuffledWindowIndices
            windowIndicesInShuffled = IntArray(shuffledWindowIndices.size)
            for (i in shuffledWindowIndices.indices) {
                windowIndicesInShuffled[shuffledWindowIndices[i]] = i
            }
            val sanitizedWindows = mutableListOf<Window>()
            for (i in windows.indices) {
                var w = windows[i]
                if (windows.size > 1 && w.uid == Window.SINGLE_WINDOW_UID) {
                    val newWindow = Window()
                    newWindow.set(
                        Any(),
                        w.mediaItem,
                        w.manifest,
                        w.presentationStartTimeMs,
                        w.windowStartTimeMs,
                        w.elapsedRealtimeEpochOffsetMs,
                        w.isSeekable,
                        w.isDynamic,
                        w.liveConfiguration,
                        w.defaultPositionUs,
                        w.durationUs,
                        w.firstPeriodIndex,
                        w.lastPeriodIndex,
                        w.positionInFirstPeriodUs
                    )
                    newWindow.isPlaceholder = w.isPlaceholder
                    w = newWindow
                }
                sanitizedWindows.add(w)
            }
            this.windows = sanitizedWindows
            val sanitizedPeriods = mutableListOf<Period>()
            val uidToIndexMap: MutableMap<Any, Int?> = Maps.newHashMapWithExpectedSize(periods.size)
            for (i in periods.indices) {
                var p = periods[i]
                if (p.uid == null) {
                    val newPeriod = Period()
                    newPeriod.set(
                        p.id,
                        Any(),
                        p.windowIndex,
                        p.durationUs,
                        p.positionInWindowUs,
                        p.adPlaybackState,
                        p.isPlaceholder
                    )
                    p = newPeriod
                }
                sanitizedPeriods.add(p)
                val uid = p.uid!!
                if (!uidToIndexMap.containsKey(uid)) {
                    uidToIndexMap[uid] = i
                }
            }
            this.periods = sanitizedPeriods
            this.uidToIndexMap = uidToIndexMap
        }

        override val windowCount: Int
            get() = windows.size

        override fun getNextWindowIndex(
            windowIndex: Int, @RepeatMode repeatMode: @RepeatMode Int, shuffleModeEnabled: Boolean
        ): Int {
            if (repeatMode == Player.REPEAT_MODE_ONE) {
                return windowIndex
            }
            if (windowIndex == getLastWindowIndex(shuffleModeEnabled)) {
                return if (repeatMode == Player.REPEAT_MODE_ALL) {
                    getFirstWindowIndex(shuffleModeEnabled)
                } else {
                    C.INDEX_UNSET
                }
            }
            return if (shuffleModeEnabled) {
                shuffledWindowIndices[windowIndicesInShuffled[windowIndex] + 1]
            } else {
                windowIndex + 1
            }
        }

        override fun getPreviousWindowIndex(
            windowIndex: Int, @RepeatMode repeatMode: @RepeatMode Int, shuffleModeEnabled: Boolean
        ): Int {
            if (repeatMode == Player.REPEAT_MODE_ONE) {
                return windowIndex
            }
            if (windowIndex == getFirstWindowIndex(shuffleModeEnabled)) {
                return if (repeatMode == Player.REPEAT_MODE_ALL) {
                    getLastWindowIndex(shuffleModeEnabled)
                } else {
                    C.INDEX_UNSET
                }
            }
            return if (shuffleModeEnabled) {
                shuffledWindowIndices[windowIndicesInShuffled[windowIndex] - 1]
            } else {
                windowIndex - 1
            }
        }

        override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int {
            if (this.isEmpty) {
                return C.INDEX_UNSET
            }
            return if (shuffleModeEnabled) {
                shuffledWindowIndices[windowCount - 1]
            } else {
                windowCount - 1
            }
        }

        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int {
            if (this.isEmpty) {
                return C.INDEX_UNSET
            }
            return if (shuffleModeEnabled) shuffledWindowIndices[0] else 0
        }

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long
        ): Window {
            val w = windows[windowIndex]
            window!!.set(
                w.uid,
                w.mediaItem,
                w.manifest,
                w.presentationStartTimeMs,
                w.windowStartTimeMs,
                w.elapsedRealtimeEpochOffsetMs,
                w.isSeekable,
                w.isDynamic,
                w.liveConfiguration,
                w.defaultPositionUs,
                w.durationUs,
                w.firstPeriodIndex,
                w.lastPeriodIndex,
                w.positionInFirstPeriodUs
            )
            window.isPlaceholder = w.isPlaceholder
            return window
        }

        override val periodCount: Int
            get() = periods.size

        override fun getPeriod(
            periodIndex: Int,
            period: Period,
            setIds: Boolean
        ): Period {
            val p = periods[periodIndex]
            period?.set(
                p.id,
                p.uid,
                p.windowIndex,
                p.durationUs,
                p.positionInWindowUs,
                p.adPlaybackState,
                p.isPlaceholder
            )
            return period!!
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            val index = uidToIndexMap[uid]
            return index ?: C.INDEX_UNSET
        }

        override fun getUidOfPeriod(periodIndex: Int): Any {
            return periods[periodIndex].uid!!
        }
    }

    companion object {
        val EMPTY: Timeline = object : Timeline() {
            override val windowCount = 0

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long
            ): Window {
                throw IndexOutOfBoundsException()
            }

            override val periodCount = 0

            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                throw IndexOutOfBoundsException()
            }

            override fun getIndexOfPeriod(uid: Any): Int {
                return C.INDEX_UNSET
            }

            override fun getUidOfPeriod(periodIndex: Int): Any {
                throw IndexOutOfBoundsException()
            }
        }

        private val FIELD_WINDOWS = intToStringMaxRadix(0)
        private val FIELD_PERIODS = intToStringMaxRadix(1)
        private val FIELD_SHUFFLED_WINDOW_INDICES = intToStringMaxRadix(2)

        fun fromBundle(bundle: Bundle, interfaceVersion: Int): Timeline {
            val windows = bundle.getBinder(FIELD_WINDOWS)?.let { binder ->
                BundleListRetriever.getList(binder).map { Window.fromBundle(it, interfaceVersion) }
            } ?: listOf()

            val periods = bundle.getBinder(FIELD_PERIODS)?.let { binder ->
                BundleListRetriever. getList(binder).map { Period.fromBundle(it, interfaceVersion) }
            } ?: listOf()
            val shuffledWindowIndices = bundle.getIntArray(FIELD_SHUFFLED_WINDOW_INDICES)

            return RemotableTimeline(
                windows,
                periods,
                shuffledWindowIndices ?: IntArray(windows.size) { it }
            )
        }

    }
}
