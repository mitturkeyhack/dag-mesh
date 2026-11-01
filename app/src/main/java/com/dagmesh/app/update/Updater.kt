package com.dagmesh.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dagmesh.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Otomatik güncelleme.
 *
 * İnternet varken GitHub'daki son sürüme bakar; yeniyse indirir ve Android'in kurulum sistemiyle
 * (PackageInstaller) yükler. Android 12+ telefonlarda, uygulamayı en son kendisi güncellediyse
 * kullanıcıya hiçbir şey sormadan kurar. İlk seferde Android bir kez onay ister.
 */
object Updater {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Downloading(val version: String, val percent: Int) : State
        data class Installing(val version: String) : State
        /** Android onay istiyor (ilk güncelleme ya da izin gerekiyor). */
        data class NeedsConfirm(val version: String, val confirm: Intent) : State
        data class Failed(val message: String) : State
    }

    private const val TAG = "DagMesh/Update"
    private const val RELEASES_URL = "https://api.github.com/repos/mitturkeyhack/dag-mesh/releases?per_page=5"
    private const val CHANNEL = "updates"
    private const val NOTIFY_ID = 50
    private const val PERIODIC_WORK = "dagmesh-update-periodic"
    private const val NOW_WORK = "dagmesh-update-now"
    private const val CHECK_INTERVAL_ON_OPEN_MS = 15 * 60 * 1000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()

    private data class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val size: Long)

    // ---------------------------------------------------------------- zamanlama

    /** İnternet geldiğinde hemen bir kez, sonra 6 saatte bir kontrol eder (uygulama kapalıyken de). */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
        wm.enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build(),
        )
    }

    /**
     * Uygulama her açıldığında çağrılır: son kontrolden bu yana 15 dk geçtiyse (internet geldiğinde)
     * hemen bir kontrol daha sıraya koyar. Böylece yeni sürüm uygulamayı açar açmaz gelir.
     */
    fun checkIfDue(context: Context) {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_ON_OPEN_MS) return
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build(),
        )
    }

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    // ---------------------------------------------------------------- kontrol + indirme + kurulum

    /** @return true ise iş bitti (güncel ya da kurulum başlatıldı); false ise sonra tekrar denenmeli. */
    suspend fun checkAndInstall(context: Context, userInitiated: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext true
        try {
            if (!hasInternet(context)) {
                if (userInitiated) _state.value = State.Failed("İnternet bağlantısı yok")
                return@withContext false
            }
            _state.value = State.Checking
            val latest = fetchLatest() ?: run {
                _state.value = if (userInitiated) State.Failed("Sürüm bilgisi alınamadı") else State.Idle
                return@withContext false
            }
            if (latest.versionCode <= currentVersionCode(context)) {
                _state.value = State.UpToDate
                return@withContext true
            }

            val apk = download(context, latest) ?: run {
                _state.value = State.Failed("Güncelleme indirilemedi")
                return@withContext false
            }

            // İndirilen dosya gerçekten bu uygulamanın daha yeni sürümü mü?
            val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            if (info == null || info.packageName != context.packageName ||
                PackageInfoCompat.getLongVersionCode(info) != latest.versionCode
            ) {
                apk.delete()
                _state.value = State.Failed("İndirilen dosya doğrulanamadı")
                return@withContext false
            }

            _state.value = State.Installing(latest.versionName)
            install(context, apk, latest.versionName)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Güncelleme başarısız", e)
            _state.value = State.Failed("Güncelleme başarısız: ${e.message ?: "bilinmeyen hata"}")
            false
        } finally {
            lock.unlock()
        }
    }

    private fun fetchLatest(): Release? {
        val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DagMesh-Android")
        }
        try {
            if (conn.responseCode != 200) return null
            val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            var best: Release? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val tag = r.optString("tag_name")
                val code = tag.substringAfterLast('.').toLongOrNull() ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (!a.optString("name").endsWith(".apk")) continue
                    if (best == null || code > best.versionCode) {
                        best = Release(code, tag.removePrefix("v"), a.getString("browser_download_url"), a.optLong("size"))
                    }
                }
            }
            return best
        } finally {
            conn.disconnect()
        }
    }

    private fun download(context: Context, release: Release): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { if (!it.name.contains("-${release.versionCode}.")) it.delete() }
        val out = File(dir, "DagMesh-${release.versionCode}.apk")
        if (out.exists() && release.size > 0 && out.length() == release.size) return out

        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DagMesh-Android")
        }
        try {
            if (conn.responseCode != 200) return null
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = State.Downloading(release.versionName, percent)
                        }
                    }
                }
            }
            if (release.size > 0 && out.length() != release.size) {
                out.delete()
                return null
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun install(context: Context, apk: File, versionName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                // Uygulamayı en son kendimiz yüklediysek Android onay sormadan kurar.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val callback = Intent(context, UpdateReceiver::class.java)
                .setAction(UpdateReceiver.ACTION_RESULT)
                .putExtra(UpdateReceiver.EXTRA_VERSION, versionName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
    }

    // ---------------------------------------------------------------- kurulum sonucu

    internal fun onNeedsConfirm(context: Context, version: String, confirm: Intent) {
        _state.value = State.NeedsConfirm(version, confirm)
        notify(
            context,
            "Dağ Mesh $version hazır",
            "Yüklemek için dokun. Bundan sonraki güncellemeler kendiliğinden kurulacak.",
            PendingIntent.getActivity(
                context, 51, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    internal fun onFailed(message: String) {
        _state.value = State.Failed(message)
    }

    internal fun onUpdated(context: Context) {
        _state.value = State.UpToDate
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        notify(
            context,
            "Dağ Mesh güncellendi",
            "Yeni sürüm: ${currentVersionName(context)}",
            launch?.let {
                PendingIntent.getActivity(context, 52, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            },
        )
    }

    /** Ekrandaki "Yükle" düğmesi için. */
    fun confirm(context: Context) {
        val s = _state.value as? State.NeedsConfirm ?: return
        try {
            context.startActivity(Intent(s.confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            _state.value = State.Failed("Kurulum ekranı açılamadı")
        }
    }

    private fun notify(context: Context, title: String, text: String, intent: PendingIntent?) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Güncellemeler", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { intent?.let { setContentIntent(it) } }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
