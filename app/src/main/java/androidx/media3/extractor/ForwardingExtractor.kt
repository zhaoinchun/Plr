package androidx.media3.extractor

import java.io.IOException

class ForwardingExtractor(private val delegate: Extractor) : Extractor {

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return delegate.sniff(input)
    }

    override val sniffFailureDetails: List<SniffFailure>
        get() = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        delegate.init(output)
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        delegate.seek(position, timeUs)
    }

    override fun release() {
        delegate.release()
    }

    override val underlyingImplementation: Extractor
        get() = delegate.underlyingImplementation
}
