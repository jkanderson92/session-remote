package com.sessionremote.app

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import java.text.DateFormat
import java.util.Date
import java.util.Locale

val Bg = Color(0xFF0E0F12)
val Surface1 = Color(0xFF16181D)
val Surface2 = Color(0xFF22252C)
val TextMain = Color(0xFFE9EAEE)
val TextDim = Color(0xFF9A9FAB)
val Accent = Color(0xFF4FA3FF)
val Rec = Color(0xFFFF3B30)
val Good = Color(0xFF34C759)
val Warn = Color(0xFFFFB020)

@Composable
fun App(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val media by vm.media.collectAsState()
    val preview by vm.preview.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<CamFile?>(null) }

    val view = LocalView.current
    DisposableEffect(ui.conn) {
        view.keepScreenOn = ui.conn == Conn.CONNECTED
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ConnectionHeader(ui, vm)
        ui.message?.let { MessageBar(it) { vm.dismissMessage() } }
        TabRow(selectedTabIndex = tab, containerColor = Bg, contentColor = Accent) {
            listOf("Control", "Settings", "Media").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 16.sp) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> ControlTab(ui, preview, vm)
                1 -> SettingsTab(ui, vm)
                else -> MediaTab(ui, media, vm) { viewing = it }
            }
        }
    }

    val previewFull by vm.previewFull.collectAsState()
    if (previewFull && preview is Preview.Live) {
        BackHandler { vm.setPreviewFull(false) }
        FullScreenPreview(ui, vm)
    }

    viewing?.let { f ->
        BackHandler { viewing = null }
        MediaViewer(f, vm) { viewing = null }
    }
}

// ---------- Header ----------

@Composable
fun ConnectionHeader(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val (color, title) = when (ui.conn) {
        Conn.CONNECTED -> Good to "Connected"
        Conn.SEARCHING -> Warn to "Connecting…"
        Conn.NOT_RESPONDING -> Rec to "Camera not responding"
        Conn.NO_WIFI -> Rec to "No Wi-Fi"
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (ui.conn == Conn.CONNECTED) {
                Text(
                    listOfNotNull(ui.modelName, ui.firmware).joinToString(" · "),
                    color = TextDim, fontSize = 12.sp
                )
            }
        }
        val help = when (ui.conn) {
            Conn.NO_WIFI -> "Turn on the camera's Wi-Fi, then join its network in Android Wi-Fi settings. If Android warns \"no internet\", choose to stay connected."
            Conn.NOT_RESPONDING -> ui.detail ?: "Camera isn't answering."
            else -> null
        }
        if (help != null) {
            Spacer(Modifier.height(6.dp))
            Text(help, color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Wi-Fi settings") }
                if (ui.conn == Conn.NOT_RESPONDING) {
                    Button(onClick = { vm.wake() }) { Text("Wake camera") }
                }
            }
        }
    }
}

@Composable
fun MessageBar(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .clickable(onClick = onDismiss)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("✕", color = TextDim, fontSize = 18.sp)
    }
}

// ---------- Control ----------

data class Preset(val name: String, val detail: String, val res: Int, val fps: Int, val fov: Int)

