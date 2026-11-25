package io.github.abhishekkaisar.glassos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * Banner art for a TV input's tile, in glass: a deep colour under a curved sheen, the device's logo (or a drawn glyph
 * where there is no logo to use) in bright white with a soft shadow, and the input's name under it. The user picks
 * the style (Menu while moving the tile); until then it is guessed from the name the input has on the TV. An image in
 * Pictures/Glass/icons named after the input replaces the art, as it does for apps.
 */
object InputArt {
    enum class Style(val title: String, val top: Int, val bottom: Int, val logo: Int, val glyph: Int) {
        HDMI("HDMI", 0xFF4A5878.toInt(), 0xFF141A2A.toInt(), 0, G_HDMI),
        PLAYSTATION("PlayStation", 0xFF1F7FE0.toInt(), 0xFF001E52.toInt(), R.drawable.logo_playstation, 0),
        // Microsoft does not let its logos be shared, so Xbox gets a controller.
        XBOX("Xbox", 0xFF2DB52D.toInt(), 0xFF083A08.toInt(), 0, G_PAD),
        SWITCH("Nintendo Switch", 0xFFFF3B47.toInt(), 0xFF7A000C.toInt(), R.drawable.logo_switch, 0),
        STEAM("Steam", 0xFF2F6E9E.toInt(), 0xFF0B1422.toInt(), R.drawable.logo_steam, 0),
        PC("PC", 0xFF5E6470.toInt(), 0xFF121418.toInt(), 0, G_PC),
        APPLETV("Apple TV", 0xFF48484C.toInt(), 0xFF050506.toInt(), R.drawable.logo_appletv, 0),
        ROKU("Roku", 0xFF8B45D6.toInt(), 0xFF2C0A55.toInt(), R.drawable.logo_roku, 0),
        SHIELD("NVIDIA Shield", 0xFF7CC00A.toInt(), 0xFF1E3300.toInt(), R.drawable.logo_nvidia, 0),
        CABLE("Cable or satellite box", 0xFFE8913A.toInt(), 0xFF5E2806.toInt(), 0, G_BOX),
        DISC("Blu-ray or DVD player", 0xFF1A9AA0.toInt(), 0xFF042C2F.toInt(), 0, G_DISC)
    }

    /** The tile's own 5:3 shape, so nothing is cropped off the sides. */
    const val W = 400
    const val H = 240

    /** The saved style name, else a guess from the input's name ("PS5" is PlayStation), else plain HDMI. */
    fun style(saved: String?, label: String): Style {
        saved?.let { s -> Style.values().firstOrNull { it.name == s }?.let { return it } }
        val l = label.lowercase()
        fun has(vararg w: String) = w.any { Regex("\\b$it").containsMatchIn(l) }
        return when {
            has("ps\\d", "playstation") -> Style.PLAYSTATION
            has("xbox") -> Style.XBOX
            has("switch", "nintendo") -> Style.SWITCH
            has("steam") -> Style.STEAM
            has("apple") -> Style.APPLETV
            has("roku") -> Style.ROKU
            has("shield", "nvidia") -> Style.SHIELD
            has("pc\\b", "computer", "laptop", "mac\\b") -> Style.PC
            has("cable", "sat", "set.?top", "stb\\b", "tivo") -> Style.CABLE
            has("blu", "dvd", "disc") -> Style.DISC
            else -> Style.HDMI
        }
    }

    /** A [W] x [H] glass banner for [style], named [label]. Runs off the main thread. */
    fun render(ctx: Context, style: Style, label: String): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = W.toFloat()
        val h = H.toFloat()

        // Deep colour, lit from the top left.
        p.shader = LinearGradient(0f, 0f, w * 0.5f, h, style.top, style.bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.22f, -h * 0.15f, w * 0.85f, 0x52FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        // The glass: a curved sheen over the upper half and a little depth at the bottom.
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x2EFFFFFF, 0x06FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(-w * 0.25f, -h * 1.05f, w * 1.25f, h * 0.5f), p)
        p.shader = LinearGradient(0f, h * 0.55f, 0f, h, 0x00000000, 0x47000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.55f, w, h, p)
        p.shader = null

