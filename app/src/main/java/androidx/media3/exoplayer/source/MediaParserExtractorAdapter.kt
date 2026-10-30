package androidx.media3.exoplayer.source

import android.annotation.SuppressLint
import android.media.MediaParser
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.mediaparser.InputReaderAdapterV30
import androidx.media3.exoplayer.source.mediaparser.MediaParserUtil
import androidx.media3.exoplayer.source.mediaparser.OutputConsumerAdapterV30
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import java.io.IOException

@RequiresApi(30)
class MediaParserExtractorAdapter @SuppressLint("WrongConstant") private constructor(
    playerId: PlayerId,
    parameters: Map<String, Any>
) : ProgressiveMediaExtractor {

    class Factory : ProgressiveMediaExtractor.Factory {
        fun setConstantBitrateSeekingEnabled(enabled: Boolean) {
            if (enabled) {
                parameters[MediaParser.PARAMETER_ADTS_ENABLE_CBR_SEEKING] = true
                parameters[MediaParser.PARAMETER_AMR_ENABLE_CBR_SEEKING] = true
                parameters[MediaParser.PARAMETER_MP3_ENABLE_CBR_SEEKING] = true
            } else {
                parameters.remove(MediaParser.PARAMETER_ADTS_ENABLE_CBR_SEEKING)
                parameters.remove(MediaParser.PARAMETER_AMR_ENABLE_CBR_SEEKING)
                parameters.remove(MediaParser.PARAMETER_MP3_ENABLE_CBR_SEEKING)
            }
        }

        override fun createProgressiveMediaExtractor(playerId: PlayerId): MediaParserExtractorAdapter {
            return MediaParserExtractorAdapter(playerId, parameters)
        }

        companion object {
            private val parameters: MutableMap<String, Any> = HashMap()
        }
    }

    private val outputConsumerAdapter: OutputConsumerAdapterV30 = OutputConsumerAdapterV30()
    private val inputReaderAdapter: InputReaderAdapterV30 = InputReaderAdapterV30()
    private val mediaParser: MediaParser = MediaParser.create(outputConsumerAdapter)
    private var parserName = MediaParser.PARSER_NAME_UNKNOWN

    @Deprecated("Use {@link Factory} instead.")
    constructor(playerId: PlayerId) : this(playerId, mapOf())

    init {
        mediaParser.setParameter(MediaParserUtil.PARAMETER_EAGERLY_EXPOSE_TRACK_TYPE, true)
        mediaParser.setParameter(MediaParserUtil.PARAMETER_IN_BAND_CRYPTO_INFO, true)
        mediaParser.setParameter(MediaParserUtil.PARAMETER_INCLUDE_SUPPLEMENTAL_DATA, true)
        for (parameter in parameters.entries) {
            mediaParser.setParameter(parameter.key, parameter.value)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            MediaParserUtil.setLogSessionIdOnMediaParser(mediaParser, playerId)
        }
    }

    @Throws(IOException::class)
    override fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: MutableMap<String, List<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput
    ) {
        outputConsumerAdapter.setExtractorOutput(output)
        inputReaderAdapter.setDataReader(dataReader, length)
        inputReaderAdapter.setCurrentPosition(position)
        val currentParserName = mediaParser.parserName
        if (MediaParser.PARSER_NAME_UNKNOWN == currentParserName) {
            // We need to sniff.
            mediaParser.advance(inputReaderAdapter)
            parserName = mediaParser.parserName
            outputConsumerAdapter.setSelectedParserName(parserName)
        } else if (currentParserName != parserName) {
            // The parser was created by name.
            parserName = mediaParser.parserName
            outputConsumerAdapter.setSelectedParserName(parserName)
        } else {
            // The parser implementation has already been selected. Do nothing.
        }
    }

    override fun release() {
        mediaParser.release()
    }

    override fun disableSeekingOnMp3Streams() {
        if (MediaParser.PARSER_NAME_MP3 == parserName) {
            outputConsumerAdapter.disableSeeking()
        }
    }

    override val currentInputPosition: Long
        get() = inputReaderAdapter.position

    override fun seek(position: Long, seekTimeUs: Long) {
        inputReaderAdapter.setCurrentPosition(position)
        val seekPoints = outputConsumerAdapter.getSeekPoints(seekTimeUs)
        mediaParser.seek((if (seekPoints.second!!.position == position) seekPoints.second else seekPoints.first)!!)
    }

    @Throws(IOException::class)
    override fun read(positionHolder: PositionHolder): Int {
        val shouldContinue = mediaParser.advance(inputReaderAdapter)
        positionHolder.position = inputReaderAdapter.getAndResetSeekPosition()
        return if (!shouldContinue)
            Extractor.RESULT_END_OF_INPUT
        else
            if (positionHolder.position != C.INDEX_UNSET.toLong())
                Extractor.RESULT_SEEK
            else
                Extractor.RESULT_CONTINUE
    }

    override val underlyingImplementationName: String?
        get() = if (mediaParser.parserName == MediaParser.PARSER_NAME_UNKNOWN)
            null
        else
            mediaParser.parserName

}
