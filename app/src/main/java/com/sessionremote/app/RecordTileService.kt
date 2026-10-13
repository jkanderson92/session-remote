package com.sessionremote.app

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: start/stop recording from the notification shade without opening the app.
 * The phone must already be on the camera's Wi-Fi.
 */
class RecordTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private val api = GoProApi()
    @Volatile private var lastStatus: CameraStatus? = null
    @Volatile private var busy = false

    /** The camera's Wi-Fi has no internet, so it's never the "active" network — find it explicitly. */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        pollJob = sc.launch {
            while (isActive) {
                refresh()
                delay(1500)
            }
        }
    }

    override fun onStopListening() {
        pollJob?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private suspend fun refresh() {
        val net = wifiNetwork()
        if (net == null) {
            lastStatus = null
            render("No Wi-Fi", Tile.STATE_UNAVAILABLE)
            return
        }
        api.useNetwork(net)
        val s = runCatching { api.status() }.getOrNull()
        lastStatus = s
        when {
            s == null -> render("Camera not found", Tile.STATE_INACTIVE)
            s.recording -> render("● REC ${fmtDuration(s.recordSeconds ?: 0)}", Tile.STATE_ACTIVE)
            else -> render("Tap to record", Tile.STATE_INACTIVE)
        }
    }

    private fun render(subtitle: String, state: Int) {
        val t = qsTile ?: return
        t.label = "GoPro"
        t.subtitle = subtitle
        t.state = state
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        val sc = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        sc.launch {
            busy = true
            try {
                val net = wifiNetwork() ?: run { render("No Wi-Fi", Tile.STATE_UNAVAILABLE); return@launch }
                api.useNetwork(net)
                val s = runCatching { api.status() }.getOrNull()
                if (s == null) { render("Camera not found", Tile.STATE_INACTIVE); return@launch }
                render(if (s.recording) "Stopping…" else "Starting…", Tile.STATE_ACTIVE)
                runCatching {
                    if (s.recording) {
                        api.shutter(false)
                    } else {
                        if (s.mode != 0) { api.setMode(0); delay(400) }
                        api.shutter(true)
                    }
                }.onFailure { render("Command failed", Tile.STATE_INACTIVE) }
                delay(600)
                refresh()
            } finally {
                busy = false
            }
        }
    }
}
