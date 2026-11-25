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
    val MINUTES = listOf(5, 10, 15, 30, 60)
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
     * Decodes a photo at most [MAX_SIDE] on its long side, upright (phone photos carry their rotation in EXIF; the
     * NAS's thumbnails are already upright). Call off the UI thread.
     */
    fun decode(f: File, rotate: Boolean): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > MAX_SIDE) sample *= 2
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
 * The full-screen screensaver: the photo as a large rounded card over a darkened blur of itself, with the time at the
 * bottom left and the caption at the bottom right, inside the photo over a soft dark gradient. A landscape photo
 * fills the card and zooms in slowly; a portrait one fills its width and pans slowly from top to bottom (or bottom to
 * top), with a slim bar at the right showing how far it has come. Photos crossfade.
 *
 * Starts by itself after [Prefs.screensaverMinutes] without a key on the home, or from Control Center. Left and
 * Right step through the photos; any other key closes it. Everything but the background drifts a few pixels a minute
 * (OLED burn-in). The screen is kept on for up to [KEEP_ON_MS]; after that the TV sleeps as it would anyway.
 *
 * Each frame draws the blur once (small, scaled up), a cached shadow, the photo once, a gradient and text: cheap
 * enough for the TV's GPU, and nothing redraws behind it (the home is paused and covered).
 */
class ScreensaverView(private val home: HomeScreen) : View(home.ctx) {
    var isOpen = false
        private set

    private class Frame(val shot: Shot, val n: Int, val sharp: Bitmap, val blurred: Bitmap, val start: Long) {
        val portrait = sharp.height > sharp.width * 1.05f
        val sharpM = Matrix()
    }

    /** The card, on a 1920 x 1080 screen (scaled by [k]). */
    private val card = RectF(97f, 71f, 1823f, 1009f)

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
    /** How long each landscape photo shows ([Prefs.screensaverSeconds]); a portrait one pans for half as long again. */
    private var showMs = 15_000L
    private var time = ""
    private var date = ""
    private var timeAt = 0L

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint()
    private val shade = Paint()
    private val clockText = text(Fonts.light, Color.WHITE)
    private val dateText = text(Fonts.regular, 0xF2FFFFFF.toInt())
    private val titleText = text(Fonts.medium, Color.WHITE)
    private val subText = text(Fonts.regular, 0xE6FFFFFF.toInt())
    private val messageText = text(Fonts.regular, 0xD9FFFFFF.toInt()).apply { textSize = 26f * k }
    private val rect = RectF()
    private val bar = RectF()
    private val clip = Path()
    private val m = Matrix()
    /** The gradient under the text, made once at the card's place and size. */
    private var bottomShade: Shader? = null

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
        val g = ++gen
        captions = prefs.screensaverCaptions
        showMs = prefs.screensaverSeconds * 1000L
        message = null
        shots = emptyList()
        openedAt = SystemClock.uptimeMillis()
        keepScreenOn = true
        ui.postDelayed(letSleep, KEEP_ON_MS)
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(if (auto) 1500L else 300L).start()
        requestFocus()
        val synology = prefs.screensaver == Screensaver.SYNOLOGY
        exec.execute {
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
        if (auto) { close(instant = true); return }
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
        // The background: the photo's own blur, darkened so the card stands out.
        prev?.let { drawBackground(c, it, 1f) }
        drawBackground(c, cf, fade)
        fill.color = 0x59000000
        c.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fill)

        val dx = drift(now, 0.006f, 211.0, 0.0) * sw
        val dy = drift(now, 0.006f, 157.0, 1.3) * sh
        c.save()
        c.translate(dx, dy)
        prev?.let { drawCard(c, it, 1f - fade, now) }
        drawCard(c, cf, fade, now)
        c.restore()

        // The next photo, in time to fade in when this one's time is up.
        if (!loading && now - cf.start >= duration(cf) - FADE_MS && fade >= 1f) advance(1)
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

    /** Portrait photos pan the length of the photo, so they stay longer and the pan stays slow. */
    private fun duration(f: Frame) = if (f.portrait) showMs * 3 / 2 else showMs

