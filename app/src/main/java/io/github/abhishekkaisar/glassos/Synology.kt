package io.github.abhishekkaisar.glassos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A Synology Photos shared album (a "share link"), for the screensaver: its photos, their place and date, and the
 * NAS's own large thumbnails (1280 px on the short side, JPEG even for HEIC). No account: the link is public, with
 * an optional password.
 *
 * The web app's protocol: opening the share page gives a session cookie (sharing_sid); API calls then carry it and
 * the link's passphrase in X-SYNO-SHARING. A password-protected album logs in first (SYNO.Core.Sharing.Login).
 *
 * A NAS on the home network is reached by its local address, where its certificate (made for its DDNS name, or
 * self-signed) cannot match. There the certificate shown when the link was checked is pinned ([Pin]); a renewed one
 * is accepted when it is valid and for the same names.
 */
object Synology {
    /** A parsed share link: [base] is the scheme, host and any alias path before /mo/sharing/. */
    class Link(val base: String, val host: String, val passphrase: String)

    /** One photo: its id and thumbnail cache key, when it was taken (local time, as seconds) and what it shows. */
    class Item(val id: Long, val cacheKey: String, val taken: Long, val description: String, val place: List<String>)

    class Result(val ok: Boolean, val message: String, val album: String = "", val count: Int = 0, val cert: String = "")

    private const val REFRESH_MS = 30 * 60_000L
    /** SYNO.Core.Sharing.Login's error for a wrong password. */
    private const val WRONG_PASSWORD = 1001
    private const val PAGE = 500
    private const val MAX_ITEMS = 3000
    /** Thumbnails kept on disk (about 1 MB each); the oldest shown go first. */
    private const val MAX_FILES = 60

