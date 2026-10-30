package androidx.media3.extractor

import android.net.Uri
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.FileTypes
import androidx.media3.common.FileTypes.inferFileTypeFromResponseHeaders
import androidx.media3.common.FileTypes.inferFileTypeFromUri
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.avif.AvifExtractor
import androidx.media3.extractor.bmp.BmpExtractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.flv.FlvExtractor
import androidx.media3.extractor.heif.HeifExtractor
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.png.PngExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.Ac3Extractor
import androidx.media3.extractor.ts.Ac4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PsExtractor
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.extractor.webp.WebpExtractor
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.Any
import kotlin.Array
import kotlin.Deprecated
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.RuntimeException
import kotlin.String
import kotlin.Throws
import kotlin.intArrayOf
import kotlin.synchronized

class DefaultExtractorsFactory : ExtractorsFactory {
    private var constantBitrateSeekingEnabled = false
    private var constantBitrateSeekingAlwaysEnabled = false
    private var disableArtworkMetadata = false
    private var adtsFlags: @AdtsExtractor.Flags Int = 0
    private var amrFlags: @AmrExtractor.Flags Int = 0
    private var flacFlags: @FlacExtractor.Flags Int = 0
    private var matroskaFlags: @MatroskaExtractor.Flags Int = 0
    private var mp4Flags: @Mp4Extractor.Flags Int = 0
    private var fragmentedMp4Flags: @FragmentedMp4Extractor.Flags Int = 0
    private var mp3Flags: @Mp3Extractor.Flags Int = 0
    private var tsMode: @TsExtractor.Mode Int
    private var tsFlags: @DefaultTsPayloadReaderFactory.Flags Int = 0

    private var tsSubtitleFormats: List<Format>? = null
    private var tsTimestampSearchBytes: Int
    private var textTrackTranscodingEnabled: Boolean
    private var subtitleParserFactory: SubtitleParser.Factory
    private var codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
    private var jpegFlags: @JpegExtractor.Flags Int = 0
    private var heifFlags: @HeifExtractor.Flags Int = 0

    init {
        tsMode = TsExtractor.MODE_SINGLE_PMT
        tsTimestampSearchBytes = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
        subtitleParserFactory = DefaultSubtitleParserFactory()
        textTrackTranscodingEnabled = true
        codecsToParseWithinGopSampleDependencies =
            C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265
    }

    @Synchronized
    fun setConstantBitrateSeekingEnabled(
        constantBitrateSeekingEnabled: Boolean
    ): DefaultExtractorsFactory {
        this.constantBitrateSeekingEnabled = constantBitrateSeekingEnabled
        return this
    }

    @Synchronized
    fun setConstantBitrateSeekingAlwaysEnabled(
        constantBitrateSeekingAlwaysEnabled: Boolean
    ): DefaultExtractorsFactory {
        this.constantBitrateSeekingAlwaysEnabled = constantBitrateSeekingAlwaysEnabled
        return this
    }

    @Synchronized
    fun setDisableArtworkMetadata(
        disableArtworkMetadata: Boolean
    ): DefaultExtractorsFactory {
        this.disableArtworkMetadata = disableArtworkMetadata
        return this
    }

    @Synchronized
    fun setAdtsExtractorFlags(
        flags: @AdtsExtractor.Flags Int
    ): DefaultExtractorsFactory {
        this.adtsFlags = flags
        return this
    }

    @Synchronized
    fun setAmrExtractorFlags(flags: @AmrExtractor.Flags Int): DefaultExtractorsFactory {
        this.amrFlags = flags
        return this
    }

    @Synchronized
    fun setFlacExtractorFlags(
        flags: @FlacExtractor.Flags Int
    ): DefaultExtractorsFactory {
        this.flacFlags = flags
        return this
    }

    @Synchronized
    fun setMatroskaExtractorFlags(
        flags: @MatroskaExtractor.Flags Int
    ): DefaultExtractorsFactory {
        this.matroskaFlags = flags
        return this
    }

    @Synchronized
    fun setMp4ExtractorFlags(flags: @Mp4Extractor.Flags Int): DefaultExtractorsFactory {
        this.mp4Flags = flags
        return this
    }

    @Synchronized
    fun setFragmentedMp4ExtractorFlags(
        flags: @FragmentedMp4Extractor.Flags Int
    ): DefaultExtractorsFactory {
        this.fragmentedMp4Flags = flags
        return this
    }

