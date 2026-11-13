package androidx.media3.exoplayer

import android.content.Context
import android.os.Build
import android.os.Handler
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
import java.lang.reflect.Constructor

class DefaultRenderersFactory(private val context: Context) : RenderersFactory {
    val codecAdapterFactory = DefaultMediaCodecAdapterFactory(context)
    private val codecAdapterProvider: (MediaCodecAdapter.Configuration) -> MediaCodecAdapter =
        { codecAdapterFactory.createAdapter(it) }
    var extensionRendererMode: Int = EXTENSION_RENDERER_MODE_OFF
    var allowedVideoJoiningTimeMs: Long = DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS
    var enableDecoderFallback = false
    var mediaCodecSelector: MediaCodecSelector? = MediaCodecSelector.DEFAULT
    var enableFloatOutput = false
    var enableMediaCodecVideoRendererPrewarming = false
    var parseAv1SampleDependencies: Boolean = true
    var lateThresholdToDropDecoderInputUs: Long =
        MediaCodecVideoRenderer.DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US
    var videoRendererEarlySchedulingThresholdUs: Long =
        MediaCodecVideoRenderer.DEFAULT_EARLY_SCHEDULING_THRESHOLD_US
    var enableMediaCodecBufferDecodeOnlyFlag = false
    var enableMediaCodecVideoRendererDurationToProgressUs = false

    fun forceEnableMediaCodecAsynchronousQueueing() {
        codecAdapterFactory.forceEnableAsynchronous()
    }

    fun forceDisableMediaCodecAsynchronousQueueing() {
        codecAdapterFactory.forceDisableAsynchronous()
    }

    fun experimentalSetMediaCodecAsyncCryptoFlagEnabled(enableAsyncCryptoFlag: Boolean) {
        codecAdapterFactory.asyncCryptoFlagEnabled = enableAsyncCryptoFlag
    }

    @RequiresApi(34)
    fun experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
        enableMediaCodecBufferDecodeOnlyFlag: Boolean
    ): DefaultRenderersFactory {
        this.enableMediaCodecBufferDecodeOnlyFlag = enableMediaCodecBufferDecodeOnlyFlag
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
        val audioSink = DefaultAudioSink(context).apply {
            this.enableFloatOutput = this@DefaultRenderersFactory.enableFloatOutput
            preferAudioOutputPlaybackParameters = enableFloatOutput
        }
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
        renderersList.add(TextRenderer(textRendererOutput, eventHandler.looper))
        repeat(METADATA_RENDERER_COUNT) {
            renderersList.add(MetadataRenderer(metadataRendererOutput, eventHandler.looper))
        }
        renderersList.add(CameraMotionRenderer())
        renderersList.add(ImageRenderer(getImageDecoderFactory(context), null))
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
        return MediaCodecVideoRenderer(
            context,
            codecAdapterProvider = codecAdapterProvider,
            mediaCodecSelector = mediaCodecSelector!!,
            eventHandler = eventHandler,
            eventListener = eventListener,
            lateThresholdToDropDecoderInputUs = lateThresholdToDropDecoderInputUs,
            enableDecoderFallback = enableDecoderFallback
        ).apply {
            maxDroppedFramesToNotify = MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            parseAv1SampleDependencies = this@DefaultRenderersFactory.parseAv1SampleDependencies
            enableDurationToProgressUs = enableMediaCodecVideoRendererDurationToProgressUs
            earlySchedulingThresholdUs = videoRendererEarlySchedulingThresholdUs
            allowedJoiningTimeMs = allowedVideoJoiningTimeMs
            if (Build.VERSION.SDK_INT >= 34) {
                this.enableMediaCodecBufferDecodeOnlyFlag =
                    this@DefaultRenderersFactory.enableMediaCodecBufferDecodeOnlyFlag
            }
        }
    }

    private fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
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
            val clazz = Class.forName("androidx.media3.decoder.vp9.LibvpxVideoRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Long::class.javaPrimitiveType,
                    Handler::class.java,
                    VideoRendererEventListener::class.java,
                    Int::class.javaPrimitiveType
                )
            val renderer =
                constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded LibvpxVideoRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating VP9 extension", e)
        }
        try {
            val clazz = Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Long::class.javaPrimitiveType,
                    Handler::class.java,
                    VideoRendererEventListener::class.java,
                    Int::class.javaPrimitiveType
                )
            val renderer =
                constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded Libdav1dVideoRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating AV1 extension", e)
        }
        try {
            val clazz =
                Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Long::class.javaPrimitiveType,
                    Handler::class.java,
                    VideoRendererEventListener::class.java,
                    Int::class.javaPrimitiveType
                )
            val renderer =
                constructor.newInstance(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) as Renderer
            out.add(extensionRendererIndex, renderer)
            i(TAG, "Loaded FfmpegVideoRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating FFmpeg extension", e)
        }
    }

    private fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
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
                codecAdapterProvider,
                mediaCodecSelector!!,
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
            val clazz = Class.forName("androidx.media3.decoder.midi.MidiRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Context::class.java,
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            val renderer =
                constructor.newInstance(context, eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded MidiRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating MIDI extension", e)
        }

        try {
            val clazz = Class.forName("androidx.media3.decoder.opus.LibopusAudioRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded LibopusAudioRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating Opus extension", e)
        }
        try {
            val clazz = Class.forName("androidx.media3.decoder.flac.LibflacAudioRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded LibflacAudioRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating FLAC extension", e)
        }

        try {
            val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded FfmpegAudioRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating FFmpeg extension", e)
        }

        try {
            val builderClass =
                Class.forName("androidx.media3.decoder.iamf.IamfAudioRenderer\$Builder")
            val builderConstructor: Constructor<*> =
                builderClass.getConstructor(AudioSink::class.java)
            val builder: Any = builderConstructor.newInstance(audioSink)
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
            out.add(extensionRendererIndex++, renderer)
            i(TAG, "Loaded IamfAudioRenderer.")
        } catch (_: ReflectiveOperationException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating IAMF extension", e)
        }
        try {
            val clazz = Class.forName("androidx.media3.decoder.mpegh.MpeghAudioRenderer")
            val constructor: Constructor<*> =
                clazz.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
            val renderer =
                constructor.newInstance(eventHandler, eventListener, audioSink) as Renderer
            out.add(extensionRendererIndex, renderer)
            i(TAG, "Loaded MpeghAudioRenderer.")
        } catch (_: ClassNotFoundException) {
        } catch (e: Exception) {
            throw IllegalStateException("Error instantiating MPEG-H extension", e)
        }
    }

    override fun createSecondaryRenderer(
        renderer: Renderer,
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener?,
        audioRendererEventListener: AudioRendererEventListener?,
        textRendererOutput: TextOutput?,
        metadataRendererOutput: MetadataOutput?
    ): Renderer? {
        if (renderer.trackType == C.TRACK_TYPE_VIDEO
            && enableMediaCodecVideoRendererPrewarming
            && renderer.javaClass == MediaCodecVideoRenderer::class.java
        ) {
            return createMediaCodecVideoRenderer(
                context,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                videoRendererEventListener,
                allowedVideoJoiningTimeMs
            )
        }
        return null
    }

    private fun getImageDecoderFactory(context: Context): ImageDecoder.Factory {
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
