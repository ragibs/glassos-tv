package io.github.abhishekkaisar.glassos

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from this fork's GitHub releases. At most every [CHECK_MS] GlassOS asks GitHub for the releases (pre-releases
 * too: GitHub's "latest" ignores them) and remembers the newest one with an APK that is newer than itself. Control
 * Center then shows one quiet row; OK there downloads the APK, checks that it is GlassOS, signed with the same key as
 * the installed app and not older, and hands it to Android's installer, which asks the user to confirm.
 */
object Updater {
    /** The repository whose releases this build updates from. */
    const val REPO = "ragibs/glassos-tv"
    private const val CHECK_MS = 12 * 3600_000L
    private const val RETRY_MS = 60 * 60_000L

    class Release(val tag: String, val version: String, val apkUrl: String, val size: Long)

    @Volatile private var busy = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "update").apply { mkdirs() }

    fun current(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Throwable) { "" }

    /** The newer release found by the last check, or null. Cheap: reads one small file. */
    fun available(ctx: Context): Release? = try {
        val o = JSONObject(File(dir(ctx), "release.json").readText())
        val r = Release(o.getString("tag"), o.getString("version"), o.getString("apk"), o.optLong("size"))
        r.takeIf { newer(it.version, current(ctx)) }
    } catch (e: Throwable) { null }

    /** Asks GitHub when the last check is older than [CHECK_MS] (or a failed one older than [RETRY_MS]). Off the UI thread. */
    fun checkIfStale(ctx: Context) {
        val d = dir(ctx)
        val now = System.currentTimeMillis()
        val ok = File(d, "checked")
        val tried = File(d, "tried")
        if (now - ok.lastModified() < CHECK_MS || now - tried.lastModified() < RETRY_MS) return
        try { tried.writeText("") } catch (e: Throwable) { }
        val (code, body) = HeroRepo.get("https://api.github.com/repos/$REPO/releases?per_page=10",
            mapOf("Accept" to "application/vnd.github+json"))
        if (code != 200 || body == null) { WLog.i("update check -> $code"); return }
        val best = try { newest(JSONArray(body), current(ctx)) } catch (e: Throwable) { null }
        val f = File(d, "release.json")
        if (best == null) f.delete()
        else f.writeText(JSONObject().put("tag", best.tag).put("version", best.version).put("apk", best.apkUrl).put("size", best.size).toString())
        try { ok.writeText("") } catch (e: Throwable) { }
        WLog.i("update check: ${best?.version ?: "up to date"}")
    }

    /** The newest published release with an APK that is newer than [cur]. */
    private fun newest(arr: JSONArray, cur: String): Release? {
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("draft")) continue
            val tag = o.optString("tag_name")
            val version = tag.removePrefix("v")
            val assets = o.optJSONArray("assets") ?: continue
            var apk: JSONObject? = null
            for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                if (a.optString("name").endsWith(".apk")) { apk = a; break }
            }
            val url = apk?.optString("browser_download_url") ?: continue
            if (!url.startsWith("https://github.com/$REPO/releases/download/")) continue
            if (!newer(version, cur) || (best != null && !newer(version, best.version))) continue
            best = Release(tag, version, url, apk.optLong("size"))
        }
        return best
    }

    /**
     * True when version [a] is newer than [b]: "1.0.3" > "1.0.2", and a release beats its own pre-releases
     * ("1.0.3" > "1.0.3-beta"; "1.0.3-beta.2" > "1.0.3-beta").
     */
    fun newer(a: String, b: String): Boolean {
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val x = an.getOrElse(i) { 0 }; val y = bn.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        if (ap.isEmpty() || bp.isEmpty()) return ap.isEmpty() && bp.isNotEmpty()
        val pa = ap.split('.'); val pb = bp.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return false
            val y = pb.getOrNull(i) ?: return true
            val xi = x.toIntOrNull(); val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c > 0
        }
        return false
    }

    private fun split(v: String): Pair<List<Int>, String> {
        val s = v.trim().removePrefix("v")
        val nums = s.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        return nums to (if (s.contains('-')) s.substringAfter('-') else "")
    }

    /**
     * Downloads [r], checks it and starts the install. [progress] gets 0..100 while downloading (on the calling
     * thread); the result is null when Android's installer has it, else what went wrong. Off the UI thread.
     */
    fun downloadAndInstall(ctx: Context, r: Release, progress: (Int) -> Unit): String? {
        if (busy) return "An update is already downloading"
        busy = true
        try {
            val f = File(dir(ctx), "GlassOS.apk")
            if (!download(r.apkUrl, f, progress)) return "Download failed. Check the network and try again."
            check(ctx, f)?.let { f.delete(); return it }
            install(ctx, f)
            return null
        } catch (e: Throwable) {
            WLog.i("update failed: $e")
            return "Update failed: ${e.message ?: e.javaClass.simpleName}"
        } finally { busy = false }
    }

    /** GitHub sends the APK from another host: follow the redirects by hand, HTTPS only. */
    private fun download(url: String, to: File, progress: (Int) -> Unit): Boolean {
        var u = url
        repeat(5) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    u = c.getHeaderField("Location") ?: return false
                    if (!u.startsWith("https://")) return false
                    return@repeat
                }
                if (code != 200) return false
                val total = c.contentLength.toLong()
                val tmp = File(to.path + ".tmp")
                var done = 0L
                var shown = -1
                c.inputStream.use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p != shown) { shown = p; progress(p) }
                    }
                } }
                return tmp.renameTo(to)
            } finally { c.disconnect() }
        }
        return false
    }

    /** Null when [f] is GlassOS, not older than this one and signed with the same key; else why not. */
    @Suppress("DEPRECATION")
    private fun check(ctx: Context, f: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(f.path, flags) ?: return "The download is not a valid app"
        if (apk.packageName != ctx.packageName) return "The download is not GlassOS"
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (apk.versionCode < mine.versionCode) return "The download is an older version"
        fun sigs(p: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures)?.map { it.toCharsString() }?.toSet()
        val a = sigs(apk)
        if (a.isNullOrEmpty() || a != sigs(mine)) return "The download is signed with a different key, so it cannot update this GlassOS"
        return null
    }

    /** Hands [f] to Android's installer; [UpdateReceiver] shows its confirmation screen. */
    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(f.length())
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("GlassOS.apk", 0, f.length()).use { o ->
                f.inputStream().use { it.copyTo(o) }
                s.fsync(o)
            }
            val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(done.intentSender)
        }
        WLog.i("update: install session $id committed")
    }
}

/** Android's installer reports here: its confirmation screen to show, or how the install ended. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        WLog.i("update: installer status $status ${msg ?: ""}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> Toast.makeText(context, "GlassOS update failed${msg?.let { ": $it" } ?: ""}", Toast.LENGTH_LONG).show()
        }
    }
}
