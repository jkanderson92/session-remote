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

object StreamProbe {
    const val PORT = 8554

    /**
     * Listens on the preview port for [millis] and returns bytes received.
     * 0 = the camera isn't sending video at all (vs. the player failing to decode it).
     * The socket is closed before returning so the player can bind the same port.
     */
    suspend fun bytesReceived(millis: Long = 3000): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val sock = DatagramSocket(null)
        try {
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(PORT))
            sock.soTimeout = 300
            val buf = ByteArray(65536)
            val end = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    total += p.length
                } catch (_: SocketTimeoutException) {
                }
            }
        } finally {
            sock.close()
        }
        total
    }
}
