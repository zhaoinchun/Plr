package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.mp3.Mp3Extractor
import com.google.common.base.Function
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.Lists
import java.io.EOFException
import java.io.IOException

class BundledExtractorsAdapter(private val extractorsFactory: ExtractorsFactory) :
    ProgressiveMediaExtractor {
    private var extractor: Extractor? = null
    private var extractorInput: ExtractorInput? = null

    @Throws(IOException::class)
    override fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: MutableMap<String, List<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput
    ) {
        val extractorInput: ExtractorInput = DefaultExtractorInput(dataReader, position, length)
        this.extractorInput = extractorInput
        if (extractor != null) {
            return
        }
        val extractors = extractorsFactory.createExtractors(uri, responseHeaders)
        val sniffFailures = mutableListOf<SniffFailure>()
        if (extractors.size == 1) {
            this.extractor = extractors[0]
        } else {
            for (extractor in extractors) {
                try {
                    if (extractor.sniff(extractorInput)) {
                        this.extractor = extractor
                        break
                    } else {
                        sniffFailures.addAll(extractor.sniffFailureDetails)
                    }
                } catch (e: EOFException) {
                    // Do nothing.
                } finally {
                    Preconditions.checkState(this.extractor != null || extractorInput.position == position)
                    extractorInput.resetPeekPosition()
                }
            }
            if (extractor == null) {
                throw UnrecognizedInputFormatException(
                    ("None of the available extractors ("
                            + Joiner.on(", ")
                        .join(
                            Lists.transform<Extractor?, String?>(
                                extractors.toList(),
                                Function { extractor: Extractor? -> extractor!!.underlyingImplementation!!.javaClass.simpleName })
                        )
                            + ") could read the stream."),
                    uri,
                    sniffFailures
                )
            }
        }
        extractor!!.init(output)
    }

    override fun release() {
        extractor?.also {
            it.release()
            extractor = null
        }
        extractorInput = null
    }

    override fun disableSeekingOnMp3Streams() {
        val extractor = extractor ?: return
        val underlyingExtractor = extractor.underlyingImplementation
        if (underlyingExtractor is Mp3Extractor) {
            underlyingExtractor.disableSeeking()
        }
    }

    override val currentInputPosition: Long
        get() = extractorInput?.position ?: C.INDEX_UNSET.toLong()

    override fun seek(position: Long, seekTimeUs: Long) {
        extractor!!.seek(position, seekTimeUs)
    }

    @Throws(IOException::class)
    override fun read(positionHolder: PositionHolder): Int {
        return extractor!!.read(extractorInput!!, positionHolder)
    }

    override val underlyingImplementationName: String?
        get() = extractor?.underlyingImplementation?.javaClass?.simpleName
}