    @Synchronized
    fun setMp3ExtractorFlags(flags: @Mp3Extractor.Flags Int): DefaultExtractorsFactory {
        mp3Flags = flags
        return this
    }

    @Synchronized
    fun setTsExtractorMode(mode: @TsExtractor.Mode Int): DefaultExtractorsFactory {
        tsMode = mode
        return this
    }

    @Synchronized
    fun setTsExtractorFlags(
        flags: @DefaultTsPayloadReaderFactory.Flags Int
    ): DefaultExtractorsFactory {
        tsFlags = flags
        return this
    }

    @Synchronized
    fun setTsSubtitleFormats(subtitleFormats: MutableList<Format>): DefaultExtractorsFactory {
        tsSubtitleFormats = subtitleFormats.toList()
        return this
    }

    @Synchronized
    fun setTsExtractorTimestampSearchBytes(
        timestampSearchBytes: Int
    ): DefaultExtractorsFactory {
        tsTimestampSearchBytes = timestampSearchBytes
        return this
    }

    @Deprecated(
        """This method (and all support for 'legacy' subtitle decoding during rendering) will
        be removed in a future release."""
    )
    @Synchronized
    fun setTextTrackTranscodingEnabled(
        textTrackTranscodingEnabled: Boolean
    ): DefaultExtractorsFactory {
        return experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }

    @Deprecated("")
    @Synchronized
    override fun experimentalSetTextTrackTranscodingEnabled(
        textTrackTranscodingEnabled: Boolean
    ): DefaultExtractorsFactory {
        this.textTrackTranscodingEnabled = textTrackTranscodingEnabled
        return this
    }

