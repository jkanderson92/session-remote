package com.sessionremote.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Legacy HERO4 (gpControl) Wi-Fi API. Reference: github.com/KonradIT/goprowifihack (HERO4 docs).
 * The HERO4 Session only answers GET requests, only in "app" mode.
 */
class CameraHttpException(val code: Int, val path: String) : IOException("HTTP $code on $path")

data class CamFile(val dir: String, val name: String, val sizeBytes: Long?, val modifiedEpoch: Long?) {
    val key get() = "$dir/$name"
    val isVideo get() = name.endsWith(".MP4", ignoreCase = true)
}

class GoProApi {
    companion object {
        const val HOST = "10.5.5.9"
        private const val BASE = "http://$HOST"
        private const val MEDIA_BASE = "http://$HOST:8080/videos/DCIM"
    }

    @Volatile private var client: OkHttpClient = builder().build()
    @Volatile private var bulkClient: OkHttpClient = bulk(client)

    private fun builder() = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)

    private fun bulk(base: OkHttpClient) = base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Pin all camera sockets to the camera's Wi-Fi network (it has no internet, Android would otherwise avoid it). */
    fun useNetwork(network: Network?) {
        val b = builder()
        if (network != null) b.socketFactory(network.socketFactory)
        client = b.build()
        bulkClient = bulk(client)
    }

    private suspend fun get(path: String, c: OkHttpClient = client): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE + path).build()
        c.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw CameraHttpException(r.code, path)
            r.body?.string() ?: ""
        }
    }

    /** Any GET path, returns the body. Used for diagnostics. */
    suspend fun raw(path: String): String = get(path)

    suspend fun status(): CameraStatus = CameraStatus.parse(JSONObject(get("/gp/gpControl/status")))

    suspend fun info(): JSONObject = JSONObject(get("/gp/gpControl/info")).optJSONObject("info") ?: JSONObject()

    suspend fun shutter(on: Boolean) { get("/gp/gpControl/command/shutter?p=${if (on) 1 else 0}") }

    /** 0 = Video, 1 = Photo, 2 = MultiShot */
    suspend fun setMode(mode: Int) { get("/gp/gpControl/command/mode?p=$mode") }

    suspend fun setSubMode(mode: Int, sub: Int) { get("/gp/gpControl/command/sub_mode?mode=$mode&sub_mode=$sub") }

    suspend fun setSetting(id: Int, value: Int) { get("/gp/gpControl/setting/$id/$value") }

    /** Puts the camera to sleep. Its Wi-Fi stays in low-power standby so Wake-on-LAN can bring it back. */
    /** Deletes one file; the camera removes its .LRV/.THM companions itself. */
    suspend fun deleteFile(f: CamFile) { get("/gp/gpControl/command/storage/delete?p=${f.dir}/${f.name}") }

    suspend fun deleteAll() { get("/gp/gpControl/command/storage/delete/all") }

    suspend fun sleep() { get("/gp/gpControl/command/system/sleep") }

    /** Tells the camera a remote app finished connecting, so it stops its "searching"/pairing countdown. */
    suspend fun pairComplete() {
        get("/gp/gpControl/command/wireless/pair/complete?success=1&deviceName=SessionRemote")
    }

    fun fileUrl(f: CamFile) = "$MEDIA_BASE/${f.dir}/${f.name}"

    fun lowResUrl(f: CamFile) = "$MEDIA_BASE/${f.dir}/${f.name.substringBeforeLast('.')}.LRV"

    suspend fun exists(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Full photo, downsampled to roughly [maxDim] px on the long side. */
    suspend fun fullImage(f: CamFile, maxDim: Int = 2400): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = bulkClient.newCall(Request.Builder().url(fileUrl(f)).build()).execute().use { r ->
            if (!r.isSuccessful) throw CameraHttpException(r.code, f.name)
            r.body?.bytes() ?: throw IOException("Empty response")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    suspend fun startStream() {
        // Firmware differs on which form it accepts; try the documented v2 form, then the older one.
        try {
            get("/gp/gpControl/execute?p1=gpStream&a1=proto_v2&c1=restart")
        } catch (e: CameraHttpException) {
            get("/gp/gpControl/execute?p1=gpStream&c1=restart")
        }
    }

    suspend fun stopStream() {
        runCatching { get("/gp/gpControl/execute?p1=gpStream&c1=stop") }
    }

    suspend fun mediaList(): List<CamFile> {
        val json = JSONObject(get("/gp/gpMediaList", bulkClient))
        val out = ArrayList<CamFile>()
        val media = json.optJSONArray("media") ?: return out
        for (i in 0 until media.length()) {
            val d = media.optJSONObject(i) ?: continue
            val dir = d.optString("d")
            val fs = d.optJSONArray("fs") ?: continue
            for (j in 0 until fs.length()) {
                val f = fs.optJSONObject(j) ?: continue
                val n = f.optString("n")
                if (n.endsWith(".MP4", true) || n.endsWith(".JPG", true)) {
                    out += CamFile(dir, n, f.optString("s").toLongOrNull(), f.optString("mod").toLongOrNull())
                }
            }
        }
        return out.sortedWith(compareByDescending<CamFile> { it.modifiedEpoch ?: 0L }.thenByDescending { it.name })
    }

    suspend fun thumbnail(f: CamFile): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$BASE/gp/gpMediaMetadata?p=${f.dir}/${f.name}").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
    }

    /** Streams the file to [out]. Blocking; call on IO. */
    fun download(f: CamFile, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        val req = Request.Builder().url("$MEDIA_BASE/${f.dir}/${f.name}").build()
        bulkClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw CameraHttpException(r.code, f.name)
            val body = r.body ?: throw IOException("Empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: (f.sizeBytes ?: -1L)
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
    }
}

/** Parsed /gp/gpControl/status. Every field is null when the camera didn't report it — nothing is invented. */
data class CameraStatus(
    val batteryPresent: Boolean?,
    /** Status 2: 0 empty, 1 low, 2 half, 3 full, 4 charging. HERO4 reports bars, not a percentage. */
    val batteryLevel: Int?,
    /** Status 70 — only present on some firmware. Shown only if the camera actually sends it. */
    val batteryPercent: Int?,
    val busy: Boolean,
    val mode: Int?,
    val subMode: Int?,
    val recordSeconds: Int?,
    val sdStatus: Int?,
    val remainingPhotos: Int?,
    val remainingVideoSec: Int?,
    val remainingSpaceKb: Long?,
    val clients: Int?,
    val streaming: Boolean,
    val settings: Map<Int, Int>,
) {
    val recording get() = busy && mode == 0
    val sdMissing get() = sdStatus == 2

    companion object {
        fun parse(root: JSONObject): CameraStatus {
            val s = root.optJSONObject("status") ?: JSONObject()
            fun i(id: Int): Int? = if (s.has(id.toString())) s.optInt(id.toString(), Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
            fun l(id: Int): Long? = if (s.has(id.toString())) s.optLong(id.toString(), Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } else null

            val settings = HashMap<Int, Int>()
            root.optJSONObject("settings")?.let { st ->
                val keys = st.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toIntOrNull() ?: continue
                    val v = st.optInt(k, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) settings[id] = v
                }
            }
            return CameraStatus(
                batteryPresent = i(1)?.let { it == 1 },
                batteryLevel = i(2),
                batteryPercent = i(70)?.takeIf { it in 0..100 },
                busy = i(8) == 1,
                mode = i(43),
                subMode = i(44),
                recordSeconds = i(13),
                sdStatus = i(33),
                remainingPhotos = i(34),
                remainingVideoSec = i(35),
                remainingSpaceKb = l(54),
                clients = i(31),
                streaming = i(32) == 1,
                settings = settings,
            )
        }
    }
}
