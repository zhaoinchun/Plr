
package androidx.media3.exoplayer

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.media3.common.C

import androidx.media3.common.util.Log.i

import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder
import androidx.media3.exoplayer.image.ImageDecoder
import androidx.media3.exoplayer.image.ImageRenderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.video.spherical.CameraMotionRenderer
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.errorprone.annotations.ForOverride
import java.lang.reflect.Constructor
import kotlin.collections.toTypedArray

class DefaultRenderersFactory(private val context: Context) : RenderersFactory {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [EXTENSION_RENDERER_MODE_OFF, EXTENSION_RENDERER_MODE_ON, EXTENSION_RENDERER_MODE_PREFER])
    annotation class ExtensionRendererMode

    private val codecAdapterFactory: DefaultMediaCodecAdapterFactory
    private var extensionRendererMode: @ExtensionRendererMode Int
    private var allowedVideoJoiningTimeMs: Long
    private var enableDecoderFallback = false
    private var mediaCodecSelector: MediaCodecSelector?
    private var enableFloatOutput = false
    private var enableAudioOutputPlaybackParameters = false
    private var enableMediaCodecVideoRendererPrewarming = false
    private var parseAv1SampleDependencies: Boolean
    private var lateThresholdToDropDecoderInputUs: Long
    private var videoRendererEarlySchedulingThresholdUs: Long
    private var enableMediaCodecBufferDecodeOnlyFlag = false
    private var enableMediaCodecVideoRendererDurationToProgressUs = false

    init {
        codecAdapterFactory = DefaultMediaCodecAdapterFactory(context)
        extensionRendererMode = EXTENSION_RENDERER_MODE_OFF
        allowedVideoJoiningTimeMs = DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS
        mediaCodecSelector = MediaCodecSelector.DEFAULT
        parseAv1SampleDependencies = true
        lateThresholdToDropDecoderInputUs =
            MediaCodecVideoRenderer.DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US
        videoRendererEarlySchedulingThresholdUs =
            MediaCodecVideoRenderer.DEFAULT_EARLY_SCHEDULING_THRESHOLD_US
    }

    fun setExtensionRendererMode(
        extensionRendererMode: @ExtensionRendererMode Int
    ): DefaultRenderersFactory {
        this.extensionRendererMode = extensionRendererMode
        return this
    }

    fun forceEnableMediaCodecAsynchronousQueueing(): DefaultRenderersFactory {
        codecAdapterFactory.forceEnableAsynchronous()
        return this
    }

    fun forceDisableMediaCodecAsynchronousQueueing(): DefaultRenderersFactory {
        codecAdapterFactory.forceDisableAsynchronous()
        return this
    }

     // TODO: b/470368123 - Remove method once flag usage once safe.
    fun experimentalSetMediaCodecAsyncCryptoFlagEnabled(
        enableAsyncCryptoFlag: Boolean
    ): DefaultRenderersFactory {
        codecAdapterFactory.setAsyncCryptoFlagEnabled(enableAsyncCryptoFlag)
        return this
    }

    fun setEnableDecoderFallback(enableDecoderFallback: Boolean): DefaultRenderersFactory {
        this.enableDecoderFallback = enableDecoderFallback
        return this
    }

    fun setMediaCodecSelector(
        mediaCodecSelector: MediaCodecSelector?
    ): DefaultRenderersFactory {
        this.mediaCodecSelector = mediaCodecSelector
        return this
    }

    fun setEnableAudioFloatOutput(enableFloatOutput: Boolean): DefaultRenderersFactory {
        this.enableFloatOutput = enableFloatOutput
        return this
    }

    @Deprecated("Use {@link #setEnableAudioOutputPlaybackParameters(boolean)} instead.")
    fun setEnableAudioTrackPlaybackParams(
        enableAudioTrackPlaybackParams: Boolean
    ): DefaultRenderersFactory {
        return setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
    }

    fun setEnableAudioOutputPlaybackParameters(
        enableAudioOutputPlaybackParameters: Boolean
    ): DefaultRenderersFactory {
        this.enableAudioOutputPlaybackParameters = enableAudioOutputPlaybackParameters
        return this
    }

     // TODO: b/470365582 - Enable as a permanent, non-experimental option.
    fun experimentalSetEnableMediaCodecVideoRendererPrewarming(
        enableMediaCodecVideoRendererPrewarming: Boolean
    ): DefaultRenderersFactory {
        this.enableMediaCodecVideoRendererPrewarming = enableMediaCodecVideoRendererPrewarming
        return this
    }

     // TODO: b/470365670 - Remove method once config is enabled by default.
    fun experimentalSetParseAv1SampleDependencies(
        parseAv1SampleDependencies: Boolean
    ): DefaultRenderersFactory {
        this.parseAv1SampleDependencies = parseAv1SampleDependencies
        return this
    }

    @RequiresApi(34)
    
     // TODO: b/470367414 - Run experiments and enable by default.
    fun experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
        enableMediaCodecBufferDecodeOnlyFlag: Boolean
    ): DefaultRenderersFactory {
        this.enableMediaCodecBufferDecodeOnlyFlag = enableMediaCodecBufferDecodeOnlyFlag
        return this
    }

     // TODO: b/369523131 - Remove once experiment is complete.
    fun setEnableMediaCodecVideoRendererDurationToProgressUs(
        enableMediaCodecVideoRendererDurationToProgressUs: Boolean
    ): DefaultRenderersFactory {
        this.enableMediaCodecVideoRendererDurationToProgressUs =
            enableMediaCodecVideoRendererDurationToProgressUs
        return this
    }

    fun setAllowedVideoJoiningTimeMs(
        allowedVideoJoiningTimeMs: Long
    ): DefaultRenderersFactory {
        this.allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs
        return this
    }

     // TODO: b/470367421 - Remove or make non-experimental.
    fun experimentalSetLateThresholdToDropDecoderInputUs(
        lateThresholdToDropDecoderInputUs: Long
    ): DefaultRenderersFactory {
        this.lateThresholdToDropDecoderInputUs = lateThresholdToDropDecoderInputUs
        return this
    }

    fun setVideoRendererEarlySchedulingThresholdUs(
        videoRendererEarlySchedulingThresholdUs: Long
    ): DefaultRenderersFactory {
        this.videoRendererEarlySchedulingThresholdUs = videoRendererEarlySchedulingThresholdUs
        return this
    }

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput?,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {
        val renderersList = ArrayList<Renderer>()
        buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            videoRendererEventListener,
            allowedVideoJoiningTimeMs,
            renderersList
        )
        val audioSink =
            buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParameters)
        if (audioSink != null) {
            buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                audioRendererEventListener,
                renderersList
            )
        }
        buildTextRenderers(
            context,
            textRendererOutput,
            eventHandler.looper,
            extensionRendererMode,
            renderersList
        )
        buildMetadataRenderers(
            context,
            metadataRendererOutput,
            eventHandler.looper,
            extensionRendererMode,
            renderersList
        )
        buildCameraMotionRenderers(context, extensionRendererMode, renderersList)
        buildImageRenderers(context, renderersList)
        buildMiscellaneousRenderers(context, eventHandler, extensionRendererMode, renderersList)
        return renderersList.toTypedArray<Renderer>()
    }

    private fun createMediaCodecVideoRenderer(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        allowedVideoJoiningTimeMs: Long
    ): MediaCodecVideoRenderer {
        var builder =
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(getCodecAdapterFactory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .experimentalSetParseAv1SampleDependencies(parseAv1SampleDependencies)
                .experimentalSetLateThresholdToDropDecoderInputUs(lateThresholdToDropDecoderInputUs)
                .setEnableDurationToProgressUs(enableMediaCodecVideoRendererDurationToProgressUs)
                .setEarlySchedulingThresholdUs(videoRendererEarlySchedulingThresholdUs)
        if (Build.VERSION.SDK_INT >= 34) {
            builder =
                builder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
                    enableMediaCodecBufferDecodeOnlyFlag
                )
        }
        return builder.build()
    }

    protected fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: @ExtensionRendererMode Int,
        mediaCodecSelector: MediaCodecSelector?,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(
            createMediaCodecVideoRenderer(
                context,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs
            )
        )

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return
        }
        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            // LINT.IfChange
            val clazz = Class.forName("androidx.media3.decoder.vp9.LibvpxVideoRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Long::class.javaPrimitiveType,
                    Handler::class.java,
                    VideoRendererEventListener::class.java,
                    Int::class.javaPrimitiveType
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded LibvpxVideoRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating VP9 extension", e)
        }

        try {
            // LINT.IfChange
            val clazz = Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Long::class.javaPrimitiveType,
                    Handler::class.java,
                    VideoRendererEventListener::class.java,
                    Int::class.javaPrimitiveType
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded Libdav1dVideoRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating AV1 extension", e)
        }

        try {
            // LINT.IfChange
            val clazz =
                Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Long::class.javaPrimitiveType,
                    Handler::class.java,
                    VideoRendererEventListener::class.java,
                    Int::class.javaPrimitiveType
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded FfmpegVideoRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating FFmpeg extension", e)
        }
    }

    protected fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: @ExtensionRendererMode Int,
        mediaCodecSelector: MediaCodecSelector?,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler?,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val audioRenderer =
            MediaCodecAudioRenderer(
                context,
                getCodecAdapterFactory(),
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                audioSink
            )
        out.add(audioRenderer)

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return
        }
        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            // LINT.IfChange
            val clazz = Class.forName("androidx.media3.decoder.midi.MidiRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Context::class.java,
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(context, eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded MidiRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating MIDI extension", e)
        }

        try {
            // LINT.IfChange
            val clazz = Class.forName("androidx.media3.decoder.opus.LibopusAudioRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded LibopusAudioRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating Opus extension", e)
        }

        try {
            // LINT.IfChange
            val clazz = Class.forName("androidx.media3.decoder.flac.LibflacAudioRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded LibflacAudioRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating FLAC extension", e)
        }

        try {
            // LINT.IfChange
            val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded FfmpegAudioRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating FFmpeg extension", e)
        }

        try {
            // LINT.IfChange
            val builderClass =
                Class.forName("androidx.media3.decoder.iamf.IamfAudioRenderer\$Builder")
            // Full class names used for media3 constructor args so the LINT rule triggers if any move.
            val builderConstructor: Constructor<*> =
                builderClass.getConstructor(AudioSink::class.java)
            val builder: Any = builderConstructor.newInstance(audioSink)
            // Full class names used for media3 constructor args so the LINT rule triggers if any move.
            val audioRenderEventListenerClass: Class<*> =
                AudioRendererEventListener::class.java
            builderClass
                .getMethod(
                    "setEventHandlerAndListener",
                    Handler::class.java,
                    audioRenderEventListenerClass
                )
                .invoke(builder, eventHandler, eventListener)
            val renderer = builderClass.getMethod("build").invoke(builder) as Renderer
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded IamfAudioRenderer.")
        } catch (e: ReflectiveOperationException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating IAMF extension", e)
        }

        try {
            // LINT.IfChange
            val clazz = Class.forName("androidx.media3.decoder.mpegh.MpeghAudioRenderer")
            // Full class names used for media3 constructor args so the LINT rule triggers if any of them
            // move.
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded MpeghAudioRenderer.")
        } catch (e: ClassNotFoundException) {
            // Expected if the app was built without the extension.
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw IllegalStateException("Error instantiating MPEG-H extension", e)
        }
    }

    protected fun buildTextRenderers(
        context: Context?,
        output: TextOutput?,
        outputLooper: Looper?,
        extensionRendererMode: @ExtensionRendererMode Int,
        out: ArrayList<Renderer>
    ) {
        out.add(TextRenderer(output, outputLooper))
    }

    protected fun buildMetadataRenderers(
        context: Context?,
        output: MetadataOutput,
        outputLooper: Looper?,
        extensionRendererMode: @ExtensionRendererMode Int,
        out: ArrayList<Renderer>
    ) {
        for (i in 0..<METADATA_RENDERER_COUNT) {
            out.add(MetadataRenderer(output, outputLooper))
        }
    }

    protected fun buildCameraMotionRenderers(
        context: Context?,
        extensionRendererMode: @ExtensionRendererMode Int,
        out: ArrayList<Renderer>
    ) {
        out.add(CameraMotionRenderer())
    }

    @Deprecated("Override {@link #buildImageRenderers(Context, ArrayList)} instead.")
    protected fun buildImageRenderers(out: ArrayList<Renderer>) {
        out.add(ImageRenderer(getImageDecoderFactory(context),  null))
    }

    protected fun buildImageRenderers(context: Context?, out: ArrayList<Renderer>) {
        buildImageRenderers(out)
    }

    protected fun buildMiscellaneousRenderers(
        context: Context?,
        eventHandler: Handler?,
        extensionRendererMode: @ExtensionRendererMode Int,
        out: ArrayList<Renderer>?
    ) {
        // Do nothing.
    }

    protected fun buildAudioSink(
        context: Context?, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean
    ): AudioSink? {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
    }

    override fun createSecondaryRenderer(
        renderer: Renderer,
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener?,
        audioRendererEventListener: AudioRendererEventListener?,
        textRendererOutput: TextOutput?,
        metadataRendererOutput: MetadataOutput?
    ): Renderer? {
        if (renderer.trackType == C.TRACK_TYPE_VIDEO) {
            return buildSecondaryVideoRenderer(
                renderer,
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                videoRendererEventListener,
                allowedVideoJoiningTimeMs
            )
        }
        return null
    }

    protected fun buildSecondaryVideoRenderer(
        renderer: Renderer,
        context: Context,
        extensionRendererMode: @ExtensionRendererMode Int,
        mediaCodecSelector: MediaCodecSelector?,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        allowedVideoJoiningTimeMs: Long
    ): Renderer? {
        if (enableMediaCodecVideoRendererPrewarming
            && renderer.javaClass == MediaCodecVideoRenderer::class.java
        ) {
            return createMediaCodecVideoRenderer(
                context,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs
            )
        }
        return null
    }

    protected fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
        return codecAdapterFactory
    }

    @ForOverride
    protected fun getImageDecoderFactory(context: Context?): ImageDecoder.Factory {
        return BitmapFactoryImageDecoder.Factory(context)
    }

    companion object {
        const val DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS: Long = 5000
        const val EXTENSION_RENDERER_MODE_OFF: Int = 0
        const val EXTENSION_RENDERER_MODE_ON: Int = 1
        const val EXTENSION_RENDERER_MODE_PREFER: Int = 2
        const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY: Int = 50
        private const val METADATA_RENDERER_COUNT = 4
        private const val TAG = "DefaultRenderersFactory"
    }
}
