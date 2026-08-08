package com.lanfps.server

import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.Checksum
import com.lanfps.shared.GameConstants
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
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
) {
    /**
     * P7-1: the receive thread already sent the PONG for this PING, so the
     * simulation thread must do the session bookkeeping only — answering a
     * second time would double-count the client's RTT sample.
     */
    @JvmField var pingAnswered: Boolean = false
}

/**
 * UDP transport for the game server.
 *
 * A dedicated receive thread drains the socket into a lock-free queue so the
 * fixed-timestep simulation loop never blocks on I/O. Sending happens directly
 * from the simulation thread (`send` on a DatagramSocket does not block for a
 * meaningful time on a LAN).
 *
 * P7-1 — the ping fast path: client PING datagrams are additionally answered
 * right here on the receive thread. Previously the reply waited for the next
 * simulation-tick wakeup, so the measured RTT quietly included up to a full
 * tick of server sleep (~16 ms; more on OSes with a coarse timer quantum) —
 * which is why a phone could show a triple-digit "ping" on a wired-quality
 * LAN where the wire itself is sub-millisecond. The packet is still queued
 * (flagged [InboundPacket.pingAnswered]) so the simulation thread keeps the
 * session alive; it just never sends a second PONG.
 */
class UdpServerSocket(
    private val bindAddress: String,
    val port: Int,
    /** Server clock (ms) embedded into PONG replies. Defaults to wall time. */
    private val serverClockMs: () -> Long = { System.currentTimeMillis() },
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

    /** P7-1: PING datagrams answered on the receive thread (observability). */
    val fastPingReplies = AtomicLong(0)
    /** P7-1: fast replies skipped by the per-endpoint flood throttle. */
    val fastPingThrottled = AtomicLong(0)

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
            // IPTOS_LOWDELAY on the snapshots/replies we send. Best effort,
            // ignored where unsupported, but several routers honour it.
            try {
                trafficClass = 0x10
            } catch (e: Exception) {
                Log.debug("could not set trafficClass: ${e.message}")
            }
            bind(addr)
        }
        running.set(true)

        receiveThread = Thread({ receiveLoop() }, "udp-receive").apply {
            isDaemon = true
            start()
        }
    }

    // ---- P7-1 ping fast path (receive thread only) -------------------------

    /** Scratch for the fast path; the receive loop is single-threaded. */
    private val pingReader = BinaryReader()
    private val pongWriter = BinaryWriter(Protocol.HEADER_SIZE + 16)

    /**
     * Per-endpoint throttle for fast replies: legitimate clients ping once a
     * second, so one answer per 50 ms loses nothing and stops a crafted PING
     * flood from being amplified back at wire speed.
     */
    private val fastReplyAt = HashMap<Long, Long>()
    private var throttlePruneAtNanos = 0L

    /**
     * Cheap structural twin of [Protocol.parse] limited to "is this a valid
     * PING": magic/version/type, an exact 8-byte payload and the CRC. The
     * queued copy still goes through the full parser on the simulation
     * thread, so a packet that fools this check has no second life.
     */
    private fun isValidPing(data: ByteArray, length: Int): Boolean {
        if (length < Protocol.HEADER_SIZE + 8) return false
        // "LANF" magic, big-endian.
        if (data[0] != 0x4C.toByte() || data[1] != 0x41.toByte() ||
            data[2] != 0x4E.toByte() || data[3] != 0x46.toByte()
        ) {
            return false
        }
        if (data[4].toInt() and 0xFF != GameConstants.PROTOCOL_VERSION) return false
        if (data[5].toInt() and 0xFF != PacketTypes.PING) return false
        val payloadLength = (data[10].toInt() and 0xFF shl 8) or (data[11].toInt() and 0xFF)
        if (payloadLength != 8 || Protocol.HEADER_SIZE + payloadLength > length) return false
        val crc = Checksum.crc32(data, Protocol.HEADER_SIZE, payloadLength)
        val declared = (data[12].toInt() and 0xFF shl 24) or
            (data[13].toInt() and 0xFF shl 16) or
            (data[14].toInt() and 0xFF shl 8) or
            (data[15].toInt() and 0xFF)
        return crc == declared
    }

    private fun fastPingAllowed(address: InetAddress, port: Int, nowNanos: Long): Boolean {
        // Inet4Address.hashCode() is the raw 32-bit address, which makes this
        // key unique per (IPv4, port). Collisions on exotic addresses would
        // only throttle two endpoints together, never corrupt a reply.
        val key = (address.hashCode().toLong() and 0xFFFF_FFFFL shl 32) or
            (port.toLong() and 0xFFFF)
        // NB: no `?: Long.MIN_VALUE` fallback here — subtracting MIN_VALUE
        // overflows and the delta comes out negative, throttling the endpoint
        // for the rest of uptime. Absence of an entry means "never replied".
        val last = fastReplyAt[key]
        if (last != null && nowNanos - last < FAST_PING_MIN_INTERVAL_NANOS) {
            fastPingThrottled.incrementAndGet()
            return false
        }
        fastReplyAt[key] = nowNanos
        if (nowNanos >= throttlePruneAtNanos) {
            throttlePruneAtNanos = nowNanos + 5_000_000_000L
            fastReplyAt.entries.removeIf { nowNanos - it.value > 2_000_000_000L }
        }
        return true
    }

    /**
     * If [packet] holds a valid PING, echo its timestamp back immediately on
     * this thread. @return true when the reply went out (the caller still
     * queues the datagram, flagged, for session bookkeeping).
     */
    private fun tryFastPingReply(packet: DatagramPacket): Boolean {
        val len = packet.length
        if (!isValidPing(packet.data, len)) return false
        val nowNanos = System.nanoTime()
        if (!fastPingAllowed(packet.address, packet.port, nowNanos)) return false

        pingReader.wrap(packet.data, Protocol.HEADER_SIZE, 8)
        // Guarded above by isValidPing: exactly one I64 — the client's clock.
        val clientTime = try {
            pingReader.readI64()
        } catch (e: Exception) {
            return false
        }
        Protocol.begin(pongWriter, PacketTypes.PONG)
        Packets.writePong(pongWriter, clientTime, serverClockMs())
        send(pongWriter.buffer, Protocol.end(pongWriter), packet.address, packet.port)
        fastPingReplies.incrementAndGet()
        return true
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
                val inboundPacket = InboundPacket(copy, packet.length, packet.address, packet.port)

                // P7-1: respond to PING before the next simulation tick. The
                // copy we enqueue remembers this happened (see the flag).
                if (tryFastPingReply(packet)) inboundPacket.pingAnswered = true

                inbound.add(inboundPacket)
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
        /** Minimum gap between two fast PING answers to the same endpoint. */
        private const val FAST_PING_MIN_INTERVAL_NANOS = 50_000_000L

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
