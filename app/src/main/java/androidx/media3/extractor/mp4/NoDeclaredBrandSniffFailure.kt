package androidx.media3.extractor.mp4

import androidx.media3.extractor.SniffFailure

class NoDeclaredBrandSniffFailure private constructor() : SniffFailure {
    override fun toString(): String {
        return "NoDeclaredBrand"
    }

    companion object {
        val INSTANCE: NoDeclaredBrandSniffFailure = NoDeclaredBrandSniffFailure()
    }
}
