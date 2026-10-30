package androidx.media3.exoplayer.util

import android.os.SystemClock
import androidx.annotation.GuardedBy
import androidx.media3.common.C

import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction
import androidx.media3.exoplayer.upstream.Loader.Loadable
import com.google.common.base.Preconditions
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Arrays

object SntpClient {
    const val DEFAULT_NTP_HOST: String = "time.android.com"
    const val DEFAULT_TIMEOUT_MS: Int = 1000
    private const val MAX_RETRY_COUNT = 10
    private const val ORIGINATE_TIME_OFFSET = 24
    private const val RECEIVE_TIME_OFFSET = 32
    private const val TRANSMIT_TIME_OFFSET = 40
    private const val NTP_PACKET_SIZE = 48
    private const val NTP_PORT = 123
    private const val NTP_MODE_CLIENT = 3
    private const val NTP_MODE_SERVER = 4
    private const val NTP_MODE_BROADCAST = 5
    private const val NTP_VERSION = 3
    private const val NTP_LEAP_NOSYNC = 3
    private const val NTP_STRATUM_DEATH = 0
    private const val NTP_STRATUM_MAX = 15
    private val OFFSET_1900_TO_1970 = ((365L * 70L) + 17L) * 24L * 60L * 60L
    private val loaderLock = Any()
    private val valueLock = Any()

    @GuardedBy("valueLock")
    private var isInitialized = false

    @GuardedBy("valueLock")
    private var elapsedRealtimeOffsetMs: Long = 0

    @GuardedBy("valueLock")
    var ntpHost: String = DEFAULT_NTP_HOST
        get() {
            synchronized(valueLock) {
                return field
            }
        }
        set(ntpHost) {
            synchronized(valueLock) {
                if (field != ntpHost) {
                    field = ntpHost
                    isInitialized = false
                }
            }
        }

    @GuardedBy("valueLock")
    var timeoutMs: Int = DEFAULT_TIMEOUT_MS
        get() {
            synchronized(valueLock) {
                return field
            }
        }
        set(timeoutMs) {
            synchronized(valueLock) {
                if (field != timeoutMs) {
                    field = timeoutMs
                    isInitialized = false
                }
            }
        }

    @GuardedBy("valueLock")
    private var maxElapsedTimeUntilUpdateMs = C.TIME_UNSET

    @GuardedBy("valueLock")
    private var lastUpdateElapsedRealtime = C.TIME_UNSET

    fun setMaxElapsedTimeUntilUpdateMs(maxElapsedTimeUntilUpdateMs: Long) {
        synchronized(valueLock) {
            SntpClient.maxElapsedTimeUntilUpdateMs = maxElapsedTimeUntilUpdateMs
        }
    }

    fun getMaxElapsedTimeUntilUpdateMs(): Long {
        synchronized(valueLock) {
            return maxElapsedTimeUntilUpdateMs
        }
    }

    fun isInitialized(): Boolean {
        synchronized(valueLock) {
            if (lastUpdateElapsedRealtime != C.TIME_UNSET
                && maxElapsedTimeUntilUpdateMs != C.TIME_UNSET
            ) {
                val deltaLastUpdate = SystemClock.elapsedRealtime() - lastUpdateElapsedRealtime
                isInitialized = isInitialized && deltaLastUpdate < maxElapsedTimeUntilUpdateMs
            }
            return isInitialized
        }
    }

    fun getElapsedRealtimeOffsetMs(): Long {
        synchronized(valueLock) {
            return if (isInitialized) elapsedRealtimeOffsetMs else C.TIME_UNSET
        }
    }

    fun initialize(
        loader: Loader?, callback: InitializationCallback?
    ) {
        var loader = loader
        if (isInitialized()) {
            callback?.onInitialized()
            return
        }
        if (loader == null) {
            loader = Loader("SntpClient")
        }
        loader.startLoading<Loadable>(
            NtpTimeLoadable(), NtpTimeCallback(callback), 1
        )
    }

    @Throws(IOException::class)
    private fun loadNtpTimeOffsetMs(): Long {
        DatagramSocket().use { socket ->
            socket.setSoTimeout(timeoutMs)
            var retryCount = 0
            var timeoutException: SocketTimeoutException? = null
            val addresses = InetAddress.getAllByName(
                ntpHost
            )
            for (address in addresses) {
                val buffer = ByteArray(NTP_PACKET_SIZE)
                val request = DatagramPacket(buffer, buffer.size, address, NTP_PORT)

                // Set mode = 3 (client) and version = 3. Mode is in low 3 bits of the first byte and
                // Version is in bits 3-5 of the first byte.
                buffer[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()

                // Get current time and write it to the request packet.
                val requestTime = System.currentTimeMillis()
                val requestTicks = SystemClock.elapsedRealtime()
                writeTimestamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)

                socket.send(request)

                // Read the response.
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (e: SocketTimeoutException) {
                    // Store the timeout exception and try the next address if we have not reached retry limit
                    if (timeoutException == null) {
                        timeoutException = e
                    } else {
                        timeoutException.addSuppressed(e)
                    }
                    if (retryCount++ < MAX_RETRY_COUNT) {
                        continue
                    } else {
                        break
                    }
                }

                val responseTicks = SystemClock.elapsedRealtime()
                val responseTime = requestTime + (responseTicks - requestTicks)

                // Extract the results.
                val leap = ((buffer[0].toInt() shr 6) and 0x3).toByte()
                val mode = (buffer[0].toInt() and 0x7).toByte()
                val stratum = buffer[1].toInt() and 0xff
                val originateTime = readTimestamp(buffer, ORIGINATE_TIME_OFFSET)
                val receiveTime = readTimestamp(buffer, RECEIVE_TIME_OFFSET)
                val transmitTime = readTimestamp(buffer, TRANSMIT_TIME_OFFSET)

                // Check server reply validity according to RFC.
                checkValidServerReply(leap, mode, stratum, transmitTime)

                // receiveTime = originateTime + transit + skew
                // responseTime = transmitTime + transit - skew
                // clockOffset = ((receiveTime - originateTime) + (transmitTime - responseTime))/2
                //             = ((originateTime + transit + skew - originateTime) +
                //                (transmitTime - (transmitTime + transit - skew)))/2
                //             = ((transit + skew) + (transmitTime - transmitTime - transit + skew))/2
                //             = (transit + skew - transit + skew)/2
                //             = (2 * skew)/2 = skew
                val clockOffset =
                    ((receiveTime - originateTime) + (transmitTime - responseTime)) / 2

                // Save our results using the times on this side of the network latency (i.e. response
                // rather than request time)
                val ntpTime = responseTime + clockOffset
                val ntpTimeReference = responseTicks

                return ntpTime - ntpTimeReference
            }
            // If no response is received from any of the addresses, throw an exception.
            throw timeoutException!!
        }
    }

