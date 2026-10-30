package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.google.common.base.Preconditions
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlin.math.min

class UdpDataSource  constructor(
    maxPacketSize: Int = DEFAULT_MAX_PACKET_SIZE,
    private val socketTimeoutMillis: Int = DEFAULT_SOCKET_TIMEOUT_MILLIS
) : BaseDataSource( true) {
    class UdpDataSourceException
        (
        cause: Throwable?,
        @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int
    ) : DataSourceException(cause, errorCode)

    private val packetBuffer: ByteArray
    private val packet: DatagramPacket

    override var uri: Uri? = null
    private var socket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var address: InetAddress? = null
    private var opened = false

    private var packetRemaining = 0

    init {
        packetBuffer = ByteArray(maxPacketSize)
        packet = DatagramPacket(packetBuffer, 0, maxPacketSize)
    }

    @Throws(UdpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val host = uri!!.host!!
        val port = uri!!.getPort()
        transferInitializing(dataSpec)
        try {
            address = InetAddress.getByName(host)
            val socketAddress = InetSocketAddress(address, port)
            if (address!!.isMulticastAddress()) {
                multicastSocket = MulticastSocket(socketAddress)
                multicastSocket!!.joinGroup(address)
                socket = multicastSocket
            } else {
                socket = DatagramSocket(socketAddress)
            }
            socket!!.setSoTimeout(socketTimeoutMillis)
        } catch (e: SecurityException) {
            throw UdpDataSourceException(e, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        } catch (e: IOException) {
            throw UdpDataSourceException(
                e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        }

        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    @Throws(UdpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }

        if (packetRemaining == 0) {
            // We've read all of the data from the current packet. Get another.
            try {
                socket!!.receive(packet)
            } catch (e: SocketTimeoutException) {
                throw UdpDataSourceException(
                    e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                )
            } catch (e: IOException) {
                throw UdpDataSourceException(
                    e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                )
            }
            packetRemaining = packet.getLength()
            bytesTransferred(packetRemaining)
        }

        val packetOffset = packet.getLength() - packetRemaining
        val bytesToRead = min(packetRemaining, length)
        System.arraycopy(packetBuffer, packetOffset, buffer, offset, bytesToRead)
        packetRemaining -= bytesToRead
        return bytesToRead
    }

    override fun close() {
        uri = null
        if (multicastSocket != null) {
            try {
                multicastSocket!!.leaveGroup(address!!)
            } catch (e: IOException) {
                // Do nothing.
            }
            multicastSocket = null
        }
        if (socket != null) {
            socket!!.close()
            socket = null
        }
        address = null
        packetRemaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    val localPort: Int
        get() {
            if (socket == null) {
                return UDP_PORT_UNSET
            }
            return socket!!.getLocalPort()
        }

    companion object {
        const val DEFAULT_MAX_PACKET_SIZE: Int = 2000
        val DEFAULT_SOCKET_TIMEOUT_MILLIS: Int = 8 * 1000
        val UDP_PORT_UNSET: Int = -1
    }
}
