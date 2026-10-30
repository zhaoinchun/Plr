
package androidx.media3.exoplayer.scheduler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import androidx.annotation.IntDef

class Requirements(@RequirementFlags requirements: @RequirementFlags Int) : Parcelable {
    
    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        flag = true,
        value = [NETWORK, NETWORK_UNMETERED, DEVICE_IDLE, DEVICE_CHARGING, DEVICE_STORAGE_NOT_LOW]
    )
    annotation class RequirementFlags

    @get:RequirementFlags
    @RequirementFlags
    val requirements: @RequirementFlags Int

    fun filterRequirements(requirementsFilter: Int): Requirements {
        val filteredRequirements = requirements and requirementsFilter
        return if (filteredRequirements == requirements) this else Requirements(filteredRequirements)
    }

    val isNetworkRequired: Boolean
        get() = (requirements and NETWORK) != 0

    val isUnmeteredNetworkRequired: Boolean
        get() = (requirements and NETWORK_UNMETERED) != 0

    val isChargingRequired: Boolean
        get() = (requirements and DEVICE_CHARGING) != 0

    val isIdleRequired: Boolean
        get() = (requirements and DEVICE_IDLE) != 0

    val isStorageNotLowRequired: Boolean
        get() = (requirements and DEVICE_STORAGE_NOT_LOW) != 0

    fun checkRequirements(context: Context): Boolean {
        return getNotMetRequirements(context) == 0
    }

    @RequirementFlags
    fun getNotMetRequirements(context: Context): @RequirementFlags Int {
        @RequirementFlags var notMetRequirements: @RequirementFlags Int =
            getNotMetNetworkRequirements(context)
        if (this.isChargingRequired && !isDeviceCharging(context)) {
            notMetRequirements = notMetRequirements or DEVICE_CHARGING
        }
        if (this.isIdleRequired && !isDeviceIdle(context)) {
            notMetRequirements = notMetRequirements or DEVICE_IDLE
        }
        if (this.isStorageNotLowRequired && !isStorageNotLow(context)) {
            notMetRequirements = notMetRequirements or DEVICE_STORAGE_NOT_LOW
        }
        return notMetRequirements
    }

    @RequirementFlags
    private fun getNotMetNetworkRequirements(context: Context): @RequirementFlags Int {
        if (!this.isNetworkRequired) {
            return 0
        }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.getActiveNetworkInfo()
        if (networkInfo == null || !networkInfo.isConnected() || !isInternetConnectivityValidated(
                connectivityManager
            )
        ) {
            return requirements and (NETWORK or NETWORK_UNMETERED)
        }

        if (this.isUnmeteredNetworkRequired && connectivityManager.isActiveNetworkMetered()) {
            return NETWORK_UNMETERED
        }

        return 0
    }

    private fun isDeviceCharging(context: Context): Boolean {
        val batteryStatus =
            context.registerReceiver( 
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        if (batteryStatus == null) {
            return false
        }
        val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isDeviceIdle(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isDeviceIdleMode
    }

    private fun isStorageNotLow(context: Context): Boolean {
        return (context.registerReceiver( 
            null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)
        )
                == null)
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        return requirements == (o as Requirements).requirements
    }

    override fun hashCode(): Int {
        return requirements
    }

    // Parcelable implementation.
    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(requirements)
    }

    init {
        var requirements = requirements
        if ((requirements and NETWORK_UNMETERED) != 0) {
            // Make sure network requirement flags are consistent.
            requirements = requirements or NETWORK
        }
        this.requirements = requirements
    }

    companion object {
        const val NETWORK: Int = 1
        const  val NETWORK_UNMETERED: Int = 1 shl 1
        const  val DEVICE_IDLE: Int = 1 shl 2
        const  val DEVICE_CHARGING: Int = 1 shl 3
        const  val DEVICE_STORAGE_NOT_LOW: Int = 1 shl 4

        private fun isInternetConnectivityValidated(connectivityManager: ConnectivityManager): Boolean {
            // It's possible to check NetworkCapabilities.NET_CAPABILITY_VALIDATED from API level 23, but
            // RequirementsWatcher only fires an event to re-check the requirements when NetworkCapabilities
            // change from API level 24. We assume that network capability is validated for API level 23 to
            // keep in sync.
            if (Build.VERSION.SDK_INT < 24) {
                return true
            }

            val activeNetwork = connectivityManager.getActiveNetwork() ?: return false

            try {
                val networkCapabilities =
                    connectivityManager.getNetworkCapabilities(activeNetwork)
                return networkCapabilities != null
                        && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } catch (e: SecurityException) {
                // Workaround for https://issuetracker.google.com/issues/175055271.
                return true
            }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<Requirements?> =
            object : Parcelable.Creator<Requirements?> {
                override fun createFromParcel(`in`: Parcel): Requirements {
                    return Requirements(`in`.readInt())
                }

                override fun newArray(size: Int): Array<Requirements?> {
                    return arrayOfNulls(size)
                }
            }
    }
}
