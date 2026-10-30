package androidx.media3.datasource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Util
import com.google.common.base.Supplier
import com.google.common.base.Suppliers
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DataSourceBitmapLoader : BitmapLoader {
    class Builder(val context: Context) {
        var listeningExecutorService: ListeningExecutorService? = null
        var dataSourceFactory: DataSource.Factory? = null
        var options: BitmapFactory.Options? = null
        var maximumOutputDimension: Int
        var makeShared = false

        init {
            this.maximumOutputDimension = C.LENGTH_UNSET
        }

        fun setDataSourceFactory(dataSourceFactory: DataSource.Factory?): Builder {
            this.dataSourceFactory = dataSourceFactory
            return this
        }

        fun setExecutorService(listeningExecutorService: ListeningExecutorService?): Builder {
            this.listeningExecutorService = listeningExecutorService
            return this
        }

        fun setBitmapFactoryOptions(options: BitmapFactory.Options?): Builder {
            this.options = options
            return this
        }

        fun setMaximumOutputDimension(maximumOutputDimension: Int): Builder {
            this.maximumOutputDimension = maximumOutputDimension
            return this
        }

        fun setMakeShared(makeShared: Boolean): Builder {
            this.makeShared = makeShared
            return this
        }

        fun build(): DataSourceBitmapLoader {
            return DataSourceBitmapLoader(this)
        }
    }

    private val listeningExecutorService: ListeningExecutorService
    private val dataSourceFactory: DataSource.Factory
    private var options: BitmapFactory.Options? = null
    private val maximumOutputDimension: Int
    private val makeShared: Boolean

    @Deprecated("Use {@link Builder} instead.")
    constructor(context: Context) : this(Builder(context))

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        maximumOutputDimension: Int
    ) : this(Builder(context).setMaximumOutputDimension(maximumOutputDimension))

    @Suppress("deprecation") // Calling deprecated constructor.
    @Deprecated("Use {@link Builder} instead.")
    constructor(
        listeningExecutorService: ListeningExecutorService,
        dataSourceFactory: DataSource.Factory
    ) : this(listeningExecutorService, dataSourceFactory,  null)

    @Suppress("deprecation") // Calling deprecated constructor.
    @Deprecated("Use {@link Builder} instead.")
    constructor(
        listeningExecutorService: ListeningExecutorService,
        dataSourceFactory: DataSource.Factory,
        options: BitmapFactory.Options?
    ) : this(
        listeningExecutorService,
        dataSourceFactory,
        options,  
        C.LENGTH_UNSET
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        listeningExecutorService: ListeningExecutorService,
        dataSourceFactory: DataSource.Factory,
        options: BitmapFactory.Options?,
        maximumOutputDimension: Int
    ) {
        this.listeningExecutorService = listeningExecutorService
        this.dataSourceFactory = dataSourceFactory
        this.options = options
        this.maximumOutputDimension = maximumOutputDimension
        this.makeShared = false
    }

    private constructor(builder: Builder) {
        this.dataSourceFactory =
            (if (builder.dataSourceFactory != null)
                builder.dataSourceFactory
            else
                DefaultDataSource.Factory(builder.context))!!
        this.listeningExecutorService =
            builder.listeningExecutorService ?: DEFAULT_EXECUTOR_SERVICE.get()!!
        this.maximumOutputDimension = builder.maximumOutputDimension
        this.makeShared = builder.makeShared
    }

    override fun supportsMimeType(mimeType: String): Boolean {
        return Util.isBitmapFactorySupportedMimeType(mimeType)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap?> {
        return listeningExecutorService.submit<Bitmap?>(
            Callable {
                maybeAsShared(
                    makeShared,
                    BitmapUtil.decode(data, data.size, options, maximumOutputDimension)
                )
            })
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap?> {
        return listeningExecutorService.submit<Bitmap?>(
            Callable {
                load(
                    dataSourceFactory.createDataSource(),
                    uri,
                    options,
                    maximumOutputDimension,
                    makeShared
                )
            })
    }

    companion object {
        val DEFAULT_EXECUTOR_SERVICE: Supplier<ListeningExecutorService?> =
            Suppliers.memoize<ListeningExecutorService?>(
                Supplier { MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()) })

        @Throws(IOException::class)
        private fun load(
            dataSource: DataSource,
            uri: Uri?,
            options: BitmapFactory.Options?,
            maximumOutputDimension: Int,
            makeShared: Boolean
        ): Bitmap? {
            try {
                val dataSpec = DataSpec(uri)
                dataSource.open(dataSpec)
                val readData = DataSourceUtil.readToEnd(dataSource)
                return maybeAsShared(
                    makeShared,
                    BitmapUtil.decode(readData, readData.size, options, maximumOutputDimension)
                )
            } finally {
                dataSource.close()
            }
        }

        private fun maybeAsShared(makeShared: Boolean, bitmap: Bitmap): Bitmap? {
            return if (makeShared) BitmapUtil.makeShared(bitmap) else bitmap
        }
    }
}
