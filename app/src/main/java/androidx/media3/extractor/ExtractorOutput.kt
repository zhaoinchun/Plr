
package androidx.media3.extractor

import androidx.media3.common.C

interface ExtractorOutput {

    fun track(id: Int, type: @C.TrackType Int): TrackOutput

    fun endTracks()

    fun seekMap(seekMap: SeekMap)

    companion object {

        val PLACEHOLDER: ExtractorOutput = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                throw UnsupportedOperationException()
            }

            override fun endTracks() {
                throw UnsupportedOperationException()
            }

            override fun seekMap(seekMap: SeekMap) {
                throw UnsupportedOperationException()
            }
        }
    }
}
