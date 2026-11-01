package com.dagmesh.app.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Uygulamanın kendisini (APK) internetsiz paylaşır.
 *
 * QR yöntemi: telefon kendine özel kısa ömürlü bir Wi‑Fi noktası açar ve küçük bir web sunucusu
 * çalıştırır. Karşı telefon 1. QR ile Wi‑Fi'a bağlanır, 2. QR ile tarayıcıda indirme sayfasını açar.
 * Karşı tarafta ne uygulama ne internet gerekir.
 */
class ApkShare(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Ready(val ssid: String, val password: String, val url: String, val downloads: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: ServerSocket? = null
    @Volatile
    private var downloads = 0

    /** Telefonda kurulu APK'nın paylaşılabilir kopyası. */
    fun apkFile(): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, APK_NAME)
        val source = File(context.applicationInfo.sourceDir)
        if (!out.exists() || out.length() != source.length() || out.lastModified() < source.lastModified()) {
            source.copyTo(out, overwrite = true)
        }
        return out
    }

    // ---------------------------------------------------------------- QR + Wi‑Fi noktası

    fun startHotspot() {
        if (_state.value is State.Starting || _state.value is State.Ready) return
        _state.value = State.Starting
        downloads = 0
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val (ssid, password) = credentials(res)
                    scope.launch { serve(ssid, password) }
                }

                override fun onStopped() {
                    if (_state.value is State.Ready) _state.value = State.Failed("Wi‑Fi noktası kapandı")
                    stopServer()
                }

                override fun onFailed(reason: Int) {
                    _state.value = State.Failed(
                        when (reason) {
                            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Telefonun kendi hotspot'u açık. Kapatıp tekrar dene."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Bu telefonda Wi‑Fi noktası açmak engellenmiş."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "Uygun Wi‑Fi kanalı bulunamadı. Biraz sonra tekrar dene."
                            else -> "Wi‑Fi noktası açılamadı. Wi‑Fi ve Konum'un açık olduğundan emin ol."
                        }
                    )
                }
            }, main)
        } catch (e: SecurityException) {
            _state.value = State.Failed("Konum / yakındaki cihazlar izni gerekli.")
        } catch (e: Exception) {
            _state.value = State.Failed("Wi‑Fi noktası açılamadı: ${e.message}")
        }
    }

    fun stopHotspot() {
        stopServer()
        reservation?.close()
        reservation = null
        _state.value = State.Idle
    }

    private suspend fun serve(ssid: String, password: String) {
        val apk = try {
            apkFile()
        } catch (e: Exception) {
            _state.value = State.Failed("Uygulama dosyası hazırlanamadı")
            return
        }
        val socket = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            try {
                ServerSocket(0)
            } catch (e2: Exception) {
                _state.value = State.Failed("Paylaşım sunucusu açılamadı")
                return
            }
        }
        server = socket

        // Wi‑Fi noktasının adresi arayüz açılınca belli olur; kısa bir süre bekle.
        var ip: String? = null
        for (attempt in 0 until 15) {
            ip = hotspotAddress()
            if (ip != null) break
            delay(300)
        }
        val url = "http://${ip ?: "192.168.43.1"}:${socket.localPort}"
        _state.value = State.Ready(ssid, password, url, 0)

        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            }
            scope.launch { handle(client, apk) }
        }
    }

    private fun handle(client: Socket, apk: File) {
        client.use { s ->
            try {
                s.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Başlıkları oku ve geç.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val out = s.getOutputStream()
                if (path.startsWith("/$APK_NAME", ignoreCase = true) || path.startsWith("/indir")) {
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.android.package-archive\r\n" +
                        "Content-Length: ${apk.length()}\r\n" +
                        "Content-Disposition: attachment; filename=\"$APK_NAME\"\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(header.toByteArray())
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    out.flush()
                    downloads++
                    (_state.value as? State.Ready)?.let { _state.value = it.copy(downloads = downloads) }
                } else {
                    val body = page(apk.length()).toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(header.toByteArray())
                    out.write(body)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "İndirme bağlantısı kapandı", e)
            }
        }
    }

    private fun stopServer() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    fun release() {
        stopHotspot()
        scope.cancel()
    }

    // ---------------------------------------------------------------- Quick Share / Bluetooth

    /** Telefonun kendi paylaşım menüsünü açar (Quick Share, Bluetooth vb.). */
    fun shareViaSystem(): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile())
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, "Dağ Mesh'i paylaş")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Paylaşım menüsü açılamadı", e)
            false
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    @Suppress("DEPRECATION")
    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val config = res.softApConfiguration
            (config.ssid ?: "DagMesh").removeSurrounding("\"") to (config.passphrase ?: "")
        } else {
            val config = res.wifiConfiguration
            (config?.SSID ?: "DagMesh").removeSurrounding("\"") to (config?.preSharedKey ?: "").removeSurrounding("\"")
        }
    }

    /** Wi‑Fi noktası arayüzünün IPv4 adresini bulur (Samsung'da genelde "swlan0"). */
    private fun hotspotAddress(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        candidates += nif.name to (addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        val preferred = listOf("swlan", "ap", "softap", "wlan1", "wigig")
        return candidates.firstOrNull { (name, _) -> preferred.any { name.startsWith(it) } }?.second
            ?: candidates.firstOrNull { (name, _) -> !name.startsWith("rmnet") && name != "wlan0" }?.second
            ?: candidates.firstOrNull()?.second
    }

    private fun page(size: Long): String {
        val mb = String.format(java.util.Locale.US, "%.1f", size / (1024.0 * 1024.0))
        return """
<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dağ Mesh</title>
<style>
body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0A1220;color:#E7EDF6;
display:flex;min-height:100vh;align-items:center;justify-content:center}
.c{max-width:420px;padding:28px 22px;text-align:center}
.logo{font-size:56px}h1{margin:8px 0 4px;font-size:28px}
p{color:#93A3BA;line-height:1.5}
a.b{display:block;margin:26px 0 18px;padding:18px;border-radius:18px;background:#FF8A3D;color:#1E0F04;
font-weight:700;font-size:18px;text-decoration:none}
ol{text-align:left;color:#93A3BA;line-height:1.7;padding-left:20px;background:#111B2E;border-radius:16px;padding:16px 16px 16px 36px}
</style></head><body><div class="c">
<div class="logo">⛰️</div><h1>Dağ Mesh</h1>
<p>İnternet ve şebeke olmadan, yakındaki telefonlarla mesajlaş, ara, dosya gönder.</p>
<a class="b" href="/$APK_NAME">Uygulamayı indir ($mb MB)</a>
<ol>
<li>İndirme bitince dosyaya dokun.</li>
<li>"Bilinmeyen uygulamalar" uyarısı çıkarsa <b>Ayarlar → Bu kaynağa izin ver</b> de.</li>
<li><b>Yükle</b>'ye dokun, açılınca adını gir.</li>
</ol>
</div></body></html>
""".trimIndent()
    }

    companion object {
        private const val TAG = "DagMesh/Share"
        const val APK_NAME = "DagMesh.apk"
        private const val PORT = 8080
    }
}
