package com.sessionremote.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Experimental HERO4 live preview.
 * 1) GET gpStream restart  2) camera sends MPEG-TS over UDP to our port 8554
 * 3) we must send a keep-alive string to camera:8554 every ~2.5 s or it stops.
 */
@OptIn(UnstableApi::class)
class LivePreview(private val context: Context, private val api: GoProApi) {
    var player: ExoPlayer? = null
        private set
    private var keepAlive: Job? = null

    suspend fun start(scope: CoroutineScope, onError: (String) -> Unit): ExoPlayer {
        stop(scope)
        api.startStream()

        keepAlive = scope.launch(Dispatchers.IO) {
            val msg = "_GPHD_:0:0:2:0.000000\n".toByteArray()
            val addr = InetAddress.getByName(GoProApi.HOST)
            DatagramSocket().use { s ->
                while (isActive) {
                    runCatching { s.send(DatagramPacket(msg, msg.size, addr, 8554)) }
                    delay(2500)
                }
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 250)
            .build()
        val p = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        val dsFactory = DataSource.Factory { UdpDataSource(UdpDataSource.DEFAULT_MAX_PACKET_SIZE, 10_000) }
        val source = ProgressiveMediaSource.Factory(dsFactory, DefaultExtractorsFactory())
            .createMediaSource(MediaItem.fromUri("udp://0.0.0.0:8554"))
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError("Preview stopped: ${error.errorCodeName}. The HERO4 Session stream is finicky — try Start preview again.")
            }
        })
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        player = p
        return p
    }

    fun stop(scope: CoroutineScope) {
        keepAlive?.cancel()
        keepAlive = null
        val hadPlayer = player != null
        player?.release()
        player = null
        if (hadPlayer) scope.launch { api.stopStream() }
    }
}
