package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlin.math.min

class UdpDataSource(
    maxPacketSize: Int = DEFAULT_MAX_PACKET_SIZE,
    private val socketTimeoutMillis: Int = DEFAULT_SOCKET_TIMEOUT_MILLIS
) : BaseDataSource(true) {
    private val packetBuffer = ByteArray(maxPacketSize)
    private val packet = DatagramPacket(packetBuffer, 0, maxPacketSize)
    override var uri: Uri? = null
    private var socket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var address: InetAddress? = null
    private var opened = false
    private var packetRemaining = 0

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        this.uri = uri
        val host = uri.host!!
        val port = uri.port
        transferInitializing(dataSpec)
        try {
            val address = InetAddress.getByName(host)
            this.address = address
            val socketAddress = InetSocketAddress(address, port)
            val socket = if (address.isMulticastAddress) {
                MulticastSocket(socketAddress).also {
                    it.joinGroup(address)
                }
            } else {
                DatagramSocket(socketAddress)
            }
            this.socket = socket
            socket.setSoTimeout(socketTimeoutMillis)
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
            packetRemaining = packet.length
            bytesTransferred(packetRemaining)
        }
        val packetOffset = packet.length - packetRemaining
        val bytesToRead = min(packetRemaining, length)
        System.arraycopy(packetBuffer, packetOffset, buffer, offset, bytesToRead)
        packetRemaining -= bytesToRead
        return bytesToRead
    }

    override fun close() {
        uri = null
        multicastSocket?.also {
            try {
                it.leaveGroup(address!!)
            } catch (_: IOException) {
            }
            multicastSocket = null
        }
        socket?.also {
            it.close()
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
        get() = socket?.getLocalPort() ?: UDP_PORT_UNSET

    class UdpDataSourceException(cause: Throwable?, errorCode: Int) :
        DataSourceException(cause, errorCode)

    companion object {
        const val DEFAULT_MAX_PACKET_SIZE: Int = 2000
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS: Int = 8 * 1000
        const val UDP_PORT_UNSET: Int = -1
    }
}
