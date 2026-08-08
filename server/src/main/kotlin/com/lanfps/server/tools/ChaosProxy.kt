package com.lanfps.server.tools

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Random
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * P3-1 of docs/IMPROVEMENT_PLAN.md: a UDP "chaos monkey" for the game protocol.
 *
 * Sits between a client and the server and forwards datagrams in both
 * directions while applying configurable:
 *  - **loss** — independent drop probability per packet;
 *  - **latency** — a fixed delivery delay per packet;
 *  - **jitter** — an extra uniform random delay on top of latency (which also
 *    naturally reorders packets);
 *  - **reorder** — probability of holding a packet back one extra "hop" of
 *    latency, overtaken by the next ones.
 *
 * Both directions are filtered independently, so a 10 % loss setting means the
 * client's uplink AND the server's downlink each lose ~10 %.
 *
 * Used programmatically by `ChaosNetworkTest` (JVM), and runnable by hand for
 * manual testing:
 *
 * ```
 * java -cp server.jar com.lanfps.server.tools.ChaosProxyKt \
 *   --port=7778 --targetHost=127.0.0.1 --target=7777 \
 *   --loss=0.10 --latency=60 --jitter=40 --reorder=0.05
 * # then point the phone (or TestClient) at the proxy instead of the server
 * ```
 *
 * Threading: one receive loop per direction pushes scheduled deliveries into a
 * shared [PriorityBlockingQueue]; one sender thread per direction releases them
 * in deadline order. All four are daemons; [close] stops everything.
 */
