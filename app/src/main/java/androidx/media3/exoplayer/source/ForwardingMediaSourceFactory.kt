package androidx.media3.exoplayer.source

class ForwardingMediaSourceFactory(private val factory: MediaSource.Factory) :
    MediaSource.Factory by factory
