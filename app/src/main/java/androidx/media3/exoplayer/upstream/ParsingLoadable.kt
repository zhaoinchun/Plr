package androidx.media3.exoplayer.upstream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Util.closeQuietly
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.upstream.Loader.Loadable
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.Volatile

class ParsingLoadable<T> : Loadable {
    interface Parser<T> {
        @Throws(IOException::class)
        fun parse(uri: Uri?, inputStream: InputStream?): T
    }

    val loadTaskId: Long= LoadEventInfo.newId
    val dataSpec: DataSpec
    val type: Int
    val steeredPathwayId: String?= null
    private val dataSource: StatsDataSource
    private val parser: Parser<out T?>

    @Volatile
    var result: T? = null
        private set

    constructor(
        dataSource: DataSource,
        uri: Uri,
        type: Int,
        parser: Parser<out T?>
    ) : this(
        dataSource,
        DataSpec(uri = uri, flags = DataSpec.FLAG_ALLOW_GZIP),
        type,
        parser
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        dataSource: DataSource,
        dataSpec: DataSpec,
        type: Int,
        parser: Parser<out T?>
    ) {
        this.dataSource = StatsDataSource(dataSource)
        this.dataSpec = dataSpec
        this.type = type
        this.parser = parser
    }

    @Throws(IOException::class)
    override fun load() {
        // We always load from the beginning, so reset bytesRead to 0.
        dataSource.resetBytesRead()
        val inputStream = DataSourceInputStream(dataSource, dataSpec)
        try {
            inputStream.open()
            val dataSourceUri = dataSource.uri!!
            result = parser.parse(dataSourceUri, inputStream)
        } finally {
            closeQuietly(inputStream)
        }
    }

    override fun cancelLoad() {
        // Do nothing.
    }

    fun bytesLoaded(): Long {
        return dataSource.bytesRead
    }

    val uri: Uri
        get() = dataSource.lastOpenedUri

    val responseHeaders: MutableMap<String, List<String>>
        get() = dataSource.lastResponseHeaders

    companion object {
        @Throws(IOException::class)
        fun <T> load(
            dataSource: DataSource, parser: Parser<out T>, uri: Uri, type: Int
        ): T? {
            val loadable = ParsingLoadable<T>(dataSource, uri, type, parser)
            loadable.load()
            return loadable.result!!
        }

        @Throws(IOException::class)
        fun <T> load(
            dataSource: DataSource,
            parser: Parser<out T>,
            dataSpec: DataSpec,
            type: Int
        ): T {
            val loadable = ParsingLoadable<T>(dataSource, dataSpec, type, parser)
            loadable.load()
            return loadable.result!!
        }
    }
}
