package androidx.media3.extractor

import androidx.annotation.RestrictTo
import androidx.media3.extractor.SeekMap.SeekPoints

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class StartOffsetExtractorOutput(
    private val startOffset: Long,
    private val extractorOutput: ExtractorOutput
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        return extractorOutput.track(id, type)
    }

    override fun endTracks() {
        extractorOutput.endTracks()
    }

    override fun seekMap(seekMap: SeekMap) {
        extractorOutput.seekMap(
            object : ForwardingSeekMap(seekMap) {
                override fun getSeekPoints(timeUs: Long): SeekPoints {
                    val seekPoints = seekMap.getSeekPoints(timeUs)!!
                    return SeekPoints(
                        SeekPoint(seekPoints.first.timeUs, seekPoints.first.position + startOffset),
                        SeekPoint(
                            seekPoints.second.timeUs,
                            seekPoints.second.position + startOffset
                        )
                    )
                }
            })
    }
}
