package com.sessionremote.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * The HERO4 keep-alive the official app sends to camera:8554 every ~2.5 s.
 * Keeps the live stream flowing and tells the camera a remote is still there.
 */
class KeepAlive {
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            val msg = "_GPHD_:0:0:2:0.000000\n".toByteArray()
            val addr = InetAddress.getByName(GoProApi.HOST)
            DatagramSocket().use { s ->
                while (isActive) {
                    runCatching { s.send(DatagramPacket(msg, msg.size, addr, 8554)) }
                    delay(2500)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

data class ProbeResult(val packets: Int, val bytes: Long, val from: String?, val firstHex: String?)

object StreamProbe {
    const val PORT = 8554
    private val KEEP_ALIVE = "_GPHD_:0:0:2:0.000000\n".toByteArray()

    /**
     * Listens on the preview port for [millis]. Optionally sends the keep-alive from this same
     * socket (source port 8554), which some firmware wants. Closed before returning so the player can bind.
     */
    suspend fun probe(millis: Long, keepAliveFromSocket: Boolean): ProbeResult = withContext(Dispatchers.IO) {
        var packets = 0
        var total = 0L
        var from: String? = null
        var first: String? = null
        val sock = DatagramSocket(null)
        try {
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(PORT))
            sock.soTimeout = 250
            val cam = InetAddress.getByName(GoProApi.HOST)
            val buf = ByteArray(65536)
            val end = System.currentTimeMillis() + millis
            var nextKa = 0L
            while (System.currentTimeMillis() < end) {
                if (keepAliveFromSocket && System.currentTimeMillis() >= nextKa) {
                    runCatching { sock.send(DatagramPacket(KEEP_ALIVE, KEEP_ALIVE.size, cam, PORT)) }
                    nextKa = System.currentTimeMillis() + 1000
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    packets++
                    total += p.length
                    if (first == null) {
                        from = "${p.address?.hostAddress}:${p.port}"
                        first = (0 until minOf(8, p.length)).joinToString("") { String.format("%02x", buf[it]) }
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } finally {
            sock.close()
        }
        ProbeResult(packets, total, from, first)
    }
}