    /** One photo's card with its time and caption, at [a] (0..1) while photos crossfade. */
    private fun drawCard(c: Canvas, f: Frame, a: Float, now: Long) {
        if (a <= 0.01f) return
        rect.set(card.left * k, card.top * k, card.right * k, card.bottom * k)
        val r = 44f * k
        // A soft shadow under the card (pre-blurred once; the card hides its middle).
        c.save()
        c.translate(rect.left, rect.top)
        GlowCache.draw(c, rect.width(), rect.height(), r, 40f * k, Color.BLACK, 0.55f * a, 14f * k, shadow, hollow = true)
        c.restore()
        // Landscape: the photo fills the card and zooms in slowly. Portrait: it fills the width and pans its length,
        // downwards and upwards in turn.
        val b = f.sharp
        val p = ((now - f.start) / (duration(f) + FADE_MS).toFloat()).coerceIn(0f, 1f)
        val e = p * p * (3 - 2 * p) * 0.3f + p * 0.7f
        var travel = 0f
        var pan = 0f
        if (f.portrait) {
            val s = rect.width() / b.width
            travel = (b.height * s - rect.height()).coerceAtLeast(0f)
            pan = if (f.n % 2 == 0) e else 1f - e
            f.sharpM.setScale(s, s)
            f.sharpM.postTranslate(rect.left, rect.top - travel * pan)
        } else {
            val s = maxOf(rect.width() / b.width, rect.height() / b.height) * (1f + ZOOM * e)
            f.sharpM.setScale(s, s)
            val px = if (f.n % 2 == 0) 0.4f else 0.6f
            f.sharpM.postTranslate(rect.left + (rect.width() - b.width * s) * px, rect.top + (rect.height() - b.height * s) / 2f)
        }
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        bmp.alpha = (a * 255).toInt()
        c.drawBitmap(b, f.sharpM, bmp)
        bmp.alpha = 255
        // A dark gradient where the text sits.
        val top = rect.bottom - rect.height() * SHADE
        shade.shader = bottomShade ?: LinearGradient(0f, top, 0f, rect.bottom, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
            .also { bottomShade = it }
        shade.alpha = (a * 255).toInt()
        c.drawRect(rect.left, top, rect.right, rect.bottom, shade)
        c.restore()
        stroke.strokeWidth = 1.5f * k
        stroke.color = alphaOf(Color.WHITE, 0.30f * a)
        c.drawRoundRect(rect, r, r, stroke)
        if (travel > 0f) drawPanBar(c, rect.height() / (rect.height() + travel), pan, a)

        // The time and date at the bottom left, the caption at the bottom right: half the card each.
        val maxW = rect.width() / 2f - 80f * k
        draw(c, time, clockText, 118f, 160f * k - 118f * 0.04f * k, 905f, maxW, a, false)
        draw(c, date, dateText, 26f, 160f * k, 948f, maxW, a, false)
        if (captions && f.shot.title.isNotEmpty()) {
            draw(c, f.shot.title, titleText, 30f, 1760f * k, 915f, maxW, a, true)
            if (f.shot.subtitle.isNotEmpty()) draw(c, f.shot.subtitle, subText, 22f, 1760f * k, 950f, maxW, a, true)
        }
    }

    /** The pan's progress: a slim track at the card's right, the part of the photo on screen bright on it. */
    private fun drawPanBar(c: Canvas, visible: Float, pan: Float, a: Float) {
        val x = 1780f * k
        val top = 390f * k
        val len = 300f * k
        val w = 6f * k
        fill.color = alphaOf(Color.WHITE, 0.25f * a)
        bar.set(x - w / 2, top, x + w / 2, top + len)
        c.drawRoundRect(bar, w / 2, w / 2, fill)
        val tl = (len * visible).coerceAtLeast(w * 4)
        val ty = top + (len - tl) * pan
        fill.color = alphaOf(Color.WHITE, 0.92f * a)
        bar.set(x - w / 2, ty, x + w / 2, ty + tl)
        c.drawRoundRect(bar, w / 2, w / 2, fill)
    }

    /** [s] at [x] (its right edge when [right]) on baseline [y] (in 1080p units), cut to [maxW]. */
    private fun draw(c: Canvas, s: String, p: TextPaint, size: Float, x: Float, y: Float, maxW: Float, a: Float, right: Boolean) {
        p.textSize = size * k
        p.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
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
        /** Share of the card's height darkened at the bottom, under the time and the caption. */
        private const val SHADE = 0.38f
        /** After this long the screen may sleep as the TV's settings say; the photos keep going until then. */
        private const val KEEP_ON_MS = 2 * 3600_000L
    }
}
