package androidx.media3.common.util

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.DisplayInfoListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.BackgroundExecutor.get
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.InlineMe
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class NetworkTypeObserver private constructor(context: Context) {

    fun interface Listener {

        fun onNetworkTypeChanged(@C.NetworkType networkType: @C.NetworkType Int)
    }

    private val backgroundExecutor: Executor?
    private val listeners: CopyOnWriteArrayList<ListenerHolder>
    private val lock: Any

    @GuardedBy("lock")
    @C.NetworkType
    private var networkType: @C.NetworkType Int

    @GuardedBy("lock")
    private var isInitialized = false

    init {
        backgroundExecutor = get()
        listeners = CopyOnWriteArrayList<ListenerHolder>()
        lock = Any()
        networkType = C.NETWORK_TYPE_UNKNOWN
        backgroundExecutor!!.execute(Runnable { init(context) })
    }

    @InlineMe(
        replacement = "this.register(listener, new Handler(Looper.getMainLooper())::post)",
        imports = ["android.os.Handler", "android.os.Looper"]
    )
    @Deprecated("Use {@link #register(Listener, Executor)} instead.")
    fun register(listener: Listener) {
        register(
            listener,  
            Executor { r: Runnable? -> Handler(Looper.getMainLooper()).post(r!!) })
    }

    fun register(listener: Listener, executor: Executor) {
        removeClearedReferences()
        val isInitialized: Boolean
        val listenerHolder: ListenerHolder = ListenerHolder(listener, executor)
        synchronized(lock) {
            listeners.add(listenerHolder)
            isInitialized = this.isInitialized
        }
        if (isInitialized) {
            // Simulate an initial update (like the sticky broadcast we'd receive if we were to register a
            // separate broadcast receiver for each listener).
            listenerHolder.callOnNetworkTypeChanged()
        }
    }

    @C.NetworkType
    fun getNetworkType(): @C.NetworkType Int {
        synchronized(lock) {
            return networkType
        }
    }

    @SuppressLint("UnprotectedReceiver") // Protected system broadcasts must not specify protection.
    private fun init(context: Context) {
        val filter = IntentFilter()
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(Receiver(), filter)
    }

    private fun removeClearedReferences() {
        for (listener in listeners) {
            if (listener.canBeRemoved()) {
                listeners.remove(listener)
            }
        }
    }

    private fun handleConnectivityActionBroadcast(context: Context) {
        @C.NetworkType val networkType: @C.NetworkType Int =
            getNetworkTypeFromConnectivityManager(context)
        if (Build.VERSION.SDK_INT >= 31 && networkType == C.NETWORK_TYPE_4G) {
            // Delay update of the network type to check whether this is actually 5G-NSA.
            Api31.disambiguate4gAnd5gNsa(context,  this@NetworkTypeObserver)
        } else {
            updateNetworkType(networkType)
        }
    }

    private fun updateNetworkType(@C.NetworkType networkType: @C.NetworkType Int) {
        removeClearedReferences()
        val currentListeners: MutableIterator<ListenerHolder?>?
        synchronized(lock) {
            if (isInitialized && this.networkType == networkType) {
                return
            }
            isInitialized = true
            this.networkType = networkType
            currentListeners = listeners.iterator()
        }
        while (currentListeners!!.hasNext()) {
            currentListeners.next()!!.callOnNetworkTypeChanged()
        }
    }

    inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            backgroundExecutor!!.execute(Runnable { handleConnectivityActionBroadcast(context) })
        }
    }

    @RequiresApi(31)
    private object Api31 {
        fun disambiguate4gAnd5gNsa(context: Context, instance: NetworkTypeObserver) {
            try {
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val callback = DisplayInfoCallback(instance)
                telephonyManager.registerTelephonyCallback(instance.backgroundExecutor!!, callback)
                // We are only interested in the initial response with the current state, so unregister
                // the listener immediately.
                telephonyManager.unregisterTelephonyCallback(callback)
            } catch (e: RuntimeException) {
                // Ignore problems with listener registration and keep reporting as 4G.
                instance.updateNetworkType(C.NETWORK_TYPE_4G)
            }
        }

        private class DisplayInfoCallback(private val instance: NetworkTypeObserver) :
            TelephonyCallback(), DisplayInfoListener {
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                val overrideNetworkType = telephonyDisplayInfo.getOverrideNetworkType()
                val is5gNsa =
                    overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA || overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE || overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                instance.updateNetworkType(if (is5gNsa) C.NETWORK_TYPE_5G_NSA else C.NETWORK_TYPE_4G)
            }
        }
    }

    private inner class ListenerHolder(listener: Listener, private val executor: Executor) {
        // This class needs to hold weak references as it doesn't require listeners to unregister.
        private val listener: WeakReference<Listener?>

        init {
            this.listener = WeakReference<Listener?>(listener)
        }

        fun canBeRemoved(): Boolean {
            return listener.get() == null
        }

        fun callOnNetworkTypeChanged() {
            executor.execute(
                Runnable {
                    val listener = this.listener.get()
                    if (listener != null) {
                        listener.onNetworkTypeChanged(getNetworkType())
                    }
                })
        }
    }

    companion object {
        private var staticInstance: NetworkTypeObserver? = null

        @Synchronized
        fun getInstance(context: Context): NetworkTypeObserver {
            if (staticInstance == null) {
                staticInstance = NetworkTypeObserver(context)
            }
            return staticInstance!!
        }

        @VisibleForTesting
        @Synchronized
        fun resetForTests() {
            staticInstance = null
        }

        @Suppress("deprecation") // Using deprecated NetworkInfo for compatibility to older APIs
        @C.NetworkType
        private fun getNetworkTypeFromConnectivityManager(context: Context): @C.NetworkType Int {
            val networkInfo: NetworkInfo?
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            if (connectivityManager == null) {
                return C.NETWORK_TYPE_UNKNOWN
            }
            try {
                networkInfo = connectivityManager.getActiveNetworkInfo()
            } catch (e: SecurityException) {
                // Expected if permission was revoked.
                return C.NETWORK_TYPE_UNKNOWN
            }
            if (networkInfo == null || !networkInfo.isConnected()) {
                return C.NETWORK_TYPE_OFFLINE
            }
            when (networkInfo.getType()) {
                ConnectivityManager.TYPE_WIFI -> return C.NETWORK_TYPE_WIFI
                ConnectivityManager.TYPE_WIMAX -> return C.NETWORK_TYPE_4G
                ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_MOBILE_DUN, ConnectivityManager.TYPE_MOBILE_HIPRI -> return getMobileNetworkType(
                    networkInfo
                )

                ConnectivityManager.TYPE_ETHERNET -> return C.NETWORK_TYPE_ETHERNET
                else -> return C.NETWORK_TYPE_OTHER
            }
        }

        @C.NetworkType
        private fun getMobileNetworkType(networkInfo: NetworkInfo): @C.NetworkType Int {
            when (networkInfo.getSubtype()) {
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> return C.NETWORK_TYPE_2G
                TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> return C.NETWORK_TYPE_3G
                TelephonyManager.NETWORK_TYPE_LTE -> return C.NETWORK_TYPE_4G
                TelephonyManager.NETWORK_TYPE_NR -> return if (Build.VERSION.SDK_INT >= 29) C.NETWORK_TYPE_5G_SA else C.NETWORK_TYPE_UNKNOWN
                TelephonyManager.NETWORK_TYPE_IWLAN -> return C.NETWORK_TYPE_WIFI
                TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_UNKNOWN -> return C.NETWORK_TYPE_CELLULAR_UNKNOWN
                else -> return C.NETWORK_TYPE_CELLULAR_UNKNOWN
            }
        }
    }
}