    private fun readTimestamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        // Special case: zero means zero.
        if (seconds == 0L && fraction == 0L) {
            return 0
        }
        return ((seconds - OFFSET_1900_TO_1970) * 1000) + ((fraction * 1000L) / 0x100000000L)
    }

    private fun writeTimestamp(buffer: ByteArray, offset: Int, time: Long) {
        // Special case: zero means zero.
        var offset = offset
        if (time == 0L) {
            Arrays.fill(buffer, offset, offset + 8, 0x00.toByte())
            return
        }

        var seconds = time / 1000L
        val milliseconds = time - seconds * 1000L
        seconds += OFFSET_1900_TO_1970

        // Write seconds in big endian format.
        buffer[offset++] = (seconds shr 24).toByte()
        buffer[offset++] = (seconds shr 16).toByte()
        buffer[offset++] = (seconds shr 8).toByte()
        buffer[offset++] = (seconds shr 0).toByte()

        val fraction = milliseconds * 0x100000000L / 1000L
        // Write fraction in big endian format.
        buffer[offset++] = (fraction shr 24).toByte()
        buffer[offset++] = (fraction shr 16).toByte()
        buffer[offset++] = (fraction shr 8).toByte()
        // Low order bits should be random data.
        buffer[offset++] = (Math.random() * 255.0).toInt().toByte()
    }

    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset]
        val b1 = buffer[offset + 1]
        val b2 = buffer[offset + 2]
        val b3 = buffer[offset + 3]

        // Convert signed bytes to unsigned values.
        val i0 = (if ((b0.toInt() and 0x80) == 0x80) (b0.toInt() and 0x7F) + 0x80 else b0.toInt())
        val i1 = (if ((b1.toInt() and 0x80) == 0x80) (b1.toInt() and 0x7F) + 0x80 else b1.toInt())
        val i2 = (if ((b2.toInt() and 0x80) == 0x80) (b2.toInt() and 0x7F) + 0x80 else b2.toInt())
        val i3 = (if ((b3.toInt() and 0x80) == 0x80) (b3.toInt() and 0x7F) + 0x80 else b3.toInt())

        return (i0.toLong() shl 24) + (i1.toLong() shl 16) + (i2.toLong() shl 8) + i3.toLong()
    }

    @Throws(IOException::class)
    private fun checkValidServerReply(leap: Byte, mode: Byte, stratum: Int, transmitTime: Long) {
        if (leap.toInt() == NTP_LEAP_NOSYNC) {
            throw IOException("SNTP: Unsynchronized server")
        }
        if ((mode.toInt() != NTP_MODE_SERVER) && (mode.toInt() != NTP_MODE_BROADCAST)) {
            throw IOException("SNTP: Untrusted mode: $mode")
        }
        if ((stratum == NTP_STRATUM_DEATH) || (stratum > NTP_STRATUM_MAX)) {
            throw IOException("SNTP: Untrusted stratum: $stratum")
        }
        if (transmitTime == 0L) {
            throw IOException("SNTP: Zero transmitTime")
        }
    }

    interface InitializationCallback {
        fun onInitialized()
        fun onInitializationFailed(error: IOException?)
    }

    private class NtpTimeLoadable : Loadable {
        override fun cancelLoad() {}

        @Throws(IOException::class)
        override fun load() {
            // Synchronized to prevent redundant parallel requests.
            synchronized(loaderLock) {
                synchronized(valueLock) {
                    if (isInitialized) {
                        return
                    }
                }
                val offsetMs = loadNtpTimeOffsetMs()
                synchronized(valueLock) {
                    lastUpdateElapsedRealtime = SystemClock.elapsedRealtime()
                    elapsedRealtimeOffsetMs = offsetMs
                    isInitialized = true
                }
            }
        }
    }

    private class NtpTimeCallback(private val callback: InitializationCallback?) :
        Loader.Callback<Loadable> {
        override fun onLoadCompleted(
            loadable: Loadable,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long
        ) {
            if (callback != null) {
                if (!isInitialized()) {
                    // This may happen in the unlikely edge case of someone calling setNtpHost between the end
                    // of the load method and this callback.
                    callback.onInitializationFailed(IOException(ConcurrentModificationException()))
                } else {
                    callback.onInitialized()
                }
            }
        }

        override fun onLoadCanceled(
            loadable: Loadable, elapsedRealtimeMs: Long, loadDurationMs: Long, released: Boolean
        ) {
            // Ignore.
        }

        override fun onLoadError(
            loadable: Loadable,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            error: IOException,
            errorCount: Int
        ): LoadErrorAction {
            callback?.onInitializationFailed(error)
            return Loader.DONT_RETRY
        }
    }
}
