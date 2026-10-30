package androidx.media3.exoplayer.mediacodec

class ForwardingMediaCodecAdapter(private val delegate: MediaCodecAdapter) :
    MediaCodecAdapter by delegate