package com.sessionremote.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Finds the Wi-Fi network the phone is on and pins this app's traffic to it.
 *
 * Why: the GoPro's Wi-Fi has no internet. Android (and Samsung's "switch to mobile data")
 * routes traffic over cellular when Wi-Fi has no internet, so requests to 10.5.5.9 vanish.
 * Requesting Wi-Fi *without* the INTERNET capability keeps that network up and lets us bind to it.
 * No location permission or SSID scanning needed — the user joins the camera's Wi-Fi in Settings.
 */
class WifiBinder(context: Context, private val onChange: (Network?) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile var current: Network? = null
        private set

    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current = network
                cm.bindProcessToNetwork(network)
                onChange(network)
            }

            override fun onLost(network: Network) {
                if (current == network) {
                    current = null
                    cm.bindProcessToNetwork(null)
                    onChange(null)
                }
            }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            callback = null
            onChange(null)
        }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        current = null
        cm.bindProcessToNetwork(null)
    }
}

/** HERO4 Session needs a Wake-on-LAN magic packet (UDP port 9) to wake from Wi-Fi standby. */
object WakeOnLan {
    /** Returns false if the MAC is unusable. */
    suspend fun send(mac: String, network: Network?): Boolean = withContext(Dispatchers.IO) {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12) return@withContext false
        val macBytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (r in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + r * 6, 6)
        DatagramSocket().use { s ->
            s.broadcast = true
            network?.let { runCatching { it.bindSocket(s) } }
            for (target in listOf(GoProApi.HOST, "10.5.5.255")) {
                runCatching {
                    val addr = InetAddress.getByName(target)
                    repeat(3) { s.send(DatagramPacket(packet, packet.size, addr, 9)) }
                }
            }
        }
        true
    }
}