    private val LINK = Regex("^(?:(https?)://)?([^/\\s?#]+)(/[^\\s?#]*?)?/mo/sharing/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)

    fun parse(input: String): Link? {
        val m = LINK.find(input.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase().ifEmpty { "https" }
        val host = m.groupValues[2]
        return Link("$scheme://$host${m.groupValues[3].trimEnd('/')}", host.substringBefore(':'), m.groupValues[4])
    }

    /** A private IPv4 address or a name that only the home network knows (no dot, .local, .lan, .home). */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase()
        if (!h.contains('.') || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }

    // ---------- Checking a link ----------

    /** Opens the album as the screensaver would and says what it found, or why it failed. Call off the UI thread. */
    fun check(input: String, password: String): Result {
        val link = parse(input)
            ?: return Result(false, "That is not a Synology Photos share link. It looks like https://<NAS address>:5001/mo/sharing/…")
        if (link.base.startsWith("http://")) return Result(false, "Use the https link: https://<NAS address>:5001/mo/sharing/…")
        val s = Session(link, password, pinned = "")
        return try {
            if (!s.start()) return Result(false, "That album is not shared any more, or the link is wrong.")
            val (album, count) = s.album() ?: return Result(false, when {
                password.isEmpty() -> "The album did not open. If it has a password, enter it."
                s.wrongPassword -> "Wrong password for this album."
                else -> "The album did not open. Check the password."
            })
            val msg = if (count == 0) "Found “$album”, with no photos yet." else "Found “$album”: $count item${if (count == 1) "" else "s"}."
            Result(true, msg, album, count, s.seen)
        } catch (e: Throwable) {
            Result(false, explain(e, link))
        }
    }

    private fun explain(e: Throwable, link: Link): String {
        val local = isLocal(link.host)
        return when (e) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                if (local) "Could not reach ${link.host}. Check the address and that the NAS is on."
                else "Could not reach ${link.host}. If the NAS is on your home network, use its local IP address instead."
            is SSLException, is CertificateException ->
                if (local) "The NAS's certificate could not be read. Check the address and the port (usually 5001)."
                else "This TV does not trust the NAS's certificate. Use the NAS's local IP address instead."
            else -> "The album did not open (${e.javaClass.simpleName}). Check the link."
        }
    }

    // ---------- The album, for the screensaver ----------

    private fun dir(ctx: Context) = File(ctx.filesDir, "screensaver/synology").apply { mkdirs() }
    private fun imgDir(ctx: Context) = File(dir(ctx), "img").apply { mkdirs() }

    /** The album's photos from the last refresh (works offline), or empty. */
    fun cached(ctx: Context): List<Item> {
        val prefs = Prefs(ctx)
        val f = File(dir(ctx), "items.json")
        if (!f.exists()) return emptyList()
        return try {
            val o = JSONObject(f.readText())
            if (o.optString("link") != prefs.synologyLink) return emptyList()
            val arr = o.getJSONArray("items")
            (0 until arr.length()).map { i ->
                val x = arr.getJSONObject(i)
                val pl = x.optJSONArray("place")
                Item(x.getLong("id"), x.getString("key"), x.optLong("taken"), x.optString("desc"),
                    if (pl == null) emptyList() else (0 until pl.length()).map { pl.getString(it) })
            }
        } catch (e: Throwable) { emptyList() }
    }

    fun isStale(ctx: Context): Boolean {
        val f = File(dir(ctx), "items.json")
        if (!f.exists()) return true
        return try { JSONObject(f.readText()).optString("link") != Prefs(ctx).synologyLink } catch (e: Throwable) { true } ||
            System.currentTimeMillis() - f.lastModified() > REFRESH_MS
    }

    /** Lists the album again. Returns true when the list was saved. Call off the UI thread. */
    fun refresh(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        val link = parse(prefs.synologyLink) ?: return false
        val s = Session(link, prefs.synologyPassword, prefs.synologyCert)
        return try {
            if (!s.start()) return false
            val album = s.album()?.first ?: return false
            val items = s.items(MAX_ITEMS)
            val arr = JSONArray()
            for (it in items) arr.put(JSONObject().apply {
                put("id", it.id); put("key", it.cacheKey); put("taken", it.taken); put("desc", it.description)
                put("place", JSONArray(it.place))
            })
            val d = dir(ctx)
            val tmp = File(d, "items.json.tmp")
            tmp.writeText(JSONObject().put("link", prefs.synologyLink).put("album", album).put("items", arr).toString())
            tmp.renameTo(File(d, "items.json"))
            if (album != prefs.synologyAlbum) prefs.synologyAlbum = album
            // A renewed certificate was accepted: pin the new one.
            if (s.seen.isNotEmpty() && s.seen != prefs.synologyCert && isLocal(link.host)) prefs.synologyCert = s.seen
            true
        } catch (e: Throwable) {
            WLog.i("synology refresh: $e")
            false
        }
    }

    /** The photo's large thumbnail, downloaded once and kept with the last [MAX_FILES] shown. Call off the UI thread. */
    fun image(ctx: Context, item: Item): File? {
        val d = imgDir(ctx)
        val f = File(d, "${item.id}_${item.cacheKey.substringAfter('_')}.jpg")
        if (f.exists()) { f.setLastModified(System.currentTimeMillis()); return f }
        val prefs = Prefs(ctx)
        val link = parse(prefs.synologyLink) ?: return null
        val s = Session(link, prefs.synologyPassword, prefs.synologyCert)
        return try {
            if (!s.start() || !s.thumbnail(item, f)) return null
            d.listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
            f
        } catch (e: Throwable) {
            WLog.i("synology image: $e")
            null
        }
    }

    /** Forgets the cached album (a new link was saved). */
    fun clear(ctx: Context) {
        File(dir(ctx), "items.json").delete()
        imgDir(ctx).listFiles()?.forEach { it.delete() }
    }

    // ---------- Talking to the NAS ----------

    private class Session(val link: Link, val password: String, val pinned: String) {
        private var cookie = ""
        private val local = isLocal(link.host)
        private val trust = if (local) Pin(pinned) else null
        /** The certificate the NAS showed, as "sha256|names" (local addresses only). */
        val seen get() = trust?.seen ?: ""
        private val api = "${link.base}/mo/sharing/webapi/entry.cgi"

        /** Opens the share page for its session cookie, then logs in when there is a password. False: no such share. */
        fun start(): Boolean {
            // Without X-SYNO-SHARING: with it, the NAS gives no session cookie.
            val c = open("${link.base}/mo/sharing/${link.passphrase}", api = false)
            try {
                if (c.responseCode != 200) return false
                takeCookie(c)
            } finally { c.disconnect() }
            // A protected album gives no cookie until the login, which is also sent without X-SYNO-SHARING.
            if (password.isNotEmpty()) {
                val r = call(mapOf("api" to "SYNO.Core.Sharing.Login", "method" to "login", "version" to "1",
                    "sharing_id" to JSONObject.quote(link.passphrase), "password" to JSONObject.quote(password)), api = false)
                r?.optJSONObject("data")?.optString("sharing_sid")?.takeIf { it.isNotEmpty() }?.let { cookie = "sharing_sid=$it" }
                wrongPassword = r?.optJSONObject("error")?.optInt("code") == WRONG_PASSWORD
            }
            return true
        }

        /** The NAS turned the password down (the album then does not open). */
        var wrongPassword = false
            private set

        /** The album's name and how many photos and videos it holds, or null when it did not open. */
        fun album(): Pair<String, Int>? {
            val r = call(mapOf("api" to "SYNO.Foto.Browse.Album", "method" to "get", "version" to "1")) ?: return null
            if (!r.optBoolean("success")) return null
            val a = r.optJSONObject("data")?.optJSONArray("list")?.optJSONObject(0)
            return (a?.optString("name")?.ifEmpty { null } ?: "Shared album") to (a?.optInt("item_count") ?: 0)
        }

        /** Up to [limit] photos with a large thumbnail; videos are left out. */
        fun items(limit: Int): List<Item> {
            val out = ArrayList<Item>()
            var offset = 0
            while (offset < limit) {
                val r = call(mapOf("api" to "SYNO.Foto.Browse.Item", "method" to "list", "version" to "1",
                    "offset" to offset.toString(), "limit" to PAGE.toString(),
                    "additional" to "[\"thumbnail\",\"address\",\"description\"]")) ?: break
                val arr = r.optJSONObject("data")?.optJSONArray("list") ?: break
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("type") != "photo" || out.size >= limit) continue
                    val add = o.optJSONObject("additional") ?: continue
                    val th = add.optJSONObject("thumbnail") ?: continue
                    if (th.optString("xl") != "ready") continue
                    val a = add.optJSONObject("address")
                    val place = if (a == null) emptyList() else listOf("landmark", "district", "town", "village", "city", "state", "country")
                        .map { a.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()
                    out.add(Item(o.getLong("id"), th.optString("cache_key"), o.optLong("time"), add.optString("description").trim(), place))
                }
                if (arr.length() < PAGE) break
                offset += PAGE
            }
            return out
        }

        fun thumbnail(item: Item, to: File): Boolean {
            val q = mapOf("api" to "SYNO.Foto.Thumbnail", "method" to "get", "version" to "2", "id" to item.id.toString(),
                "cache_key" to item.cacheKey, "type" to "unit", "size" to "xl")
            val c = open("$api?" + form(q))
            try {
                if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) return false
                val tmp = File(to.path + ".tmp")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                return tmp.renameTo(to)
            } finally { c.disconnect() }
        }

