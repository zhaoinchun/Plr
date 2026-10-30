
package androidx.media3.extractor.wav

internal class WavFormat(
    val formatType: Int,
    val numChannels: Int,
    val frameRateHz: Int,
    val averageBytesPerSecond: Int,
    val blockSize: Int,
    val bitsPerSample: Int,
    val extraData: ByteArray,
    val channelMask: Int
)
