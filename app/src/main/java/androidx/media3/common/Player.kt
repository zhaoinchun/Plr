package androidx.media3.common

import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.FloatRange
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions
import kotlin.math.max

interface Player {
    data class Events(private val flags: FlagSet) {
        fun contains(event: Int): Boolean {
            return flags.contains(event)
        }

        fun containsAny(vararg events: Int): Boolean {
            return flags.containsAny(events)
        }

        fun containsAny(events: Events): Boolean {
            return flags.containsAny(events.flags)
        }

        fun size(): Int {
            return flags.size()
        }

        fun get(index: Int): Int {
            return flags.get(index)
        }
    }

    class PositionInfo(
        val windowUid: Any?,
        val mediaItemIndex: Int,
        val mediaItem: MediaItem?,
        val periodUid: Any?,
        val periodIndex: Int,
        val positionMs: Long,
        val contentPositionMs: Long,
        val adGroupIndex: Int,
        val adIndexInAdGroup: Int
    ) {
        init {
            Preconditions.checkArgument(mediaItemIndex >= 0)
            Preconditions.checkArgument(periodIndex >= 0)
        }

        fun filterByAvailableCommands(
            canAccessCurrentMediaItem: Boolean, canAccessTimeline: Boolean
        ): PositionInfo {
            if (canAccessCurrentMediaItem && canAccessTimeline) {
                return this
            }
            return PositionInfo(
                windowUid,
                if (canAccessTimeline) mediaItemIndex else 0,
                if (canAccessCurrentMediaItem) mediaItem else null,
                periodUid,
                if (canAccessTimeline) periodIndex else 0,
                if (canAccessCurrentMediaItem) positionMs else 0,
                if (canAccessCurrentMediaItem) contentPositionMs else 0,
                if (canAccessCurrentMediaItem) adGroupIndex else C.INDEX_UNSET,
                if (canAccessCurrentMediaItem) adIndexInAdGroup else C.INDEX_UNSET
            )
        }

        fun toBundle(interfaceVersion: Int): Bundle {
            val bundle = Bundle()
            if (interfaceVersion < 3 || mediaItemIndex != 0) {
                bundle.putInt(FIELD_MEDIA_ITEM_INDEX, mediaItemIndex)
            }
            if (mediaItem != null) {
                bundle.putBundle(FIELD_MEDIA_ITEM, mediaItem.toBundle(interfaceVersion))
            }
            if (interfaceVersion < 3 || periodIndex != 0) {
                bundle.putInt(FIELD_PERIOD_INDEX, periodIndex)
            }
            if (interfaceVersion < 3 || positionMs != 0L) {
                bundle.putLong(FIELD_POSITION_MS, positionMs)
            }
            if (interfaceVersion < 3 || contentPositionMs != 0L) {
                bundle.putLong(FIELD_CONTENT_POSITION_MS, contentPositionMs)
            }
            if (adGroupIndex != C.INDEX_UNSET) {
                bundle.putInt(FIELD_AD_GROUP_INDEX, adGroupIndex)
            }
            if (adIndexInAdGroup != C.INDEX_UNSET) {
                bundle.putInt(FIELD_AD_INDEX_IN_AD_GROUP, adIndexInAdGroup)
            }
            return bundle
        }

        companion object {
            val FIELD_MEDIA_ITEM_INDEX: String = intToStringMaxRadix(0)
            private val FIELD_MEDIA_ITEM = intToStringMaxRadix(1)
            val FIELD_PERIOD_INDEX: String = intToStringMaxRadix(2)
            val FIELD_POSITION_MS: String = intToStringMaxRadix(3)
            val FIELD_CONTENT_POSITION_MS: String = intToStringMaxRadix(4)
            private val FIELD_AD_GROUP_INDEX = intToStringMaxRadix(5)
            private val FIELD_AD_INDEX_IN_AD_GROUP = intToStringMaxRadix(6)

            fun fromBundle(bundle: Bundle, interfaceVersion: Int): PositionInfo {
                val mediaItemIndex =
                    max(0, bundle.getInt(FIELD_MEDIA_ITEM_INDEX, 0))
                val mediaItemBundle = bundle.getBundle(FIELD_MEDIA_ITEM)
                val mediaItem =
                    if (mediaItemBundle == null) null else MediaItem.fromBundle(
                        mediaItemBundle,
                        interfaceVersion
                    )
                val periodIndex = max(0, bundle.getInt(FIELD_PERIOD_INDEX, 0))
                val positionMs = bundle.getLong(FIELD_POSITION_MS, 0)
                val contentPositionMs =
                    bundle.getLong(FIELD_CONTENT_POSITION_MS, 0)
                val adGroupIndex =
                    bundle.getInt(FIELD_AD_GROUP_INDEX, C.INDEX_UNSET)
                val adIndexInAdGroup =
                    bundle.getInt(FIELD_AD_INDEX_IN_AD_GROUP, C.INDEX_UNSET)
                return PositionInfo(
                    null,
                    mediaItemIndex,
                    mediaItem,
                    null,
                    periodIndex,
                    positionMs,
                    contentPositionMs,
                    adGroupIndex,
                    adIndexInAdGroup
                )
            }
        }
    }

