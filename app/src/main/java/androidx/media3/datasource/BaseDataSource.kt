package androidx.media3.datasource

abstract class BaseDataSource protected constructor(private val isNetwork: Boolean) : DataSource {
    private val listeners: ArrayList<TransferListener?> = ArrayList( 1)
    private var listenerCount = 0
    private var dataSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        if (!listeners.contains(transferListener)) {
            listeners.add(transferListener)
            listenerCount++
        }
    }

    protected fun transferInitializing(dataSpec: DataSpec) {
        for (i in 0..<listenerCount) {
            listeners[i]!!.onTransferInitializing( this, dataSpec, isNetwork)
        }
    }

    protected fun transferStarted(dataSpec: DataSpec) {
        this.dataSpec = dataSpec
        for (i in 0..<listenerCount) {
            listeners[i]!!.onTransferStart( this, dataSpec, isNetwork)
        }
    }

    protected fun bytesTransferred(bytesTransferred: Int) {
        val dataSpec = dataSpec!!
        for (i in 0..<listenerCount) {
            listeners[i]!!
                .onBytesTransferred( this, dataSpec, isNetwork, bytesTransferred)
        }
    }

    protected fun transferEnded() {
        val dataSpec = dataSpec!!
        for (i in 0..<listenerCount) {
            listeners[i]!!.onTransferEnd( this, dataSpec, isNetwork)
        }
        this.dataSpec = null
    }
}
