package com.sessionremote.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.videolan.libvlc.MediaPlayer

@Composable
fun MediaViewer(f: CamFile, vm: MainViewModel, onClose: () -> Unit) {
    val media by vm.media.collectAsState()
    var askDelete by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // swallow taps so they don't reach the list underneath
            .clickable(enabled = true, onClick = {})
    ) {
        if (f.isVideo) VideoView(f, vm) else PhotoView(f, vm)

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "✕", color = TextMain, fontSize = 26.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(horizontal = 14.dp, vertical = 6.dp)
            )
            Text(f.name, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val p = media.progress[f.key]
            when {
                p != null -> Text("Saving ${(p * 100).toInt()}%", color = TextMain)
                f.key in media.done -> Text("Saved", color = Good, fontWeight = FontWeight.Bold)
                else -> Button(onClick = { vm.download(f) }) { Text("Save") }
            }
            Text(
                "🗑", fontSize = 22.sp,
                modifier = Modifier.clip(CircleShape).clickable { askDelete = true }.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete ${f.name}?") },
            text = { Text("It will be removed from the camera's SD card. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { askDelete = false; vm.deleteFiles(listOf(f)) { onClose() } }) { Text("Delete", color = Rec) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VideoView(f: CamFile, vm: MainViewModel) {
    var fullQuality by remember(f.key) { mutableStateOf(false) }
    var url by remember(f.key, fullQuality) { mutableStateOf<String?>(null) }
    LaunchedEffect(f.key, fullQuality) { url = vm.playableUrl(f, fullQuality) }

    var state by remember { mutableStateOf(PlayState.OPENING) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pos by remember { mutableFloatStateOf(0f) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            player?.let { p ->
                if (!dragging) pos = p.position.coerceIn(0f, 1f)
                lengthMs = p.length
            }
            delay(400)
        }
    }

    Box(Modifier.fillMaxSize()) {
        val u = url
        if (u != null) {
            key(u) {
                VlcPlayer(
                    uri = u,
                    options = listOf(":network-caching=1500"),
                    modifier = Modifier.fillMaxSize(),
                    onState = { state = it },
                    onPlayer = { player = it },
                )
            }
        }
        val overlay = when {
            u == null || state == PlayState.OPENING -> "Loading…"
            state == PlayState.ERROR -> "Couldn't play this file over Wi-Fi. Save it and play it from your gallery."
            else -> null
        }
        overlay?.let {
            Text(it, color = TextDim, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Slider(
                value = pos,
                onValueChange = { dragging = true; pos = it },
                onValueChangeFinished = { player?.setPosition(pos); dragging = false },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${fmtDuration((pos * lengthMs / 1000).toInt())} / ${fmtDuration((lengthMs / 1000).toInt())}",
                    color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val p = player ?: return@Button
                    when (state) {
                        PlayState.PLAYING, PlayState.VIDEO -> p.pause()
                        PlayState.ENDED -> { p.stop(); p.play() }
                        else -> p.play()
                    }
                }) { Text(if (state == PlayState.PLAYING || state == PlayState.VIDEO) "Pause" else "Play") }
                Chip(
                    if (fullQuality) "Full quality" else "Low-res",
                    selected = fullQuality, enabled = true,
                ) { fullQuality = !fullQuality }
            }
            Text(
                if (fullQuality) "Full-res over camera Wi-Fi may stutter."
                else if (url?.endsWith(".LRV", true) == true) "Smooth low-res copy. Tap Low-res for full quality."
                else "No low-res copy on the card; playing the full file.",
                color = TextDim, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PhotoView(f: CamFile, vm: MainViewModel) {
    var bmp by remember(f.key) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(f.key) { mutableStateOf(false) }
    LaunchedEffect(f.key) {
        val b = vm.fullImage(f)
        bmp = b
        failed = b == null
    }
    var scale by remember(f.key) { mutableFloatStateOf(1f) }
    var offset by remember(f.key) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(f.key) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        when {
            b != null -> Image(
                b.asImageBitmap(),
                contentDescription = f.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
            )
            failed -> Text("Couldn't load this photo.", color = TextDim)
            else -> Text("Loading photo…", color = TextDim)
        }
    }
    Spacer(Modifier.width(0.dp))
}
