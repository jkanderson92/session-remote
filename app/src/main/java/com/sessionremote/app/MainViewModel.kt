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
import androidx.media3.exoplayer.ExoPlayer
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

data class UiState(
    val conn: Conn = Conn.NO_WIFI,
    val status: CameraStatus? = null,
    val detail: String? = null,
    val message: String? = null,
    val working: Boolean = false,
    val modelName: String? = null,
    val firmware: String? = null,
    val savedMac: String? = null,
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

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    @Volatile private var network: Network? = null
    private val binder = WifiBinder(app) { net ->
        network = net
        api.useNetwork(net)
        _ui.update { it.copy(conn = if (net == null) Conn.NO_WIFI else Conn.SEARCHING) }
    }
    private val preview = LivePreview(app, api)
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
                    _ui.update { it.copy(conn = Conn.NO_WIFI, status = null, detail = null) }
                    delay(1000)
                    continue
                }
                try {
                    val s = api.status()
                    failures = 0
                    _ui.update { it.copy(conn = Conn.CONNECTED, status = s, detail = null) }
                    if (_ui.value.modelName == null) loadInfo()
                    delay(1000)
                } catch (e: Exception) {
                    failures++
                    if (failures >= 2) {
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
    }

    override fun onCleared() {
        preview.stop(viewModelScope)
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

    fun startPreview() = act("Live preview") {
        val p = preview.start(viewModelScope) { err -> say(err); stopPreview() }
        _player.value = p
    }

    fun stopPreview() {
        if (_player.value == null) return
        _player.value = null
        preview.stop(viewModelScope)
    }

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
