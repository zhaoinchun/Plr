package androidx.media3.extractor.jpeg

import android.util.Pair
import androidx.annotation.IntDef
import androidx.media3.common.MimeTypes

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SingleSampleExtractor
import com.google.common.base.Preconditions

import java.io.IOException

class JpegExtractor  constructor(flags: @Flags Int = 0) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true, value = [FLAG_READ_IMAGE]
    )
    annotation class Flags

    private val imageExtractor: Extractor
    private val videoExtractor: Extractor?

    private var extractorOutput:ExtractorOutput? = null
    private var activeExtractor:Extractor? = null

    private var pendingSeek: Pair<Long?, Long?>? = null

    init {
        imageExtractor =
            SingleSampleExtractor(
                JPEG_FILE_SIGNATURE, JPEG_FILE_SIGNATURE_LENGTH, MimeTypes.IMAGE_JPEG
            )
        videoExtractor = if ((flags and FLAG_READ_IMAGE) == 0) JpegMotionPhotoExtractor() else null
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        if (videoExtractor != null && videoExtractor.sniff(input)) {
            return true
        }
        input.resetPeekPosition()
        return imageExtractor.sniff(input)
    }

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
        if (videoExtractor == null) {
            activeExtractor = imageExtractor
            activeExtractor!!.init(output)
        }
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        if (activeExtractor == null) {
            assignActiveExtractor(input)
        }
        return activeExtractor!!.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (activeExtractor != null) {
            activeExtractor!!.seek(position, timeUs)
        } else {
            pendingSeek = Pair.create<Long?, Long?>(position, timeUs)
        }
    }

    override fun release() {
        if (videoExtractor != null) {
            videoExtractor.release()
        }
        imageExtractor.release()
    }

    @Throws(IOException::class)
    private fun assignActiveExtractor(input: ExtractorInput) {
        Preconditions.checkState(activeExtractor == null)
        // If activeExtractor is null it means it wasn't assigned in init(), which only happens if
        // videoExtractor is non-null (which means FLAG_READ_IMAGE wasn't set).
        activeExtractor = if (videoExtractor!!.sniff(input)) videoExtractor else imageExtractor
        input.resetPeekPosition()
        if (pendingSeek != null) {
            activeExtractor!!.seek(pendingSeek!!.first!!, pendingSeek!!.second!!)
            pendingSeek = null
        }
        activeExtractor!!.init(extractorOutput!!)
    }

    companion object {

        const val FLAG_READ_IMAGE: Int = 1

        // Specification reference: ITU-T.81 (1992) subsection B.1.1.3
        private const val JPEG_FILE_SIGNATURE = 0xFFD8 // Start of image marker
        private const val JPEG_FILE_SIGNATURE_LENGTH = 2
    }
}
