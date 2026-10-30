package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.AudioTrack.StreamEventCallback
import android.media.PlaybackParams
import android.media.metrics.LogSessionId
import android.os.Build
import android.os.Handler
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.BackgroundExecutor.get
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Log.w

import androidx.media3.common.util.Util.ceilDivide
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import androidx.media3.common.util.Util.durationUsToSampleCount
import androidx.media3.common.util.Util.getPcmFrameSize
import androidx.media3.common.util.Util.isEncodingLinearPcm
import androidx.media3.common.util.Util.newSingleThreadScheduledExecutor
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class AudioTrackAudioOutput(
    val audioTrack: AudioTrack,
    private val config: OutputConfig,
    private val capabilityChangeListener: CapabilityChangeListener?,
    private val maxPlaybackSpeed: Float,
    clock: Clock
) : AudioOutput {

    interface CapabilityChangeListener {
        fun onRoutedDeviceChanged(routedDevice: AudioDeviceInfo?)
        fun onRecoverableWriteError()
    }

    private var onRoutingChangedListener: OnRoutingChangedListenerApi24? = null
    private val audioTrackPositionTracker: AudioTrackPositionTracker
    private val isOutputPcm: Boolean = isEncodingLinearPcm(config.encoding)
    private val pcmFrameSize: Int
    private val offloadStreamEventCallbackV29: StreamEventCallbackV29?
    private val listeners: ListenerSet<AudioOutput.Listener> = ListenerSet(Thread.currentThread())

    private var hasBeenStopped = false
    private var writtenPcmBytes: Long = 0
    private var writtenEncodedFrames: Long = 0
    private var lastTunnelingAvSyncPresentationTimeUs: Long = 0
    private var avSyncHeader: ByteBuffer? = null
    private var bytesUntilNextAvSync = 0
    private var framesPerEncodedSample = 0
    private var lastUnderrunCount = 0
    private var hasData = false

    init {
        if (isOutputPcm) {
            val channelCount = Integer.bitCount(config.channelMask)
            pcmFrameSize = getPcmFrameSize(config.encoding, channelCount)
        } else {
            pcmFrameSize = C.LENGTH_UNSET
        }

        audioTrackPositionTracker =
            AudioTrackPositionTracker(
                PositionTrackerListener(),
                clock,
                audioTrack,
                config.encoding,
                pcmFrameSize,
                config.bufferSize
            )
        if (Build.VERSION.SDK_INT >= 24 && capabilityChangeListener != null) {
            onRoutingChangedListener =
                OnRoutingChangedListenerApi24(audioTrack, capabilityChangeListener)
        }
        offloadStreamEventCallbackV29 =
            if (isOffloadedPlayback) StreamEventCallbackV29() else null
    }

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override val isOffloadedPlayback: Boolean
        get() = Build.VERSION.SDK_INT >= 29 && audioTrack.isOffloadedPlayback

    override val audioSessionId: Int
        get() = audioTrack.audioSessionId

    override val sampleRate: Int
        get() = audioTrack.sampleRate

    override val bufferSizeInFrames: Long
        get() = audioTrack.bufferSizeInFrames.toLong()

    override val positionUs: Long
        get() = audioTrackPositionTracker.getCurrentPositionUs(this.writtenFrames)

    override var playbackParameters: PlaybackParameters
        get() {
            val playbackParams = audioTrack.playbackParams
            return PlaybackParameters(playbackParams.getSpeed(), playbackParams.getPitch())
        }
        set(value) {
            val playbackParams =
                PlaybackParams()
                    .allowDefaults()
                    .setSpeed(
                        constrainValue(
                            value.speed,
                            DefaultAudioSink.MIN_PLAYBACK_SPEED,
                            maxPlaybackSpeed
                        )
                    )
                    .setPitch(
                        constrainValue(
                            value.pitch,
                            DefaultAudioSink.MIN_PITCH,
                            DefaultAudioSink.MAX_PITCH
                        )
                    )
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
            try {
                audioTrack.setPlaybackParams(playbackParams)
            } catch (e: IllegalArgumentException) {
                w(TAG, "Failed to set playback params", e)
            }
            audioTrackPositionTracker.setAudioTrackPlaybackSpeed(
                audioTrack.playbackParams.getSpeed()
            )
        }

    override fun play() {
        audioTrackPositionTracker.start()
        if (!hasBeenStopped || isOffloadedPlayback) {
            audioTrack.play()
        }
    }

    override fun pause() {
        audioTrackPositionTracker.pause()
        if (!hasBeenStopped || isOffloadedPlayback) {
            audioTrack.pause()
        }
    }

    @Throws(AudioOutput.WriteException::class)
    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long
    ): Boolean {
        var presentationTimeUs = presentationTimeUs
        if (!isOutputPcm && framesPerEncodedSample == 0) {
            // If this is the first encoded sample, calculate the sample size in frames.
            framesPerEncodedSample =
                DefaultAudioSink.getFramesPerEncodedSample(config.encoding, buffer)
        }
        maybeReportUnderrun()
        val bytesRemaining = buffer.remaining()
        val bytesWrittenOrError: Int
        if (config.isTunneling) {
            if (presentationTimeUs == C.TIME_END_OF_SOURCE) {
                // Audio processors during tunneling are required to produce buffers immediately when
                // queuing, so we can assume the timestamp during draining at the end of the stream is the
                // same as the timestamp of the last sample we processed.
                presentationTimeUs = lastTunnelingAvSyncPresentationTimeUs
            } else {
                lastTunnelingAvSyncPresentationTimeUs = presentationTimeUs
            }
            bytesWrittenOrError = writeWithAvSync(audioTrack, buffer, presentationTimeUs)
        } else {
            bytesWrittenOrError =
                audioTrack.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        }

        if (bytesWrittenOrError < 0) {
            val isRecoverable: Boolean = isAudioTrackDeadObject(bytesWrittenOrError)
            if (isRecoverable && capabilityChangeListener != null) {
                capabilityChangeListener.onRecoverableWriteError()
            }
            throw AudioOutput.WriteException(bytesWrittenOrError, isRecoverable)
        }
        val fullyHandled = bytesWrittenOrError == bytesRemaining

        if (isOutputPcm) {
            writtenPcmBytes += bytesWrittenOrError.toLong()
        } else if (fullyHandled) {
            // For non-PCM we can only be sure about the number of written frames once the entire buffer
            // is submitted.
            writtenEncodedFrames += framesPerEncodedSample.toLong() * encodedAccessUnitCount
        }
        return fullyHandled
    }

    override fun flush() {
        avSyncHeader = null
        bytesUntilNextAvSync = 0
        writtenPcmBytes = 0
        writtenEncodedFrames = 0
        hasBeenStopped = false
        framesPerEncodedSample = 0
        audioTrack.flush()
        audioTrackPositionTracker.reset()
    }

    override fun stop() {
        if (hasBeenStopped) {
            return
        }
        hasBeenStopped = true
        audioTrackPositionTracker.handleEndOfStream(this.writtenFrames)
        audioTrack.stop()
        bytesUntilNextAvSync = 0
    }

    override fun release() {
        if (audioTrackPositionTracker.isPlaying) {
            audioTrack.pause()
        }
        if (Build.VERSION.SDK_INT >= 29 && isOffloadedPlayback) {
            offloadStreamEventCallbackV29!!.unregister()
        }
        if (Build.VERSION.SDK_INT >= 24 && onRoutingChangedListener != null) {
            onRoutingChangedListener!!.release()
            onRoutingChangedListener = null
        }
        releaseAudioTrackAsync(audioTrack, listeners)
    }

    override fun setVolume(volume: Float) {
        audioTrack.setVolume(volume)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        if (Build.VERSION.SDK_INT < 29) {
            return
        }
        audioTrack.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    }

    override fun setOffloadEndOfStream() {
        if (Build.VERSION.SDK_INT < 29) {
            return
        }
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            // If the first track is very short (typically <1s), the offload AudioTrack might
            // not have started yet. Do not call setOffloadEndOfStream as it would throw.
            return
        }
        audioTrack.setOffloadEndOfStream()
        audioTrackPositionTracker.expectRawPlaybackHeadReset()
    }

    override fun setPlayerId(playerId: PlayerId) {
        if (Build.VERSION.SDK_INT < 31) {
            return
        }
        val logSessionId = playerId.logSessionId
        if (logSessionId != LogSessionId.LOG_SESSION_ID_NONE) {
            audioTrack.setLogSessionId(logSessionId)
        }
    }

    override fun attachAuxEffect(effectId: Int) {
        audioTrack.attachAuxEffect(effectId)
    }

    override fun setAuxEffectSendLevel(level: Float) {
        audioTrack.setAuxEffectSendLevel(level)
    }

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        audioTrack.setPreferredDevice(preferredDevice)
    }

    override val isStalled: Boolean
        get() = audioTrackPositionTracker.isStalled(this.writtenFrames)

    private val writtenFrames: Long
        get() = if (isOutputPcm) ceilDivide(
            writtenPcmBytes,
            pcmFrameSize.toLong()
        ) else writtenEncodedFrames

    private fun writeWithAvSync(
        audioTrack: AudioTrack,
        buffer: ByteBuffer,
        presentationTimeUs: Long
    ): Int {
        val size = buffer.remaining()
        if (Build.VERSION.SDK_INT >= 26) {
            // The underlying platform AudioTrack writes AV sync headers directly.
            return audioTrack.write(
                buffer, size, AudioTrack.WRITE_NON_BLOCKING, presentationTimeUs * 1000
            )
        }
        val avSyncHeader = avSyncHeader ?: ByteBuffer.allocate(16).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(0x55550001)
            avSyncHeader = this
        }
        if (bytesUntilNextAvSync == 0) {
            avSyncHeader.putInt(4, size)
            avSyncHeader.putLong(8, presentationTimeUs * 1000)
            avSyncHeader.position(0)
            bytesUntilNextAvSync = size
        }
        val avSyncHeaderBytesRemaining = avSyncHeader.remaining()
        if (avSyncHeaderBytesRemaining > 0) {
            val result =
                audioTrack.write(
                    avSyncHeader,
                    avSyncHeaderBytesRemaining,
                    AudioTrack.WRITE_NON_BLOCKING
                )
            if (result < 0) {
                bytesUntilNextAvSync = 0
                return result
            }
            if (result < avSyncHeaderBytesRemaining) {
                return 0
            }
        }
        val result = audioTrack.write(buffer, size, AudioTrack.WRITE_NON_BLOCKING)
        if (result < 0) {
            bytesUntilNextAvSync = 0
            return result
        }
        bytesUntilNextAvSync -= result
        return result
    }

    private fun maybeReportUnderrun() {
        if (listeners.isRunningOnCorrectThread && hasPendingAudioTrackUnderruns(this.writtenFrames)) {
            listeners.sendEvent { obj: AudioOutput.Listener? -> obj!!.onUnderrun() }
        }
    }

    private fun hasPendingAudioTrackUnderruns(writtenFrames: Long): Boolean {
        val underrunCount = getAudioOutputUnderrunCount(writtenFrames)
        val result = underrunCount > lastUnderrunCount

        // If the AudioTrack unexpectedly resets the underrun count, we should update it silently.
        lastUnderrunCount = underrunCount

        return result
    }

    private fun getAudioOutputUnderrunCount(writtenFrames: Long): Int {
        if (Build.VERSION.SDK_INT >= 24) {
            return audioTrack.underrunCount
        }
        val hadData = hasData
        val currentPositionFrames = durationUsToSampleCount(positionUs, sampleRate)
        hasData = writtenFrames > currentPositionFrames
        // For API 23- AudioTrack has no underrun API so we need to infer underruns heuristically.
        val emitUnderrun =
            hadData && !hasData && audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED
        return if (emitUnderrun) lastUnderrunCount + 1 else lastUnderrunCount
    }

    private inner class PositionTrackerListener : AudioTrackPositionTracker.Listener {
        override fun onPositionFramesMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        ) {
            val message = "Spurious audio timestamp (frame position mismatch): " +
                    "$audioTimestampPositionFrames, $audioTimestampSystemTimeUs, $systemTimeUs, " +
                    "$playbackPositionUs, $writtenFrames"
            if (!enableWorkarounds()) {
                throw InvalidAudioTrackTimestampException(message)
            }
            w(TAG, message)
        }

        override fun onSystemTimeUsMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        ) {
            val message = "Spurious audio timestamp (system clock mismatch): " +
                    "$audioTimestampPositionFrames , $audioTimestampSystemTimeUs, $systemTimeUs, " +
                    "$playbackPositionUs, $writtenFrames"
            if (!enableWorkarounds()) {
                throw InvalidAudioTrackTimestampException(message)
            }
            w(TAG, message)
        }

        override fun onInvalidLatency(latencyUs: Long) {
            w(TAG, "Ignoring impossibly large audio latency: $latencyUs")
        }

        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            if (listeners.isRunningOnCorrectThread) {
                listeners.sendEvent { listener: AudioOutput.Listener? ->
                    listener!!.onPositionAdvancing(playoutStartSystemTimeMs)
                }
            }
        }
    }

    class InvalidAudioTrackTimestampException(message: String?) : RuntimeException(message)

    @RequiresApi(24)
    private class OnRoutingChangedListenerApi24(
        private val audioTrack: AudioTrack,
        private val capabilityChangeListener: CapabilityChangeListener
    ) {
        private val playbackThreadHandler: Handler = createHandlerForCurrentLooper()
        private var listener: AudioRouting.OnRoutingChangedListener? =
            AudioRouting.OnRoutingChangedListener { router: AudioRouting? ->
                onRoutingChanged(router!!)
            }

        init {
            audioTrack.addOnRoutingChangedListener(listener, playbackThreadHandler)
        }

        fun release() {
            audioTrack.removeOnRoutingChangedListener(listener!!)
            listener = null
        }

        fun onRoutingChanged(router: AudioRouting) {
            if (listener == null) {
                // Stale event.
                return
            }
            get().execute(
                Runnable {
                    val routedDevice = router.getRoutedDevice()
                    if (routedDevice != null) {
                        playbackThreadHandler.post(
                            Runnable {
                                if (listener == null) {
                                    // Stale event.
                                    return@Runnable
                                }
                                capabilityChangeListener.onRoutedDeviceChanged(routedDevice)
                            })
                    }
                })
        }
    }

    @RequiresApi(29)
    private inner class StreamEventCallbackV29 {
        private val handler: Handler = createHandlerForCurrentLooper()

        // Avoid StreamEventCallbackV29 inheriting directly from AudioTrack.StreamEventCallback as it
        // would cause a NoClassDefFoundError warning on load of DefaultAudioSink for SDK < 29.
        // See: https://github.com/google/ExoPlayer/issues/8058
        private val callback = object : StreamEventCallback() {
            override fun onDataRequest(track: AudioTrack, size: Int) {
                listeners.sendEvent { obj: AudioOutput.Listener -> obj.onOffloadDataRequest() }
            }

            override fun onPresentationEnded(track: AudioTrack) {
                listeners.sendEvent { obj: AudioOutput.Listener -> obj.onOffloadPresentationEnded() }
            }

            override fun onTearDown(track: AudioTrack) {
                // The audio track was destroyed while in use. Thus a new AudioTrack needs to be
                // created and its buffer filled. Request this call explicitly in case ExoPlayer is
                // sleeping waiting for a data request.
                listeners.sendEvent { obj: AudioOutput.Listener -> obj.onOffloadDataRequest() }
            }
        }

        init {
            audioTrack.registerStreamEventCallback(
                { r: Runnable? -> handler.post(r!!) },
                callback
            )
        }

        fun unregister() {
            audioTrack.unregisterStreamEventCallback(callback)
            handler.removeCallbacksAndMessages( null)
        }
    }

    companion object {
        private const val TAG = "AudioTrackAudioOutput"
        private const val ERROR_NATIVE_DEAD_OBJECT = -32
        private const val AUDIO_TRACK_VOLUME_RAMP_TIME_MS = 20
        private val releaseExecutorLock = Any()

        // Intentional statically shared mutable state
        @GuardedBy("releaseExecutorLock")
        private var releaseExecutor: ScheduledExecutorService? = null

        @GuardedBy("releaseExecutorLock")
        private var pendingReleaseCount = 0

        private fun releaseAudioTrackAsync(
            audioTrack: AudioTrack, listeners: ListenerSet<AudioOutput.Listener>
        ) {
            // AudioTrack.release can take some time, so we call it on a background thread. The background
            // thread is shared statically to avoid creating many threads when multiple players are released
            // at the same time.
            val audioTrackThreadHandler = createHandlerForCurrentLooper()
            synchronized(releaseExecutorLock) {
                if (releaseExecutor == null) {
                    releaseExecutor =
                        newSingleThreadScheduledExecutor("ExoPlayer:AudioTrackReleaseThread")
                }
                pendingReleaseCount++
                releaseExecutor!!.schedule(
                    {
                        try {
                            // We need to flush the audio track as some devices are known to keep state from
                            // previous playbacks if the track is not flushed at all (see b/22967293).
                            audioTrack.flush()
                            audioTrack.release()
                        } finally {
                            if (audioTrackThreadHandler.looper.thread.isAlive) {
                                audioTrackThreadHandler.post {
                                    if (listeners.isRunningOnCorrectThread) {
                                        listeners.sendEvent { obj: AudioOutput.Listener? -> obj!!.onReleased() }
                                    }
                                }
                            }
                            synchronized(releaseExecutorLock) {
                                pendingReleaseCount--
                                if (pendingReleaseCount == 0) {
                                    releaseExecutor!!.shutdown()
                                    releaseExecutor = null
                                }
                            }
                        }
                    },  // We need to schedule the flush and release with a delay to ensure the audio system
                    // can completely ramp down the audio output after the preceding pause.
                    AUDIO_TRACK_VOLUME_RAMP_TIME_MS.toLong(),
                    TimeUnit.MILLISECONDS
                )
            }
        }

        private fun isAudioTrackDeadObject(status: Int): Boolean {
            return (Build.VERSION.SDK_INT >= 24 && status == AudioTrack.ERROR_DEAD_OBJECT)
                    || status == ERROR_NATIVE_DEAD_OBJECT
        }
    }
}
