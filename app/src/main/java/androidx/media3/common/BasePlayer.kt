package androidx.media3.common

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.percentInt
import com.google.errorprone.annotations.ForOverride
import kotlin.math.max
import kotlin.math.min
abstract class BasePlayer protected constructor() : Player {
    protected val window: Timeline.Window

    init {
        window = Timeline.Window()
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        setMediaItems(listOf(mediaItem))
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        setMediaItems(listOf(mediaItem),  0, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        setMediaItems(listOf(mediaItem), resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        setMediaItems(mediaItems,  true)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        addMediaItems(index, listOf(mediaItem))
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        addMediaItems(listOf(mediaItem))
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        addMediaItems( Int.MAX_VALUE, mediaItems)
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        if (currentIndex != newIndex) {
            moveMediaItems( currentIndex,  currentIndex + 1, newIndex)
        }
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        replaceMediaItems( 
            index,  index + 1, listOf(mediaItem)
        )
    }

    override fun removeMediaItem(index: Int) {
        removeMediaItems( index,  index + 1)
    }

    override fun clearMediaItems() {
        removeMediaItems( 0,  Int.MAX_VALUE)
    }

    override fun isCommandAvailable(@Player.Command command: @Player.Command Int): Boolean {
        return availableCommands!!.contains(command)
    }

    override fun canAdvertiseSession(): Boolean {
        return true
    }

    override fun play() {
        playWhenReady = true
    }

    override fun pause() {
        playWhenReady = false
    }

    override val isPlaying: Boolean
        get() = playbackState == Player.STATE_READY && playWhenReady
                && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun seekToDefaultPosition() {
        seekToDefaultPositionInternal(
            currentMediaItemIndex, Player.COMMAND_SEEK_TO_DEFAULT_POSITION
        )
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        seekToDefaultPositionInternal(mediaItemIndex, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
    }

    override fun seekBack() {
        seekToOffset(-seekBackIncrement, Player.COMMAND_SEEK_BACK)
    }

    override fun seekForward() {
        seekToOffset(seekForwardIncrement, Player.COMMAND_SEEK_FORWARD)
    }

    override fun hasPreviousMediaItem(): Boolean {
        return previousMediaItemIndex != C.INDEX_UNSET
    }

    override fun seekToPreviousMediaItem() {
        seekToPreviousMediaItemInternal(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    }

    override fun seekToPrevious() {
        val timeline = currentTimeline
        if (timeline!!.isEmpty || isPlayingAd) {
            ignoreSeek(Player.COMMAND_SEEK_TO_PREVIOUS)
            return
        }
        val hasPreviousMediaItem = hasPreviousMediaItem()
        if (isCurrentMediaItemLive && !isCurrentMediaItemSeekable) {
            if (hasPreviousMediaItem) {
                seekToPreviousMediaItemInternal(Player.COMMAND_SEEK_TO_PREVIOUS)
            } else {
                ignoreSeek(Player.COMMAND_SEEK_TO_PREVIOUS)
            }
        } else if (hasPreviousMediaItem && currentPosition <= maxSeekToPreviousPosition) {
            seekToPreviousMediaItemInternal(Player.COMMAND_SEEK_TO_PREVIOUS)
        } else {
            seekToCurrentItem( 0, Player.COMMAND_SEEK_TO_PREVIOUS)
        }
    }

    override fun hasNextMediaItem(): Boolean {
        return nextMediaItemIndex != C.INDEX_UNSET
    }

    override fun seekToNextMediaItem() {
        seekToNextMediaItemInternal(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }

    override fun seekToNext() {
        val timeline = currentTimeline
        if (timeline!!.isEmpty || isPlayingAd) {
            ignoreSeek(Player.COMMAND_SEEK_TO_NEXT)
            return
        }
        if (hasNextMediaItem()) {
            seekToNextMediaItemInternal(Player.COMMAND_SEEK_TO_NEXT)
        } else if (isCurrentMediaItemLive && isCurrentMediaItemDynamic) {
            seekToDefaultPositionInternal(currentMediaItemIndex, Player.COMMAND_SEEK_TO_NEXT)
        } else {
            ignoreSeek(Player.COMMAND_SEEK_TO_NEXT)
        }
    }

    override fun seekTo(positionMs: Long) {
        seekToCurrentItem(positionMs, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seekTo(
            mediaItemIndex,
            positionMs,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,  
            false
        )
    }

    @ForOverride
    protected abstract fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: @Player.Command Int,
        isRepeatingCurrentItem: Boolean
    )

    override fun setPlaybackSpeed(speed: Float) {
        playbackParameters = playbackParameters!!.withSpeed(speed)
    }

    @get:Deprecated("Use {@link #getCurrentMediaItemIndex()} instead.")
    val currentWindowIndex: Int
        get() = currentMediaItemIndex

    @get:Deprecated("Use {@link #getNextMediaItemIndex()} instead.")
    val nextWindowIndex: Int
        get() = nextMediaItemIndex

    override val nextMediaItemIndex: Int
        get() {
            val timeline = currentTimeline
            return if (timeline!!.isEmpty)
                C.INDEX_UNSET
            else
                timeline.getNextWindowIndex(
                    currentMediaItemIndex, this.repeatModeForNavigation, shuffleModeEnabled
                )
        }

    @get:Deprecated("Use {@link #getPreviousMediaItemIndex()} instead.")
    val previousWindowIndex: Int
        get() = previousMediaItemIndex

    override val previousMediaItemIndex: Int
        get() {
            val timeline = currentTimeline
            return if (timeline!!.isEmpty)
                C.INDEX_UNSET
            else
                timeline.getPreviousWindowIndex(
                    currentMediaItemIndex, this.repeatModeForNavigation, shuffleModeEnabled
                )
        }

    override val currentMediaItem: MediaItem?
        get() {
            val timeline = currentTimeline
            return if (timeline!!.isEmpty)
                null
            else
                timeline.getWindow(currentMediaItemIndex, window).mediaItem
        }

    override val mediaItemCount: Int
        get() = currentTimeline!!.windowCount

    override fun getMediaItemAt(index: Int): MediaItem? {
        return currentTimeline!!.getWindow(index, window).mediaItem
    }

    override val currentManifest: Any?
        get() {
            val timeline = currentTimeline
            return if (timeline!!.isEmpty)
                null
            else
                timeline.getWindow(currentMediaItemIndex, window).manifest
        }

    override val bufferedPercentage: Int
        get() {
            // This method is not guarded by COMMAND_GET_CURRENT_MEDIA_ITEM, but the implementation below
            // relies on methods that are, so we have to check the command and exit early with the 0
            // 'unknown value'.
            if (!isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
                return 0
            }
            val position = bufferedPosition
            val duration = duration
            return if (position == C.TIME_UNSET || duration == C.TIME_UNSET)
                0
            else
                if (duration == 0L) 100 else constrainValue(
                    percentInt(position, duration),
                    0,
                    100
                )
        }

    @get:Deprecated("Use {@link #isCurrentMediaItemDynamic()} instead.")
    val isCurrentWindowDynamic: Boolean
        get() = isCurrentMediaItemDynamic

    override val isCurrentMediaItemDynamic: Boolean
        get() {
            val timeline = currentTimeline
            return !timeline!!.isEmpty && timeline.getWindow(
                currentMediaItemIndex,
                window
            ).isDynamic
        }

    @get:Deprecated("Use {@link #isCurrentMediaItemLive()} instead.")
    val isCurrentWindowLive: Boolean
        get() = isCurrentMediaItemLive

    override val isCurrentMediaItemLive: Boolean
        get() {
            val timeline = currentTimeline
            return !timeline!!.isEmpty && timeline.getWindow(currentMediaItemIndex, window).isLive
        }

    override val currentLiveOffset: Long
        get() {
            val timeline = currentTimeline
            if (timeline!!.isEmpty) {
                return C.TIME_UNSET
            }
            val windowStartTimeMs =
                timeline.getWindow(currentMediaItemIndex, window).windowStartTimeMs
            if (windowStartTimeMs == C.TIME_UNSET) {
                return C.TIME_UNSET
            }
            return window.currentUnixTimeMs - window.windowStartTimeMs - contentPosition
        }

    @get:Deprecated("Use {@link #isCurrentMediaItemSeekable()} instead.")
    val isCurrentWindowSeekable: Boolean
        get() = isCurrentMediaItemSeekable

    override val isCurrentMediaItemSeekable: Boolean
        get() {
            val timeline = currentTimeline
            return !timeline!!.isEmpty && timeline.getWindow(
                currentMediaItemIndex,
                window
            ).isSeekable
        }

    override val contentDuration: Long
        get() {
            val timeline = currentTimeline
            return if (timeline!!.isEmpty)
                C.TIME_UNSET
            else
                timeline.getWindow(currentMediaItemIndex, window).durationMs
        }

    @get:Player.RepeatMode
    private val repeatModeForNavigation: @Player.RepeatMode Int
        get() {
            @Player.RepeatMode val repeatMode: @Player.RepeatMode Int =
                repeatMode
            return if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeatMode
        }

    private fun ignoreSeek(@Player.Command seekCommand: @Player.Command Int) {
        seekTo( 
            C.INDEX_UNSET,  
            C.TIME_UNSET,
            seekCommand,  
            false
        )
    }

    private fun seekToCurrentItem(
        positionMs: Long,
        @Player.Command seekCommand: @Player.Command Int
    ) {
        seekTo(
            currentMediaItemIndex, positionMs, seekCommand,  false
        )
    }

    private fun seekToOffset(offsetMs: Long, @Player.Command seekCommand: @Player.Command Int) {
        var positionMs = currentPosition + offsetMs
        val durationMs = duration
        if (durationMs != C.TIME_UNSET) {
            positionMs = min(positionMs, durationMs)
        }
        positionMs = max(positionMs, 0)
        seekToCurrentItem(positionMs, seekCommand)
    }

    private fun seekToDefaultPositionInternal(
        mediaItemIndex: Int,
        @Player.Command seekCommand: @Player.Command Int
    ) {
        seekTo(
            mediaItemIndex,  
            C.TIME_UNSET,
            seekCommand,  
            false
        )
    }

    private fun seekToNextMediaItemInternal(@Player.Command seekCommand: @Player.Command Int) {
        val nextMediaItemIndex = nextMediaItemIndex
        if (nextMediaItemIndex == C.INDEX_UNSET) {
            ignoreSeek(seekCommand)
            return
        }
        if (nextMediaItemIndex == currentMediaItemIndex) {
            repeatCurrentMediaItem(seekCommand)
        } else {
            seekToDefaultPositionInternal(nextMediaItemIndex, seekCommand)
        }
    }

    private fun seekToPreviousMediaItemInternal(@Player.Command seekCommand: @Player.Command Int) {
        val previousMediaItemIndex = previousMediaItemIndex
        if (previousMediaItemIndex == C.INDEX_UNSET) {
            ignoreSeek(seekCommand)
            return
        }
        if (previousMediaItemIndex == currentMediaItemIndex) {
            repeatCurrentMediaItem(seekCommand)
        } else {
            seekToDefaultPositionInternal(previousMediaItemIndex, seekCommand)
        }
    }

    private fun repeatCurrentMediaItem(@Player.Command seekCommand: @Player.Command Int) {
        seekTo(
            currentMediaItemIndex,  
            C.TIME_UNSET,
            seekCommand,  
            true
        )
    }
}
