package io.github.abhishekkaisar.glassos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import java.io.File
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/** A photo for the screensaver: its caption and how to get the image (a download for an album photo). */
class Shot(val key: String, val title: String, val subtitle: String, val fetch: () -> File?, val rotate: Boolean)

/**
 * The screensaver's photos: a Synology Photos shared album ([Synology]) or the user's own photos (Pictures/Glass,
 * the Top Shelf's private copies).
 */
object Screensaver {
    const val OFF = "off"
    const val SYNOLOGY = "synology"
    const val PHOTOS = "photos"
    val SOURCES = listOf(OFF to "Off", SYNOLOGY to "Synology album", PHOTOS to "Your photos")
    fun label(id: String) = SOURCES.firstOrNull { it.first == id }?.second ?: id
    val MINUTES = listOf(1, 2, 5, 10, 15, 30, 60)
    val SECONDS = listOf(10, 15, 20, 30, 60)

    /** The photos to show, from the disk only (an album's list from its last refresh). Call off the UI thread. */
    fun shots(ctx: Context): List<Shot> = when (Prefs(ctx).screensaver) {
        SYNOLOGY -> Synology.cached(ctx).map { item ->
            val (title, sub) = caption(item)
            Shot("s:${item.id}", title, sub, { Synology.image(ctx, item) }, rotate = false)
        }
        PHOTOS -> HeroRepo.localImages(ctx, canRead(ctx)).map { f ->
            Shot("p:${f.name}", f.nameWithoutExtension.removePrefix("w_").removePrefix("r_"), dateOf(f), { f.takeIf { it.exists() } }, rotate = true)
        }
        else -> emptyList()
    }

    private fun canRead(ctx: Context) = Build.VERSION.SDK_INT < 23 ||
        ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * An album photo's title and line under it: its description, else the nearest place name, else the date; then
     * the date and the wider place. Synology gives the time taken as local time, so it is formatted as UTC.
     */
    private fun caption(item: Synology.Item): Pair<String, String> {
        val date = if (item.taken > 0) longDate(item.taken * 1000) else ""
        val place = item.place
        val title = item.description.ifEmpty { place.firstOrNull() ?: date }
        val wider = place.filter { it != title }.let { p ->
            // The city and the region, not the whole address: "Calgary, Alberta".
            if (p.size > 2) p.takeLast(3).dropLast(1) else p
        }.joinToString(", ")
        val sub = listOf(if (title == date) "" else date, wider).filter { it.isNotEmpty() }.joinToString(" · ")
        return title to sub
    }

    /**
     * The date the photo was taken (EXIF), else nothing: the file is GlassOS's private copy, whose own date is the
     * day it was copied.
     */
    private fun dateOf(f: File): String {
        val exif = try {
            val e = android.media.ExifInterface(f.path)
            (e.getAttribute("DateTimeOriginal") ?: e.getAttribute(android.media.ExifInterface.TAG_DATETIME))
                ?.let { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(it)?.time }
        } catch (e: Throwable) { null }
        return if (exif != null) longDate(exif) else ""
    }

    private fun longDate(ms: Long): String {
        val f = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    /**
     * Decodes a photo at most [maxSide] on its long side, upright (phone photos carry their rotation in EXIF; the
     * NAS's thumbnails are already upright). Call off the UI thread.
     */
    fun decode(f: File, rotate: Boolean, maxSide: Int = MAX_SIDE): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
            var b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            val deg = if (!rotate || b == null) 0 else try {
                when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90; 3 -> 180; 8 -> 270; else -> 0
                }
            } catch (e: Throwable) { 0 }
            if (b != null && deg != 0) {
                val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                if (r !== b) b.recycle()
                b = r
            }
            b?.apply { setHasAlpha(false) }
        }
    } catch (e: Throwable) { null }

    private const val MAX_SIDE = 2048
}

