package io.github.abhishekkaisar.glassos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The weather for the screensaver: the city from the TV's public IP address (ipwho.is, looked up once a day), the
 * forecast from Open-Meteo (no key; at most every [FRESH_MS]). Both are kept on disk, so the last forecast shows at
 * once and offline. Fahrenheit in the United States and a few others, else Celsius.
 */
object Weather {
    class Now(val temp: Int, val high: Int, val low: Int, val code: Int, val day: Boolean, val city: String, val at: Long) {
        val label: String get() = label(code)
    }

    private const val FRESH_MS = 30 * 60_000L
    private const val PLACE_MS = 24 * 3600_000L
    /** An older forecast is not shown at all. */
    private const val SHOW_MS = 6 * 3600_000L
    private val FAHRENHEIT = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

    private fun file(ctx: Context, name: String) = File(ctx.filesDir, "weather").apply { mkdirs() }.let { File(it, name) }

    /** The last forecast on disk, while it is recent enough to show. */
    fun cached(ctx: Context): Now? = try {
        val o = JSONObject(file(ctx, "now.json").readText())
        val at = o.getLong("at")
        if (System.currentTimeMillis() - at > SHOW_MS) null
        else Now(o.getInt("temp"), o.getInt("high"), o.getInt("low"), o.getInt("code"), o.getBoolean("day"), o.optString("city"), at)
    } catch (e: Throwable) { null }

    /** Fetches a new forecast when the one on disk is older than [FRESH_MS]. Call off the UI thread. */
    fun refresh(ctx: Context): Now? {
        cached(ctx)?.let { if (System.currentTimeMillis() - it.at < FRESH_MS) return it }
        val place = place(ctx) ?: return cached(ctx)
        val unit = if (place.optString("country") in FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.getDouble("lat")}&longitude=${place.getDouble("lon")}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1$unit"
        val (code, body) = HeroRepo.get(url)
        if (code != 200 || body == null) { WLog.i("weather -> $code"); return cached(ctx) }
        return try {
            val o = JSONObject(body)
            val cur = o.getJSONObject("current")
            val daily = o.getJSONObject("daily")
            val now = JSONObject()
                .put("temp", cur.getDouble("temperature_2m").roundToInt())
                .put("code", cur.getInt("weather_code"))
                .put("day", cur.optInt("is_day", 1) == 1)
                .put("high", daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt())
                .put("low", daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt())
                .put("city", place.optString("city"))
                .put("at", System.currentTimeMillis())
            file(ctx, "now.json").writeText(now.toString())
            cached(ctx)
        } catch (e: Throwable) { cached(ctx) }
    }

    /** Where the TV is, from its public IP address; looked up again once a day. */
    private fun place(ctx: Context): JSONObject? {
        val f = file(ctx, "place.json")
        val old = try { JSONObject(f.readText()) } catch (e: Throwable) { null }
        if (old != null && System.currentTimeMillis() - old.optLong("at") < PLACE_MS) return old
        val (code, body) = HeroRepo.get("https://ipwho.is/?fields=success,city,country_code,latitude,longitude")
        val o = try { if (code == 200 && body != null) JSONObject(body) else null } catch (e: Throwable) { null }
        if (o == null || !o.optBoolean("success")) { WLog.i("weather place -> $code"); return old }
        val p = JSONObject()
            .put("lat", o.getDouble("latitude")).put("lon", o.getDouble("longitude"))
            .put("city", o.optString("city")).put("country", o.optString("country_code"))
            .put("at", System.currentTimeMillis())
        f.writeText(p.toString())
        return p
    }

