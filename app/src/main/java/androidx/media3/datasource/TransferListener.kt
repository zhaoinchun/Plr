package androidx.media3.datasource

interface TransferListener {
    fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean)

    fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean)

    fun onBytesTransferred(
        source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int
    )

    fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean)
}