/**
 * The full-screen screensaver: the photo in a frosted frame at the left, over a dimmed blur of itself, filling its
 * card and zooming in slowly (a wide card for a landscape photo, a 3:4 one for a portrait photo), with its caption at
 * the bottom left inside it over a soft dark gradient. A column at the right holds the time and date, the weather
 * ([Weather]), what plays now (with its progress) and the next three photos. Photos crossfade.
 *
 * Starts by itself after [Prefs.screensaverMinutes] without a key on the home, or from Control Center. Left and
 * Right step through the photos; any other key closes it. Everything but the background drifts a few pixels a minute
 * (OLED burn-in). The screen is kept on for up to [KEEP_ON_MS]; after that the TV sleeps as it would anyway.
 *
 * Each frame draws the blur once (small, scaled up), cached shadows, the photo once, a few translucent shapes and
 * text: cheap enough for the TV's GPU, and nothing redraws behind it (the home is paused and covered).
 */
class ScreensaverView(private val home: HomeScreen) : View(home.ctx) {
    var isOpen = false
        private set
    /** True when it started by itself and closed again with nothing to show. */
    var gaveUp = false
        private set

    private class Frame(val shot: Shot, val n: Int, val sharp: Bitmap, val blurred: Bitmap, val start: Long) {
        val portrait = sharp.height > sharp.width * 1.05f
        val sharpM = Matrix()
    }

    /** The photo's frame for landscape and portrait photos, on a 1920 x 1080 screen (scaled by [k]). */
    private val card = RectF(97f, 151f, 1295f, 929f)
    private val portraitCard = RectF(363f, 91f, 1029f, 989f)

    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val sw = Backdrop.screenW
    private val sh = Backdrop.screenH
    /** Sizes are given for a 1920 x 1080 screen and scaled by this. */
    private val k = sh / 1080f

    private var shots: List<Shot> = emptyList()
    private var order = IntArray(0)
    private var pos = -1
    private var cur: Frame? = null
    private var prev: Frame? = null
    private var fadeStart = 0L
    private var loading = false
    private var gen = 0
    private var openedAt = 0L
    private var message: String? = null
    private var captions = true
    /** How long each photo shows ([Prefs.screensaverSeconds]). */
    private var showMs = 15_000L
    private var time = ""
    private var date = ""
    private var timeAt = 0L
    private var weather: Weather.Now? = null

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint()
    private val clockText = text(Fonts.light, Color.WHITE)
    private val dateText = text(Fonts.regular, 0xF2FFFFFF.toInt())
    private val titleText = text(Fonts.bold, Color.WHITE)
    private val subText = text(Fonts.regular, 0xE6FFFFFF.toInt())
    private val labelText = text(Fonts.medium, 0xE6FFFFFF.toInt()).apply { letterSpacing = 0.2f }
    private val messageText = text(Fonts.regular, 0xD9FFFFFF.toInt()).apply { textSize = 26f * k }
    // Inside the frosted cards the text needs no shadow; one would show as a dark band behind it.
    private val tempText = text(Fonts.light, Color.WHITE).apply { clearShadowLayer() }
    private val cardTitle = text(Fonts.medium, Color.WHITE).apply { clearShadowLayer() }
    private val cardSub = text(Fonts.regular, 0xD9FFFFFF.toInt()).apply { clearShadowLayer() }
    private val songLabel = text(Fonts.medium, ACCENT).apply { clearShadowLayer(); letterSpacing = 0.15f }
    private val songTitle = text(Fonts.bold, Color.WHITE).apply { clearShadowLayer() }

    /**
     * What plays now ([NowPlaying], looked up every [SONG_MS]) with its cover made small once, its place in the
     * track (moved on between lookups) and how far it has faded in.
     */
    private var song: NowPlaying? = null
    /** The last song shown, kept while it fades out. */
    private var shownSong: NowPlaying? = null
    private var songKey = ""
    private var songArt: Bitmap? = null
    private var songA = 0f
    private var songAt = 0L
    private var songPos = 0L
    private var songPosAt = 0L
    private var songDur = 0L
    private var songSpeed = 0f
    private val pollSong = object : Runnable {
        override fun run() {
            if (!isOpen) return
            val np = NowPlaying.current(context)?.takeIf { it.playing }
            if (np != null) {
                val key = np.title + "\u0000" + np.subtitle
                if (key != songKey) {
                    songKey = key
                    val s = (ART * k).toInt()
                    songArt = np.art?.let { a -> try { Bitmap.createScaledBitmap(a, s, s, true) } catch (e: Throwable) { null } }
                }
                val st = try { np.controller.playbackState } catch (e: Throwable) { null }
                songDur = try { np.controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L } catch (e: Throwable) { 0L }
                songPos = st?.position ?: -1L
                songPosAt = if (st != null && st.lastPositionUpdateTime > 0L) st.lastPositionUpdateTime else SystemClock.elapsedRealtime()
                songSpeed = if (st?.state == android.media.session.PlaybackState.STATE_PLAYING) st.playbackSpeed else 0f
                song = np
                shownSong = np
            } else if (song != null) song = null
            ui.postDelayed(this, SONG_MS)
        }
    }

