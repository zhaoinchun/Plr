package androidx.media3.extractor.mp4

import androidx.media3.extractor.SniffFailure

class IncorrectFragmentationSniffFailure private constructor(val fileIsFragmented: Boolean) :
    SniffFailure {
    override fun toString(): String {
        return "IncorrectFragmentation{expected=" + !fileIsFragmented + "}"
    }

    companion object {
        val FILE_FRAGMENTED: IncorrectFragmentationSniffFailure =
            IncorrectFragmentationSniffFailure( true)

        val FILE_NOT_FRAGMENTED: IncorrectFragmentationSniffFailure =
            IncorrectFragmentationSniffFailure( false)
    }
}
