package com.sessionremote.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Network
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

enum class Conn { NO_WIFI, SEARCHING, CONNECTED, NOT_RESPONDING }

sealed class Preview {
    data object Off : Preview()
    data object Starting : Preview()
    data class Live(val kbReceived: Long) : Preview()
    data class Failed(val reason: String) : Preview()
}

data class UiState(
    val conn: Conn = Conn.NO_WIFI,
    val status: CameraStatus? = null,
    val detail: String? = null,
    val message: String? = null,
    val working: Boolean = false,
    val modelName: String? = null,
    val firmware: String? = null,
    val savedMac: String? = null,
    val locating: Boolean = false,
)

data class MediaUi(
    val loading: Boolean = false,
    val files: List<CamFile> = emptyList(),
    val error: String? = null,
    val progress: Map<String, Float> = emptyMap(),
    val done: Set<String> = emptySet(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val api = GoProApi()
    private val prefs = app.getSharedPreferences("session_remote", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(UiState(savedMac = prefs.getString("mac", null)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _media = MutableStateFlow(MediaUi())
    val media: StateFlow<MediaUi> = _media.asStateFlow()

    private val _preview = MutableStateFlow<Preview>(Preview.Off)
    val preview: StateFlow<Preview> = _preview.asStateFlow()

    @Volatile private var network: Network? = null
    private val binder = WifiBinder(app) { net ->
        network = net
        api.useNetwork(net)
        _ui.update { it.copy(conn = if (net == null) Conn.NO_WIFI else Conn.SEARCHING) }
    }
    private val keepAlive = KeepAlive()
    private var paired = false
    private var pollJob: Job? = null
    private val thumbs = HashMap<String, Bitmap?>()

    init { binder.start() }

    fun onForeground() {
        binder.start()
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                if (network == null) {
                    paired = false
                    keepAlive.stop()
                    _ui.update { it.copy(conn = Conn.NO_WIFI, status = null, detail = null) }
                    delay(1000)
                    continue
                }
                try {
                    val s = api.status()
                    failures = 0
                    _ui.update { it.copy(conn = Conn.CONNECTED, status = s, detail = null) }
                    if (!paired) {
                        paired = true
                        // Ends the camera's "searching" countdown; harmless if this firmware ignores it.
                        runCatching { api.pairComplete() }
                        keepAlive.start(viewModelScope)
                    }
                    if (_ui.value.modelName == null) loadInfo()
                    delay(1000)
                } catch (e: Exception) {
                    failures++
                    if (failures >= 2) {
                        paired = false
                        keepAlive.stop()
                        _ui.update { it.copy(conn = Conn.NOT_RESPONDING, status = null, detail = describe(e)) }
                    } else {
                        _ui.update { it.copy(conn = Conn.SEARCHING) }
                    }
                    delay(2000)
                }
            }
        }
    }

    fun onBackground() {
        pollJob?.cancel()
        pollJob = null
        stopPreview()
        keepAlive.stop()
        paired = false
    }

    override fun onCleared() {
        keepAlive.stop()
        binder.stop()
    }

    private suspend fun loadInfo() {
        runCatching { api.info() }.onSuccess { info ->
            val mac = info.optString("ap_mac").takeIf { it.isNotBlank() }
            if (mac != null) prefs.edit().putString("mac", mac).apply()
            _ui.update {
                it.copy(
                    modelName = info.optString("model_name").takeIf { s -> s.isNotBlank() } ?: "GoPro",
                    firmware = info.optString("firmware_version").takeIf { s -> s.isNotBlank() },
                    savedMac = mac ?: it.savedMac,
                )
            }
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "Camera didn't answer at 10.5.5.9. Make sure the phone is joined to the GoPro's Wi-Fi and the camera is awake (in app/remote mode)."
        is ConnectException, is NoRouteToHostException -> "Can't reach 10.5.5.9. You're on Wi-Fi, but probably not the GoPro's network."
        is CameraHttpException -> "Camera refused the request (HTTP ${e.code})."
        is JSONException -> "Camera answered with something that isn't valid status data."
        is IOException -> "Connection problem: ${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun say(msg: String) = _ui.update { it.copy(message = msg) }

    private fun act(label: String, block: suspend () -> Unit) {
        if (_ui.value.working) return
        viewModelScope.launch {
            _ui.update { it.copy(working = true) }
            try {
                block()
            } catch (e: CameraHttpException) {
                say("$label failed: camera rejected it (HTTP ${e.code}). It may not be valid in the current mode or with the current resolution/FPS.")
            } catch (e: Exception) {
                say("$label failed: ${describe(e)}")
            } finally {
                runCatching { api.status() }.onSuccess { s -> _ui.update { it.copy(status = s, conn = Conn.CONNECTED) } }
                _ui.update { it.copy(working = false) }
            }
        }
    }

    fun toggleRecord() = act("Recording") {
        val s = _ui.value.status
        if (s?.recording == true) {
            api.shutter(false)
        } else {
            if (s?.mode != 0) { api.setMode(0); delay(400) }
            api.shutter(true)
        }
    }

    fun takePhoto() = act("Photo") {
        if (_ui.value.status?.mode != 1) { api.setMode(1); delay(500) }
        api.shutter(true)
    }

    /** MultiShot: burst fires once; time-lapse runs until stopped. */
    fun multiShot() = act("MultiShot") {
        val s = _ui.value.status
        if (s?.busy == true) {
            api.shutter(false)
        } else {
            if (s?.mode != 2) { api.setMode(2); delay(500) }
            api.shutter(true)
        }
    }

    fun setMode(mode: Int) = act("Mode change") { api.setMode(mode) }

    fun setSubMode(mode: Int, sub: Int) = act("Sub-mode change") { api.setSubMode(mode, sub) }

    fun setSetting(id: Int, value: Int) = act("Setting change") { api.setSetting(id, value) }

    /** Changes resolution and moves FPS/FOV to the closest values that resolution supports. */
    fun setResolution(res: Int) = act("Resolution change") { changeResolution(res) }

    /** One-tap preset: video mode, resolution, then exact FPS and FOV. */
    fun applyPreset(res: Int, fps: Int, fov: Int) = act("Preset") {
        if (_ui.value.status?.mode != 0) { api.setMode(0); delay(400) }
        changeResolution(res)
        delay(250)
        val now = runCatching { api.status().settings }.getOrDefault(emptyMap())
        if (now[3] != fps) api.setSetting(3, fps)
        if (now[4] != fov) runCatching { api.setSetting(4, fov) }
    }

    fun setLocate(on: Boolean) = act("Locate") {
        api.raw("/gp/gpControl/command/system/locate?p=${if (on) 1 else 0}")
        _ui.update { it.copy(locating = on) }
    }

    private suspend fun changeResolution(res: Int) {
        val st = runCatching { api.status().settings }.getOrNull() ?: _ui.value.status?.settings ?: emptyMap()
        val fps = SessionModes.bestFps(res, st[3])
        val fov = SessionModes.bestFov(res, st[4])
        try {
            api.setSetting(2, res)
        } catch (e: CameraHttpException) {
            // Camera refused because the current FPS doesn't fit the new resolution: fix FPS, retry.
            if (fps == null || fps == st[3]) throw e
            api.setSetting(3, fps)
            delay(250)
            api.setSetting(2, res)
        }
        delay(250)
        val now = runCatching { api.status().settings }.getOrDefault(st)
        if (fps != null && now[3] != fps && !SessionModes.fpsAllowed(res, now[3] ?: -1)) api.setSetting(3, fps)
        if (fov != null && now[4] != fov && !SessionModes.fovAllowed(res, now[4] ?: -1)) api.setSetting(4, fov)
    }

    /** Red LED (setting 55). HERO4 firmware differs on the "on" value, so fall back from 2 to 1. */
    fun setLed(on: Boolean) = act("LED change") {
        if (!on) {
            api.setSetting(55, 0)
        } else {
            try { api.setSetting(55, 2) } catch (e: CameraHttpException) { api.setSetting(55, 1) }
        }
    }

    fun deleteFiles(files: List<CamFile>, onDone: () -> Unit = {}) = act("Delete") {
        var failed = 0
        for (f in files) {
            try {
                api.deleteFile(f)
                _media.update { m -> m.copy(files = m.files.filterNot { it.key == f.key }) }
            } catch (e: Exception) {
                failed++
            }
        }
        if (failed > 0) say("$failed of ${files.size} files couldn't be deleted. The camera may be busy; try again.")
        else say(if (files.size == 1) "Deleted ${files[0].name}." else "Deleted ${files.size} files.")
        onDone()
    }

    fun deleteAll() = act("Delete all") {
        api.deleteAll()
        _media.update { it.copy(files = emptyList()) }
        say("SD card cleared.")
        delay(800)
        loadMedia()
    }

    fun sleepCamera() = act("Turn off") {
        stopPreview()
        api.sleep()
        say("Camera turned off. Tap Wake camera to turn it back on.")
    }

    fun wake() {
        val mac = _ui.value.savedMac
        if (mac == null) {
            say("Can't wake yet: the camera's MAC address is learned the first time it connects while awake. Turn it on with its button this once.")
            return
        }
        viewModelScope.launch {
            val ok = WakeOnLan.send(mac, network)
            say(if (ok) "Wake signal sent. Give it a few seconds." else "Saved MAC address looks invalid.")
        }
    }

    // ---- Live preview ----

    private val _previewFull = MutableStateFlow(false)
    val previewFull: StateFlow<Boolean> = _previewFull.asStateFlow()
    fun setPreviewFull(on: Boolean) { _previewFull.value = on }

    private val _previewSoftware = MutableStateFlow(false)
    val previewSoftware: StateFlow<Boolean> = _previewSoftware.asStateFlow()
    fun setPreviewSoftware(on: Boolean) { _previewSoftware.value = on }

    private val _previewLog = MutableStateFlow<List<String>>(emptyList())
    val previewLog: StateFlow<List<String>> = _previewLog.asStateFlow()
    private var logStart = 0L

    fun plog(line: String) {
        val t = (System.currentTimeMillis() - logStart) / 100 / 10.0
        _previewLog.update { it + "[${t}s] $line" }
    }

    private suspend fun clientsLine(): String = runCatching {
        val s = api.status()
        "status: clients(31)=${s.clients} streaming(32)=${if (s.streaming) 1 else 0} mode=${s.mode} busy=${s.busy}"
    }.getOrElse { "status read failed: ${describe(it)}" }

    /**
     * Tries the known ways to start the HERO4 stream, one by one, until UDP video arrives.
     * Every step is logged so a failure can be pinned down from one test.
     */
    fun startPreview() {
        if (_preview.value is Preview.Starting) return
        viewModelScope.launch {
            _preview.value = Preview.Starting
            logStart = System.currentTimeMillis()
            _previewLog.value = emptyList()
            plog("model=${_ui.value.modelName} fw=${_ui.value.firmware} wifiBound=${network != null}")
            plog(clientsLine())
            keepAlive.start(viewModelScope)

            data class Attempt(val name: String, val cmd: String?, val keepAliveFrom8554: Boolean)
            val attempts = listOf(
                Attempt("A proto_v2 restart", "/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart", false),
                Attempt("B proto_v2 restart + keep-alive from :8554", "/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart", true),
                Attempt("C plain restart + keep-alive from :8554", "/gp/gpControl/execute?p1=gpStream&c1=restart", true),
                Attempt("D no command (Session fw2 style) + keep-alive from :8554", null, true),
            )
            for (a in attempts) {
                plog("--- ${a.name}")
                if (a.cmd != null) {
                    val r = runCatching { api.raw(a.cmd) }
                    plog(r.fold({ "cmd OK: ${it.take(80)}" }, { "cmd FAILED: ${describe(it)}" }))
                }
                val probe = runCatching { StreamProbe.probe(2500, a.keepAliveFrom8554) }
                    .getOrElse { plog("probe error: ${it.javaClass.simpleName}: ${it.message}"); null }
                if (probe != null) {
                    plog("probe: ${probe.packets} packets, ${probe.bytes} bytes, from=${probe.from ?: "-"}, first=${probe.firstHex ?: "-"}")
                    if (probe.packets > 0) {
                        val ts = probe.firstHex?.startsWith("47") == true
                        plog(if (ts) "stream: MPEG-TS -> player" else "stream: GoPro framing -> player")
                        plog(clientsLine())
                        _preview.value = Preview.Live(probe.bytes / 1024)
                        return@launch
                    }
                }
                plog(clientsLine())
            }
            plog("no UDP video arrived with any method")
            _preview.value = Preview.Failed("No video arrived from the camera with any start method. Tap Copy log and send it to me.")
        }
    }

    fun previewPlayerFailed() {
        if (_preview.value is Preview.Live) {
            plog("player reported an error")
            _preview.value = Preview.Failed("Video is arriving, but the player couldn't decode it. Try Software decode, or Copy log and send it to me.")
        }
    }

    fun stopPreview() {
        if (_preview.value == Preview.Off) return
        _previewFull.value = false
        _preview.value = Preview.Off
        viewModelScope.launch { api.stopStream() }
    }

    // ---- Media viewer ----

    fun fileUrl(f: CamFile) = api.fileUrl(f)

    /** Low-res proxy (.LRV) if the camera made one — plays smoothly over the camera's slow Wi-Fi. */
    suspend fun playableUrl(f: CamFile, fullQuality: Boolean): String {
        if (fullQuality) return api.fileUrl(f)
        val lrv = api.lowResUrl(f)
        return if (api.exists(lrv)) lrv else api.fileUrl(f)
    }

    suspend fun fullImage(f: CamFile): Bitmap? = runCatching { api.fullImage(f) }.getOrNull()

    // ---- Media ----

    fun loadMedia() {
        if (_media.value.loading) return
        viewModelScope.launch {
            _media.update { it.copy(loading = true, error = null) }
            try {
                val files = api.mediaList()
                _media.update { it.copy(loading = false, files = files) }
            } catch (e: Exception) {
                _media.update { it.copy(loading = false, error = "Couldn't load media: ${describe(e)}") }
            }
        }
    }

    suspend fun thumbnail(f: CamFile): Bitmap? {
        if (thumbs.containsKey(f.key)) return thumbs[f.key]
        val b = api.thumbnail(f)
        thumbs[f.key] = b
        return b
    }

    fun download(f: CamFile) {
        if (_media.value.progress.containsKey(f.key)) return
        viewModelScope.launch {
            _media.update { it.copy(progress = it.progress + (f.key to 0f)) }
            val resolver = getApplication<Application>().contentResolver
            var uri: Uri? = null
            try {
                withContext(Dispatchers.IO) {
                    val collection = if (f.isVideo)
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, if (f.isVideo) "video/mp4" else "image/jpeg")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            (if (f.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/SessionRemote"
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val target = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file in your gallery")
                    uri = target
                    val out = resolver.openOutputStream(target) ?: throw IOException("Couldn't open the gallery file for writing")
                    var lastPct = -1
                    out.use { stream ->
                        api.download(f, stream) { read, total ->
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _media.update { it.copy(progress = it.progress + (f.key to pct / 100f)) }
                                }
                            }
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(target, values, null, null)
                }
                _media.update { it.copy(progress = it.progress - f.key, done = it.done + f.key) }
                say("Saved ${f.name} to ${if (f.isVideo) "Movies" else "Pictures"}/SessionRemote.")
            } catch (e: Exception) {
                uri?.let { u -> withContext(Dispatchers.IO) { runCatching { resolver.delete(u, null, null) } } }
                _media.update { it.copy(progress = it.progress - f.key) }
                say("Download of ${f.name} failed: ${describe(e)}")
            }
        }
    }
}