class ChaosProxy(
    val listenPort: Int,
    targetHost: String,
    val targetPort: Int,
    val loss: Double = 0.0,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val reorder: Double = 0.0,
    seed: Long = 0xC0FFEE,
) : AutoCloseable {

    private val targetAddress: InetAddress = InetAddress.getByName(targetHost)
    private val rng = Random(seed)
    private val running = AtomicBoolean(false)

    /** Socket the CLIENT talks to; forwards game traffic both ways as needed. */
    private lateinit var ingress: DatagramSocket

    /** Socket the SERVER sees as the client; receives the server's replies. */
    private lateinit var egress: DatagramSocket

    /** The most recent peer to send us client traffic — replies go back here. */
    @Volatile private var clientAddress: InetAddress? = null
    @Volatile private var clientPort: Int = -1

    val dropped = AtomicLong(0)
    val forwardedClientToServer = AtomicLong(0)
    val forwardedServerToClient = AtomicLong(0)
    val receivedClientToServer = AtomicLong(0)
    val receivedServerToClient = AtomicLong(0)

    private class Scheduled(
        val deliverAtNanos: Long,
        seq: Long,
        val data: ByteArray,
        val replyToServer: Boolean,
    ) : Comparable<Scheduled> {
        // Tie-break equal deadlines by arrival sequence so the queue is stable.
        private val order = seq
        override fun compareTo(other: Scheduled): Int {
            val c = deliverAtNanos.compareTo(other.deliverAtNanos)
            return if (c != 0) c else order.compareTo(other.order)
        }
    }

    private val queue = PriorityBlockingQueue<Scheduled>()
    private var seqCounter = 0L
    private var c2sHasHeldback = false
    private var s2cHasHeldback = false

    /** Starts the proxy. Returns immediately; call [close] to stop. */
    fun start(): ChaosProxy {
        ingress = DatagramSocket(listenPort)
        egress = DatagramSocket()
        running.set(true)
        Thread({ receiveLoop(ingress, replyToServer = true) }, "chaos-c2s-rx").apply {
            isDaemon = true; start()
        }
        Thread({ receiveLoop(egress, replyToServer = false) }, "chaos-s2c-rx").apply {
            isDaemon = true; start()
        }
        Thread({ sendLoop(replyToServer = true) }, "chaos-c2s-tx").apply {
            isDaemon = true; start()
        }
        Thread({ sendLoop(replyToServer = false) }, "chaos-s2c-tx").apply {
            isDaemon = true; start()
        }
        return this
    }

    private fun receiveLoop(sock: DatagramSocket, replyToServer: Boolean) {
        val buf = ByteArray(64 * 1024)
        val packet = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try {
                packet.setData(buf, 0, buf.size)
                sock.receive(packet)
            } catch (e: Exception) {
                if (running.get()) println("[chaos] receive ended: $e")
                break
            }

            if (replyToServer) {
                // From a client; remember who to send replies back to.
                if (packet.address != targetAddress || packet.port != targetPort) {
                    clientAddress = packet.address
                    clientPort = packet.port
                }
                receivedClientToServer.incrementAndGet()
            } else {
                if (packet.address != targetAddress || packet.port != targetPort) continue
                receivedServerToClient.incrementAndGet()
            }

            if (rng.nextDouble() < loss) {
                dropped.incrementAndGet()
                continue
            }

            var delayMs = latencyMs.toLong()
            if (jitterMs > 0) delayMs += rng.nextInt(jitterMs + 1).toLong()

            // Reorder: occasionally park this packet for one extra latency hop so
            // the following packets overtake it.
            if (reorder > 0.0 && rng.nextDouble() < reorder) {
                delayMs += latencyMs.coerceAtLeast(30)
                if (replyToServer) c2sHasHeldback = true else s2cHasHeldback = true
            }

            val copy = ByteArray(packet.length)
            System.arraycopy(packet.data, packet.offset, copy, 0, packet.length)
            queue.add(
                Scheduled(
                    System.nanoTime() + delayMs * 1_000_000L,
                    seqCounter++,
                    copy,
                    replyToServer,
                ),
            )
        }
    }

    private fun sendLoop(replyToServer: Boolean) {
        while (running.get()) {
            val head = queue.peek()
            if (head == null) {
                Thread.sleep(1)
                continue
            }
            val waitNanos = head.deliverAtNanos - System.nanoTime()
            if (waitNanos > 0) {
                Thread.sleep((waitNanos / 1_000_000L).coerceAtLeast(1))
                continue
            }
            val item = queue.poll() ?: continue
            if (item.replyToServer != replyToServer) {
                // Put it back for the other sender thread.
                queue.add(item)
                Thread.sleep(0, 200_000)
                continue
            }
            try {
                if (replyToServer) {
                    egress.send(DatagramPacket(item.data, item.data.size, targetAddress, targetPort))
                    forwardedClientToServer.incrementAndGet()
                } else {
                    val ca = clientAddress
                    val cp = clientPort
                    if (ca != null && cp > 0) {
                        ingress.send(DatagramPacket(item.data, item.data.size, ca, cp))
                        forwardedServerToClient.incrementAndGet()
                    }
                }
            } catch (e: Exception) {
                if (running.get()) println("[chaos] send failed: $e")
            }
        }
    }

    /**
     * True once at least one packet travelled in each direction — used by tests
     * to wait until the proxy path is actually established.
     */
    fun isEstablished(): Boolean =
        receivedClientToServer.get() > 0 && receivedServerToClient.get() > 0

    fun describe(): String =
        "chaos(loss=$loss latency=${latencyMs}ms jitter=${jitterMs}ms reorder=$reorder): " +
            "c2s ${receivedClientToServer.get()}->${forwardedClientToServer.get()} " +
            "s2c ${receivedServerToClient.get()}->${forwardedServerToClient.get()} " +
            "dropped=${dropped.get()}"

    override fun close() {
        running.set(false)
        try {
            ingress.close()
        } catch (_: Exception) {
        }
        try {
            egress.close()
        } catch (_: Exception) {
        }
    }
}

fun main(args: Array<String>) {
    fun arg(name: String, default: String): String =
        args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=') ?: default

    val proxy = ChaosProxy(
        listenPort = arg("port", "7778").toInt(),
        targetHost = arg("targetHost", "127.0.0.1"),
        targetPort = arg("target", "7777").toInt(),
        loss = arg("loss", "0.10").toDouble(),
        latencyMs = arg("latency", "60").toInt(),
        jitterMs = arg("jitter", "40").toInt(),
        reorder = arg("reorder", "0.05").toDouble(),
    ).start()

    println("[chaos] proxy :${proxy.listenPort} -> ${arg("targetHost", "127.0.0.1")}:${proxy.targetPort}")
    println("[chaos] loss=${proxy.loss} latency=${proxy.latencyMs}ms jitter=${proxy.jitterMs}ms reorder=${proxy.reorder}")
    println("[chaos] press Ctrl+C to stop")

    Runtime.getRuntime().addShutdownHook(Thread { proxy.close() })
    while (true) {
        Thread.sleep(10_000)
        println("[chaos] ${proxy.describe()}")
    }
}
