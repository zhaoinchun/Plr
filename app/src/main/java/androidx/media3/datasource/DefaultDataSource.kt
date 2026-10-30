package androidx.media3.datasource

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.isLocalFileUri
import com.google.common.base.Preconditions
import java.io.IOException

class DefaultDataSource(context: Context, baseDataSource: DataSource?) :
    DataSource {
    class Factory  constructor(
        context: Context,
        private val baseDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    ) : DataSource.Factory {
        private val context: Context = context.applicationContext
        private var transferListener: TransferListener? = null

        fun setTransferListener(transferListener: TransferListener?): Factory {
            this.transferListener = transferListener
            return this
        }

        override fun createDataSource(): DefaultDataSource {
            val dataSource =
                DefaultDataSource(context, baseDataSourceFactory.createDataSource())
            transferListener?.also { dataSource.addTransferListener(it) }
            return dataSource
        }
    }

    private val context: Context
    private val transferListeners: MutableList<TransferListener>
    private val baseDataSource: DataSource

    // Lazily initialized.
    private var fileDataSource: DataSource? = null
    private var assetDataSource: DataSource? = null
    private var contentDataSource: DataSource? = null
    private var rtmpDataSource: DataSource? = null
    private var udpDataSource: DataSource? = null
    private var dataSchemeDataSource: DataSource? = null
    private var rawResourceDataSource: DataSource? = null

    private var dataSource: DataSource? = null

    constructor(context: Context, allowCrossProtocolRedirects: Boolean) : this(
        context,  
        null,
        DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
        DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
        allowCrossProtocolRedirects
    )

    constructor(context: Context, userAgent: String?, allowCrossProtocolRedirects: Boolean) : this(
        context,
        userAgent,
        DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
        DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
        allowCrossProtocolRedirects
    )

    constructor(
        context: Context,
        userAgent: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        allowCrossProtocolRedirects: Boolean
    ) : this(
        context,
        DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(connectTimeoutMillis)
            .setReadTimeoutMs(readTimeoutMillis)
            .setAllowCrossProtocolRedirects(allowCrossProtocolRedirects)
            .createDataSource()
    )

    init {
        this.context = context.applicationContext
        this.baseDataSource = baseDataSource!!
        transferListeners = mutableListOf()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        baseDataSource.addTransferListener(transferListener)
        transferListeners.add(transferListener)
        maybeAddListenerToDataSource(fileDataSource, transferListener)
        maybeAddListenerToDataSource(assetDataSource, transferListener)
        maybeAddListenerToDataSource(contentDataSource, transferListener)
        maybeAddListenerToDataSource(rtmpDataSource, transferListener)
        maybeAddListenerToDataSource(udpDataSource, transferListener)
        maybeAddListenerToDataSource(dataSchemeDataSource, transferListener)
        maybeAddListenerToDataSource(rawResourceDataSource, transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        Preconditions.checkState(dataSource == null)
        // Choose the correct source for the scheme.
        val scheme = dataSpec.uri.getScheme()
        if (isLocalFileUri(dataSpec.uri)) {
            val uriPath = dataSpec.uri.getPath()
            if (uriPath != null && uriPath.startsWith("/android_asset/")) {
                dataSource = getAssetDataSource()
            } else {
                dataSource = getFileDataSource()
            }
        } else if (SCHEME_ASSET == scheme) {
            dataSource = getAssetDataSource()
        } else if (SCHEME_CONTENT == scheme) {
            dataSource = getContentDataSource()
        } else if (SCHEME_RTMP == scheme) {
            dataSource = getRtmpDataSource()
        } else if (SCHEME_UDP == scheme) {
            dataSource = getUdpDataSource()
        } else if (SCHEME_DATA == scheme) {
            dataSource = getDataSchemeDataSource()
        } else if (SCHEME_RAW == scheme || SCHEME_ANDROID_RESOURCE == scheme) {
            dataSource = getRawResourceDataSource()
        } else {
            dataSource = baseDataSource
        }
        // Open the source and return.
        return dataSource!!.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return dataSource!!.read(buffer, offset, length)
    }

    override val uri: Uri?
        get() = dataSource?.uri

    override val responseHeaders: MutableMap<String, List<String>>
        get() = dataSource?.responseHeaders?:mutableMapOf()

    @Throws(IOException::class)
    override fun close() {
        if (dataSource != null) {
            try {
                dataSource!!.close()
            } finally {
                dataSource = null
            }
        }
    }

    private fun getUdpDataSource(): DataSource? {
        if (udpDataSource == null) {
            udpDataSource = UdpDataSource()
            addListenersToDataSource(udpDataSource!!)
        }
        return udpDataSource
    }

    private fun getFileDataSource(): DataSource? {
        if (fileDataSource == null) {
            fileDataSource = FileDataSource()
            addListenersToDataSource(fileDataSource!!)
        }
        return fileDataSource
    }

    private fun getAssetDataSource(): DataSource? {
        if (assetDataSource == null) {
            assetDataSource = AssetDataSource(context)
            addListenersToDataSource(assetDataSource!!)
        }
        return assetDataSource
    }

    private fun getContentDataSource(): DataSource? {
        if (contentDataSource == null) {
            contentDataSource = ContentDataSource(context)
            addListenersToDataSource(contentDataSource!!)
        }
        return contentDataSource
    }

    private fun getRtmpDataSource(): DataSource? {
        if (rtmpDataSource == null) {
            try {
                // LINT.IfChange
                val clazz = Class.forName("androidx.media3.datasource.rtmp.RtmpDataSource")
                rtmpDataSource = clazz.getConstructor().newInstance() as DataSource
                // LINT.ThenChange(../../../../../../proguard-rules.txt)
                addListenersToDataSource(rtmpDataSource!!)
            } catch (e: ClassNotFoundException) {
                // Expected if the app was built without the RTMP extension.
                w(TAG, "Attempting to play RTMP stream without depending on the RTMP extension")
            } catch (e: Exception) {
                // The RTMP extension is present, but instantiation failed.
                throw RuntimeException("Error instantiating RTMP extension", e)
            }
            if (rtmpDataSource == null) {
                rtmpDataSource = baseDataSource
            }
        }
        return rtmpDataSource
    }

    private fun getDataSchemeDataSource(): DataSource? {
        if (dataSchemeDataSource == null) {
            dataSchemeDataSource = DataSchemeDataSource()
            addListenersToDataSource(dataSchemeDataSource!!)
        }
        return dataSchemeDataSource
    }

    private fun getRawResourceDataSource(): DataSource? {
        if (rawResourceDataSource == null) {
            rawResourceDataSource = RawResourceDataSource(context)
            addListenersToDataSource(rawResourceDataSource!!)
        }
        return rawResourceDataSource
    }

    private fun addListenersToDataSource(dataSource: DataSource) {
        for (i in transferListeners.indices) {
            dataSource.addTransferListener(transferListeners[i])
        }
    }

    private fun maybeAddListenerToDataSource(
        dataSource: DataSource?, listener: TransferListener
    ) {
        dataSource?.addTransferListener(listener)
    }

    companion object {
        private const val TAG = "DefaultDataSource"

        private const val SCHEME_ASSET = "asset"
        private val SCHEME_CONTENT = ContentResolver.SCHEME_CONTENT
        private const val SCHEME_RTMP = "rtmp"
        private const val SCHEME_UDP = "udp"
        private val SCHEME_DATA: String = DataSchemeDataSource.SCHEME_DATA

        @Suppress("deprecation") // Detecting deprecated scheme.
        private val SCHEME_RAW: String = RawResourceDataSource.RAW_RESOURCE_SCHEME

        private val SCHEME_ANDROID_RESOURCE = ContentResolver.SCHEME_ANDROID_RESOURCE
    }
}