    data class Commands private constructor(private val flags: FlagSet) {
        class Builder() {
            private val flagsBuilder = FlagSet.Builder()

            constructor(commands: Commands) : this() {
                flagsBuilder.addAll(commands.flags)
            }

            fun add(command: Int): Builder {
                flagsBuilder.add(command)
                return this
            }

            fun addIf(command: Int, condition: Boolean): Builder {
                flagsBuilder.addIf(command, condition)
                return this
            }

            fun addAll(vararg commands: Int): Builder {
                flagsBuilder.addAll(*commands)
                return this
            }

            fun addAll(commands: Commands): Builder {
                flagsBuilder.addAll(commands.flags)
                return this
            }

            fun addAllReadOnlyCommands(): Builder {
                flagsBuilder.addAll(SUPPORTED_READ_COMMANDS)
                return this
            }

            fun addAllCommands(): Builder {
                flagsBuilder.addAll(SUPPORTED_READ_COMMANDS).addAll(SUPPORTED_WRITE_COMMANDS)
                return this
            }

            fun remove(command: Int): Builder {
                flagsBuilder.remove(command)
                return this
            }

            fun removeIf(command: Int, condition: Boolean): Builder {
                flagsBuilder.removeIf(command, condition)
                return this
            }

            fun removeAll(vararg commands: Int): Builder {
                flagsBuilder.removeAll(*commands)
                return this
            }

            fun build(): Commands {
                return Commands(flagsBuilder.build())
            }

            companion object {
                private val SUPPORTED_READ_COMMANDS = FlagSet.Builder()
                    .addAll(
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_TIMELINE,
                        COMMAND_GET_METADATA,
                        COMMAND_GET_AUDIO_ATTRIBUTES,
                        COMMAND_GET_VOLUME,
                        COMMAND_GET_DEVICE_VOLUME,
                        COMMAND_GET_TEXT,
                        COMMAND_GET_TRACKS
                    )
                    .build()

                private val SUPPORTED_WRITE_COMMANDS = FlagSet.Builder()
                    .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_PREPARE,
                        COMMAND_STOP,
                        COMMAND_SEEK_TO_DEFAULT_POSITION,
                        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_MEDIA_ITEM,
                        COMMAND_SEEK_BACK,
                        COMMAND_SEEK_FORWARD,
                        COMMAND_SET_SPEED_AND_PITCH,
                        COMMAND_SET_SHUFFLE_MODE,
                        COMMAND_SET_REPEAT_MODE,
                        COMMAND_SET_PLAYLIST_METADATA,
                        COMMAND_SET_MEDIA_ITEM,
                        COMMAND_CHANGE_MEDIA_ITEMS,
                        COMMAND_SET_VOLUME,
                        COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                        COMMAND_SET_AUDIO_ATTRIBUTES,
                        COMMAND_SET_VIDEO_SURFACE,
                        COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                        COMMAND_RELEASE
                    )
                    .build()
            }
        }

        fun buildUpon(): Builder {
            return Builder(this)
        }

        fun contains(command: Int): Boolean {
            return flags.contains(command)
        }

        fun containsAny(commands: IntArray): Boolean {
            return flags.containsAny(commands)
        }

        fun size(): Int {
            return flags.size()
        }

        fun get(index: Int): Int {
            return flags.get(index)
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            val commandsBundle = ArrayList<Int?>()
            for (i in 0..<flags.size()) {
                commandsBundle.add(flags[i])
            }
            bundle.putIntegerArrayList(FIELD_COMMANDS, commandsBundle)
            return bundle
        }

