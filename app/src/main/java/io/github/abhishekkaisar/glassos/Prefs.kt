package io.github.abhishekkaisar.glassos

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) { sp.edit().putBoolean("sound", v).apply() }

    /** Settings > Reduce transparency: solid panels instead of glass ([GlassPainter.solid]). Off by default. */
    var reduceTransparency: Boolean
        get() = sp.getBoolean("reduceTransparency", false)
        set(v) { sp.edit().putBoolean("reduceTransparency", v).apply() }

    /**
     * Fallback background, shown when the Top Shelf has no title art: a built-in scene (scene:..., default),
     * the user's own photos in Pictures/Glass (hero:photos) or one image (file:...).
     */
    var scene: String
        get() = sp.getString("fallback", DEFAULT_SCENE) ?: DEFAULT_SCENE
        set(v) { sp.edit().putString("fallback", v).apply() }

    /** Top Shelf source: one of [HeroRepo.SOURCES], [HeroRepo.SRC_TMDB] by default. */
    var topShelf: String
        get() = sp.getString("topShelf", HeroRepo.SRC_TMDB) ?: HeroRepo.SRC_TMDB
        set(v) { sp.edit().putString("topShelf", v).apply() }

    /** TMDB key entered in Settings; the build's own key (BuildConfig.TMDB_API_KEY) is used when this is empty. */
    var tmdbKey: String
        get() = sp.getString("tmdbKey", "") ?: ""
        set(v) { sp.edit().putString("tmdbKey", v.trim()).apply() }

    val effectiveTmdbKey: String get() = tmdbKey.ifEmpty { BuildConfig.TMDB_API_KEY }

    /** Settings > Screensaver: one of [Screensaver.SOURCES], [Screensaver.OFF] by default. */
    var screensaver: String
        get() = sp.getString("screensaver", Screensaver.OFF) ?: Screensaver.OFF
        set(v) { sp.edit().putString("screensaver", v).apply() }

    /** Minutes without a key on the home before the screensaver starts. */
    var screensaverMinutes: Int
        get() = sp.getInt("screensaverMinutes", 10)
        set(v) { sp.edit().putInt("screensaverMinutes", v).apply() }

    /** Seconds each screensaver photo shows. */
    var screensaverSeconds: Int
        get() = sp.getInt("screensaverSeconds", 15)
        set(v) { sp.edit().putInt("screensaverSeconds", v).apply() }

    /** The photo's title, date and place over the screensaver. */
    var screensaverCaptions: Boolean
        get() = sp.getBoolean("screensaverCaptions", true)
        set(v) { sp.edit().putBoolean("screensaverCaptions", v).apply() }

    /** A Synology Photos shared album link, checked before it was saved ([Synology.check]). */
    var synologyLink: String
        get() = sp.getString("synologyLink", "") ?: ""
        set(v) { sp.edit().putString("synologyLink", v.trim()).apply() }

    /** The shared album's password, when it has one. */
    var synologyPassword: String
        get() = sp.getString("synologyPassword", "") ?: ""
        set(v) { sp.edit().putString("synologyPassword", v).apply() }

    /** The album's name, shown in Settings and on the captions. */
    var synologyAlbum: String
        get() = sp.getString("synologyAlbum", "") ?: ""
        set(v) { sp.edit().putString("synologyAlbum", v).apply() }

    /** The certificate a NAS on the home network showed when its link was checked (see [Synology.Pin]). */
    var synologyCert: String
        get() = sp.getString("synologyCert", "") ?: ""
        set(v) { sp.edit().putString("synologyCert", v).apply() }

    /** ISO 3166 country code for the Top Shelf region (streaming availability), once chosen in Settings. */
    var country: String
        get() = sp.getString("country", "") ?: ""
        set(v) { sp.edit().putString("country", v).apply() }

    /** The region in use: [country], or the TV's own until one is chosen (setup does not ask). */
    val effectiveCountry: String
        get() = country.ifEmpty { tvCountry() }.uppercase()

    var replaceHome: Boolean
        get() = sp.getBoolean("replaceHome", true)
        set(v) { sp.edit().putBoolean("replaceHome", v).apply() }

    var dock: List<String>?
        get() = sp.getString("dock", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) { sp.edit().putString("dock", v?.joinToString(",")).apply() }

    /** The drawer's order once the user has moved an app (null: alphabetical). Apps not in it go at the end. */
    var order: List<String>?
        get() = sp.getString("order", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) { sp.edit().putString("order", v?.joinToString(",")).apply() }

    /** Apps left out of the drawer, the top row and the top row picker (Settings > Hidden apps). */
    var hidden: Set<String>
        get() = sp.getString("hidden", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) { sp.edit().putString("hidden", v.joinToString(",")).apply() }

    /** The icon chosen for each TV input, by input id ([InputArt.Style] names). Inputs not in it get a guess. */
    var inputIcons: Map<String, String>
        get() = sp.getString("inputIcons", null)?.split('\n')?.mapNotNull { e ->
            val k = e.lastIndexOf('=')
            if (k > 0) e.substring(0, k) to e.substring(k + 1) else null
        }?.toMap() ?: emptyMap()
        set(v) { sp.edit().putString("inputIcons", v.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply() }

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) { sp.edit().putString("name", v).apply() }

    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) { sp.edit().putBoolean("setupDone", v).apply() }

    var askedStorage: Boolean
        get() = sp.getBoolean("askedStorage", false)
        set(v) { sp.edit().putBoolean("askedStorage", v).apply() }

    /** Build.FINGERPRINT of a Fire OS build that kept GlassOS out of its own ADB; setup then leads with a computer. */
    var adbBlockedBuild: String
        get() = sp.getString("adbBlockedBuild", "") ?: ""
        set(v) { sp.edit().putString("adbBlockedBuild", v).apply() }

    companion object {
        const val DEFAULT_SCENE = "scene:desert"

        /**
         * The TV's country from its locale, else US. Only a two-letter code is a country: a locale such as es-419
         * (Latin America) gives a UN region number, which TMDB's watch regions do not accept.
         */
        fun tvCountry(): String = java.util.Locale.getDefault().country
            .takeIf { it.length == 2 && it.all { c -> c.isLetter() } }?.uppercase() ?: "US"
    }
}
