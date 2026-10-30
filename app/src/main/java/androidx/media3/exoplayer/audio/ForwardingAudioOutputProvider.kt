package androidx.media3.exoplayer.audio

class ForwardingAudioOutputProvider(private val audioOutputProvider: AudioOutputProvider) :
    AudioOutputProvider by audioOutputProvider
