package androidx.media3.exoplayer.upstream

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.util.BackgroundExecutor.get
import androidx.media3.common.util.Clock
import androidx.media3.common.util.NetworkTypeObserver
import androidx.media3.common.util.Util.getCountryCode
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import com.google.common.base.Strings

import kotlin.math.sqrt

class DefaultBandwidthMeter private constructor(
    context: Context?,
    initialBitrateEstimates: MutableMap<Int, Long?>,
    maxWeight: Int,
    private val clock: Clock,
    private val resetOnNetworkTypeChange: Boolean,
    private val initialBitrateSupplier: InitialBitrateSupplier?
) : BandwidthMeter, TransferListener {
    interface InitialBitrateSupplier {
        fun getInitialBitrateEstimate(@C.NetworkType networkType: @C.NetworkType Int): Long
    }

    class Builder(context: Context?) {
        // Handling of null is for backward compatibility only.
        private val context: Context? = context?.applicationContext
        private val initialBitrateEstimates: MutableMap<Int, Long?>

        private var slidingWindowMaxWeight: Int
        private var clock: Clock
        private var resetOnNetworkTypeChange = true
        private var initialBitrateSupplier: InitialBitrateSupplier? = null

        init {
            slidingWindowMaxWeight = DEFAULT_SLIDING_WINDOW_MAX_WEIGHT
            clock = Clock.DEFAULT
            initialBitrateEstimates = HashMap<Int, Long?>(8)
            initialBitrateEstimates[C.NETWORK_TYPE_UNKNOWN] = DEFAULT_INITIAL_BITRATE_ESTIMATE
            initialBitrateEstimates[C.NETWORK_TYPE_WIFI] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_2G] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_3G] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_4G] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_5G_NSA] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_5G_SA] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_ETHERNET] = C.TIME_UNSET
        }

        fun setSlidingWindowMaxWeight(slidingWindowMaxWeight: Int): Builder {
            this.slidingWindowMaxWeight = slidingWindowMaxWeight
            return this
        }

        fun setInitialBitrateEstimate(initialBitrateEstimate: Long): Builder {
            for (networkType in initialBitrateEstimates.keys) {
                setInitialBitrateEstimate(networkType, initialBitrateEstimate)
            }
            return this
        }

        fun setInitialBitrateEstimate(
            @C.NetworkType networkType: @C.NetworkType Int, initialBitrateEstimate: Long
        ): Builder {
            initialBitrateEstimates.put(networkType, initialBitrateEstimate)
            return this
        }

        fun setInitialBitrateEstimate(countryCode: String): Builder {
            var countryCode = countryCode
            countryCode = Ascii.toUpperCase(countryCode)
            for (networkType in initialBitrateEstimates.keys) {
                setInitialBitrateEstimate(
                    networkType, getInitialBitrateEstimatesForCountry(countryCode, networkType)
                )
            }
            return this
        }

        fun setClock(clock: Clock): Builder {
            this.clock = clock
            return this
        }

        fun setResetOnNetworkTypeChange(resetOnNetworkTypeChange: Boolean): Builder {
            this.resetOnNetworkTypeChange = resetOnNetworkTypeChange
            return this
        }

        fun setInitialBitrateSupplier(initialBitrateSupplier: InitialBitrateSupplier?): Builder {
            this.initialBitrateSupplier = initialBitrateSupplier
            return this
        }

        fun build(): DefaultBandwidthMeter {
            return DefaultBandwidthMeter(
                context,
                initialBitrateEstimates,
                slidingWindowMaxWeight,
                clock,
                resetOnNetworkTypeChange,
                initialBitrateSupplier
            )
        }
    }

    private val context: Context? = context?.applicationContext
    private val initialBitrateEstimates = initialBitrateEstimates.toMap()
    private val eventDispatcher = BandwidthMeter.EventListener.EventDispatcher()

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    private val slidingPercentile = SlidingPercentile(maxWeight)

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    private var streamCount = 0

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    private var sampleStartTimeMs: Long = 0

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    private var sampleBytesTransferred: Long = 0

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    private var totalElapsedTimeMs: Long = 0

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    private var totalBytesTransferred: Long = 0

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    override var bitrateEstimate: Long = 0
        @Synchronized
        get

    @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
    private var lastReportedBitrateEstimate: Long = 0

    @C.NetworkType
    private var networkType: @C.NetworkType Int = 0
    private var networkTypeOverrideSet = false

    @C.NetworkType
    private var networkTypeOverride: @C.NetworkType Int = 0
    private var countryCode:String? = null

    init {
        if (context != null) {
            val networkTypeObserver = NetworkTypeObserver.getInstance(context)
            networkType = networkTypeObserver.getNetworkType()
            bitrateEstimate = getInitialBitrateEstimateForNetworkType(networkType)
            networkTypeObserver.register(
                NetworkTypeObserver.Listener { networkType: Int ->
                    this.onNetworkTypeChanged(
                        networkType
                    )
                }, get()
            )
        } else {
            networkType = C.NETWORK_TYPE_UNKNOWN
            bitrateEstimate = DEFAULT_INITIAL_BITRATE_ESTIMATE
        }
    }

    @Synchronized
    fun setNetworkTypeOverride(@C.NetworkType networkType: @C.NetworkType Int) {
        networkTypeOverride = networkType
        networkTypeOverrideSet = true
        onNetworkTypeChanged(networkType)
    }

    override val transferListener: TransferListener?
        get() = this

    override fun addEventListener(
        eventHandler: Handler,
        eventListener: BandwidthMeter.EventListener
    ) {
        eventDispatcher.addListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        eventDispatcher.removeListener(eventListener)
    }

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean
    ) {
        // Do nothing.
    }

    @Synchronized
    override fun onTransferStart(
        source: DataSource, dataSpec: DataSpec, isNetwork: Boolean
    ) {
        if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
            return
        }
        if (streamCount == 0) {
            sampleStartTimeMs = clock.elapsedRealtime()
        }
        streamCount++
    }

    @Synchronized
    override fun onBytesTransferred(
        source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int
    ) {
        if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
            return
        }
        sampleBytesTransferred += bytesTransferred.toLong()
    }

    @Synchronized
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
            return
        }
        Preconditions.checkState(streamCount > 0)
        val nowMs = clock.elapsedRealtime()
        val sampleElapsedTimeMs = (nowMs - sampleStartTimeMs).toInt()
        totalElapsedTimeMs += sampleElapsedTimeMs.toLong()
        totalBytesTransferred += sampleBytesTransferred
        if (sampleElapsedTimeMs > 0) {
            val bitsPerSecond = (sampleBytesTransferred * 8000f) / sampleElapsedTimeMs
            slidingPercentile.addSample(
                sqrt(sampleBytesTransferred.toDouble()).toInt(),
                bitsPerSecond
            )
            if (totalElapsedTimeMs >= ELAPSED_MILLIS_FOR_ESTIMATE
                || totalBytesTransferred >= BYTES_TRANSFERRED_FOR_ESTIMATE
            ) {
                bitrateEstimate = slidingPercentile.getPercentile(0.5f).toLong()
            }
            maybeNotifyBandwidthSample(sampleElapsedTimeMs, sampleBytesTransferred, bitrateEstimate)
            sampleStartTimeMs = nowMs
            sampleBytesTransferred = 0
        } // Else any sample bytes transferred will be carried forward into the next sample.

        streamCount--
    }

    @Synchronized
    private fun onNetworkTypeChanged(@C.NetworkType networkType: @C.NetworkType Int) {
        var networkType = networkType
        if (this.networkType != C.NETWORK_TYPE_UNKNOWN && !resetOnNetworkTypeChange) {
            // Reset on network change disabled. Ignore all updates except the initial one.
            return
        }

        if (networkTypeOverrideSet) {
            networkType = networkTypeOverride
        }
        if (this.networkType == networkType && countryCode != null) {
            return
        }

        this.networkType = networkType
        if (networkType == C.NETWORK_TYPE_OFFLINE || networkType == C.NETWORK_TYPE_UNKNOWN || networkType == C.NETWORK_TYPE_OTHER) {
            // It's better not to reset the bandwidth meter for these network types.
            return
        }

        if (countryCode == null) {
            countryCode = getCountryCode(context)
        }

        // Reset the bitrate estimate and report it, along with any bytes transferred.
        this.bitrateEstimate = getInitialBitrateEstimateForNetworkType(networkType)
        val nowMs = clock.elapsedRealtime()
        val sampleElapsedTimeMs = if (streamCount > 0) (nowMs - sampleStartTimeMs).toInt() else 0
        maybeNotifyBandwidthSample(sampleElapsedTimeMs, sampleBytesTransferred, bitrateEstimate)

        // Reset the remainder of the state.
        sampleStartTimeMs = nowMs
        sampleBytesTransferred = 0
        totalBytesTransferred = 0
        totalElapsedTimeMs = 0
        slidingPercentile.reset()
    }

    @GuardedBy("this")
    private fun maybeNotifyBandwidthSample(
        elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long
    ) {
        if (elapsedMs == 0 && bytesTransferred == 0L && bitrateEstimate == lastReportedBitrateEstimate) {
            return
        }
        lastReportedBitrateEstimate = bitrateEstimate
        eventDispatcher.bandwidthSample(elapsedMs, bytesTransferred, bitrateEstimate)
    }

    private fun getInitialBitrateEstimateForNetworkType(@C.NetworkType networkType: @C.NetworkType Int): Long {
        if (initialBitrateSupplier != null) {
            val supplierEstimate = initialBitrateSupplier.getInitialBitrateEstimate(networkType)
            if (supplierEstimate != C.TIME_UNSET) {
                return supplierEstimate
            }
        }
        var initialBitrateEstimate = initialBitrateEstimates[networkType]
        if (initialBitrateEstimate == null) {
            initialBitrateEstimate = initialBitrateEstimates[C.NETWORK_TYPE_UNKNOWN]
        } else if (initialBitrateEstimate == C.TIME_UNSET) {
            initialBitrateEstimate = getInitialBitrateEstimatesForCountry(countryCode, networkType)
        }
        if (initialBitrateEstimate == null) {
            initialBitrateEstimate = DEFAULT_INITIAL_BITRATE_ESTIMATE
        }
        return initialBitrateEstimate
    }

    companion object {
        val DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI =
            listOf(4300000L, 3200000L, 2400000L, 1700000L, 860000L)
        val DEFAULT_INITIAL_BITRATE_ESTIMATES_2G =
            listOf(1500000L, 980000L, 750000L, 520000L, 290000L)
        val DEFAULT_INITIAL_BITRATE_ESTIMATES_3G =
            listOf(2000000L, 1300000L, 1000000L, 860000L, 610000L)
        val DEFAULT_INITIAL_BITRATE_ESTIMATES_4G =
            listOf(2500000L, 1700000L, 1200000L, 970000L, 680000L)
        val DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA =
            listOf(4700000L, 2800000L, 2100000L, 1700000L, 980000L)
        val DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA =
            listOf(2700000L, 2000000L, 1600000L, 1300000L, 1000000L)

        const val DEFAULT_INITIAL_BITRATE_ESTIMATE: Long = 1000000
        const val DEFAULT_SLIDING_WINDOW_MAX_WEIGHT: Int = 2000
        private const val COUNTRY_GROUP_SHIFT_2G = 3
        private const val COUNTRY_GROUP_SHIFT_3G = 6
        private const val COUNTRY_GROUP_SHIFT_4G = 9
        private const val COUNTRY_GROUP_SHIFT_5G_NSA = 12
        private const val COUNTRY_GROUP_SHIFT_5G_SA = 15
        private const val COUNTRY_GROUP_INDEX_MASK = 0x7

        // Intentionally mutable static field and using application context to prevent leakage.
        @SuppressLint("NonFinalStaticField", "StaticFieldLeak")
        private var singletonInstance: DefaultBandwidthMeter? = null

        @Synchronized
        fun getSingletonInstance(context: Context?): DefaultBandwidthMeter {
            if (singletonInstance == null) {
                singletonInstance = Builder(context).build()
            }
            return singletonInstance!!
        }

        private const val ELAPSED_MILLIS_FOR_ESTIMATE = 2000
        private const val BYTES_TRANSFERRED_FOR_ESTIMATE = 512 * 1024

        private fun isTransferAtFullNetworkSpeed(dataSpec: DataSpec, isNetwork: Boolean): Boolean {
            return isNetwork && !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
        }

        private fun getInitialBitrateEstimatesForCountry(
            countryCode: String?, @C.NetworkType networkType: @C.NetworkType Int
        ): Long {
            val packedGroupIndices: Int =
                getInitialBitrateCountryGroupAssignment(Strings.nullToEmpty(countryCode))
            when (networkType) {
                C.NETWORK_TYPE_WIFI, C.NETWORK_TYPE_ETHERNET ->         // Assume default Wifi speed for Ethernet to prevent using the slower fallback.
                    return DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI[packedGroupIndices and DefaultBandwidthMeter.COUNTRY_GROUP_INDEX_MASK]

                C.NETWORK_TYPE_2G -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_2G[(packedGroupIndices shr DefaultBandwidthMeter.COUNTRY_GROUP_SHIFT_2G) and DefaultBandwidthMeter.COUNTRY_GROUP_INDEX_MASK]

                C.NETWORK_TYPE_3G -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_3G[(packedGroupIndices shr DefaultBandwidthMeter.COUNTRY_GROUP_SHIFT_3G) and DefaultBandwidthMeter.COUNTRY_GROUP_INDEX_MASK]

                C.NETWORK_TYPE_4G -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_4G[(packedGroupIndices shr DefaultBandwidthMeter.COUNTRY_GROUP_SHIFT_4G) and DefaultBandwidthMeter.COUNTRY_GROUP_INDEX_MASK]

                C.NETWORK_TYPE_5G_NSA -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA[(packedGroupIndices shr DefaultBandwidthMeter.COUNTRY_GROUP_SHIFT_5G_NSA) and DefaultBandwidthMeter.COUNTRY_GROUP_INDEX_MASK]

                C.NETWORK_TYPE_5G_SA -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA[(packedGroupIndices shr DefaultBandwidthMeter.COUNTRY_GROUP_SHIFT_5G_SA) and DefaultBandwidthMeter.COUNTRY_GROUP_INDEX_MASK]

                C.NETWORK_TYPE_UNKNOWN -> return DEFAULT_INITIAL_BITRATE_ESTIMATE
                else -> return DEFAULT_INITIAL_BITRATE_ESTIMATE
            }
        }

        private fun getInitialBitrateCountryGroupAssignment(country: String): Int {
            when (country) {
                "AE" -> return (1
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AL" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AO" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AR" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AS" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AU" -> return (0
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AW" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BD" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BE" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BH" -> return (1
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BJ" -> return (4
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BM" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BN" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BO" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BR" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BS" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BT" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BW" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BY" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CF" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CH" -> return (0
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AG", "CI" -> return (2
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BZ", "CK" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CN" -> return (2
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CO" -> return (2
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CV" -> return (2
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CY" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CZ" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "DE" -> return (0
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "DK" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "EC" -> return (1
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "ES" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "ET" -> return (4
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "FI" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "FJ" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "FM" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "FO" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "FR" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GA" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GB" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GD" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GE" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GF" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GG" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GH" -> return (3
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GN" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GP" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GR" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GT" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GU" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GW" -> return (4
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GY" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "HK" -> return (0
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "ID" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "IE" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "IL" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "IN" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "IO" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "IQ" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "IR" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "IT" -> return (0
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GI", "IM", "JE" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "JM" -> return (2
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "JP" -> return (0
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "KE" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "KG" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "KH" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "KR" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "HR", "KW" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "KZ" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "LA" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "LB" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "LC" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "DO", "LR" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "LT" -> return (0
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "LU" -> return (4
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MA" -> return (3
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GL", "MC" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MD" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "ME" -> return (2
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MF" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CG", "EG", "MG" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MK" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CD", "ML" -> return (3
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "LK", "MM" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MN" -> return (2
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MO" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MQ" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CM", "MR" -> return (4
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MU" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MV" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MW" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MX" -> return (2
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MY" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "NA" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "NG" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CR", "NI" -> return (2
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "NL" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "NO" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "NP" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "NZ" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "OM" -> return (2
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AM", "PA" -> return (2
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PE" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PF" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "LS", "PG" -> return (4
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PH" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PK" -> return (3
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PL" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PR" -> return (2
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PS" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "PW" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BL", "MP", "PY" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "QA" -> return (1
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "RE" -> return (0
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "RO" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "RS" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "RU" -> return (1
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "RW" -> return (3
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SA" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AT", "EE", "HU", "IS", "LV", "MT", "SE" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SG" -> return (2
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AQ", "ER", "NU", "SC", "SH" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BG", "PT", "SI" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "FK", "NF", "SJ" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SK" -> return (0
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AZ", "DJ", "LY", "SL" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SN" -> return (4
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SO" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SR" -> return (2
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "GM", "SS" -> return (4
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "ST" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SV" -> return (2
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AF", "SZ" -> return (4
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "TC" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BF", "SD", "SY", "TD" -> return (4
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "TG" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CL", "TH" -> return (0
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "DZ", "TJ" -> return (3
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CU", "KI", "NR", "TL" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "TN" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "TO" -> return (3
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BA", "JO", "TR" -> return (1
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "TT" -> return (2
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "TW" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (0 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "TZ" -> return (3
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "CA", "UA" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "UG" -> return (3
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "US" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "UY" -> return (2
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "UZ" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (3 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AX", "CX", "LI", "MS", "PM", "SM", "VA" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "AD", "AI", "BB", "BQ", "CW", "DM", "KN", "KY", "SX", "VC" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (0 shl COUNTRY_GROUP_SHIFT_3G)
                        or (0 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "VG" -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (4 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "VI" -> return (0
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "VN" -> return (0
                        or (0 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "KM", "VU" -> return (4
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MH", "TM", "TV", "WF" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "MZ", "WS" -> return (3
                        or (1 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "XK" -> return (1
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (1 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "BI", "GQ", "HT", "NE", "VE", "YE" -> return (4
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "NC", "YT" -> return (2
                        or (3 shl COUNTRY_GROUP_SHIFT_2G)
                        or (3 shl COUNTRY_GROUP_SHIFT_3G)
                        or (4 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "ZA" -> return (2
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (1 shl COUNTRY_GROUP_SHIFT_4G)
                        or (1 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "ZM" -> return (4
                        or (4 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                "SB", "ZW" -> return (4
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (4 shl COUNTRY_GROUP_SHIFT_3G)
                        or (3 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))

                else -> return (2
                        or (2 shl COUNTRY_GROUP_SHIFT_2G)
                        or (2 shl COUNTRY_GROUP_SHIFT_3G)
                        or (2 shl COUNTRY_GROUP_SHIFT_4G)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_NSA)
                        or (2 shl COUNTRY_GROUP_SHIFT_5G_SA))
            }
        }
    }
}