        private fun call(params: Map<String, String>, api: Boolean = true): JSONObject? {
            val c = open(this.api, api)
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form(params).toByteArray()) }
                if (c.responseCode != 200) return null
                return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } finally { c.disconnect() }
        }

        private fun open(url: String, api: Boolean = true): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            if (api) setRequestProperty("X-SYNO-SHARING", link.passphrase)
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            if (this is HttpsURLConnection && trust != null) {
                sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
                // The pin stands in for the name check: the certificate cannot carry a home network address.
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
        }

        private fun takeCookie(c: HttpURLConnection) {
            c.headerFields?.get("Set-Cookie")?.forEach { h ->
                val kv = h.substringBefore(';').trim()
                if (kv.startsWith("sharing_sid=")) cookie = kv
            }
        }

        private fun form(p: Map<String, String>) = p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }

    /**
     * Trust for a NAS on the home network. With nothing pinned (the link being checked) any certificate is taken and
     * recorded in [seen]; afterwards only the pinned one, or a valid renewal for the same names (Let's Encrypt
     * renews every few months).
     */
    class Pin(private val pinned: String) : X509TrustManager {
        @Volatile var seen = ""
        private val system: X509TrustManager? = try {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Throwable) { null }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
            val sha = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            val names = names(leaf)
            seen = "$sha|${names.joinToString(",")}"
            if (pinned.isEmpty()) return
            if (sha == pinned.substringBefore('|')) return
            val was = pinned.substringAfter('|', "").split(',').filter { it.isNotEmpty() }.toSet()
            if (names.isEmpty() || names.toSet() != was) throw CertificateException("the NAS's certificate changed")
            (system ?: throw CertificateException("no system trust")).checkServerTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

        private fun names(c: X509Certificate): List<String> {
            val alt = try { c.subjectAlternativeNames?.filter { it[0] == 2 }?.map { it[1].toString().lowercase() } } catch (e: Throwable) { null }
            if (!alt.isNullOrEmpty()) return alt.sorted()
            return listOfNotNull(Regex("CN=([^,]+)").find(c.subjectX500Principal.name)?.groupValues?.get(1)?.lowercase())
        }
    }
}
