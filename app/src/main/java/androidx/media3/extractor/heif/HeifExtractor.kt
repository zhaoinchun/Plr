
package androidx.media3.extractor.heif

import android.util.Pair
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.MimeTypes

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SingleSampleExtractor
import com.google.common.base.Preconditions

import java.io.IOException

class HeifExtractor  constructor(flags: @Flags Int = 0) : Extractor {
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
        imageExtractor = SingleSampleExtractor(C.INDEX_UNSET, C.LENGTH_UNSET, MimeTypes.IMAGE_HEIF)
        videoExtractor = if ((flags and FLAG_READ_IMAGE) != 0) null else HeicMotionPhotoExtractor()
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        if (videoExtractor != null && videoExtractor.sniff(input)) {
            return true
        }
        input.resetPeekPosition()
        return HeifSniffer.sniff(input,  false)
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
        activeExtractor!!.init(extractorOutput!! )
    }

    companion object {
        
        const val FLAG_READ_IMAGE: Int = 1
    }
}
