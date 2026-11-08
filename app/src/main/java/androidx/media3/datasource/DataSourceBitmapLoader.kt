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

class DataSourceBitmapLoader(
    context: Context,
    var listeningExecutorService: ListeningExecutorService = DEFAULT_EXECUTOR_SERVICE.get()!!,
    var dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    var options: BitmapFactory.Options? = null,
    var maximumOutputDimension: Int = C.LENGTH_UNSET,
    var makeShared: Boolean = false,
) : BitmapLoader {

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
            uri: Uri,
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
