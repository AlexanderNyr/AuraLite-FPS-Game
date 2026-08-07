package com.lanfps.server

import com.lanfps.shared.GameConstants
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A datagram handed from the receive thread to the simulation thread. */
class InboundPacket(
    @JvmField val data: ByteArray,
    @JvmField val length: Int,
    @JvmField val address: InetAddress,
    @JvmField val port: Int,
)

/**
 * UDP transport for the game server.
 *
 * A dedicated receive thread drains the socket into a lock-free queue so the
 * fixed-timestep simulation loop never blocks on I/O. Sending happens directly
 * from the simulation thread (`send` on a DatagramSocket does not block for a
 * meaningful time on a LAN).
 */
class UdpServerSocket(
    private val bindAddress: String,
    val port: Int,
) {
    private lateinit var socket: DatagramSocket
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null

    private val inbound = ConcurrentLinkedQueue<InboundPacket>()

    val packetsReceived = AtomicLong(0)
    val packetsSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)
    val bytesSent = AtomicLong(0)
    val packetsDropped = AtomicLong(0)

    /** Bounds the queue so a flood cannot exhaust memory. */
    private val maxQueued = 4096

    fun bind() {
        val addr = if (bindAddress == "0.0.0.0" || bindAddress.isBlank()) {
            InetSocketAddress(port)
        } else {
            InetSocketAddress(InetAddress.getByName(bindAddress), port)
        }
        socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            receiveBufferSize = 512 * 1024
            sendBufferSize = 512 * 1024
            bind(addr)
        }
        running.set(true)

        receiveThread = Thread({ receiveLoop() }, "udp-receive").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(GameConstants.MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running.get()) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
                packetsReceived.incrementAndGet()
                bytesReceived.addAndGet(packet.length.toLong())

                if (inbound.size >= maxQueued) {
                    packetsDropped.incrementAndGet()
                    continue
                }
                // Copy: the shared buffer is reused for the next datagram.
                val copy = ByteArray(packet.length)
                System.arraycopy(packet.data, packet.offset, copy, 0, packet.length)
                inbound.add(InboundPacket(copy, packet.length, packet.address, packet.port))
            } catch (e: SocketException) {
                if (running.get()) Log.warn("socket error while receiving: $e")
                // Socket closed during shutdown -> exit quietly.
            } catch (e: Exception) {
                if (running.get()) Log.warn("receive failed: $e")
            }
        }
        Log.debug("udp receive thread stopped")
    }

    /** Non-blocking: returns the next queued datagram or null. */
    fun poll(): InboundPacket? = inbound.poll()

    fun send(data: ByteArray, length: Int, address: InetAddress, port: Int) {
        if (!running.get()) return
        try {
            socket.send(DatagramPacket(data, 0, length, address, port))
            packetsSent.incrementAndGet()
            bytesSent.addAndGet(length.toLong())
        } catch (e: Exception) {
            Log.debug("send to $address:$port failed: $e")
        }
    }

    fun close() {
        running.set(false)
        try {
            if (this::socket.isInitialized) socket.close()
        } catch (_: Exception) {
        }
        receiveThread?.interrupt()
    }

    companion object {
        /**
         * Best-effort list of this machine's LAN IPv4 addresses, printed at
         * startup so the operator knows what to type on the phones.
         */
        fun localIpv4Addresses(): List<String> {
            val result = ArrayList<String>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr.isLoopbackAddress) continue
                        val ip = addr.hostAddress ?: continue
                        // IPv4 only, and skip link-local 169.254.x.x
                        if (ip.contains(':')) continue
                        if (ip.startsWith("169.254.")) continue
                        result.add("$ip (${iface.displayName})")
                    }
                }
            } catch (e: Exception) {
                Log.debug("could not enumerate interfaces: $e")
            }
            return result
        }
    }
}
