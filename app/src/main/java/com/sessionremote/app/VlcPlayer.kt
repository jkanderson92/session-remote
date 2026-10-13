package com.sessionremote.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

object Vlc {
    @Volatile private var instance: LibVLC? = null

    fun get(context: Context): LibVLC = instance ?: synchronized(this) {
        instance ?: LibVLC(context.applicationContext, arrayListOf("--no-stats", "--no-sub-autodetect-file")).also { instance = it }
    }
}

enum class PlayState { OPENING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * Plays [uri] (udp://@:8554 live preview, or http:// camera files) with libVLC.
 * libVLC's native sockets follow the process network binding, so traffic stays on the camera Wi-Fi.
 */
@Composable
fun VlcPlayer(
    uri: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onState: (PlayState) -> Unit = {},
    onPlayer: (MediaPlayer?) -> Unit = {},
) {
    val context = LocalContext.current
    val libVlc = remember { Vlc.get(context) }
    val player = remember(uri) { MediaPlayer(libVlc) }

    AndroidView(
        factory = { c -> VLCVideoLayout(c).also { layout -> player.attachViews(layout, null, false, false) } },
        modifier = modifier,
    )

    DisposableEffect(uri) {
        player.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Opening, MediaPlayer.Event.Buffering -> if (!player.isPlaying) onState(PlayState.OPENING)
                MediaPlayer.Event.Playing -> onState(PlayState.PLAYING)
                MediaPlayer.Event.Paused -> onState(PlayState.PAUSED)
                MediaPlayer.Event.EndReached -> onState(PlayState.ENDED)
                MediaPlayer.Event.EncounteredError -> onState(PlayState.ERROR)
                else -> {}
            }
        }
        val media = Media(libVlc, Uri.parse(uri))
        options.forEach { media.addOption(it) }
        player.setMedia(media)
        media.release()
        player.play()
        onPlayer(player)
        onDispose {
            onPlayer(null)
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
        }
    }
}
