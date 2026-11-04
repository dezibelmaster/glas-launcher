package de.glas.launcher

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.StatFs
import de.glas.launcher.PhoneRemote.HttpError
import de.glas.launcher.PhoneRemote.Request
import de.glas.launcher.PhoneRemote.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** What the phone page can read and change. Same rules as on the TV: the parental lock needs the PIN. */
object PhoneApi {
    private const val PAGE = "phone/index.html"
    private val glassRates = listOf(0, 1, 2, 4, 8, 15)
    private val idleOptions = listOf(0, 2, 5, 10, 15, 30)
    private val weatherStyles = listOf("realistic", "lifelike", "modern", "abstract")
    private val timeWindow = Regex("^([01]\\d|2[0-3]):[0-5]\\d-([01]\\d|2[0-3]):[0-5]\\d$")

    private var wrongPins = 0
    private var pinBlockedUntil = 0L

    private var appsCache: List<AppEntry> = emptyList()
    private var appsTime = 0L
    private val icons = HashMap<String, ByteArray>()

    fun handle(ctx: Context, req: Request): Response {
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            val html = ctx.assets.open(PAGE).use { it.readBytes() }
            return Response(200, "text/html; charset=utf-8", html, mapOf("X-Frame-Options" to "DENY"))
        }
        if (!req.path.startsWith("/api/")) return error(404, "Not found")
        if (!PhoneRemote.allowed(req.header("X-Glas-Key") ?: req.query["k"])) {
            throw HttpError(403, tr("Der Zugang ist abgelaufen. Scanne den QR-Code am Fernseher neu."))
        }
        PhoneRemote.used(ctx, req.phone)
        val prefs = Prefs(ctx)
        return when ("${req.method} ${req.path}") {
            "GET /api/texts" -> json(texts(ctx))
            "GET /api/state" -> json(state(ctx, prefs))
            "GET /api/icon" -> icon(ctx, req.query["c"] ?: "")
            "POST /api/settings" -> { settings(ctx, prefs, req, JSONObject(req.text())); ok() }
            "POST /api/dock" -> { dock(ctx, prefs, JSONArray(req.text())); ok() }
            "GET /api/places/search" -> places(req.query["q"] ?: "")
            "POST /api/places" -> { setPlaces(prefs, JSONArray(req.text())); ok() }
            "POST /api/pin/check" -> { requirePin(prefs, req); ok() }
            "GET /api/lock" -> { requirePin(prefs, req); json(lock(prefs)) }
            "POST /api/lock" -> { requirePin(prefs, req); setLock(prefs, JSONObject(req.text())); ok() }
            "POST /api/pin" -> { requirePin(prefs, req); setPin(prefs, JSONObject(req.text())); ok() }
            "POST /api/upload" -> upload(ctx, req)
            "POST /api/media/delete" -> deleteMedia(ctx, JSONObject(req.text()).getString("uri"))
            "GET /api/backup" -> { requirePin(prefs, req); backup(ctx) }
            "POST /api/restore" -> { requirePin(prefs, req); restore(ctx, req.text()); ok() }
            else -> error(404, "Not found")
        }
    }

    fun error(code: Int, message: String) =
        Response(code, "application/json; charset=utf-8", JSONObject().put("error", message).toString().toByteArray())

    private fun json(o: Any) = Response(200, "application/json; charset=utf-8", o.toString().toByteArray())
    private fun ok() = json(JSONObject().put("ok", true))
    private fun bad(message: String): Nothing = throw HttpError(400, message)

    /** The page's texts in the TV's language: every t('…') in the page, translated. */
    private fun texts(ctx: Context): JSONObject {
        val html = ctx.assets.open(PAGE).bufferedReader().use { it.readText() }
        val out = JSONObject().put("lang", I18n.lang)
        val map = JSONObject()
        Regex("""\bt\('((?:[^'\\]|\\.)*)'""").findAll(html).forEach { m ->
            val de = m.groupValues[1].replace("\\'", "'").replace("\\n", "\n")
            val translated = I18n.lookup(de)
            if (translated != de) map.put(de, translated)
        }
        return out.put("texts", map)
    }

    private fun apps(ctx: Context): List<AppEntry> = synchronized(this) {
        if (System.currentTimeMillis() - appsTime > 60_000) {
            appsCache = Apps.installed(ctx)
            appsTime = System.currentTimeMillis()
        }
        appsCache
    }

    // ---- Reading ----

    private fun state(ctx: Context, prefs: Prefs): JSONObject {
        val apps = apps(ctx)
        val dock = prefs.dock ?: Apps.defaultDock(apps)
        val o = JSONObject()
            .put("tv", Build.MODEL)
            .put("version", Updater.installedVersion(ctx))
            .put("language", prefs.language ?: JSONObject.NULL)
            .put("languages", JSONArray(listOf(JSONObject().put("code", JSONObject.NULL).put("name", I18n.nameOf(null))) +
                I18n.languages.map { JSONObject().put("code", it.code).put("name", it.name) }))
            .put("background", prefs.background)
            .put("weatherStyle", prefs.weatherStyle)
            .put("imageUri", prefs.imageUri ?: JSONObject.NULL)
            .put("videoUri", prefs.videoUri ?: JSONObject.NULL)
            .put("otherBackground", otherBackground(ctx, prefs) ?: JSONObject.NULL)
            .put("glass", prefs.glass)
            .put("glassVideoRate", prefs.glassVideoRate)
            .put("topStyle", prefs.topStyle)
            .put("statusHidden", prefs.statusHidden)
            .put("idleMinutes", prefs.idleMinutes)
            .put("saverClock", prefs.saverClock)
            .put("saverDate", prefs.saverDate)
            .put("saverWeather", prefs.saverWeather)
            .put("weatherSource", prefs.weatherSource)
            .put("weatherSources", JSONArray(WeatherApi.SOURCES.map { JSONObject().put("id", it).put("name", WeatherApi.sourceName(it)) }))
            .put("updateRepo", prefs.updateRepo ?: JSONObject.NULL)
            .put("hasPin", prefs.pinHash != null)
            .put("dock", JSONArray(dock))
            .put("apps", JSONArray(apps.map { JSONObject().put("component", it.component).put("pkg", it.pkg).put("label", it.label) }))
            .put("places", JSONArray(prefs.places.map(::placeJson)))
        o.put("images", JSONArray(Media.list(ctx, video = false).map { JSONObject().put("name", it.name).put("uri", it.uri) }))
        o.put("videos", JSONArray(Media.list(ctx, video = true).map { JSONObject().put("name", it.name).put("uri", it.uri) }))
        o.put("freeSpace", freeSpace())
        return o
    }

    /** Name of a background the phone can't choose itself (extension or plugin), to show what is set. */
    private fun otherBackground(ctx: Context, prefs: Prefs): String? = when (prefs.background) {
        "extension" -> Extensions.installed(ctx).firstOrNull { it.authority == prefs.extAuthority }?.label ?: tr("Erweiterung")
        "plugin" -> ProjectivyPlugins.find(ctx, prefs.plugin)?.name ?: tr("Plugin nicht mehr installiert")
        else -> null
    }

    private fun placeJson(p: Place) = JSONObject().put("name", p.name).put("region", p.region).put("lat", p.lat).put("lon", p.lon)

    private fun icon(ctx: Context, component: String): Response {
        val png = synchronized(icons) { icons[component] } ?: run {
            val app = apps(ctx).firstOrNull { it.component == component } ?: return error(404, "Not found")
            val bmp = Ui.roundedIcon(app.icon, 96)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray().also { synchronized(icons) { icons[component] = it } }
        }
        return Response(200, "image/png", png, mapOf("Cache-Control" to "private, max-age=3600"))
    }

    private fun lock(prefs: Prefs) = JSONObject()
        .put("locked", JSONArray(prefs.locked.toList()))
        .put("lockAllApps", prefs.lockAllApps)
        .put("protectSettings", prefs.protectSettings)
        .put("timeWindows", JSONObject(prefs.timeWindows))

    // ---- Changing ----

    private fun settings(ctx: Context, prefs: Prefs, req: Request, o: JSONObject) {
        for (key in o.keys()) {
            when (key) {
                "glass" -> prefs.glass = o.getInt(key).takeIf { it in 0..2 } ?: bad(key)
                "glassVideoRate" -> prefs.glassVideoRate = o.getInt(key).takeIf { it in glassRates } ?: bad(key)
                "topStyle" -> prefs.topStyle = o.getString(key).takeIf { it == "bar" || it == "classic" } ?: bad(key)
                "statusHidden" -> prefs.statusHidden = o.getBoolean(key)
                "idleMinutes" -> prefs.idleMinutes = o.getInt(key).takeIf { it in idleOptions } ?: bad(key)
                "saverClock" -> prefs.saverClock = o.getBoolean(key)
                "saverDate" -> prefs.saverDate = o.getBoolean(key)
                "saverWeather" -> prefs.saverWeather = o.getBoolean(key)
                "weatherSource" -> {
                    prefs.weatherSource = o.getString(key).takeIf { it in WeatherApi.SOURCES } ?: bad(key)
                    prefs.clearWeather()
                }
                "language" -> {
                    prefs.language = if (o.isNull(key)) null else o.getString(key).takeIf { c -> I18n.languages.any { it.code == c } } ?: bad(key)
                    I18n.init(ctx)
                }
                "weatherStyle" -> {
                    prefs.weatherStyle = o.getString(key).takeIf { it in weatherStyles } ?: bad(key)
                    prefs.background = "weather"
                }
                "image" -> {
                    val uri = o.getString(key)
                    if (Media.list(ctx, video = false).none { it.uri == uri }) bad(tr("Das Bild gibt es nicht mehr"))
                    prefs.imageUri = uri
                    prefs.background = "image"
                }
                "video" -> {
                    // null: all own videos in turn
                    val uri = if (o.isNull(key)) null else o.getString(key)
                    val videos = Media.list(ctx, video = true)
                    if (uri != null && videos.none { it.uri == uri }) bad(tr("Das Video gibt es nicht mehr"))
                    if (videos.isEmpty()) bad(tr("Auf dem Fernseher sind keine eigenen Videos"))
                    prefs.videoUri = uri
                    prefs.background = "video"
                }
                "updateRepo" -> {
                    requirePin(prefs, req)
                    val repo = if (o.isNull(key)) "" else o.getString(key).trim().removePrefix("https://github.com/").trim('/')
                    if (repo.isNotEmpty() && repo.count { it == '/' } != 1) bad(tr("Bitte im Format Name/Projekt eingeben"))
                    prefs.updateRepo = repo.ifEmpty { null }
                }
                else -> bad(key)
            }
        }
        PhoneRemote.changed()
    }

    private fun dock(ctx: Context, prefs: Prefs, arr: JSONArray) {
        val installed = apps(ctx).map { it.component }.toSet()
        val list = (0 until arr.length()).map { arr.getString(it) }.filter { it in installed }.distinct()
        prefs.dock = list
        PhoneRemote.changed()
    }

    private fun places(query: String): Response {
        if (query.isBlank()) return json(JSONArray())
        val found = try {
            WeatherApi.search(query)
        } catch (e: Exception) {
            throw HttpError(502, tr("Keine Verbindung zur Ortssuche"))
        }
        return json(JSONArray(found.map(::placeJson)))
    }

    private fun setPlaces(prefs: Prefs, arr: JSONArray) {
        val places = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val lat = o.getDouble("lat")
            val lon = o.getDouble("lon")
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) bad("lat/lon")
            Place(o.getString("name").take(80), o.optString("region").take(120), lat, lon)
        }.distinctBy { it.key }
        if (places.isEmpty()) bad(tr("Füge zuerst einen weiteren Ort hinzu"))
        prefs.places = places
        PhoneRemote.changed()
    }

    /** Like on the TV: with a PIN set, the request must carry it. Five wrong tries block for a minute. */
    @Synchronized
    private fun requirePin(prefs: Prefs, req: Request) {
        val expected = prefs.pinHash ?: return
        val now = System.currentTimeMillis()
        if (now < pinBlockedUntil) throw HttpError(429, tr("Zu viele falsche Versuche. Bitte warte eine Minute."))
        val given = req.header("X-Glas-Pin")
        if (given != null && Pin.hash(given) == expected) {
            wrongPins = 0
            return
        }
        if (given != null && ++wrongPins >= 5) {
            wrongPins = 0
            pinBlockedUntil = now + 60_000
        }
        throw HttpError(401, if (given == null) tr("PIN eingeben") else tr("Falscher PIN"))
    }

    private fun setLock(prefs: Prefs, o: JSONObject) {
        if (prefs.pinHash == null) bad(tr("Lege zuerst einen PIN fest"))
        if (o.has("locked")) {
            val arr = o.getJSONArray("locked")
            prefs.locked = (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
        if (o.has("lockAllApps")) prefs.lockAllApps = o.getBoolean("lockAllApps")
        if (o.has("protectSettings")) prefs.protectSettings = o.getBoolean("protectSettings")
        if (o.has("timeWindows")) {
            val w = o.getJSONObject("timeWindows")
            prefs.timeWindows = w.keys().asSequence().associateWith { pkg ->
                val window = w.getString(pkg)
                if (!timeWindow.matches(window) || window.substringBefore('-') >= window.substringAfter('-')) {
                    bad(tr("Das Zeitfenster muss am selben Tag enden, z. B. 06:00 bis 20:00 Uhr"))
                }
                window
            }
        }
        PhoneRemote.changed()
    }

    private fun setPin(prefs: Prefs, o: JSONObject) {
        if (o.isNull("pin")) {
            // Same as on the TV: without a PIN there are no locks
            prefs.pinHash = null
            prefs.locked = emptySet()
            prefs.protectSettings = false
            prefs.lockAllApps = false
        } else {
            val pin = o.getString("pin")
            if (!Regex("^\\d{4}$").matches(pin)) bad(tr("Der PIN hat 4 Ziffern"))
            prefs.pinHash = Pin.hash(pin)
        }
        PhoneRemote.changed()
    }

    // ---- Pictures and videos ----

    private fun freeSpace(): Long = runCatching {
        @Suppress("DEPRECATION")
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(0L)

    private fun upload(ctx: Context, req: Request): Response {
        val name = Media.safeName(req.query["name"] ?: "") ?: bad(tr("Die Datei braucht einen Namen"))
        val ext = name.substringAfterLast('.', "").lowercase()
        val video = when (ext) {
            in Media.videoTypes -> true
            in Media.imageTypes -> false
            else -> bad(tr("Nur Bilder (JPG, PNG, WebP) und Videos (MP4, MKV, WebM, MOV) gehen"))
        }
        val length = req.length
        if (length <= 0) bad(tr("Die Datei ist leer"))
        val free = freeSpace()
        if (length + 300_000_000L > free) {
            throw HttpError(507, tr("Auf dem Fernseher ist nicht genug Platz frei ({0} MB)", free / 1_000_000))
        }
        val uri = Media.save(ctx, name, video) { out ->
            val buf = ByteArray(256 * 1024)
            var left = length
            while (left > 0) {
                val n = req.input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException(tr("Die Übertragung wurde abgebrochen"))
                out.write(buf, 0, n)
                left -= n
            }
        }
        return json(JSONObject().put("uri", uri).put("name", name).put("video", video))
    }

    private fun deleteMedia(ctx: Context, uri: String): Response {
        val item = (Media.list(ctx, video = false) + Media.list(ctx, video = true)).firstOrNull { it.uri == uri }
            ?: bad(tr("Die Datei gibt es nicht mehr"))
        return when (val result = Media.delete(ctx, uri)) {
            Media.Deletion.Done -> { deleted(ctx, uri); ok() }
            Media.Deletion.Failed -> bad(tr("„{0}“ ließ sich nicht löschen", item.name))
            is Media.Deletion.Confirm -> {
                // Not the launcher's own file. Delete it through the TV's debugging port like Bugjaeger
                // would; without debugging, Android asks on the TV itself.
                if (deleteWithShell(ctx, uri)) {
                    deleted(ctx, uri)
                    ok()
                } else {
                    DeleteConfirmActivity.start(ctx, result.sender, uri)
                    json(JSONObject().put("ok", false).put("confirm", true)
                        .put("message", tr("Bitte am Fernseher mit der Fernbedienung bestätigen, dass „{0}“ gelöscht werden darf.", item.name)))
                }
            }
        }
    }

    /** Deletes a file the launcher may not delete itself, through the debugging port. Blocking. */
    private fun deleteWithShell(ctx: Context, uri: String): Boolean {
        val path = Media.pathOf(ctx, uri) ?: return false
        @Suppress("DEPRECATION")
        val storage = Environment.getExternalStorageDirectory().absolutePath
        // Only files on the TV's shared storage, never anything else
        if (!path.startsWith("$storage/") || path.contains("/../")) return false
        val q = TvShell.quote(path)
        val result = TvShell.run(ctx, listOf("rm -f $q && ! test -e $q"), waitForApproval = 30_000)
        if (result != GoogleTv.Result.Done) return false
        Media.forget(ctx, path)
        return true
    }

    /** After a file is gone: if it was the background, the weather comes back, as on the TV. */
    fun deleted(ctx: Context, uri: String) {
        val prefs = Prefs(ctx)
        if ((prefs.background == "image" && prefs.imageUri == uri) || (prefs.background == "video" && prefs.videoUri == uri)) {
            prefs.background = "weather"
        }
        PhoneRemote.changed()
    }

    // ---- Backup ----

    private fun backup(ctx: Context): Response {
        val text = Backup.text(ctx)
        return Response(200, "application/json; charset=utf-8", text.toByteArray(),
            mapOf("Content-Disposition" to "attachment; filename=\"${Backup.FILE}\""))
    }

    private fun restore(ctx: Context, text: String) {
        if (!Backup.restore(ctx, text)) bad(tr("Das ist keine Sicherung des Glas Launchers"))
        I18n.init(ctx)
        PhoneRemote.changed()
    }
}
