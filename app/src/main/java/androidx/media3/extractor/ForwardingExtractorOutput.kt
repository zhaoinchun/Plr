package androidx.media3.extractor

import androidx.media3.common.C

class ForwardingExtractorOutput(private val output: ExtractorOutput) : ExtractorOutput {
    override fun track(id: Int, type: @C.TrackType Int): TrackOutput {
        return output.track(id, type)
    }

    override fun endTracks() {
        output.endTracks()
    }

    override fun seekMap(seekMap: SeekMap) {
        output.seekMap(seekMap)
    }
}