val PRESETS = listOf(
    Preset("Max detail", "1440p · 30", 7, 8, 0),
    Preset("Action", "1080p · 60", 9, 5, 0),
    Preset("Wide action", "1080p SV · 48", 8, 7, 0),
    Preset("Slow-mo", "WVGA · 120", 13, 1, 0),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlTab(ui: UiState, preview: Preview, vm: MainViewModel) {
    val s = ui.status
    val connected = ui.conn == Conn.CONNECTED && s != null
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BatteryCard(s, Modifier.weight(1f))
            StorageCard(s, Modifier.weight(1f))
        }

        PreviewCard(preview, connected, vm)

        // Mode selector
        Card {
            Label("Mode")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Video" to 0, "Photo" to 1, "Multi" to 2).forEach { (name, m) ->
                    Chip(name, s?.mode == m, connected && !ui.working, Modifier.weight(1f)) { vm.setMode(m) }
                }
            }
            val subs = when (s?.mode) {
                0 -> listOf("Video" to 0, "Looping" to 3)
                2 -> listOf("Burst" to 0, "Time-lapse" to 1)
                else -> emptyList()
            }
            if (subs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow {
                    subs.forEach { (name, v) ->
                        Chip(name, s?.subMode == v, connected && !ui.working) { vm.setSubMode(s!!.mode!!, v) }
                    }
                }
            }
        }

        // Presets
        Card {
            Label("Video presets")
            val st = s?.settings
            FlowRow {
                PRESETS.forEach { p ->
                    val active = s?.mode == 0 && st?.get(2) == p.res && st[3] == p.fps
                    Column(
                        Modifier
                            .padding(3.dp)
                            .alpha(if (connected && !ui.working || active) 1f else 0.4f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) Accent else Surface2)
                            .clickable(enabled = connected && !ui.working && !active && s?.recording != true) { vm.applyPreset(p.res, p.fps, p.fov) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(p.name, color = if (active) Color.Black else TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(p.detail, color = if (active) Color.Black else TextDim, fontSize = 12.sp)
                    }
                }
            }
        }

        // Big shutter
        ShutterArea(ui, vm, connected)

        // Power + locate
        Card {
            Label("Camera")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.sleepCamera() },
                    enabled = connected && !ui.working && s?.recording != true,
                    modifier = Modifier.weight(1f)
                ) { Text("Turn off") }
                Button(onClick = { vm.wake() }, modifier = Modifier.weight(1f)) { Text("Wake") }
            }
            Spacer(Modifier.height(8.dp))
            if (ui.locating) {
                Button(
                    onClick = { vm.setLocate(false) },
                    enabled = connected,
                    colors = ButtonDefaults.buttonColors(containerColor = Warn),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stop beeping", color = Color.Black) }
            } else {
                OutlinedButton(onClick = { vm.setLocate(true) }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Text("Beep to find camera")
                }
            }
            Text(
                if (ui.savedMac != null) "Wake works while the phone stays on the camera's Wi-Fi."
                else "Wake needs one normal connection first so the app can learn the camera's address.",
                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** The live UDP player. Only one may exist at a time (it binds port 8554). */
@Composable
fun LivePlayer(software: Boolean, vm: MainViewModel, modifier: Modifier = Modifier) {
    var state by remember(software) { mutableStateOf(PlayState.OPENING) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        key(software) {
            VlcPlayer(
                uri = "udp://@:${StreamProbe.PORT}",
                options = listOf(":network-caching=300", ":clock-jitter=0", ":clock-synchro=0", ":no-audio") +
                    (if (software) listOf(":codec=avcodec") else emptyList()),
                modifier = Modifier.fillMaxSize(),
                onState = { st ->
                    if (st != state) vm.plog("player: $st${if (software) " (software)" else ""}")
                    if (!(state == PlayState.VIDEO && st == PlayState.PLAYING)) state = st
                    if (st == PlayState.ERROR) vm.previewPlayerFailed()
                },
            )
        }
        if (state == PlayState.OPENING || state == PlayState.PLAYING) {
            Text(if (state == PlayState.OPENING) "Connecting to video…" else "Waiting for first frame…", color = TextDim)
        }
    }
}

@Composable
fun PreviewCard(preview: Preview, connected: Boolean, vm: MainViewModel) {
    val log by vm.previewLog.collectAsState()
    val full by vm.previewFull.collectAsState()
    val software by vm.previewSoftware.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showLog by rememberSaveable { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Live preview", Modifier.weight(1f))
            when (preview) {
                Preview.Off, is Preview.Failed ->
                    Button(onClick = { vm.startPreview() }, enabled = connected) { Text("Start") }
                Preview.Starting -> Text("Starting…", color = TextDim)
                is Preview.Live -> {
                    Button(onClick = { vm.setPreviewFull(true) }) { Text("Full screen") }
                    OutlinedButton(onClick = { vm.stopPreview() }) { Text("Stop") }
                }
            }
        }
        when (preview) {
            is Preview.Live -> {
                Spacer(Modifier.height(10.dp))
                if (full) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Bg),
                        contentAlignment = Alignment.Center
                    ) { Text("Showing full screen", color = TextDim) }
                } else {
                    LivePlayer(
                        software, vm,
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                            .clickable { vm.setPreviewFull(true) }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Tap video for full screen. ~1 s delay.", color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Chip("Software decode", software, true) { vm.setPreviewSoftware(!software) }
                }
            }
            is Preview.Failed -> Text(preview.reason, color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            else -> {}
        }
        if (log.isNotEmpty()) {
            val open = showLog || preview is Preview.Failed
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (open) "Hide log" else "Show log") }
                if (open) TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy log") }
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp)
                ) {
                    log.takeLast(14).forEach {
                        Text(it, color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** Full-screen aiming view: landscape, video edge to edge, record button on top. */
@Composable
fun FullScreenPreview(ui: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val software by vm.previewSoftware.collectAsState()
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    val s = ui.status
    val recording = s?.recording == true
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LivePlayer(software, vm, Modifier.fillMaxSize())
        Text(
            "✕", color = Color.White, fontSize = 26.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clip(CircleShape)
                .background(Color(0x88000000))
                .clickable { vm.setPreviewFull(false) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (recording) {
            Text(
                "● REC ${fmtDuration(s?.recordSeconds ?: 0)}",
                color = Rec, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(20.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0x88000000)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        // Record button
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(24.dp)
                .size(76.dp)
                .alpha(if (ui.conn == Conn.CONNECTED && !ui.working) 1f else 0.4f)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .clickable(enabled = ui.conn == Conn.CONNECTED && !ui.working) { vm.toggleRecord() }
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(if (recording) 28.dp else 56.dp)
                    .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                    .background(Rec)
            )
        }
    }
}

@Composable
fun ShutterArea(ui: UiState, vm: MainViewModel, connected: Boolean) {
    val s = ui.status
    val enabled = connected && !ui.working
    val mode = s?.mode ?: 0
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val recording = s?.recording == true
        val label: String
        val fill: Color
        val onClick: () -> Unit
        when (mode) {
            1 -> { label = "TAKE PHOTO"; fill = TextMain; onClick = { vm.takePhoto() } }
            2 -> {
                label = if (s?.busy == true) "STOP" else "SHOOT"
                fill = if (s?.busy == true) Rec else TextMain
                onClick = { vm.multiShot() }
            }
            else -> {
                label = if (recording) "STOP" else "RECORD"
                fill = Rec
                onClick = { vm.toggleRecord() }
            }
        }
        Box(
            Modifier
                .size(150.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(CircleShape)
                .border(6.dp, if (recording) Rec else TextMain, CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(if (recording) 56.dp else 118.dp)
                    .clip(if (recording) RoundedCornerShape(10.dp) else CircleShape)
                    .background(fill)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (recording) {
            Text(
                "● REC  ${fmtDuration(s?.recordSeconds ?: 0)}",
                color = Rec, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (mode == 1 || mode == 2) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.toggleRecord() }, enabled = enabled) { Text("Switch to video & record") }
        }
    }
}

@Composable
fun BatteryCard(s: CameraStatus?, modifier: Modifier) {
    Card(modifier) {
        Label("Battery")
        val lvl = s?.batteryLevel
        val (text, color) = when {
            s == null -> "—" to TextDim
            s.batteryPresent == false -> "No battery" to Rec
            lvl == 4 -> "Charging" to Accent
            lvl == 3 -> "Full" to Good
            lvl == 2 -> "Half" to Good
            lvl == 1 -> "Low" to Warn
            lvl == 0 -> "Empty" to Rec
            else -> "Not reported" to TextDim
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val bars = when (lvl) { 3, 4 -> 3; 2 -> 2; 1 -> 1; else -> 0 }
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(width = 22.dp, height = 12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i < bars) color else Surface2)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        s?.batteryPercent?.let { Text("$it% (camera-reported)", color = TextDim, fontSize = 12.sp) }
        if (s != null && s.batteryPercent == null && lvl != null) {
            Text("Camera reports bars, not %", color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
fun StorageCard(s: CameraStatus?, modifier: Modifier) {
    Card(modifier) {
        Label("SD card")
        when {
            s == null -> Text("—", color = TextDim, fontSize = 20.sp)
            s.sdMissing -> Text("No SD card", color = Rec, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            else -> {
                val video = s.remainingVideoSec
                Text(
                    if (video != null) fmtDuration(video) else "—",
                    color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                Text("video left (camera's own figure)", color = TextDim, fontSize = 12.sp)
                s.remainingPhotos?.let { Text("$it photos left", color = TextDim, fontSize = 12.sp) }
                s.remainingSpaceKb?.let { Text("${fmtFree(it)} free", color = TextDim, fontSize = 12.sp) }
            }
        }
    }
}

// ---------- Settings ----------

data class Opt(val label: String, val value: Int)
data class SettingDef(val id: Int, val title: String, val opts: List<Opt>)

private val VIDEO_SETTINGS = listOf(
    SettingDef(2, "Resolution", listOf(
        Opt("1440p", 7), Opt("1080p", 9), Opt("1080p SuperView", 8), Opt("960p", 10),
        Opt("720p", 12), Opt("720p SuperView", 11), Opt("WVGA", 13),
    )),
    SettingDef(3, "Frame rate", listOf(
        Opt("25", 9), Opt("30", 8), Opt("48", 7), Opt("50", 6), Opt("60", 5),
        Opt("100", 2), Opt("120", 1),
    )),
    SettingDef(4, "Field of view", listOf(Opt("Ultra Wide", 0), Opt("Medium", 1))),
)

private val AUTO_OFF = SettingDef(59, "Auto off", listOf(
    Opt("Never", 0), Opt("1 min", 1), Opt("2 min", 2), Opt("3 min", 3), Opt("5 min", 4),
))

// Session-specific Protune values (firmware 2.00+, video mode only) per goprowifihack HERO4-Session doc.
private val PROTUNE_SETTINGS = listOf(
    SettingDef(10, "Protune", listOf(Opt("Off", 0), Opt("On", 1))),
    SettingDef(11, "White balance", listOf(
        Opt("Auto", 0), Opt("3000K", 1), Opt("5500K", 2), Opt("6500K", 3), Opt("Native", 4),
    )),
    SettingDef(12, "Color", listOf(Opt("GoPro", 0), Opt("Flat", 1))),
    SettingDef(13, "ISO limit", listOf(Opt("400", 2), Opt("1600", 1))),
    SettingDef(14, "Sharpness", listOf(Opt("On", 3), Opt("Off", 4))),
    SettingDef(15, "Exposure (EV)", listOf(
        Opt("-2", 8), Opt("-1.5", 7), Opt("-1", 6), Opt("-0.5", 5), Opt("0", 4),
        Opt("+0.5", 3), Opt("+1", 2), Opt("+1.5", 1), Opt("+2", 0),
    )),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(ui: UiState, vm: MainViewModel) {
    val s = ui.status
    val connected = ui.conn == Conn.CONNECTED && s != null
    val enabled = connected && !ui.working && s?.recording != true
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!connected) Text("Connect to the camera to change settings.", color = TextDim)
        if (s?.recording == true) Text("Stop recording to change settings.", color = Warn)
        Text(
            "Options that don't work with your current resolution are crossed out.",
            color = TextDim, fontSize = 13.sp
        )
        SectionTitle("Video")
        val res = s?.settings?.get(2)
        SettingRow(
            VIDEO_SETTINGS[0], s, enabled, vm,
            note = "FPS and FOV adjust automatically if the new resolution needs it.",
            onPick = { vm.setResolution(it) },
        )
        SettingRow(
            VIDEO_SETTINGS[1], s, enabled, vm,
            note = "Crossed out = not available at this resolution.",
            allowed = { SessionModes.fpsAllowed(res, it) },
        )
        SettingRow(
            VIDEO_SETTINGS[2], s, enabled, vm,
            note = if (res == 8 || res == 11) "SuperView resolutions use their own fixed field of view." else null,
            allowed = { SessionModes.fovAllowed(res, it) },
        )

        SectionTitle("Camera")
        SettingRow(AUTO_OFF, s, connected && !ui.working, vm)
        Card {
            Label("Red recording LED")
            val ledOn = s?.settings?.get(55)?.let { it != 0 }
            Row {
                Chip("Off", ledOn == false, connected && !ui.working, Modifier.weight(1f)) { vm.setLed(false) }
                Chip("On", ledOn == true, connected && !ui.working, Modifier.weight(1f)) { vm.setLed(true) }
            }
            if (connected && ledOn == null) Text("Camera doesn't report LED state; buttons still send the command.", color = TextDim, fontSize = 12.sp)
        }
        Text("Auto off controls the camera's sleep timer. If an option is rejected, your firmware doesn't support it.", color = TextDim, fontSize = 13.sp)

        SectionTitle("Protune")
        val inVideo = s?.mode == 0
        if (connected && !inVideo) {
            Text("Protune only works in Video mode.", color = Warn, fontSize = 13.sp)
            Button(onClick = { vm.setMode(0) }, enabled = enabled) { Text("Switch to Video mode") }
        }
        val protuneOn = s?.settings?.get(10) == 1
        PROTUNE_SETTINGS.forEach { def ->
            val rowEnabled = enabled && inVideo && (def.id == 10 || protuneOn)
            SettingRow(def, s, rowEnabled, vm)
        }
        if (connected && inVideo && !protuneOn) {
            Text("Turn Protune on to adjust the rest.", color = TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingRow(
    def: SettingDef,
    s: CameraStatus?,
    enabled: Boolean,
    vm: MainViewModel,
    note: String? = null,
    allowed: (Int) -> Boolean = { true },
    onPick: (Int) -> Unit = { vm.setSetting(def.id, it) },
) {
    val current = s?.settings?.get(def.id)
    Card {
        Label(def.title)
        FlowRow {
            def.opts.forEach { o ->
                Chip(o.label, current == o.value, enabled, crossed = !allowed(o.value)) { onPick(o.value) }
            }
        }
        note?.let { Text(it, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

// ---------- Media ----------

@Composable
fun MediaTab(ui: UiState, media: MediaUi, vm: MainViewModel, onOpen: (CamFile) -> Unit) {
    val connected = ui.conn == Conn.CONNECTED
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<String?>(null) } // "selected" | "all"
    val totalBytes = media.files.sumOf { it.sizeBytes ?: 0L }
    LaunchedEffect(connected) {
        if (connected && media.files.isEmpty() && media.error == null) vm.loadMedia()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        selecting -> "${selected.size} selected"
                        media.loading -> "Loading…"
                        else -> "${media.files.size} files"
                    },
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
                if (!selecting && totalBytes > 0) Text(fmtBytes(totalBytes), color = TextDim, fontSize = 12.sp)
            }
            if (selecting) {
                OutlinedButton(onClick = { selecting = false; selected = emptySet() }) { Text("Cancel") }
                Button(
                    onClick = { confirm = "selected" },
                    enabled = connected && selected.isNotEmpty() && !ui.working,
                    colors = ButtonDefaults.buttonColors(containerColor = Rec)
                ) { Text("Delete") }
            } else {
                OutlinedButton(onClick = { selecting = true }, enabled = connected && media.files.isNotEmpty()) { Text("Select") }
                Button(onClick = { vm.loadMedia() }, enabled = connected && !media.loading) { Text("Refresh") }
            }
        }
        if (selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedButton(onClick = { selected = media.files.map { it.key }.toSet() }) { Text("Select all") }
                OutlinedButton(
                    onClick = { confirm = "all" },
                    enabled = connected && !ui.working,
                ) { Text("Wipe SD card", color = Rec) }
            }
        }
        if (ui.status?.recording == true) {
            Text("Downloading while recording may fail.", color = Warn, fontSize = 13.sp)
        }
        media.error?.let { Text(it, color = Rec, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (!connected && media.files.isEmpty()) {
            Text("Connect to the camera to browse media.", color = TextDim)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(media.files, key = { it.key }) { f ->
                MediaRow(
                    f, media.progress[f.key], f.key in media.done, connected, vm,
                    selecting = selecting, isSelected = f.key in selected,
                ) {
                    if (selecting) selected = if (f.key in selected) selected - f.key else selected + f.key
                    else onOpen(f)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    confirm?.let { what ->
        val count = if (what == "all") media.files.size else selected.size
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "all") "Wipe the SD card?" else "Delete $count file${if (count == 1) "" else "s"}?") },
            text = { Text(if (what == "all") "Every photo and video on the camera will be deleted. This can't be undone." else "They'll be removed from the camera's SD card. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (what == "all") vm.deleteAll()
                    else vm.deleteFiles(media.files.filter { it.key in selected })
                    selecting = false
                    selected = emptySet()
                }) { Text("Delete", color = Rec) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun MediaRow(
    f: CamFile, progress: Float?, done: Boolean, connected: Boolean, vm: MainViewModel,
    selecting: Boolean = false, isSelected: Boolean = false, onOpen: () -> Unit,
) {
    var thumb by remember(f.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(f.key, connected) { if (connected && thumb == null) thumb = vm.thumbnail(f) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Color(0xFF2A1A1C) else Surface1)
            .border(2.dp, if (isSelected) Rec else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(enabled = connected, onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(t.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(if (f.isVideo) "VIDEO" else "PHOTO", color = TextDim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val meta = listOfNotNull(
                f.sizeBytes?.let { fmtBytes(it) },
                f.modifiedEpoch?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000)) },
            ).joinToString(" · ")
            Text(meta, color = TextDim, fontSize = 12.sp)
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            progress != null -> Text("${(progress * 100).toInt()}%", color = TextMain)
            done -> Text("Saved", color = Good, fontWeight = FontWeight.Bold)
            selecting -> Text(if (isSelected) "✓" else "○", color = if (isSelected) Rec else TextDim, fontSize = 24.sp)
            else -> Button(
                onClick = { vm.download(f) },
                enabled = connected,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Save") }
        }
    }
}

// ---------- Building blocks ----------

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(14.dp)
    ) { content() }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.US), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Chip(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, crossed: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .padding(3.dp)
            .alpha(if (crossed && !selected) 0.35f else if (enabled || selected) 1f else 0.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent else Surface2)
            .clickable(enabled = enabled && !selected && !crossed, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Color.Black else TextMain,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (crossed) TextDecoration.LineThrough else null,
            textAlign = TextAlign.Center
        )
    }
}

fun fmtDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", b / 1024)
}

/** Status 54 is documented as KB on later cameras, bytes in the HERO4 notes. Values that can only be bytes are treated as bytes. */
fun fmtFree(v: Long): String = if (v > 4_000_000_000L) fmtBytes(v) else fmtBytes(v * 1024)