    @Synchronized
    override fun setSubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory
    ): DefaultExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        return this
    }

    @Synchronized
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
    ): DefaultExtractorsFactory {
        this.codecsToParseWithinGopSampleDependencies = codecsToParseWithinGopSampleDependencies
        return this
    }

    @Synchronized
    fun setJpegExtractorFlags(
        flags: @JpegExtractor.Flags Int
    ): DefaultExtractorsFactory {
        this.jpegFlags = flags
        return this
    }

    @Synchronized
    fun setHeifExtractorFlags(
        flags: @HeifExtractor.Flags Int
    ): DefaultExtractorsFactory {
        this.heifFlags = flags
        return this
    }

    @Synchronized
    override fun createExtractors(): Array<Extractor> {
        return createExtractors(Uri.EMPTY, HashMap())
    }

    @Synchronized
    override fun createExtractors(
        uri: Uri, responseHeaders: MutableMap<String, List<String>>
    ): Array<Extractor> {
        val extractors: MutableList<Extractor> =
            ArrayList<Extractor>( DEFAULT_EXTRACTOR_ORDER.size)

        val responseHeadersInferredFileType: Int =
            inferFileTypeFromResponseHeaders(responseHeaders)
        if (responseHeadersInferredFileType != FileTypes.UNKNOWN) {
            addExtractorsForFileType(responseHeadersInferredFileType, extractors)
        }

        val uriInferredFileType: Int = inferFileTypeFromUri(uri)
        if (uriInferredFileType != FileTypes.UNKNOWN
            && uriInferredFileType != responseHeadersInferredFileType
        ) {
            addExtractorsForFileType(uriInferredFileType, extractors)
        }

        for (fileType in DEFAULT_EXTRACTOR_ORDER) {
            if (fileType != responseHeadersInferredFileType && fileType != uriInferredFileType) {
                addExtractorsForFileType(fileType, extractors)
            }
        }
        return extractors.toTypedArray<Extractor>()
    }

    private fun addExtractorsForFileType(
        fileType: Int,
        extractors: MutableList<Extractor>
    ) {
        when (fileType) {
            FileTypes.AC3 -> extractors.add(Ac3Extractor())
            FileTypes.AC4 -> extractors.add(Ac4Extractor())
            FileTypes.ADTS -> extractors.add(
                AdtsExtractor(
                    (adtsFlags
                            or (if (constantBitrateSeekingEnabled)
                        AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
                    else
                        0)
                            or (if (constantBitrateSeekingAlwaysEnabled)
                        AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS
                    else
                        0))
                )
            )

            FileTypes.AMR -> extractors.add(
                AmrExtractor(
                    (amrFlags
                            or (if (constantBitrateSeekingEnabled)
                        AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
                    else
                        0)
                            or (if (constantBitrateSeekingAlwaysEnabled)
                        AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS
                    else
                        0))
                )
            )

            FileTypes.FLAC -> {
                val effectiveFlacFlags =
                    flacFlags or (if (disableArtworkMetadata) FlacExtractor.FLAG_DISABLE_ARTWORK_METADATA else 0)
                val flacExtractor: Extractor? =
                    FLAC_EXTENSION_LOADER.getExtractor(effectiveFlacFlags)
                if (flacExtractor != null) {
                    extractors.add(flacExtractor)
                } else {
                    extractors.add(FlacExtractor(effectiveFlacFlags))
                }
            }

            FileTypes.FLV -> extractors.add(FlvExtractor())
            FileTypes.MATROSKA -> extractors.add(
                MatroskaExtractor(
                    subtitleParserFactory,
                    matroskaFlags
                            or (if (textTrackTranscodingEnabled)
                        0
                    else
                        MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
                )
            )

            FileTypes.MP3 -> extractors.add(
                Mp3Extractor(
                    (mp3Flags
                            or (if (constantBitrateSeekingEnabled)
                        Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
                    else
                        0)
                            or (if (constantBitrateSeekingAlwaysEnabled)
                        Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS
                    else
                        0)
                            or (if (disableArtworkMetadata) Mp3Extractor.FLAG_DISABLE_ARTWORK_METADATA else 0))
                )
            )

            FileTypes.MP4 -> {
                extractors.add(
                    FragmentedMp4Extractor(
                        subtitleParserFactory,
                        (fragmentedMp4Flags
                                or FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP
                                or FragmentedMp4Extractor.codecsToParseWithinGopSampleDependenciesAsFlags(
                            codecsToParseWithinGopSampleDependencies
                        )
                                or (if (textTrackTranscodingEnabled)
                            0
                        else
                            FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
                                or (if (disableArtworkMetadata)
                            FragmentedMp4Extractor.FLAG_DISABLE_ARTWORK_METADATA
                        else
                            0))
                    )
                )
                extractors.add(
                    Mp4Extractor(
                        subtitleParserFactory,
                        (mp4Flags
                                or Mp4Extractor.codecsToParseWithinGopSampleDependenciesAsFlags(
                            codecsToParseWithinGopSampleDependencies
                        )
                                or (if (textTrackTranscodingEnabled) 0 else Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
                                or (if (disableArtworkMetadata) Mp4Extractor.FLAG_DISABLE_ARTWORK_METADATA else 0))
                    )
                )
            }

            FileTypes.OGG -> extractors.add(OggExtractor())
            FileTypes.PS -> extractors.add(PsExtractor())
            FileTypes.TS -> {
                if (tsSubtitleFormats == null) {
                    tsSubtitleFormats = mutableListOf()
                }
                extractors.add(
                    TsExtractor(
                        tsMode,
                        (if (textTrackTranscodingEnabled) 0 else TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA),
                        subtitleParserFactory,
                        TimestampAdjuster(0),
                        DefaultTsPayloadReaderFactory(tsFlags, tsSubtitleFormats!!),
                        tsTimestampSearchBytes
                    )
                )
            }

            FileTypes.WAV -> extractors.add(WavExtractor())
            FileTypes.JPEG -> extractors.add(JpegExtractor(jpegFlags))
            FileTypes.MIDI -> {
                val midiExtractor: Extractor? = MIDI_EXTENSION_LOADER.getExtractor()
                if (midiExtractor != null) {
                    extractors.add(midiExtractor)
                }
            }

            FileTypes.AVI -> extractors.add(
                AviExtractor(
                    (if (textTrackTranscodingEnabled) 0 else AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA),
                    subtitleParserFactory
                )
            )

            FileTypes.PNG -> extractors.add(PngExtractor())
            FileTypes.WEBP -> extractors.add(WebpExtractor())
            FileTypes.BMP -> extractors.add(BmpExtractor())
            FileTypes.HEIF -> extractors.add(HeifExtractor(heifFlags))
            FileTypes.AVIF -> extractors.add(AvifExtractor())
            FileTypes.WEBVTT, FileTypes.UNKNOWN -> {}
            else -> {}
        }
    }

    private class ExtensionLoader(private val constructorSupplier: ConstructorSupplier) {
        interface ConstructorSupplier {
            @get:Throws(
                InvocationTargetException::class,
                IllegalAccessException::class,
                NoSuchMethodException::class,
                ClassNotFoundException::class
            )
            val constructor: Constructor<out Extractor?>?
        }

        private val extensionLoaded: AtomicBoolean

        @GuardedBy("extensionLoaded")
        private val extractorConstructor: Constructor<out Extractor?>? = null

        init {
            extensionLoaded = AtomicBoolean(false)
        }

        fun getExtractor(vararg constructorParams: Any?): Extractor? {
            val extractorConstructor = maybeLoadExtractorConstructor()
            if (extractorConstructor == null) {
                return null
            }
            try {
                return extractorConstructor.newInstance(*constructorParams)
            } catch (e: Exception) {
                throw IllegalStateException("Unexpected error creating extractor", e)
            }
        }

        fun maybeLoadExtractorConstructor(): Constructor<out Extractor?>? {
            synchronized(extensionLoaded) {
                if (extensionLoaded.get()) {
                    return extractorConstructor
                }
                try {
                    return constructorSupplier.constructor
                } catch (e: ClassNotFoundException) {
                    // Expected if the app was built without the extension.
                } catch (e: Exception) {
                    // The extension is present, but instantiation failed.
                    throw RuntimeException("Error instantiating extension", e)
                }
                extensionLoaded.set(true)
                return extractorConstructor
            }
        }
    }

    companion object {
        // Extractors order is optimized according to
        // https://docs.google.com/document/d/1w2mKaWMxfz2Ei8-LdxqbPs1VLe_oudB-eryXXw9OvQQ.
        // The JPEG extractor appears after audio/video extractors because we expect audio/video input to
        // be more common.
        private val DEFAULT_EXTRACTOR_ORDER = intArrayOf(
            FileTypes.FLV,
            FileTypes.FLAC,
            FileTypes.WAV,
            FileTypes.MP4,
            FileTypes.AMR,
            FileTypes.PS,
            FileTypes.OGG,
            FileTypes.TS,
            FileTypes.MATROSKA,
            FileTypes.ADTS,
            FileTypes.AC3,
            FileTypes.AC4,
            FileTypes.MP3,  // The following extractors are not part of the optimized ordering, and were appended
            // without further analysis.
            FileTypes.AVI,
            FileTypes.MIDI,
            FileTypes.JPEG,
            FileTypes.PNG,
            FileTypes.WEBP,
            FileTypes.BMP,
            FileTypes.HEIF,
            FileTypes.AVIF
        )

        private val FLAC_EXTENSION_LOADER =
            ExtensionLoader(object : ExtensionLoader.ConstructorSupplier {
                override val constructor: Constructor<out Extractor?>?
                    get() = flacExtractorConstructor
            })
        private val MIDI_EXTENSION_LOADER =
            ExtensionLoader(object : ExtensionLoader.ConstructorSupplier {
                override val constructor: Constructor<out Extractor?>
                    get() = midiExtractorConstructor
            })

        @get:Throws(
            ClassNotFoundException::class,
            NoSuchMethodException::class
        )
        private val midiExtractorConstructor: Constructor<out Extractor?>
            get() =// LINT.IfChange
                Class.forName("androidx.media3.decoder.midi.MidiExtractor")
                    .asSubclass<Extractor?>(Extractor::class.java)
                    .getConstructor()
        // LINT.ThenChange(../../../../../../proguard-rules.txt)

        @get:Throws(
            ClassNotFoundException::class,
            NoSuchMethodException::class,
            InvocationTargetException::class,
            IllegalAccessException::class
        )
        private val flacExtractorConstructor: Constructor<out Extractor?>?
            get() {
                // LINT.IfChange
                val isFlacNativeLibraryAvailable =
                    true == Class.forName("androidx.media3.decoder.flac.FlacLibrary")
                        .getMethod("isAvailable")
                        .invoke( null)
                if (isFlacNativeLibraryAvailable) {
                    return Class.forName("androidx.media3.decoder.flac.FlacExtractor")
                        .asSubclass<Extractor?>(Extractor::class.java)
                        .getConstructor(Int::class.javaPrimitiveType)
                }
                return null
                // LINT.ThenChange(../../../../../../proguard-rules.txt)
            }
    }
}
