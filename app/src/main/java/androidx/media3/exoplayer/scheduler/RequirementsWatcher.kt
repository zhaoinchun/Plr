package androidx.media3.exoplayer.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.exoplayer.scheduler.Requirements.RequirementFlags

class RequirementsWatcher(
    context: Context, private val listener: Listener,
    val requirements: Requirements
) {

    fun interface Listener {
        fun onRequirementsStateChanged(
            requirementsWatcher: RequirementsWatcher?,
            notMetRequirements: @RequirementFlags Int
        )
    }

    private val context: Context = context.applicationContext
    private val handler: Handler = createHandlerForCurrentOrMainLooper()
    private var receiver: DeviceStatusChangeReceiver? = null

    private var notMetRequirements: @RequirementFlags Int = 0
    private var networkCallback: NetworkCallback? = null

    fun start(): @RequirementFlags Int {
        notMetRequirements = requirements.getNotMetRequirements(context)
        val filter = IntentFilter()
        if (requirements.isNetworkRequired) {
            if (Build.VERSION.SDK_INT >= 24) {
                registerNetworkCallbackV24()
            } else {
                filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
        }
        if (requirements.isChargingRequired) {
            filter.addAction(Intent.ACTION_POWER_CONNECTED)
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (requirements.isIdleRequired) {
            filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        if (requirements.isStorageNotLowRequired) {
            filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK)
        }
        receiver = DeviceStatusChangeReceiver()
        context.registerReceiver(receiver, filter, null, handler)
        return notMetRequirements
    }

    fun stop() {
        context.unregisterReceiver(receiver!!)
        receiver = null
        if (Build.VERSION.SDK_INT >= 24 && networkCallback != null) {
            unregisterNetworkCallbackV24()
        }
    }

    @RequiresApi(24)
    private fun registerNetworkCallbackV24() {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = NetworkCallback()
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
    }

    @RequiresApi(24)
    private fun unregisterNetworkCallbackV24() {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback!!)
        networkCallback = null
    }

    private fun checkRequirements() {
        @RequirementFlags val notMetRequirements: @RequirementFlags Int =
            requirements.getNotMetRequirements(context)
        if (this.notMetRequirements != notMetRequirements) {
            this.notMetRequirements = notMetRequirements
            listener.onRequirementsStateChanged(this, notMetRequirements)
        }
    }

    private fun recheckNotMetNetworkRequirements() {
        if ((notMetRequirements and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED)) == 0) {
            // No unmet network requirements to recheck.
            return
        }
        checkRequirements()
    }

    private inner class DeviceStatusChangeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isInitialStickyBroadcast()) {
                checkRequirements()
            }
        }
    }

    @RequiresApi(24)
    private inner class NetworkCallback : ConnectivityManager.NetworkCallback() {
        private var receivedCapabilitiesChange = false
        private var networkValidated = false

        override fun onAvailable(network: Network) {
            postCheckRequirements()
        }

        override fun onLost(network: Network) {
            postCheckRequirements()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (!blocked) {
                postRecheckNotMetNetworkRequirements()
            }
        }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) {
            val networkValidated =
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!receivedCapabilitiesChange || this.networkValidated != networkValidated) {
                receivedCapabilitiesChange = true
                this.networkValidated = networkValidated
                postCheckRequirements()
            } else if (networkValidated) {
                postRecheckNotMetNetworkRequirements()
            }
        }

        fun postCheckRequirements() {
            handler.post {
                if (networkCallback != null) {
                    checkRequirements()
                }
            }
        }

        fun postRecheckNotMetNetworkRequirements() {
            handler.post {
                if (networkCallback != null) {
                    recheckNotMetNetworkRequirements()
                }
            }
        }
    }
}
