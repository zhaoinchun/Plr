package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import java.io.IOException

interface ProgressiveMediaExtractor {
    fun interface Factory {
        fun createProgressiveMediaExtractor(playerId: PlayerId): ProgressiveMediaExtractor
    }

    val currentInputPosition: Long

    @Throws(IOException::class)
    fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: MutableMap<String, List<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput
    )

    fun release()
    fun disableSeekingOnMp3Streams()
    fun seek(position: Long, seekTimeUs: Long)

    @Throws(IOException::class)
    fun read(positionHolder: PositionHolder): Int

    val underlyingImplementationName: String?
        get() = null
}