        // The mark, fitted into a box above the name, in white glass with a soft shadow under it.
        mask(ctx, style)?.let { (m, src) ->
            val boxW = w * 0.56f
            val boxH = h * 0.40f
            val sc = minOf(boxW / src.width(), boxH / src.height())
            val dw = src.width() * sc
            val dh = src.height() * sc
            val cy = h * 0.42f
            val dst = RectF((w - dw) / 2f, cy - dh / 2f, (w + dw) / 2f, cy + dh / 2f)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = 0x73000000
                maskFilter = BlurMaskFilter(11f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawBitmap(m, src, RectF(dst.left, dst.top + 6f, dst.right, dst.bottom + 6f), shadow)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = 0x38FFFFFF
                maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.OUTER)
            }
            c.drawBitmap(m, src, dst, glow)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = LinearGradient(0f, dst.top, 0f, dst.bottom, 0xFFFFFFFF.toInt(), 0xD9FFFFFF.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawBitmap(m, src, dst, fill)
            m.recycle()
        }

        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.medium
            color = 0xF2FFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            textSize = 30f
            setShadowLayer(6f, 0f, 2f, 0x66000000)
        }
        c.drawText(ellipsize(label, t), w / 2f, h * 0.84f, t)
        return b
    }

    private const val M = 240

    /**
     * The style's logo or glyph as an alpha mask, with the rectangle its pixels fill (logos differ in shape: Roku's
     * is a wide word, Switch's a square). Null if it could not be drawn.
     */
    private fun mask(ctx: Context, style: Style): Pair<Bitmap, Rect>? = try {
        val full = Bitmap.createBitmap(M, M, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        if (style.logo != 0) {
            ctx.getDrawable(style.logo)?.mutate()?.apply { setBounds(0, 0, M, M); draw(c) }
        } else {
            glyph(c, style.glyph, M / 2f, M / 2f, M * 0.86f, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        val alpha = full.extractAlpha()
        full.recycle()
        bounds(alpha)?.let { alpha to it } ?: run { alpha.recycle(); null }
    } catch (e: Throwable) { null }

    private fun bounds(m: Bitmap): Rect? {
        val px = IntArray(M * M)
        // extractAlpha gives ALPHA_8; getPixels returns its alpha in the top byte.
        m.getPixels(px, 0, M, 0, 0, M, M)
        var l = M; var t = M; var r = -1; var btm = -1
        for (y in 0 until M) for (x in 0 until M) {
            if ((px[y * M + x] ushr 24) > 8) {
                if (x < l) l = x; if (x > r) r = x
                if (y < t) t = y; if (y > btm) btm = y
            }
        }
        return if (r < l) null else Rect(l, t, r + 1, btm + 1)
    }

    private fun ellipsize(s: String, p: Paint): String {
        if (p.measureText(s) <= W - 40) return s
        var n = s.length
        while (n > 1 && p.measureText(s.substring(0, n) + "…") > W - 40) n--
        return s.substring(0, n).trimEnd() + "…"
    }

    private val r = RectF()
    private val path = Path()

    /** Glyphs for devices without a logo, drawn in white (only their alpha is used). */
    private fun glyph(c: Canvas, g: Int, cx: Float, cy: Float, s: Float, p: Paint) {
        val white = 0xFFFFFFFF.toInt()
        val h = s / 2
        if (g == G_PAD) { Glyphs.draw(c, Glyph.GAMEPAD, cx, cy, s, white, p); return }
        p.color = white
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.08f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (g) {
            G_HDMI -> {
                // The HDMI plug's face: wide on top, its lower corners cut off, a row of pins inside.
                path.reset()
                path.moveTo(cx - h * 0.95f, cy - h * 0.32f)
                path.lineTo(cx + h * 0.95f, cy - h * 0.32f)
                path.lineTo(cx + h * 0.95f, cy + h * 0.02f)
                path.lineTo(cx + h * 0.66f, cy + h * 0.32f)
                path.lineTo(cx - h * 0.66f, cy + h * 0.32f)
                path.lineTo(cx - h * 0.95f, cy + h * 0.02f)
                path.close()
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
                for (k in 0 until 7) c.drawCircle(cx - h * 0.6f + k * h * 0.2f, cy - h * 0.06f, s * 0.035f, p)
            }
            G_PC -> {
                // A monitor on a stand.
                r.set(cx - h * 0.95f, cy - h * 0.72f, cx + h * 0.95f, cy + h * 0.42f)
                c.drawRoundRect(r, h * 0.12f, h * 0.12f, p)
                c.drawLine(cx, cy + h * 0.42f, cx, cy + h * 0.78f, p)
                c.drawLine(cx - h * 0.42f, cy + h * 0.8f, cx + h * 0.42f, cy + h * 0.8f, p)
            }
            G_BOX -> {
                // A flat box with a light and a slot.
                r.set(cx - h * 0.98f, cy - h * 0.34f, cx + h * 0.98f, cy + h * 0.38f)
                c.drawRoundRect(r, h * 0.14f, h * 0.14f, p)
                c.drawLine(cx - h * 0.62f, cy + h * 0.02f, cx - h * 0.1f, cy + h * 0.02f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx + h * 0.62f, cy + h * 0.02f, s * 0.06f, p)
            }
            G_DISC -> {
                c.drawCircle(cx, cy, h * 0.88f, p)
                c.drawCircle(cx, cy, h * 0.22f, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    private const val G_HDMI = 0
    private const val G_PAD = 1
    private const val G_PC = 2
    private const val G_BOX = 3
    private const val G_DISC = 4
}
