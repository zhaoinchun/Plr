package androidx.media3.exoplayer.upstream.experimental

import android.content.Context
import android.os.Handler
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.util.BackgroundExecutor.get

import androidx.media3.common.util.NetworkTypeObserver
import androidx.media3.common.util.Util.getCountryCode
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.TimeToFirstByteEstimator
import com.google.common.base.Ascii
import com.google.common.base.Strings

class ExperimentalBandwidthMeter private constructor(
    context: Context?,
    initialBitrateEstimates: MutableMap<Int, Long?>,
// Used in TransferListener methods that are called on a background thread.
    @field:GuardedBy("this") private val timeToFirstByteEstimator: TimeToFirstByteEstimator,
    // Used in TransferListener methods that are called on a background thread.
    @field:GuardedBy("this") private val bandwidthEstimator: BandwidthEstimator,
    private val resetOnNetworkTypeChange: Boolean
) : BandwidthMeter, TransferListener {
    class Builder(context: Context) {
        // Handling of null is for backward compatibility only.
        private val context: Context? = context.applicationContext
        private val initialBitrateEstimates = HashMap<Int, Long?>(8)

        private var timeToFirstByteEstimator: TimeToFirstByteEstimator =
            PercentileTimeToFirstByteEstimator(
                DEFAULT_TIME_TO_FIRST_BYTE_SAMPLES,
                DEFAULT_TIME_TO_FIRST_BYTE_PERCENTILE
            )
        private var bandwidthEstimator: BandwidthEstimator =
            SplitParallelSampleBandwidthEstimator.Builder().build()
        private var resetOnNetworkTypeChange = true

        init {
            initialBitrateEstimates[C.NETWORK_TYPE_UNKNOWN] = DEFAULT_INITIAL_BITRATE_ESTIMATE
            initialBitrateEstimates[C.NETWORK_TYPE_WIFI] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_2G] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_3G] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_4G] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_5G_NSA] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_5G_SA] = C.TIME_UNSET
            initialBitrateEstimates[C.NETWORK_TYPE_ETHERNET] = C.TIME_UNSET
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

        fun setTimeToFirstByteEstimator(timeToFirstByteEstimator: TimeToFirstByteEstimator): Builder {
            this.timeToFirstByteEstimator = timeToFirstByteEstimator
            return this
        }

        fun setBandwidthEstimator(bandwidthEstimator: BandwidthEstimator): Builder {
            this.bandwidthEstimator = bandwidthEstimator
            return this
        }

        fun setResetOnNetworkTypeChange(resetOnNetworkTypeChange: Boolean): Builder {
            this.resetOnNetworkTypeChange = resetOnNetworkTypeChange
            return this
        }

        fun build(): ExperimentalBandwidthMeter {
            return ExperimentalBandwidthMeter(
                context,
                initialBitrateEstimates,
                timeToFirstByteEstimator,
                bandwidthEstimator,
                resetOnNetworkTypeChange
            )
        }
    }

    private val context: Context?
    private val initialBitrateEstimates = initialBitrateEstimates.toMap()

    @C.NetworkType
    private var networkType: @C.NetworkType Int
    private var initialBitrateEstimate: Long
    private var networkTypeOverrideSet = false

    @C.NetworkType
    private var networkTypeOverride: @C.NetworkType Int = 0
    private var countryCode:String? = null

    init {
        this.context = if (context == null) null else context.getApplicationContext()
        val networkTypeObserver = NetworkTypeObserver.getInstance(context!!)
        networkType = networkTypeObserver.getNetworkType()
        initialBitrateEstimate = getInitialBitrateEstimateForNetworkType(networkType)
        networkTypeObserver.register(
            NetworkTypeObserver.Listener { networkType: Int -> this.onNetworkTypeChanged(networkType) },
            get()!!
        )
    }

    @Synchronized
    fun setNetworkTypeOverride(@C.NetworkType networkType: @C.NetworkType Int) {
        networkTypeOverride = networkType
        networkTypeOverrideSet = true
        onNetworkTypeChanged(networkType)
    }

    @get:Synchronized
    override val bitrateEstimate: Long
        get() {
            val bandwidthEstimate = bandwidthEstimator.bandwidthEstimate
            return if (bandwidthEstimate != BandwidthEstimator.ESTIMATE_NOT_AVAILABLE)
                bandwidthEstimate
            else
                initialBitrateEstimate
        }

    @get:Synchronized
    override val timeToFirstByteEstimateUs: Long
        get() = timeToFirstByteEstimator.timeToFirstByteEstimateUs

    override val transferListener: TransferListener
        get() = this

    @Synchronized
    override fun addEventListener(
        eventHandler: Handler,
        eventListener: BandwidthMeter.EventListener
    ) {
        bandwidthEstimator.addEventListener(eventHandler, eventListener)
    }

    @Synchronized
    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        bandwidthEstimator.removeEventListener(eventListener)
    }

    @Synchronized
    override fun onTransferInitializing(
        source: DataSource, dataSpec: DataSpec, isNetwork: Boolean
    ) {
        if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
            return
        }
        timeToFirstByteEstimator.onTransferInitializing(dataSpec)
        bandwidthEstimator.onTransferInitializing(source)
    }

    @Synchronized
    override fun onTransferStart(
        source: DataSource, dataSpec: DataSpec, isNetwork: Boolean
    ) {
        if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
            return
        }
        timeToFirstByteEstimator.onTransferStart(dataSpec)
        bandwidthEstimator.onTransferStart(source)
    }

    @Synchronized
    override fun onBytesTransferred(
        source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int
    ) {
        if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
            return
        }
        bandwidthEstimator.onBytesTransferred(source, bytesTransferred)
    }

    @Synchronized
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
            return
        }
        bandwidthEstimator.onTransferEnd(source)
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
        this.initialBitrateEstimate = getInitialBitrateEstimateForNetworkType(networkType)
        bandwidthEstimator.onNetworkTypeChange(initialBitrateEstimate)
        timeToFirstByteEstimator.reset()
    }

    private fun getInitialBitrateEstimateForNetworkType(@C.NetworkType networkType: @C.NetworkType Int): Long {
        var initialBitrateEstimate = initialBitrateEstimates.get(networkType)
        if (initialBitrateEstimate == null) {
            initialBitrateEstimate = initialBitrateEstimates.get(C.NETWORK_TYPE_UNKNOWN)
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
        const val DEFAULT_TIME_TO_FIRST_BYTE_SAMPLES: Int = 20
        const val DEFAULT_TIME_TO_FIRST_BYTE_PERCENTILE: Float = 0.5f
        const val DEFAULT_INITIAL_BITRATE_ESTIMATE: Long = 1000000
        private const val COUNTRY_GROUP_INDEX_WIFI = 0
        private const val COUNTRY_GROUP_INDEX_2G = 1
        private const val COUNTRY_GROUP_INDEX_3G = 2
        private const val COUNTRY_GROUP_INDEX_4G = 3
        private const val COUNTRY_GROUP_INDEX_5G_NSA = 4
        private const val COUNTRY_GROUP_INDEX_5G_SA = 5

        private fun isTransferAtFullNetworkSpeed(dataSpec: DataSpec, isNetwork: Boolean): Boolean {
            return isNetwork && !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
        }

        private fun getInitialBitrateEstimatesForCountry(
            countryCode: String?, @C.NetworkType networkType: @C.NetworkType Int
        ): Long {
            val groupIndices: IntArray =
                getInitialBitrateCountryGroupAssignment(Strings.nullToEmpty(countryCode))
            when (networkType) {
                C.NETWORK_TYPE_WIFI, C.NETWORK_TYPE_ETHERNET ->         // Assume default Wifi speed for Ethernet to prevent using the slower fallback.
                    return DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI.get(groupIndices[COUNTRY_GROUP_INDEX_WIFI])!!

                C.NETWORK_TYPE_2G -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_2G.get(groupIndices[COUNTRY_GROUP_INDEX_2G])!!
                C.NETWORK_TYPE_3G -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_3G.get(groupIndices[COUNTRY_GROUP_INDEX_3G])!!
                C.NETWORK_TYPE_4G -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_4G.get(groupIndices[COUNTRY_GROUP_INDEX_4G])!!
                C.NETWORK_TYPE_5G_NSA -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA.get(
                    groupIndices[ExperimentalBandwidthMeter.COUNTRY_GROUP_INDEX_5G_NSA]
                )!!

                C.NETWORK_TYPE_5G_SA -> return DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA.get(
                    groupIndices[COUNTRY_GROUP_INDEX_5G_SA]
                )!!

                C.NETWORK_TYPE_UNKNOWN -> return DEFAULT_INITIAL_BITRATE_ESTIMATE
                else -> return DEFAULT_INITIAL_BITRATE_ESTIMATE
            }
        }

        private fun getInitialBitrateCountryGroupAssignment(country: String): IntArray {
            when (country) {
                "AE" -> return intArrayOf(1, 4, 2, 3, 4, 1)
                "AL" -> return intArrayOf(1, 1, 1, 2, 2, 2)
                "AO" -> return intArrayOf(3, 4, 4, 3, 2, 2)
                "AR" -> return intArrayOf(2, 2, 2, 2, 1, 2)
                "AS" -> return intArrayOf(2, 2, 3, 3, 2, 2)
                "AU" -> return intArrayOf(0, 3, 1, 1, 3, 0)
                "AW" -> return intArrayOf(2, 2, 3, 4, 2, 2)
                "BD" -> return intArrayOf(2, 1, 3, 2, 4, 2)
                "BE" -> return intArrayOf(0, 0, 1, 0, 1, 2)
                "BH" -> return intArrayOf(1, 3, 1, 3, 4, 2)
                "BJ" -> return intArrayOf(4, 4, 2, 3, 2, 2)
                "BM" -> return intArrayOf(0, 2, 0, 0, 2, 2)
                "BN" -> return intArrayOf(3, 2, 0, 0, 2, 2)
                "BO" -> return intArrayOf(1, 2, 4, 4, 2, 2)
                "BR" -> return intArrayOf(1, 1, 1, 1, 2, 4)
                "BS" -> return intArrayOf(3, 2, 1, 1, 2, 2)
                "BT" -> return intArrayOf(3, 1, 2, 2, 3, 2)
                "BW" -> return intArrayOf(3, 2, 1, 0, 2, 2)
                "BY" -> return intArrayOf(1, 2, 3, 3, 2, 2)
                "CF" -> return intArrayOf(4, 2, 4, 2, 2, 2)
                "CH" -> return intArrayOf(0, 1, 0, 0, 0, 2)
                "AG", "CI" -> return intArrayOf(2, 4, 3, 4, 2, 2)
                "BZ", "CK" -> return intArrayOf(2, 2, 2, 1, 2, 2)
                "CN" -> return intArrayOf(2, 0, 1, 1, 3, 1)
                "CO" -> return intArrayOf(2, 3, 3, 2, 2, 2)
                "CV" -> return intArrayOf(2, 3, 0, 1, 2, 2)
                "CY" -> return intArrayOf(1, 0, 1, 0, 0, 2)
                "CZ" -> return intArrayOf(0, 0, 2, 0, 1, 2)
                "DE" -> return intArrayOf(0, 1, 4, 2, 2, 1)
                "DK" -> return intArrayOf(0, 0, 2, 0, 0, 2)
                "EC" -> return intArrayOf(1, 3, 2, 1, 2, 2)
                "ES" -> return intArrayOf(0, 0, 0, 0, 1, 0)
                "ET" -> return intArrayOf(4, 3, 4, 4, 4, 2)
                "FI" -> return intArrayOf(0, 0, 0, 1, 0, 2)
                "FJ" -> return intArrayOf(3, 2, 2, 3, 2, 2)
                "FM" -> return intArrayOf(4, 2, 4, 0, 2, 2)
                "FO" -> return intArrayOf(0, 2, 2, 0, 2, 2)
                "FR" -> return intArrayOf(1, 1, 1, 1, 0, 2)
                "GA" -> return intArrayOf(3, 4, 0, 0, 2, 2)
                "GB" -> return intArrayOf(1, 1, 3, 2, 2, 2)
                "GD" -> return intArrayOf(2, 2, 0, 0, 2, 2)
                "GE" -> return intArrayOf(1, 1, 0, 2, 2, 2)
                "GF" -> return intArrayOf(3, 2, 3, 3, 2, 2)
                "GG" -> return intArrayOf(0, 2, 1, 1, 2, 2)
                "GH" -> return intArrayOf(3, 3, 3, 2, 2, 2)
                "GN" -> return intArrayOf(3, 4, 4, 2, 2, 2)
                "GP" -> return intArrayOf(2, 1, 1, 3, 2, 2)
                "GR" -> return intArrayOf(1, 0, 0, 0, 1, 2)
                "GT" -> return intArrayOf(2, 1, 2, 1, 2, 2)
                "GU" -> return intArrayOf(2, 2, 4, 3, 3, 2)
                "GW" -> return intArrayOf(4, 4, 1, 2, 2, 2)
                "GY" -> return intArrayOf(3, 1, 1, 3, 2, 2)
                "HK" -> return intArrayOf(0, 1, 0, 1, 1, 0)
                "ID" -> return intArrayOf(3, 1, 3, 3, 2, 4)
                "IE" -> return intArrayOf(1, 1, 1, 1, 1, 2)
                "IL" -> return intArrayOf(1, 2, 2, 3, 4, 2)
                "IN" -> return intArrayOf(1, 1, 3, 2, 2, 3)
                "IO" -> return intArrayOf(3, 2, 2, 0, 2, 2)
                "IQ" -> return intArrayOf(3, 2, 3, 2, 2, 2)
                "IR" -> return intArrayOf(4, 2, 3, 3, 4, 3)
                "IT" -> return intArrayOf(0, 1, 1, 2, 1, 2)
                "GI", "IM", "JE" -> return intArrayOf(0, 2, 0, 1, 2, 2)
                "JM" -> return intArrayOf(2, 4, 3, 1, 2, 2)
                "JP" -> return intArrayOf(0, 3, 2, 3, 4, 2)
                "KE" -> return intArrayOf(3, 2, 1, 1, 1, 2)
                "KG" -> return intArrayOf(2, 1, 1, 2, 2, 2)
                "KH" -> return intArrayOf(1, 0, 4, 2, 2, 2)
                "KR" -> return intArrayOf(0, 2, 2, 4, 4, 4)
                "HR", "KW" -> return intArrayOf(1, 0, 0, 0, 0, 2)
                "KZ" -> return intArrayOf(2, 1, 2, 2, 3, 2)
                "LA" -> return intArrayOf(1, 2, 1, 3, 2, 2)
                "LB" -> return intArrayOf(3, 1, 1, 2, 2, 2)
                "LC" -> return intArrayOf(2, 2, 1, 1, 2, 2)
                "DO", "LR" -> return intArrayOf(3, 4, 4, 4, 2, 2)
                "LT" -> return intArrayOf(0, 1, 0, 1, 0, 2)
                "LU" -> return intArrayOf(4, 0, 3, 2, 1, 3)
                "MA" -> return intArrayOf(3, 3, 1, 1, 2, 2)
                "GL", "MC" -> return intArrayOf(1, 2, 2, 0, 2, 2)
                "MD" -> return intArrayOf(1, 0, 0, 0, 2, 2)
                "ME" -> return intArrayOf(2, 0, 0, 1, 3, 2)
                "MF" -> return intArrayOf(1, 2, 2, 3, 2, 2)
                "CG", "EG", "MG" -> return intArrayOf(3, 4, 3, 3, 2, 2)
                "MK" -> return intArrayOf(1, 0, 0, 1, 3, 2)
                "CD", "ML" -> return intArrayOf(3, 3, 2, 2, 2, 2)
                "LK", "MM" -> return intArrayOf(3, 2, 3, 3, 4, 2)
                "MN" -> return intArrayOf(2, 0, 2, 2, 2, 2)
                "MO" -> return intArrayOf(0, 2, 4, 4, 3, 1)
                "MQ" -> return intArrayOf(2, 1, 2, 3, 2, 2)
                "CM", "MR" -> return intArrayOf(4, 3, 3, 4, 2, 2)
                "MU" -> return intArrayOf(3, 1, 0, 2, 2, 2)
                "MV" -> return intArrayOf(3, 2, 1, 3, 4, 2)
                "MW" -> return intArrayOf(3, 2, 2, 1, 2, 2)
                "MX" -> return intArrayOf(2, 4, 4, 4, 3, 2)
                "MY" -> return intArrayOf(1, 0, 4, 1, 1, 0)
                "NA" -> return intArrayOf(3, 4, 3, 2, 2, 2)
                "NG" -> return intArrayOf(3, 4, 2, 1, 2, 2)
                "CR", "NI" -> return intArrayOf(2, 4, 4, 4, 2, 2)
                "NL" -> return intArrayOf(2, 1, 4, 3, 0, 4)
                "NO" -> return intArrayOf(0, 0, 3, 0, 0, 2)
                "NP" -> return intArrayOf(2, 2, 4, 3, 2, 2)
                "NZ" -> return intArrayOf(0, 0, 1, 2, 4, 2)
                "OM" -> return intArrayOf(2, 3, 1, 2, 4, 2)
                "AM", "PA" -> return intArrayOf(2, 3, 2, 3, 2, 2)
                "PE" -> return intArrayOf(1, 2, 4, 4, 3, 2)
                "PF" -> return intArrayOf(2, 2, 3, 1, 2, 2)
                "LS", "PG" -> return intArrayOf(4, 3, 3, 3, 2, 2)
                "PH" -> return intArrayOf(2, 1, 2, 3, 2, 1)
                "PK" -> return intArrayOf(3, 3, 3, 3, 2, 2)
                "PL" -> return intArrayOf(1, 0, 2, 2, 4, 4)
                "PR" -> return intArrayOf(2, 0, 2, 1, 2, 0)
                "PS" -> return intArrayOf(3, 4, 1, 3, 2, 2)
                "PW" -> return intArrayOf(2, 2, 4, 1, 2, 2)
                "BL", "MP", "PY" -> return intArrayOf(1, 2, 2, 2, 2, 2)
                "QA" -> return intArrayOf(1, 4, 4, 4, 4, 2)
                "RE" -> return intArrayOf(0, 3, 2, 3, 1, 2)
                "RO" -> return intArrayOf(0, 0, 1, 1, 3, 2)
                "RS" -> return intArrayOf(1, 0, 0, 1, 2, 2)
                "RU" -> return intArrayOf(1, 0, 0, 1, 3, 3)
                "RW" -> return intArrayOf(3, 3, 2, 0, 2, 2)
                "SA" -> return intArrayOf(3, 1, 1, 2, 2, 0)
                "AT", "EE", "HU", "IS", "LV", "MT", "SE" -> return intArrayOf(0, 0, 0, 0, 0, 2)
                "SG" -> return intArrayOf(2, 3, 3, 3, 1, 1)
                "AQ", "ER", "NU", "SC", "SH" -> return intArrayOf(4, 2, 2, 2, 2, 2)
                "BG", "PT", "SI" -> return intArrayOf(0, 0, 0, 0, 1, 2)
                "FK", "NF", "SJ" -> return intArrayOf(3, 2, 2, 2, 2, 2)
                "SK" -> return intArrayOf(0, 1, 1, 1, 2, 2)
                "AZ", "DJ", "LY", "SL" -> return intArrayOf(4, 2, 3, 3, 2, 2)
                "SN" -> return intArrayOf(4, 4, 3, 2, 2, 2)
                "SO" -> return intArrayOf(2, 2, 3, 4, 4, 2)
                "SR" -> return intArrayOf(2, 4, 4, 1, 2, 2)
                "GM", "SS" -> return intArrayOf(4, 3, 2, 4, 2, 2)
                "ST" -> return intArrayOf(2, 2, 1, 2, 2, 2)
                "SV" -> return intArrayOf(2, 3, 2, 1, 2, 2)
                "AF", "SZ" -> return intArrayOf(4, 4, 3, 4, 2, 2)
                "TC" -> return intArrayOf(3, 2, 1, 2, 2, 2)
                "BF", "SD", "SY", "TD" -> return intArrayOf(4, 3, 4, 4, 2, 2)
                "TG" -> return intArrayOf(3, 4, 1, 0, 2, 2)
                "CL", "TH" -> return intArrayOf(0, 1, 2, 2, 2, 2)
                "DZ", "TJ" -> return intArrayOf(3, 3, 4, 4, 2, 2)
                "CU", "KI", "NR", "TL" -> return intArrayOf(4, 2, 4, 4, 2, 2)
                "TN" -> return intArrayOf(3, 1, 1, 1, 2, 2)
                "TO" -> return intArrayOf(3, 2, 4, 3, 2, 2)
                "BA", "JO", "TR" -> return intArrayOf(1, 1, 1, 1, 2, 2)
                "TT" -> return intArrayOf(2, 4, 1, 0, 2, 2)
                "TW" -> return intArrayOf(0, 0, 0, 0, 0, 0)
                "TZ" -> return intArrayOf(3, 4, 2, 1, 3, 2)
                "CA", "UA" -> return intArrayOf(0, 2, 1, 2, 3, 3)
                "UG" -> return intArrayOf(3, 3, 2, 3, 4, 2)
                "US" -> return intArrayOf(2, 2, 4, 1, 3, 1)
                "UY" -> return intArrayOf(2, 1, 1, 2, 1, 2)
                "UZ" -> return intArrayOf(1, 2, 3, 4, 3, 2)
                "AX", "CX", "LI", "MS", "PM", "SM", "VA" -> return intArrayOf(0, 2, 2, 2, 2, 2)
                "AD", "AI", "BB", "BQ", "CW", "DM", "KN", "KY", "SX", "VC" -> return intArrayOf(
                    1,
                    2,
                    0,
                    0,
                    2,
                    2
                )

                "VG" -> return intArrayOf(2, 2, 1, 1, 2, 4)
                "VI" -> return intArrayOf(0, 2, 1, 2, 2, 2)
                "VN" -> return intArrayOf(0, 0, 1, 2, 2, 2)
                "KM", "VU" -> return intArrayOf(4, 3, 3, 2, 2, 2)
                "MH", "TM", "TV", "WF" -> return intArrayOf(4, 2, 2, 4, 2, 2)
                "MZ", "WS" -> return intArrayOf(3, 1, 2, 2, 2, 2)
                "XK" -> return intArrayOf(1, 2, 1, 1, 2, 2)
                "BI", "GQ", "HT", "NE", "VE", "YE" -> return intArrayOf(4, 4, 4, 4, 2, 2)
                "NC", "YT" -> return intArrayOf(2, 3, 3, 4, 2, 2)
                "ZA" -> return intArrayOf(2, 4, 2, 1, 1, 2)
                "ZM" -> return intArrayOf(4, 4, 4, 3, 2, 2)
                "SB", "ZW" -> return intArrayOf(4, 2, 4, 3, 2, 2)
                else -> return intArrayOf(2, 2, 2, 2, 2, 2)
            }
        }
    }
}
