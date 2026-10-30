
package androidx.media3.exoplayer.offline

import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.IOException
import java.io.InputStream

class FilteringManifestParser<T : FilterableManifest<T?>?>(
    private val parser: ParsingLoadable.Parser<out T?>,
    private val streamKeys: List<StreamKey>?
) : ParsingLoadable.Parser<T?> {
    @Throws(IOException::class)
    override fun parse(uri: Uri?, inputStream: InputStream?): T? {
        val manifest: T? = parser.parse(uri, inputStream)
        return if (streamKeys.isNullOrEmpty()) manifest else manifest!!.copy(
            streamKeys
        )
    }
}