    /** Small copies of the next photos, by [Shot.key], for "Up next". */
    private val thumbs = HashMap<String, Bitmap>()
    private var nextKeys: List<String> = emptyList()

    private val pollWeather = object : Runnable {
        override fun run() {
            if (!isOpen) return
            val ctx = context
            val g = gen
            Thread {
                val w = try { Weather.refresh(ctx) } catch (e: Throwable) { null }
                ui.post { if (g == gen && w != null) weather = w }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
            ui.postDelayed(this, WEATHER_MS)
        }
    }

    private val rect = RectF()
    private val inner = RectF()
    private val box = RectF()
    private val clip = Path()
    private val m = Matrix()
    private val shade = Paint()
    /** The gradients under the caption, made once for each frame's place and size. */
    private var shadeLandscape: Shader? = null
    private var shadePortrait: Shader? = null

    private fun text(face: android.graphics.Typeface, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; typeface = face
        setShadowLayer(12f * k, 0f, 2f * k, 0x59000000)
    }

    init {
        visibility = GONE
        isFocusable = true
    }

    /**
     * Shows the chosen source's photos. The list is read off the UI thread; with none to show, a screensaver that
     * started by itself closes again at once, and one opened by hand says why. The album is listed again (by hand
     * always, by itself when older than half an hour) while the cached photos already show.
     */
    fun open(auto: Boolean): Boolean {
        if (isOpen) return true
        val ctx = context
        val prefs = Prefs(ctx)
        if (prefs.screensaver == Screensaver.OFF) return false
        isOpen = true
        gaveUp = false
        val g = ++gen
        captions = prefs.screensaverCaptions
        showMs = prefs.screensaverSeconds * 1000L
        message = null
        shots = emptyList()
        openedAt = SystemClock.uptimeMillis()
        keepScreenOn = true
        ui.postDelayed(letSleep, KEEP_ON_MS)
        song = null; shownSong = null; songKey = ""; songArt = null; songA = 0f
        ui.post(pollSong)
        ui.post(pollWeather)
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(if (auto) 1500L else 300L).start()
        requestFocus()
        val synology = prefs.screensaver == Screensaver.SYNOLOGY
        exec.execute {
            val w = Weather.cached(ctx)
            ui.post { if (g == gen && weather == null) weather = w }
            val list = Screensaver.shots(ctx)
            // Started by hand, the album is always listed again: photos just added show at once.
            val stale = synology && (!auto || Synology.isStale(ctx))
            ui.post {
                if (!isOpen || g != gen) return@post
                if (list.isNotEmpty()) setShots(list)
                else if (auto || !stale) { empty(auto); return@post }
                else { message = "Loading photos…"; invalidate() }
            }
            if (!stale || !Synology.refresh(ctx)) {
                if (list.isEmpty() && stale) ui.post { if (isOpen && g == gen) empty(auto) }
                return@execute
            }
            val fresh = Screensaver.shots(ctx)
            ui.post {
                if (!isOpen || g != gen) return@post
                if (fresh.map { it.key } != shots.map { it.key }) setShots(fresh)
                if (shots.isEmpty()) empty(auto)
            }
        }
        return true
    }

    /** Nothing to show: closes at once when it started by itself, else says so for a moment. */
    private fun empty(auto: Boolean) {
        if (auto) { gaveUp = true; close(instant = true); return }
        message = if (Prefs(context).screensaver == Screensaver.SYNOLOGY) "No photos in the album, or the NAS could not be reached"
            else "No photos in Pictures/Glass"
        invalidate()
        val g = gen
        ui.postDelayed({ if (g == gen) close() }, 3500)
    }

    fun close(instant: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        gen++
        ui.removeCallbacksAndMessages(null)
        keepScreenOn = false
        val done = {
            visibility = GONE
            cur = null; prev = null; shots = emptyList(); pos = -1; loading = false
            thumbs.clear(); nextKeys = emptyList()
            home.onScreensaverClosed()
        }
        if (instant) { animate().cancel(); done(); return }
        animate().alpha(0f).setDuration(300).withEndAction { done() }.start()
    }

    fun release() {
        isOpen = false
        gen++
        ui.removeCallbacksAndMessages(null)
        exec.shutdownNow()
    }

    private val letSleep = Runnable { keepScreenOn = false }

    /** A new list keeps the photo on screen and shuffles the rest. */
    private fun setShots(list: List<Shot>) {
        val showing = cur?.shot?.key
        shots = list
        val idx = list.indices.shuffled().toMutableList()
        val at = list.indexOfFirst { it.key == showing }
        if (at >= 0) { idx.remove(at); idx.add(0, at) }
        order = idx.toIntArray()
        pos = if (at >= 0) 0 else -1
        if (list.isNotEmpty() && message == "Loading photos…") message = null
        if (cur == null && list.isNotEmpty() && !loading) advance(1)
        else loadThumbs()
    }

    /** Loads the next (or previous) photo off the UI thread; it fades in when ready. */
    private fun advance(dir: Int) {
        if (!isOpen || loading || shots.isEmpty()) return
        loading = true
        val g = gen
        val list = shots
        val ord = order
        val n = list.size
        val start = pos
        exec.execute {
            // Skip photos that will not load (a download failed), but try each once at most.
            var p = start
            var f: Frame? = null
            for (tries in 0 until minOf(n, 5)) {
                p = ((p + dir) % n + n) % n
                val s = list.getOrNull(ord.getOrNull(p) ?: break) ?: break
                val file = try { s.fetch() } catch (e: Throwable) { null } ?: continue
                val sharp = Screensaver.decode(file, s.rotate) ?: continue
                val blurred = try { Blur.makeBackdrop(sharp) } catch (e: Throwable) { continue }
                f = Frame(s, p, sharp, blurred, 0L)
                break
            }
            val got = f
            ui.post {
                if (g != gen || !isOpen) return@post
                loading = false
                if (list !== shots) { advance(dir); return@post }
                if (got == null) {
                    if (cur == null) { message = "The photos could not be loaded"; invalidate() }
                    ui.postDelayed({ if (g == gen) advance(1) }, RETRY_MS)
                    return@post
                }
                pos = got.n
                show(got)
                loadThumbs()
            }
        }
    }

    /** "Up next": small copies of the next three photos in the order, loaded off the UI thread. */
    private fun loadThumbs() {
        val list = shots
        val n = list.size
        if (n < 2 || pos < 0) { nextKeys = emptyList(); return }
        val next = (1..minOf(UP_NEXT, n - 1)).mapNotNull { i -> list.getOrNull(order.getOrNull((pos + i) % n) ?: -1) }
        nextKeys = next.map { it.key }
        thumbs.keys.retainAll(nextKeys.toSet() + listOfNotNull(cur?.shot?.key))
        val g = gen
        val side = (THUMB_W * 2 * k).toInt()
        for (s in next) {
            if (thumbs.containsKey(s.key)) continue
            exec.execute {
                val b = try { s.fetch()?.let { Screensaver.decode(it, s.rotate, side) } } catch (e: Throwable) { null }
                if (b != null) ui.post { if (g == gen && s.key in nextKeys) thumbs[s.key] = b }
            }
        }
    }

    private fun show(f: Frame) {
        val now = SystemClock.uptimeMillis()
        prev = cur
        cur = Frame(f.shot, f.n, f.sharp, f.blurred, now)
        fadeStart = now
        message = null
        invalidate()
    }

    // ---------- Drawing ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val now = SystemClock.uptimeMillis()
        tick(now)
        val cf = cur
        if (cf == null) {
            message?.let { c.drawText(it, (sw - messageText.measureText(it)) / 2, sh / 2f, messageText) }
            return
        }
        val fade = if (prev == null) 1f else ((now - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (fade >= 1f) prev = null
        // The background: the photo's own blur, dimmed a little so the frame and the text stand out.
        prev?.let { drawBackground(c, it, 1f) }
        drawBackground(c, cf, fade)
        fill.color = 0x40000000
        c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fill)

        val dx = drift(now, 0.006f, 211.0, 0.0) * sw
        val dy = drift(now, 0.006f, 157.0, 1.3) * sh
        c.save()
        c.translate(dx, dy)
        prev?.let { drawCard(c, it, 1f - fade, now) }
        drawCard(c, cf, fade, now)
        drawColumn(c, now)
        c.restore()

        // The next photo, in time to fade in when this one's time is up.
        if (!loading && now - cf.start >= showMs - FADE_MS && fade >= 1f) advance(1)
        if (isOpen) postInvalidateOnAnimation()
    }

    private fun tick(now: Long) {
        if (now - timeAt < 1000) return
        timeAt = now
        val t = System.currentTimeMillis()
        time = android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", t).toString()
        date = android.text.format.DateFormat.format("EEEE, MMMM d", t).toString()
    }

    private fun drawBackground(c: Canvas, f: Frame, a: Float) {
        val b = f.blurred
        val s = maxOf(sw / b.width.toFloat(), sh / b.height.toFloat())
        m.setScale(s, s)
        m.postTranslate((sw - b.width * s) / 2f, (sh - b.height * s) / 2f)
        bmp.alpha = (a * 255).toInt()
        c.drawBitmap(b, m, bmp)
        bmp.alpha = 255
    }

    /**
     * A frosted card: a light translucent fill and a thin bright edge, over a soft shadow when [shadowA] is above 0.
     * The shadow skips the card's middle, so only a card whose middle is covered (the photo's frame) may have one:
     * through a bare card the skipped part would show as a lighter patch.
     */
    private fun glass(c: Canvas, r: RectF, radius: Float, a: Float, shadowA: Float = 0f) {
        if (shadowA > 0f) {
            c.save()
            c.translate(r.left, r.top)
            GlowCache.draw(c, r.width(), r.height(), radius, 28f * k, Color.BLACK, shadowA * a, 10f * k, shadow, hollow = true)
            c.restore()
        }
        fill.color = alphaOf(Color.WHITE, 0.16f * a)
        c.drawRoundRect(r, radius, radius, fill)
        stroke.strokeWidth = 1.5f * k
        stroke.color = alphaOf(Color.WHITE, 0.32f * a)
        c.drawRoundRect(r, radius, radius, stroke)
    }

    /** One photo in its frosted frame, at [a] (0..1) while photos crossfade. It fills the frame and zooms in slowly. */
    private fun drawCard(c: Canvas, f: Frame, a: Float, now: Long) {
        if (a <= 0.01f) return
        val cr = if (f.portrait) portraitCard else card
        rect.set(cr.left * k, cr.top * k, cr.right * k, cr.bottom * k)
        glass(c, rect, 48f * k, a, 0.5f)
        val inset = 21f * k
        inner.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        val r = 30f * k
        val b = f.sharp
        val p = ((now - f.start) / (showMs + FADE_MS).toFloat()).coerceIn(0f, 1f)
        val e = p * p * (3 - 2 * p) * 0.3f + p * 0.7f
        val s = maxOf(inner.width() / b.width, inner.height() / b.height) * (1f + ZOOM * e)
        f.sharpM.setScale(s, s)
        val px = if (f.n % 2 == 0) 0.4f else 0.6f
        f.sharpM.postTranslate(inner.left + (inner.width() - b.width * s) * px, inner.top + (inner.height() - b.height * s) / 2f)
        clip.reset()
        clip.addRoundRect(inner, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        bmp.alpha = (a * 255).toInt()
        c.drawBitmap(b, f.sharpM, bmp)
        bmp.alpha = 255
        if (captions && f.shot.title.isNotEmpty()) {
            // The caption at the bottom left, over a dark gradient so it reads on any photo.
            val top = inner.bottom - inner.height() * SHADE
            val made = if (f.portrait) shadePortrait else shadeLandscape
            shade.shader = made ?: LinearGradient(0f, top, 0f, inner.bottom, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
                .also { if (f.portrait) shadePortrait = it else shadeLandscape = it }
            shade.alpha = (a * 255).toInt()
            c.drawRect(inner.left, top, inner.right, inner.bottom, shade)
            val x = inner.left + 40f * k
            val maxW = inner.width() - 80f * k
            val base = inner.bottom / k - 40f
            if (f.shot.subtitle.isEmpty()) draw(c, f.shot.title, titleText, 34f, x, base, maxW, a)
            else {
                draw(c, f.shot.title, titleText, 34f, x, base - 38f, maxW, a)
                draw(c, f.shot.subtitle, subText, 24f, x, base, maxW, a)
            }
        }
        c.restore()
    }

    /**
     * The column at the right, top to bottom: time, date, weather, what plays now and "Up next". A part with nothing
     * to show is left out and the rest close up.
     */
    private fun drawColumn(c: Canvas, now: Long) {
        val x = COL_X * k
        val w = COL_W * k
        draw(c, time, clockText, 190f, x - 190f * 0.04f * k, 268f, w, 1f)
        draw(c, date, dateText, 36f, x, 351f, w, 1f)
        var top = 405f

        weather?.let { wx ->
            box.set(x, top * k, x + w, (top + 102f) * k)
            glass(c, box, 24f * k, 1f)
            val icon = 52f * k
            Weather.drawIcon(c, wx.code, wx.day, x + 28f * k, box.centerY() - icon / 2, icon, 1f)
            draw(c, "${wx.temp}°", tempText, 52f, x + 100f * k, top + 70f, 100f * k, 1f)
            val tx = x + 196f * k
            draw(c, wx.label, cardTitle, 22f, tx, top + 46f, x + w - tx - 24f * k, 1f)
            val detail = listOf("H ${wx.high}° · L ${wx.low}°", wx.city).filter { it.isNotEmpty() }.joinToString(" · ")
            draw(c, detail, cardSub, 18f, tx, top + 72f, x + w - tx - 24f * k, 1f)
            top += 102f + 58f
        }

        drawSong(c, top, now)?.let { top = it + 41f }

        if (nextKeys.isNotEmpty()) {
            draw(c, "UP NEXT", labelText, 18f, x, top + 13f, w, 1f)
            val ty = top + 33f
            for ((i, key) in nextKeys.withIndex()) {
                box.set(x + i * (THUMB_W + 15f) * k, ty * k, x + (i * (THUMB_W + 15f) + THUMB_W) * k, (ty + THUMB_H) * k)
                val r = 10f * k
                val t = thumbs[key]
                if (t != null) {
                    val s = maxOf(box.width() / t.width, box.height() / t.height)
                    m.setScale(s, s)
                    m.postTranslate(box.left + (box.width() - t.width * s) / 2f, box.top + (box.height() - t.height * s) / 2f)
                    clip.reset()
                    clip.addRoundRect(box, r, r, Path.Direction.CW)
                    c.save()
                    c.clipPath(clip)
                    c.drawBitmap(t, m, bmp)
                    c.restore()
                } else {
                    fill.color = alphaOf(Color.WHITE, 0.12f)
                    c.drawRoundRect(box, r, r, fill)
                }
                stroke.strokeWidth = 1.5f * k
                stroke.color = alphaOf(Color.WHITE, 0.3f)
                c.drawRoundRect(box, r, r, stroke)
            }
        }
    }

    /**
     * What plays now, fading in and out: a frosted card with the cover at its left, a "Now playing" label, the title
     * and artist, and how far into the track it is. Returns where it ends (1080p units), or null when nothing shows.
     */
    private fun drawSong(c: Canvas, top: Float, now: Long): Float? {
        val dt = (now - songAt).coerceIn(0L, 100L)
        songAt = now
        songA = if (song != null) minOf(1f, songA + dt / 600f) else maxOf(0f, songA - dt / 600f)
        val np = shownSong ?: return null
        if (songA <= 0f) return null
        val a = songA
        val x = COL_X * k
        box.set(x, top * k, x + COL_W * k, (top + 134f) * k)
        glass(c, box, 24f * k, a)
        val art = ART * k
        val ax = x + 21f * k
        val ay = box.top + 19f * k
        rect.set(ax, ay, ax + art, ay + art)
        val ar = 14f * k
        val cover = songArt
        if (cover != null) {
            clip.reset()
            clip.addRoundRect(rect, ar, ar, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            bmp.alpha = (a * 255).toInt()
            c.drawBitmap(cover, null, rect, bmp)
            bmp.alpha = 255
            c.restore()
        } else {
            fill.color = alphaOf(Color.WHITE, 0.16f * a)
            c.drawRoundRect(rect, ar, ar, fill)
        }
        val tx = rect.right + 18f * k
        val tw = box.right - 21f * k - tx
        draw(c, "NOW PLAYING", songLabel, 13f, tx, top + 36f, tw, a)
        draw(c, np.title, songTitle, 22f, tx, top + 65f, tw, a)
        if (np.subtitle.isNotEmpty()) draw(c, np.subtitle, cardSub, 17f, tx, top + 92f, tw, a)
        // The progress bar, moved on from the last lookup while it plays; none for live streams.
        if (songDur > 0L && songPos >= 0L) {
            val at = songPos + ((SystemClock.elapsedRealtime() - songPosAt) * songSpeed).toLong()
            val p = (at.toFloat() / songDur).coerceIn(0f, 1f)
            val y = (top + 108f) * k
            val h = 4f * k
            fill.color = alphaOf(Color.WHITE, 0.3f * a)
            c.drawRoundRect(tx, y, tx + tw, y + h, h / 2, h / 2, fill)
            fill.color = alphaOf(Color.WHITE, 0.95f * a)
            c.drawRoundRect(tx, y, tx + tw * p, y + h, h / 2, h / 2, fill)
        }
        return top + 134f
    }

    /** [s] at [x] on baseline [y] (in 1080p units), [size] in 1080p units, cut to [maxW], at [a]. */
    private fun draw(c: Canvas, s: String, p: TextPaint, size: Float, x: Float, y: Float, maxW: Float, a: Float) {
        if (a <= 0.01f || s.isEmpty()) return
        p.textSize = size * k
        val was = p.alpha
        p.alpha = (was * a).toInt()
        c.drawText(TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString(), x, y * k, p)
        p.alpha = was
    }

    /** A slow sine, as a share of the screen: [amount] at most, one loop every [period] seconds. */
    private fun drift(now: Long, amount: Float, period: Double, phase: Double) =
        (amount * sin(2 * PI * (now - openedAt) / 1000.0 / period + phase)).toFloat()

    // ---------- Keys ----------

    /** Left and Right step through the photos; any other key closes (Back, Home and Menu close through the home). */
    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (cur != null && prev == null) advance(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME -> return super.onKeyDown(keyCode, e)
        }
        close()
        return true
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean =
        keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_MENU || super.onKeyUp(keyCode, e)

    companion object {
        private const val FADE_MS = 1500L
        private const val RETRY_MS = 30_000L
        /** How far a photo zooms in, inside its frame, while it shows. */
        private const val ZOOM = 0.05f
        /** Share of the photo's height darkened at the bottom, under the caption. */
        private const val SHADE = 0.32f
        /** After this long the screen may sleep as the TV's settings say; the photos keep going until then. */
        private const val KEEP_ON_MS = 2 * 3600_000L
        /** The right column's left edge and width, in 1080p units. */
        private const val COL_X = 1360f
        private const val COL_W = 480f
        /** The cover's side, and how often what plays is looked up. */
        private const val ART = 96f
        private const val SONG_MS = 2000L
        private const val WEATHER_MS = 30 * 60_000L
        private const val UP_NEXT = 3
        private const val THUMB_W = 137f
        private const val THUMB_H = 88f
        private const val ACCENT = 0xFFF0A955.toInt()
    }
}
