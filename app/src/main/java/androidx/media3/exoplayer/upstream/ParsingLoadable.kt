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
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.Volatile

class ParsingLoadable<T> : Loadable {
    interface Parser<T> {
        @Throws(IOException::class)
        fun parse(uri: Uri?, inputStream: InputStream?): T
    }

    class Builder<T>(
        val dataSource: DataSource,
        val dataSpec: DataSpec,
        val type: @C.DataType Int,
        val parser: Parser<out T>
    ) {
        var steeredPathwayId: String? = null

        constructor(
            dataSource: DataSource,
            uri: Uri,
            type: @C.DataType Int,
            parser: Parser<out T>
        ) : this(
            dataSource,
            DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build(),
            type,
            parser
        )

        fun setSteeredPathwayId(steeredPathwayId: String?): Builder<T> {
            this.steeredPathwayId = steeredPathwayId
            return this
        }

        fun build(): ParsingLoadable<T> {
            return ParsingLoadable<T>(this)
        }
    }

    val loadTaskId: Long
    val dataSpec: DataSpec
    val type: @C.DataType Int
    val steeredPathwayId: String?
    private val dataSource: StatsDataSource
    private val parser: Parser<out T?>

    @Volatile
    var result: T? = null
        private set

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        dataSource: DataSource,
        uri: Uri?,
        type: @C.DataType Int,
        parser: Parser<out T?>
    ) : this(
        dataSource,
        DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build(),
        type,
        parser
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        dataSource: DataSource,
        dataSpec: DataSpec,
        type: @C.DataType Int,
        parser: Parser<out T?>
    ) {
        this.dataSource = StatsDataSource(dataSource)
        this.dataSpec = dataSpec
        this.type = type
        this.parser = parser
        this.steeredPathwayId = null
        loadTaskId = LoadEventInfo.newId
    }

    private constructor(builder: Builder<T>) {
        this.dataSource = StatsDataSource(builder.dataSource)
        this.dataSpec = builder.dataSpec
        this.type = builder.type
        this.parser = builder.parser
        this.steeredPathwayId = builder.steeredPathwayId
        loadTaskId = LoadEventInfo.newId
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
            dataSource: DataSource, parser: Parser<out T>, uri: Uri, type: @C.DataType Int
        ): T? {
            val loadable =
                Builder<T>(dataSource, uri, type, parser).build()
            loadable.load()
            return loadable.result!!
        }

        @Throws(IOException::class)
        fun <T> load(
            dataSource: DataSource,
            parser: Parser<out T>,
            dataSpec: DataSpec,
            type: @C.DataType Int
        ): T {
            val loadable =
                Builder<T>(dataSource, dataSpec, type, parser).build()
            loadable.load()
            return loadable.result!!
        }
    }
}
