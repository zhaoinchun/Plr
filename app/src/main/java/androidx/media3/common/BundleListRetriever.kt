package androidx.media3.common

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

class BundleListRetriever(list: List<Bundle>) : Binder() {
    private val list: List<Bundle> = list.toList()

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != FIRST_CALL_TRANSACTION) {
            return super.onTransact(code, data, reply, flags)
        }

        if (reply == null) {
            return false
        }

        val count = list.size
        var index = data.readInt()
        while (index < count && reply.dataSize() < C.SUGGESTED_MAX_IPC_SIZE) {
            reply.writeInt(REPLY_CONTINUE)
            reply.writeBundle(list[index])
            index++
        }
        reply.writeInt(if (index < count) REPLY_BREAK else REPLY_END_OF_LIST)
        return true
    }

    companion object {
        private const val REPLY_END_OF_LIST = 0
        private const val REPLY_CONTINUE = 1
        private const val REPLY_BREAK = 2

        fun getList(binder: IBinder): List<Bundle> {
            if (binder is BundleListRetriever) {
                // In-process binder calls can return the list directly instead of using the transact method.
                return binder.list
            }
            return getListFromRemoteBinder(binder)
        }

        fun getListFromRemoteBinder(binder: IBinder): List<Bundle> {
            val builder = mutableListOf<Bundle>()
            var index = 0
            var replyCode: Int = REPLY_CONTINUE
            while (replyCode != REPLY_END_OF_LIST) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInt(index)
                    try {
                        binder.transact(FIRST_CALL_TRANSACTION, data, reply,0)
                    } catch (e: RemoteException) {
                        throw RuntimeException(e)
                    }
                    while ((reply.readInt().also { replyCode = it }) == REPLY_CONTINUE) {
                        reply.readBundle()?.also {
                            builder.add(it)
                            index++
                        }
                    }
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
            return builder.toList()
        }
    }
}
