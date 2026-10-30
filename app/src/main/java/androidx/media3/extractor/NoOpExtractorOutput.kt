package androidx.media3.extractor

class NoOpExtractorOutput : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        return DiscardingTrackOutput()
    }

    override fun endTracks() {
        // Do nothing.
    }

    override fun seekMap(seekMap: SeekMap) {
        // Do nothing.
    }
}
