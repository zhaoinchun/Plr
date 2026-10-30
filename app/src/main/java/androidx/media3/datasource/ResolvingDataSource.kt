package androidx.media3.datasource

import android.net.Uri
import com.google.common.base.Preconditions
import java.io.IOException

class ResolvingDataSource(
    private val upstreamDataSource: DataSource,
    private val resolver: Resolver
) : DataSource {
    interface Resolver {
        @Throws(IOException::class)
        fun resolveDataSpec(dataSpec: DataSpec?): DataSpec

        fun resolveReportedUri(uri: Uri?): Uri? {
            return uri
        }
    }

    class Factory(private val upstreamFactory: DataSource.Factory, private val resolver: Resolver) :
        DataSource.Factory {
        override fun createDataSource(): ResolvingDataSource {
            return ResolvingDataSource(upstreamFactory.createDataSource(), resolver)
        }
    }

    private var upstreamOpened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstreamDataSource.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val resolvedDataSpec = resolver.resolveDataSpec(dataSpec)
        upstreamOpened = true
        return upstreamDataSource.open(resolvedDataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstreamDataSource.read(buffer, offset, length)
    }

    override val uri: Uri?
        get() {
            val reportedUri = upstreamDataSource.uri
            return if (reportedUri == null) null else resolver.resolveReportedUri(reportedUri)
        }

    override val responseHeaders: MutableMap<String, List<String>>
        get() = upstreamDataSource.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        if (upstreamOpened) {
            upstreamOpened = false
            upstreamDataSource.close()
        }
    }
}