        companion object {
            val EMPTY: Commands = Builder().build()
            private val FIELD_COMMANDS = intToStringMaxRadix(0)

            fun fromBundle(bundle: Bundle): Commands {
                val commands = bundle.getIntegerArrayList(FIELD_COMMANDS) ?: return EMPTY
                val builder = Builder()
                for (i in commands.indices) {
                    builder.add(commands[i]!!)
                }
                return builder.build()
            }
        }
    }

    interface Listener {
        fun onEvents(player: Player, events: Events) {}

        fun onTimelineChanged(
            timeline: Timeline, reason: Int
        ) {
        }

        fun onMediaItemTransition(
            mediaItem: MediaItem?, reason: Int
        ) {
        }

        fun onTracksChanged(tracks: Tracks) {}
        fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {}
        fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {}
        fun onIsLoadingChanged(isLoading: Boolean) {}
        fun onAvailableCommandsChanged(availableCommands: Commands) {}
        fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {}
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onPlayWhenReadyChanged(
            playWhenReady: Boolean, reason: Int
        ) {
        }

        fun onPlaybackSuppressionReasonChanged(
            playbackSuppressionReason: Int
        ) {
        }

        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onRepeatModeChanged(repeatMode: Int) {}
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onPlayerErrorChanged(error: PlaybackException?) {}
        fun onPositionDiscontinuity(
            oldPosition: PositionInfo,
            newPosition: PositionInfo,
            reason: Int
        ) {
        }

        fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
        fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {}
        fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {}
        fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) {}
        fun onAudioSessionIdChanged(audioSessionId: Int) {}
        fun onAudioAttributesChanged(audioAttributes: AudioAttributes) {}
        fun onVolumeChanged(volume: Float) {}
        fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
        fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {}
        fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onSurfaceSizeChanged(width: Int, height: Int) {}
        fun onRenderedFirstFrame() {}
        fun onCues(cueGroup: CueGroup) {}
        fun onMetadata(metadata: Metadata) {}
    }

    val applicationLooper: Looper?
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun setMediaItems(mediaItems: List<MediaItem>)
    fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean)
    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun setMediaItem(mediaItem: MediaItem)
    fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long)
    fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean)
    fun addMediaItem(mediaItem: MediaItem)
    fun addMediaItem(index: Int, mediaItem: MediaItem)
    fun addMediaItems(mediaItems: List<MediaItem>)
    fun addMediaItems(index: Int, mediaItems: List<MediaItem>)
    fun moveMediaItem(currentIndex: Int, newIndex: Int)
    fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int)
    fun replaceMediaItem(index: Int, mediaItem: MediaItem)
    fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>)
    fun removeMediaItem(index: Int)
    fun removeMediaItems(fromIndex: Int, toIndex: Int)
    fun clearMediaItems()
    fun isCommandAvailable(command: Int): Boolean
    fun canAdvertiseSession(): Boolean
    val availableCommands: Commands?
    fun prepare()
    val playbackState: Int
    val playbackSuppressionReason: Int
    val isPlaying: Boolean
    val playerError: PlaybackException?
    fun play()
    fun pause()
    var playWhenReady: Boolean
    var repeatMode: Int
    var shuffleModeEnabled: Boolean
    val isLoading: Boolean
    fun seekToDefaultPosition()
    fun seekToDefaultPosition(mediaItemIndex: Int)
    fun seekTo(positionMs: Long)
    fun seekTo(mediaItemIndex: Int, positionMs: Long)
    val seekBackIncrement: Long
    fun seekBack()
    val seekForwardIncrement: Long
    fun seekForward()
    fun hasPreviousMediaItem(): Boolean
    fun seekToPreviousMediaItem()
    val maxSeekToPreviousPosition: Long
    fun seekToPrevious()
    fun hasNextMediaItem(): Boolean
    fun seekToNextMediaItem()
    fun seekToNext()
    fun setPlaybackSpeed(@FloatRange(from = 0.0, fromInclusive = false) speed: Float)
    var playbackParameters: PlaybackParameters?
    fun stop()
    fun release()
    val currentTracks: Tracks?
    var trackSelectionParameters: TrackSelectionParameters?
    val mediaMetadata: MediaMetadata?
    var playlistMetadata: MediaMetadata?
    val currentManifest: Any?
    val currentTimeline: Timeline
    val currentPeriodIndex: Int
    val currentMediaItemIndex: Int
    val nextMediaItemIndex: Int
    val previousMediaItemIndex: Int
    val currentMediaItem: MediaItem?
    val mediaItemCount: Int
    fun getMediaItemAt(index: Int): MediaItem?
    val duration: Long
    val currentPosition: Long
    val bufferedPosition: Long

    @get:IntRange(from = 0, to = 100)
    val bufferedPercentage: Int
    val totalBufferedDuration: Long
    val isCurrentMediaItemDynamic: Boolean
    val isCurrentMediaItemLive: Boolean
    val currentLiveOffset: Long
    val isCurrentMediaItemSeekable: Boolean
    val isPlayingAd: Boolean
    val currentAdGroupIndex: Int
    val currentAdIndexInAdGroup: Int
    val contentDuration: Long
    val contentPosition: Long
    val contentBufferedPosition: Long
    val audioAttributes: AudioAttributes?
    val audioSessionId: Int
        get() = C.AUDIO_SESSION_ID_UNSET

    var volume: Float
    fun mute()
    fun unmute()
    fun clearVideoSurface()
    fun clearVideoSurface(surface: Surface?)
    fun setVideoSurface(surface: Surface?)
    fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?)
    fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?)
    fun setVideoSurfaceView(surfaceView: SurfaceView?)
    fun clearVideoSurfaceView(surfaceView: SurfaceView?)
    fun setVideoTextureView(textureView: TextureView?)
    fun clearVideoTextureView(textureView: TextureView?)
    val videoSize: VideoSize?
    val surfaceSize: Size?
    val currentCues: CueGroup?
    val deviceInfo: DeviceInfo?
    val deviceVolume: Int
    val isDeviceMuted: Boolean
    fun setDeviceVolume(@IntRange(from = 0) volume: Int, flags: Int)
    fun increaseDeviceVolume(flags: Int)
    fun decreaseDeviceVolume(flags: Int)
    fun setDeviceMuted(muted: Boolean, flags: Int)
    fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean)

    companion object {
        const val STATE_IDLE: Int = 1
        const val STATE_BUFFERING: Int = 2
        const val STATE_READY: Int = 3
        const val STATE_ENDED: Int = 4
        const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST: Int = 1
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS: Int = 2
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY: Int = 3
        const val PLAY_WHEN_READY_CHANGE_REASON_REMOTE: Int = 4
        const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM: Int = 5
        const val PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG: Int = 6
        const val PLAYBACK_SUPPRESSION_REASON_NONE: Int = 0
        const val PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS: Int = 1
        const val PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT: Int = 3
        const val PLAYBACK_SUPPRESSION_REASON_SCRUBBING: Int = 4
        const val REPEAT_MODE_OFF: Int = 0
        const val REPEAT_MODE_ONE: Int = 1
        const val REPEAT_MODE_ALL: Int = 2
        const val DISCONTINUITY_REASON_AUTO_TRANSITION: Int = 0
        const val DISCONTINUITY_REASON_SEEK: Int = 1
        const val DISCONTINUITY_REASON_SEEK_ADJUSTMENT: Int = 2
        const val DISCONTINUITY_REASON_SKIP: Int = 3
        const val DISCONTINUITY_REASON_REMOVE: Int = 4
        const val DISCONTINUITY_REASON_INTERNAL: Int = 5
        const val DISCONTINUITY_REASON_SILENCE_SKIP: Int = 6
        const val TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED: Int = 0
        const val TIMELINE_CHANGE_REASON_SOURCE_UPDATE: Int = 1
        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT: Int = 0
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO: Int = 1
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK: Int = 2
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED: Int = 3
        const val EVENT_TIMELINE_CHANGED: Int = 0
        const val EVENT_MEDIA_ITEM_TRANSITION: Int = 1
        const val EVENT_TRACKS_CHANGED: Int = 2
        const val EVENT_IS_LOADING_CHANGED: Int = 3
        const val EVENT_PLAYBACK_STATE_CHANGED: Int = 4
        const val EVENT_PLAY_WHEN_READY_CHANGED: Int = 5
        const val EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED: Int = 6
        const val EVENT_IS_PLAYING_CHANGED: Int = 7
        const val EVENT_REPEAT_MODE_CHANGED: Int = 8
        const val EVENT_SHUFFLE_MODE_ENABLED_CHANGED: Int = 9
        const val EVENT_PLAYER_ERROR: Int = 10
        const val EVENT_POSITION_DISCONTINUITY: Int = 11
        const val EVENT_PLAYBACK_PARAMETERS_CHANGED: Int = 12
        const val EVENT_AVAILABLE_COMMANDS_CHANGED: Int = 13
        const val EVENT_MEDIA_METADATA_CHANGED: Int = 14
        const val EVENT_PLAYLIST_METADATA_CHANGED: Int = 15
        const val EVENT_SEEK_BACK_INCREMENT_CHANGED: Int = 16
        const val EVENT_SEEK_FORWARD_INCREMENT_CHANGED: Int = 17
        const val EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED: Int = 18
        const val EVENT_TRACK_SELECTION_PARAMETERS_CHANGED: Int = 19
        const val EVENT_AUDIO_ATTRIBUTES_CHANGED: Int = 20
        const val EVENT_AUDIO_SESSION_ID: Int = 21
        const val EVENT_VOLUME_CHANGED: Int = 22
        const val EVENT_SKIP_SILENCE_ENABLED_CHANGED: Int = 23
        const val EVENT_SURFACE_SIZE_CHANGED: Int = 24
        const val EVENT_VIDEO_SIZE_CHANGED: Int = 25
        const val EVENT_RENDERED_FIRST_FRAME: Int = 26
        const val EVENT_CUES: Int = 27
        const val EVENT_METADATA: Int = 28
        const val EVENT_DEVICE_INFO_CHANGED: Int = 29
        const val EVENT_DEVICE_VOLUME_CHANGED: Int = 30
        const val COMMAND_PLAY_PAUSE: Int = 1
        const val COMMAND_PREPARE: Int = 2
        const val COMMAND_STOP: Int = 3
        const val COMMAND_SEEK_TO_DEFAULT_POSITION: Int = 4
        const val COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM: Int = 5
        const val COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM: Int = 6
        const val COMMAND_SEEK_TO_PREVIOUS: Int = 7
        const val COMMAND_SEEK_TO_NEXT_MEDIA_ITEM: Int = 8
        const val COMMAND_SEEK_TO_NEXT: Int = 9
        const val COMMAND_SEEK_TO_MEDIA_ITEM: Int = 10
        const val COMMAND_SEEK_BACK: Int = 11
        const val COMMAND_SEEK_FORWARD: Int = 12
        const val COMMAND_SET_SPEED_AND_PITCH: Int = 13
        const val COMMAND_SET_SHUFFLE_MODE: Int = 14
        const val COMMAND_SET_REPEAT_MODE: Int = 15
        const val COMMAND_GET_CURRENT_MEDIA_ITEM: Int = 16
        const val COMMAND_GET_TIMELINE: Int = 17
        const val COMMAND_GET_METADATA: Int = 18
        const val COMMAND_SET_PLAYLIST_METADATA: Int = 19
        const val COMMAND_SET_MEDIA_ITEM: Int = 31
        const val COMMAND_CHANGE_MEDIA_ITEMS: Int = 20
        const val COMMAND_GET_AUDIO_ATTRIBUTES: Int = 21
        const val COMMAND_GET_VOLUME: Int = 22
        const val COMMAND_GET_DEVICE_VOLUME: Int = 23
        const val COMMAND_SET_VOLUME: Int = 24
        const val COMMAND_SET_DEVICE_VOLUME: Int = 25
        const val COMMAND_ADJUST_DEVICE_VOLUME: Int = 26
        const val COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS: Int = 33
        const val COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS: Int = 34
        const val COMMAND_SET_AUDIO_ATTRIBUTES: Int = 35
        const val COMMAND_SET_VIDEO_SURFACE: Int = 27
        const val COMMAND_GET_TEXT: Int = 28
        const val COMMAND_SET_TRACK_SELECTION_PARAMETERS: Int = 29
        const val COMMAND_GET_TRACKS: Int = 30
        const val COMMAND_RELEASE: Int = 32
        const val COMMAND_INVALID: Int = -1
    }
}
