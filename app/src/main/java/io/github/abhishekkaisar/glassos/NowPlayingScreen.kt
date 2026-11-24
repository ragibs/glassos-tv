package io.github.abhishekkaisar.glassos

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen Now Playing: the album art large on a blur of itself, the title, artist and album, and the progress,
 * tinted with the art's colour. Opened from Control Center, or by itself when music plays and the home has been left
 * alone for [HomeScreen.NOW_PLAYING_IDLE_MS]. No buttons: OK opens the playing app's own player, Back closes.
 *
 * Made to be left on: a new song fades in, the text and art drift a few pixels a second so nothing stays in one
 * place (OLED burn-in), and after [DIM_MS] without a key the screen dims. While something plays it keeps the screen
 * on (no screensaver); paused, the TV sleeps as usual.
 */
class NowPlayingScreen(private val home: HomeScreen) : FrameLayout(home.ctx) {
    var isOpen = false
        private set

    private val ui = Handler(Looper.getMainLooper())
    private val sw = Backdrop.screenW
    private val sh = Backdrop.screenH
    private val artSize = sh * 50 / 100
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val art = ArtView(context, artSize, sh * 0.022f)
    private val app = text(0.022f, LABEL, Fonts.medium).apply { letterSpacing = 0.08f }
    private val title = text(0.054f, Color.WHITE, Fonts.medium).apply { maxLines = 2 }
    private val artist = text(0.032f, 0xD9FFFFFF.toInt(), Fonts.regular)
    private val album = text(0.026f, 0x8CFFFFFF.toInt(), Fonts.regular)
    /** Its time labels at 2.4% of the screen height (ProgressLine sizes itself in dp, 11 at scale 1). */
    private val progress = ProgressLine(context, sh * 0.024f / (11f * resources.displayMetrics.density))
    /** Black over everything, faded in to dim the screen after [DIM_MS] without a key. */
    private val dim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }

    private var watched: MediaController? = null
    private var pkg: String? = null
    private var icon: android.graphics.drawable.Drawable? = null
    /** The song shown (title and artist); another one fades in. */
    private var song: String? = null
    /** The newest state while the old song fades out. */
    private var pending: NowPlaying? = null
    /** The art shown and its blur; a new song's blur and colour are made off the UI thread. */
    private var shownArt: Bitmap? = null
    private var blurred: Bitmap? = null
    /** The previous song's blur, under the new one while it fades in ([fade] 0..1). */
    private var oldBlur: Bitmap? = null
    private var fade = 1f
    private var fadeAnim: ValueAnimator? = null
    private var artGen = 0
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bgMatrix = Matrix()
    private var openedAt = 0L
    private var lastKey = 0L
    private var dimmed = false
    /**
     * Set while the app's player starts: the view stays up until the home is hidden ([onHidden]), so the home does
     * not show between this screen and the player.
     */
    private var launching = false
    /** The player never came up: close anyway. */
    private val giveUp = Runnable { if (launching) close(instant = true) }

    private val sessionCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { ui.post { refresh(false) } }
        override fun onMetadataChanged(metadata: MediaMetadata?) { ui.post { refresh(false) } }
        override fun onSessionDestroyed() { ui.post { refresh(true) } }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            updateProgress()
            drift()
            if (!dimmed && SystemClock.uptimeMillis() - lastKey > DIM_MS) {
                dimmed = true
                dim.animate().alpha(DIM_ALPHA).setDuration(4000).start()
            }
            ui.postDelayed(this, 1000)
        }
    }

    init {
        visibility = View.GONE
        setWillNotDraw(false)
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        row.addView(art, LinearLayout.LayoutParams(artSize, artSize))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(app)
        col.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = sh * 14 / 1080 })
        col.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = sh * 10 / 1080 })
        col.addView(album, LinearLayout.LayoutParams(-1, -2).apply { topMargin = sh * 6 / 1080 })
        col.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = sh * 48 / 1080 })
        row.addView(col, LinearLayout.LayoutParams(sw * 40 / 100, -2).apply { leftMargin = sw * 64 / 1920 })
        addView(row, LayoutParams(-2, -2, Gravity.CENTER))
        addView(dim, LayoutParams(-1, -1))
    }

    private fun text(size: Float, color: Int, face: android.graphics.Typeface) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * size)
        setTextColor(color)
        typeface = face
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Opens on what plays now; does nothing when nothing does. Opened by itself ([auto]) it fades in slowly. */
    fun open(auto: Boolean = false): Boolean {
        if (isOpen) return true
        val now = NowPlaying.current(context) ?: return false
        isOpen = true
        openedAt = SystemClock.uptimeMillis()
        lastKey = openedAt
        dimmed = false
        dim.animate().cancel(); dim.alpha = 0f
        row.animate().cancel(); row.alpha = 1f
        row.translationX = 0f; row.translationY = 0f
        watch(now.controller)
        show(now)
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(if (auto) 1500L else 260L).start()
        requestFocus()
        ui.removeCallbacks(tick); ui.post(tick)
        return true
    }

    fun close(instant: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        launching = false
        ui.removeCallbacks(tick)
        ui.removeCallbacks(giveUp)
        unwatch()
        keepScreenOn = false
        val done = {
            visibility = View.GONE
            artGen++
            fadeAnim?.cancel(); fadeAnim = null
            row.animate().cancel()
            shownArt = null; blurred = null; oldBlur = null; pkg = null; icon = null; song = null; pending = null
            progress.accent = ACCENT
            app.setTextColor(LABEL)
            home.onNowPlayingClosed()
        }
        if (instant) { animate().cancel(); done(); return }
        animate().alpha(0f).setDuration(200).withEndAction { done() }.start()
    }

    fun release() {
        isOpen = false
        ui.removeCallbacksAndMessages(null)
        fadeAnim?.cancel()
        unwatch()
    }

    private fun watch(c: MediaController) {
        unwatch()
        watched = c
        try { c.registerCallback(sessionCb, ui) } catch (e: Throwable) { }
    }

    private fun unwatch() {
        try { watched?.unregisterCallback(sessionCb) } catch (e: Throwable) { }
        watched = null
    }

    /** Follows the session playing now; closes when the one shown [ended] and none is left. */
    private fun refresh(ended: Boolean) {
        if (!isOpen) return
        val now = NowPlaying.current(context)
        if (now == null) {
            if (ended) close()
            return
        }
        if (now.controller.sessionToken != watched?.sessionToken) watch(now.controller)
        show(now)
    }

    /** A new song fades out the old one and fades in; anything else (art arriving, play/pause) shows at once. */
    private fun show(now: NowPlaying) {
        val key = now.pkg + "\n" + now.title + "\n" + now.subtitle
        if (song == null || key == song) { song = key; fill(now); return }
        if (pending != null) { pending = now; return }
        pending = now
        row.animate().alpha(0f).setDuration(250).withEndAction {
            val p = pending ?: return@withEndAction
            pending = null
            song = p.pkg + "\n" + p.title + "\n" + p.subtitle
            fill(p)
            row.animate().alpha(1f).setDuration(450).start()
        }.start()
    }

    private fun fill(now: NowPlaying) {
        if (pkg != now.pkg) {
            pkg = now.pkg
            val pm = context.packageManager
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(now.pkg, 0)).toString() } catch (e: Throwable) { now.pkg }
            app.tag = label.uppercase()
            icon = try { pm.getApplicationIcon(now.pkg) } catch (e: Throwable) { null }
        }
        app.text = if (now.playing) app.tag as String else "${app.tag} · PAUSED"
        title.text = now.title
        artist.text = now.subtitle
        artist.visibility = if (now.subtitle.isEmpty()) View.GONE else View.VISIBLE
        val alb = now.controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            // A single's album has the song's name: shown once is enough.
            ?.takeIf { it.isNotBlank() && it != now.subtitle && !it.equals(now.title, ignoreCase = true) }
        album.text = alb ?: ""
        album.visibility = if (alb == null) View.GONE else View.VISIBLE
        art.set(now.art, icon)
        setArt(now.art)
        keepScreenOn = now.playing
        updateProgress()
    }

    /** A new song's art: its blur and colour are made once, off the UI thread, then the background crossfades. */
    private fun setArt(a: Bitmap?) {
        if (a === shownArt) return
        shownArt = a
        val gen = ++artGen
        if (a == null) { crossfade(null, ACCENT); return }
        Thread {
            val b = try { Blur.makeBackdrop(a) } catch (e: Throwable) { null }
            val c = try { accentOf(a) } catch (e: Throwable) { ACCENT }
            ui.post { if (gen == artGen && isOpen) crossfade(b, c) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun crossfade(b: Bitmap?, accent: Int) {
        progress.accent = accent
        app.setTextColor(if (accent == ACCENT) LABEL else accent)
        fadeAnim?.cancel()
        oldBlur = blurred
        blurred = b
        fade = 0f
        fadeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener {
                fade = it.animatedValue as Float
                if (fade >= 1f) oldBlur = null
                invalidate()
            }
            start()
        }
    }

    /** Elapsed and remaining time, ticking while it plays. Hidden for live streams and apps that give no duration. */
    private fun updateProgress() {
        val st = watched?.playbackState
        val dur = watched?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (st == null || dur <= 0L || st.position < 0L) {
            progress.visibility = View.INVISIBLE
            return
        }
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0L) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        progress.visibility = View.VISIBLE
        progress.set(pos.coerceIn(0L, dur), dur)
    }

    /**
     * Moves the art and text along a slow loop (two sines of different periods), at most about two pixels a second,
     * in whole pixels once a second: cheap, and too slow to notice, but nothing stays put for hours.
     */
    private fun drift() {
        val t = (SystemClock.uptimeMillis() - openedAt) / 1000.0
        row.translationX = (sw * DRIFT_X * sin(2 * PI * t / 173.0)).roundToInt().toFloat()
        row.translationY = (sh * DRIFT_Y * sin(2 * PI * t / 127.0 + 1.0)).roundToInt().toFloat()
    }

    override fun onDraw(c: Canvas) {
        // During a crossfade the old background is drawn first, the new one over it at [fade].
        if (fade < 1f) oldBlur?.let { drawBlur(c, it, 255) } ?: c.drawColor(NIGHT)
        val a = (fade * 255).toInt()
        blurred?.let { drawBlur(c, it, a) } ?: c.drawColor(Color.argb(a, Color.red(NIGHT), Color.green(NIGHT), Color.blue(NIGHT)))
        c.drawColor(SCRIM)
    }

    /** The blur fills the screen (centre crop), dimmed by [SCRIM] so the text stays readable on bright art. */
    private fun drawBlur(c: Canvas, b: Bitmap, alpha: Int) {
        val s = maxOf(width / b.width.toFloat(), height / b.height.toFloat())
        bgMatrix.setScale(s, s)
        bgMatrix.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
        bgPaint.alpha = alpha
        c.drawBitmap(b, bgMatrix, bgPaint)
    }

    /** Any key wakes a dimmed screen. OK opens the app's player; arrows do nothing (they would move focus behind). */
    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        lastKey = SystemClock.uptimeMillis()
        if (dimmed) {
            dimmed = false
            dim.animate().alpha(0f).setDuration(400).start()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> super.onKeyDown(keyCode, e)
        }
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !e.isCanceled) {
            openPlayer()
            return true
        }
        return super.onKeyUp(keyCode, e)
    }

    /** The app's own player (the session's activity), else the app. This screen closes once it is out of sight. */
    private fun openPlayer() {
        if (launching) return
        val c = watched ?: return
        val p = c.packageName
        val pi = c.sessionActivity
        launching = true
        ui.removeCallbacks(giveUp)
        ui.postDelayed(giveUp, LAUNCH_MS)
        if (pi != null) home.startPending(pi, p) else home.launch(p, null)
    }

    /** The home went behind another app: a player opened from here has taken over, so close out of sight. */
    fun onHidden() {
        if (launching) close(instant = true)
    }

    companion object {
        private const val NIGHT = 0xFF101018.toInt()
        private const val SCRIM = 0x8C000000.toInt()
        /** The progress bar's and the app name's colours when the art gives none (grey or no art). */
        private const val ACCENT = 0xE6FFFFFF.toInt()
        private const val LABEL = 0x99FFFFFF.toInt()
        /** After this long without a key, the screen dims to [DIM_ALPHA] black. */
        private const val DIM_MS = 15 * 60_000L
        private const val DIM_ALPHA = 0.55f
        /** How far the art and text drift, as a share of the screen. */
        private const val DRIFT_X = 0.025
        private const val DRIFT_Y = 0.03
        /** How long the app's player may take to come up before this screen closes anyway. */
        private const val LAUNCH_MS = 4000L

        /**
         * The art's main colour, lifted to read on the dark screen: the hue with the most colourful pixels (12
         * buckets, weighted by saturation and brightness), averaged, then given at least 90% brightness. Grey or
         * nearly colourless art gives [ACCENT] (white).
         */
        fun accentOf(a: Bitmap): Int {
            val n = 32
            val small = Bitmap.createScaledBitmap(a, n, n, true)
            val px = IntArray(n * n)
            small.getPixels(px, 0, n, 0, 0, n, n)
            if (small !== a) small.recycle()
            val weight = FloatArray(12)
            val sumR = FloatArray(12); val sumG = FloatArray(12); val sumB = FloatArray(12)
            val hsv = FloatArray(3)
            for (c in px) {
                Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
                val k = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                val w = hsv[1] * hsv[2]
                weight[k] += w
                sumR[k] += Color.red(c) * w; sumG[k] += Color.green(c) * w; sumB[k] += Color.blue(c) * w
            }
            val best = weight.indices.maxByOrNull { weight[it] } ?: return ACCENT
            // Under about 6% of the art clearly coloured: keep white rather than tint with a stray pixel.
            if (weight[best] < n * n * 0.06f * 0.5f) return ACCENT
            val w = weight[best]
            Color.colorToHSV(Color.rgb((sumR[best] / w).toInt(), (sumG[best] / w).toInt(), (sumB[best] / w).toInt()), hsv)
            hsv[1] = hsv[1].coerceIn(0.35f, 0.7f)
            hsv[2] = maxOf(hsv[2], 0.9f)
            return Color.HSVToColor(hsv)
        }
    }
}