    /** Open-Meteo's WMO weather code in words. */
    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 80, 81 -> "Rain"
        65, 82 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 85 -> "Snow"
        75, 86 -> "Heavy snow"
        77 -> "Snow grains"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> ""
    }

    private enum class Sky { SUN, MOON, PARTLY_SUN, PARTLY_MOON, CLOUD, FOG, RAIN, SNOW, STORM }

    private fun sky(code: Int, day: Boolean) = when (code) {
        0, 1 -> if (day) Sky.SUN else Sky.MOON
        2 -> if (day) Sky.PARTLY_SUN else Sky.PARTLY_MOON
        45, 48 -> Sky.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Sky.RAIN
        71, 73, 75, 77, 85, 86 -> Sky.SNOW
        95, 96, 99 -> Sky.STORM
        else -> Sky.CLOUD
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloud = Path()
    private val tmp = Path()
    private val r = RectF()
    private const val SUN = 0xFFF2B84B.toInt()

    /** The weather as a line icon, [s] wide with its top left at [x], [y]; [a] is its opacity. */
    fun drawIcon(c: Canvas, code: Int, day: Boolean, x: Float, y: Float, s: Float, a: Float) {
        line.strokeWidth = s * 0.055f
        val white = alphaOf(Color.WHITE, a)
        val sun = alphaOf(SUN, a)
        when (val k = sky(code, day)) {
            Sky.SUN -> sun(c, x + s / 2, y + s / 2, s * 0.2f, s * 0.36f, sun)
            Sky.MOON -> moon(c, x + s / 2, y + s / 2, s * 0.3f, sun)
            Sky.FOG -> {
                line.color = white
                for (i in 0..2) c.drawLine(x + s * (0.12f + 0.08f * (i % 2)), y + s * (0.32f + 0.18f * i), x + s * (0.88f - 0.08f * (i % 2)), y + s * (0.32f + 0.18f * i), line)
            }
            else -> {
                val partly = k == Sky.PARTLY_SUN || k == Sky.PARTLY_MOON
                // A smaller cloud low and right when the sun or moon peeks out behind it.
                val cx = if (partly) x + s * 0.58f else x + s / 2
                val cy = if (partly) y + s * 0.64f else if (k == Sky.CLOUD) y + s * 0.54f else y + s * 0.42f
                val cw = if (partly) s * 0.72f else s * 0.84f
                cloudPath(cx, cy, cw)
                if (partly) {
                    c.save()
                    if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(cloud) else @Suppress("DEPRECATION") c.clipPath(cloud, android.graphics.Region.Op.DIFFERENCE)
                    if (k == Sky.PARTLY_SUN) sun(c, x + s * 0.36f, y + s * 0.36f, s * 0.15f, s * 0.29f, sun)
                    else moon(c, x + s * 0.36f, y + s * 0.36f, s * 0.22f, sun)
                    c.restore()
                }
                line.color = white
                c.drawPath(cloud, line)
                val bottom = cy + cw * 0.2f
                when (k) {
                    Sky.RAIN -> for (i in 0..2) {
                        val lx = cx - cw * 0.24f + i * cw * 0.24f
                        c.drawLine(lx, bottom + s * 0.1f, lx - s * 0.06f, bottom + s * 0.24f, line)
                    }
                    Sky.SNOW -> {
                        dot.color = white
                        for (i in 0..2) c.drawCircle(cx - cw * 0.24f + i * cw * 0.24f, bottom + s * (if (i == 1) 0.22f else 0.14f), s * 0.04f, dot)
                    }
                    Sky.STORM -> {
                        line.color = sun
                        tmp.reset()
                        tmp.moveTo(cx + s * 0.04f, bottom + s * 0.06f)
                        tmp.lineTo(cx - s * 0.06f, bottom + s * 0.18f)
                        tmp.lineTo(cx + s * 0.04f, bottom + s * 0.18f)
                        tmp.lineTo(cx - s * 0.06f, bottom + s * 0.3f)
                        c.drawPath(tmp, line)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, rad: Float, ray: Float, color: Int) {
        line.color = color
        c.drawCircle(cx, cy, rad, line)
        for (i in 0 until 8) {
            val t = i * Math.PI / 4
            val ix = cos(t).toFloat(); val iy = sin(t).toFloat()
            c.drawLine(cx + ix * (rad + ray * 0.32f), cy + iy * (rad + ray * 0.32f), cx + ix * ray * 1.25f, cy + iy * ray * 1.25f, line)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, rad: Float, color: Int) {
        line.color = color
        tmp.reset()
        tmp.addCircle(cx, cy, rad, Path.Direction.CW)
        val cut = Path().apply { addCircle(cx + rad * 0.55f, cy - rad * 0.4f, rad * 0.85f, Path.Direction.CW) }
        tmp.op(cut, Path.Op.DIFFERENCE)
        c.drawPath(tmp, line)
    }

    /** A cloud [w] wide centred on [cx], [cy]: three bumps on a flat base. */
    private fun cloudPath(cx: Float, cy: Float, w: Float) {
        cloud.reset()
        val h = w * 0.4f
        r.set(cx - w / 2, cy - h * 0.1f, cx + w / 2, cy + h / 2)
        cloud.addRoundRect(r, h * 0.3f, h * 0.3f, Path.Direction.CW)
        tmp.reset()
        tmp.addCircle(cx - w * 0.2f, cy, w * 0.2f, Path.Direction.CW)
        cloud.op(tmp, Path.Op.UNION)
        tmp.reset()
        tmp.addCircle(cx + w * 0.06f, cy - h * 0.25f, w * 0.27f, Path.Direction.CW)
        cloud.op(tmp, Path.Op.UNION)
        tmp.reset()
        tmp.addCircle(cx + w * 0.3f, cy + h * 0.05f, w * 0.17f, Path.Direction.CW)
        cloud.op(tmp, Path.Op.UNION)
    }
}
