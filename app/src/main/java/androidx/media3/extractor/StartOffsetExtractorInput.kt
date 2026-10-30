package androidx.media3.extractor

import androidx.annotation.RestrictTo
import com.google.common.base.Preconditions
import java.io.IOException

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class StartOffsetExtractorInput(input: ExtractorInput, startOffset: Long) :
    ForwardingExtractorInput(input) {
    private val startOffset: Long

    init {
        Preconditions.checkArgument(input.position >= startOffset)
        this.startOffset = startOffset
    }
    override val peekPosition: Long
        get() = super.peekPosition-startOffset
    override val position: Long
        get() = super.position - startOffset
    override val length: Long
        get() = super.length-startOffset

    @Throws(IOException::class)
    override fun <E : Throwable?> setRetryPosition(position: Long, e: E?) {
        super.setRetryPosition<E?>(position + startOffset, e)
    }
}
