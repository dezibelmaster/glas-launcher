package de.glas.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private var afterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(40), 0, dp(60))
        }
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1A2230.toInt(), 0xFF07090D.toInt()))
            addView(ScrollView(this@SettingsActivity).apply {
                clipToPadding = false
                addView(list)
            }, FrameLayout.LayoutParams(dp(760), MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val focused = list.indexOfChild(list.focusedChild)
        list.removeAllViews()

        list.addView(Ui.text(this, 34f, Ui.thin).apply {
            text = "Einstellungen"
            setPadding(dp(8), 0, 0, dp(8))
        })

        section("Aussehen")
        row("Hintergrund", backgroundLabel()) { chooseBackground() }
        row("Milchglas", Ui.glassNames[prefs.glass]) { chooseGlass() }
        row("Milchglas über Videos", rateLabel(prefs.glassVideoRate)) { chooseGlassRate() }
        row("Eigene Bilder und Videos verwalten") { manageMedia() }
        row("Heruntergeladene Videos löschen", megabytes(MediaCache.size(this))) { clearDownloads() }
        row("Oben anzeigen", if (prefs.topStyle == "classic") "Große Uhr und Wetterkarte" else "Statusleiste") {
            GlassMenu.show(this, "Oben anzeigen", listOf(
                GlassMenu.Item("Statusleiste: Wetter, WLAN, Einstellungen, Datum, Uhrzeit") { prefs.topStyle = "bar"; render() },
                GlassMenu.Item("Große Uhr und Wetterkarte") { prefs.topStyle = "classic"; render() },
            ), focus = if (prefs.topStyle == "classic") 1 else 0)
        }
        note(
            "Eigene Wetterfotos: Lege Bilder in den Ordner Pictures/GlasLauncher auf dem Fernseher, z. B. " +
                "clear_day.jpg, partly_day.jpg, overcast_day.jpg, fog_day.jpg, rain_day.jpg, snow_day.jpg, storm_day.jpg " +
                "und für die Nacht z. B. clear_night.jpg. Sie ersetzen die eingebauten Fotos.",
        )

        section("Wetter")
        val places = prefs.places
        places.forEachIndexed { i, place ->
            row(place.name, if (i == 0) "Hauptort" else "") { placeMenu(place, i) }
        }
        row("Ort hinzufügen …") { addPlace() }
        note("Der Hauptort bestimmt den Wetter-Hintergrund. Weitere Orte erscheinen klein unter der Wetterkarte.")

        section("Dock")
        note("Halte OK auf einer App gedrückt oder drücke die Menü-Taste, um sie zu verschieben, zu entfernen oder mit PIN zu sperren. Neue Apps fügst du unter „Alle Apps“ hinzu.")
        row("Dock zurücksetzen") {
            prefs.dock = null
            toast("Das Dock zeigt wieder YouTube, Netflix, MagentaTV und Jellyfin")
        }

        section("Kindersicherung")
        row("PIN", if (prefs.pinHash == null) "Festlegen" else "Ändern") { changePin() }
        row("Gesperrte Apps", "${prefs.locked.size}") { withPin { chooseLocked(0) } }
        row("„Alle Apps“ mit PIN schützen", if (prefs.lockAllApps) "An" else "Aus") {
            toggleProtection { prefs.lockAllApps = !prefs.lockAllApps }
        }
        row("Einstellungen mit PIN schützen", if (prefs.protectSettings) "An" else "Aus") {
            toggleProtection { prefs.protectSettings = !prefs.protectSettings }
        }
        row("Zeitfenster", if (prefs.timeWindows.isEmpty()) "keine" else "${prefs.timeWindows.size} Apps") { chooseTimeApp(0) }
        note("Mit Zeitfenster lässt sich eine App nur zu bestimmten Uhrzeiten ohne PIN öffnen, z. B. 06:00 bis 20:00 Uhr.")

        section("Startbildschirm")
        row("Status", homeStatus()) {}
        row("Als Standard festlegen") { makeDefault() }
        row("Home-Taste umleiten", if (redirectOn()) "An" else "Aus") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        note(
            "Google TV bietet keine Auswahl für den Startbildschirm. Zwei Wege:\n" +
                "1. Home-Taste umleiten: In den Bedienungshilfen „Glas Launcher“ einschalten. Kommt eine Meldung " +
                "„Eingeschränkte Einstellung“, zuerst unter Apps → Glas Launcher → ⋮ „Eingeschränkte Einstellungen zulassen“ wählen.\n" +
                "2. Google-Oberfläche abschalten (am zuverlässigsten), am Computer:\n" +
                "adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx",
        )

        section("Bildschirmschoner")
        row("Im Launcher starten nach", idleLabel(prefs.idleMinutes)) { chooseIdle() }
        row("Jetzt anzeigen") { startActivity(Intent(this, ScreensaverActivity::class.java)) }
        row("System-Bildschirmschoner öffnen") { open(Intent(Settings.ACTION_DREAM_SETTINGS)) }
        note(
            "Damit der Glas-Bildschirmschoner auch in anderen Apps erscheint, am Computer:\n" +
                "adb shell settings put secure screensaver_components de.glas.launcher/.ClockDream",
        )

        section("Leistung")
        val mem = Memory.info(this)
        row("Arbeitsspeicher frei", "${Memory.gb(mem.available)} von ${Memory.gb(mem.total)}") { render() }
        row("Davon nutzt Glas Launcher", Memory.mb(mem.launcher)) {}
        row("Arbeitsspeicher jetzt freigeben") { freeMemory() }
        row("Automatisch freigeben beim Bildschirmschoner", if (prefs.autoFreeMemory) "An" else "Aus") {
            prefs.autoFreeMemory = !prefs.autoFreeMemory; render()
        }
        note(
            "Freigeben beendet Apps, die im Hintergrund ruhen (z. B. YouTube nach dem Zurückkehren). " +
                "Sie starten beim nächsten Öffnen etwas langsamer neu. Android räumt den Speicher auch selbst auf; " +
                "Freigeben hilft vor allem, wenn eine App ruckelt oder abstürzt. Laufende Systemdienste bleiben unberührt.",
        )

        section("Sichern")
        row("Einstellungen sichern") { exportSettings() }
        row("Einstellungen wiederherstellen") { importSettings() }
        note(
            "Gesichert werden Dock, PIN und Sperren, Zeitfenster, Orte, Favoriten und Hintergrund in die Datei " +
                "Download/${Backup.FILE}. Auf einen anderen Fernseher bringst du sie z. B. mit Bugjaeger nach " +
                "/sdcard/Android/data/$packageName/files/ und wählst dann „Wiederherstellen“.",
        )

        section("Updates")
        row("Installierte Version", Updater.installedVersion(this)) {}
        row("Update-Quelle (GitHub)", prefs.updateRepo ?: "nicht festgelegt") { chooseUpdateRepo() }
        row("Jetzt nach Updates suchen") { checkUpdate() }
        note("Ist eine Quelle festgelegt, sucht Glas Launcher einmal am Tag nach einer neuen Version und zeigt einen Hinweis.")

        section("Fernseher")
        row("Fernseher-Einstellungen") { open(Intent(Settings.ACTION_SETTINGS)) }

        section("Info")
        row("Glas Launcher", versionOf(packageName) ?: "") {}
        versionOf("de.glas.launcher.videos")?.let { row("Glas Natur-Videos", it) {} }

        val target = list.getChildAt(focused)?.takeIf { it.isFocusable } ?: (0 until list.childCount).map { list.getChildAt(it) }.first { it.isFocusable }
        target.requestFocus()
    }

    /** "Version 2.21" of an installed app, or null if it isn't installed. */
    private fun versionOf(pkg: String): String? =
        runCatching { packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()?.let { "Version $it" }

    // ---- Building blocks ----

    private fun section(title: String) {
        list.addView(Ui.text(this, 13f, color = Ui.MUTED).apply {
            text = title.uppercase()
            letterSpacing = .15f
            setPadding(dp(8), dp(28), 0, dp(8))
        })
    }

    private fun row(title: String, value: String? = null, onClick: () -> Unit) {
        list.addView(Ui.row(this, title, value, onClick), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun note(text: String) {
        list.addView(Ui.text(this, 13f, color = Ui.MUTED).apply {
            this.text = text
            setLineSpacing(0f, 1.25f)
            setPadding(dp(8), dp(10), dp(8), 0)
        })
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Diese Einstellung gibt es auf deinem Fernseher nicht")
        }
    }

    private fun withPin(action: () -> Unit) = Pin.verify(this, prefs, "PIN eingeben", action)

    // ---- Appearance ----

    private fun backgroundLabel() = when (prefs.background) {
        "image" -> "Eigenes Bild"
        "video" -> "Eigenes Video"
        "extension" -> if (prefs.extUri == null) "Erweiterung · alle Videos" else "Erweiterung"
        "plugin" -> ProjectivyPlugins.find(this, prefs.plugin)?.name ?: "Plugin nicht mehr installiert"
        else -> when (prefs.weatherStyle) {
            "modern" -> "Wetter · Modern"
            "abstract" -> "Wetter · Abstrakt"
            "lifelike" -> "Wetter · Realistisch animiert"
            else -> "Wetter · Realistisch"
        }
    }

    private fun chooseBackground() {
        fun weather(style: String) = { prefs.background = "weather"; prefs.weatherStyle = style; render() }
        GlassMenu.show(
            this, "Hintergrund",
            listOf(
                GlassMenu.Item("Wetter · Realistisch (Fotos)", weather("realistic")),
                GlassMenu.Item("Wetter · Realistisch animiert", weather("lifelike")),
                GlassMenu.Item("Wetter · Modern (gezeichnet)", weather("modern")),
                GlassMenu.Item("Wetter · Abstrakt (Farbverläufe)", weather("abstract")),
                GlassMenu.Item("Eigenes Bild …") { chooseMedia(video = false) },
                GlassMenu.Item("Eigenes Video …") { chooseMedia(video = true) },
            ) + Extensions.installed(this).map { ext ->
                GlassMenu.Item("${ext.label} …") { chooseFromExtension(ext) }
            } + ProjectivyPlugins.installed(this).map { plugin ->
                GlassMenu.Item("Plugin: ${plugin.name} …") { pluginMenu(plugin) }
            } + GlassMenu.Item("Plugins installieren …") { pluginStore() },
            footer = "Erweiterungen und Projectivy-Plugins sind eigene Apps mit Hintergründen. " +
                "Installierte erscheinen hier automatisch.",
        )
    }

    private fun chooseFromExtension(ext: Extensions.Extension) {
        toast("${ext.label} wird geladen …")
        Thread {
            val items = Extensions.wallpapers(this, ext.authority)
            runOnUiThread {
                if (items.isEmpty()) {
                    toast("${ext.label} bietet gerade keine Hintergründe an")
                    return@runOnUiThread
                }
                fun use(uri: String?, type: String) {
                    prefs.background = "extension"
                    prefs.extAuthority = ext.authority
                    prefs.extUri = uri
                    prefs.extType = type
                    render()
                }
                val all = if (items.any { it.type == "video" }) listOf(GlassMenu.Item("Alle Videos abwechselnd") { use(null, "video") }) else emptyList()
                GlassMenu.show(this, ext.label, all + items.map { w ->
                    val label = if (w.subtitle.isBlank()) w.title else "${w.title} · ${w.subtitle}"
                    GlassMenu.Item(label) { use(w.uri, w.type) }
                }, footer = "Videos werden beim ersten Abspielen einmal heruntergeladen und danach vom Fernseher abgespielt.")
            }
        }.start()
    }

    // ---- Projectivy plugins ----

    private fun pluginMenu(plugin: ProjectivyPlugins.Plugin) {
        val items = mutableListOf(
            GlassMenu.Item("Als Hintergrund verwenden") {
                prefs.background = "plugin"
                prefs.plugin = plugin.component.flattenToString()
                prefs.pluginPin = null
                render()
            },
            GlassMenu.Item("Wechseln alle ${prefs.pluginMinutes} Minuten …") { choosePluginMinutes() },
        )
        val settings = plugin.settings
        if (settings != null) items += GlassMenu.Item("Einstellungen des Plugins") {
            open(Intent().setComponent(settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        GlassMenu.show(this, plugin.name, items, footer = "Auf dem Startbildschirm im Dock nach oben drücken: dort schaltest du weiter oder wählst mit Vorschau aus. " +
            "Manche Plugins brauchen zuerst Einstellungen, z. B. die Adresse deines Jellyfin-Servers.")
    }

    private fun choosePluginMinutes() {
        val options = listOf(1, 5, 10, 30, 60)
        GlassMenu.show(this, "Hintergrund wechseln alle", options.map { m ->
            GlassMenu.Item(if (m == 1) "1 Minute" else "$m Minuten") { prefs.pluginMinutes = m; render() }
        }, focus = options.indexOf(prefs.pluginMinutes).coerceAtLeast(0))
    }

    private fun pluginStore() {
        GlassMenu.show(this, "Plugins installieren", PluginStore.catalog.map { e ->
            val state = if (PluginStore.isInstalled(this, e)) "  ✓ installiert" else ""
            GlassMenu.Item("${e.name}$state") { PluginStore.install(this, e) }
        }, footer = "Wallpaper-Plugins für Projectivy Launcher, direkt von der GitHub-Seite ihrer Entwickler. " +
            "Beim ersten Mal fragt der Fernseher, ob der Glas Launcher Apps installieren darf.")
    }

    // ---- Weather places ----

    private fun placeMenu(place: Place, index: Int) {
        val places = prefs.places
        val items = mutableListOf<GlassMenu.Item>()
        if (index > 0) items += GlassMenu.Item("Als Hauptort festlegen") {
            prefs.places = listOf(place) + (places - place)
            render()
        }
        if (places.size > 1) items += GlassMenu.Item("Entfernen") {
            prefs.places = places - place
            render()
        }
        if (items.isEmpty()) {
            toast("Füge zuerst einen weiteren Ort hinzu")
            return
        }
        GlassMenu.show(this, place.name, items, footer = place.region)
    }

    private fun addPlace() {
        TextPrompt.ask(this, "Ort suchen", "z. B. Düsseldorf oder Palma") { name ->
            if (name.isBlank()) return@ask
            Thread {
                val found = runCatching { WeatherApi.search(name) }
                runOnUiThread {
                    val results = found.getOrNull()
                    when {
                        results == null -> toast("Keine Verbindung zur Ortssuche")
                        results.isEmpty() -> toast("Keinen Ort „$name“ gefunden")
                        else -> GlassMenu.show(this, "Ort wählen", results.map { p ->
                            GlassMenu.Item(if (p.region.isBlank()) p.name else "${p.name} · ${p.region}") {
                                if (prefs.places.none { it.key == p.key }) prefs.places = prefs.places + p
                                toast("${p.name} hinzugefügt")
                                render()
                            }
                        })
                    }
                }
            }.start()
        }
    }

    private fun chooseMedia(video: Boolean) = withPermission(Media.permission(video)) {
        val items = Media.list(this, video)
        if (items.isEmpty()) {
            toast(
                "Keine ${if (video) "Videos" else "Bilder"} gefunden. Kopiere sie z. B. mit " +
                    "„adb push datei /sdcard/${if (video) "Movies" else "Pictures"}/“ auf den Fernseher.",
            )
            return@withPermission
        }
        GlassMenu.show(this, if (video) "Video wählen" else "Bild wählen", items.map { item ->
            GlassMenu.Item(item.name) {
                if (video) {
                    prefs.videoUri = item.uri
                    prefs.background = "video"
                    warnIfHeavy(item)
                } else {
                    prefs.imageUri = item.uri
                    prefs.background = "image"
                }
                render()
            }
        })
    }

    /** Large, fast or HDR videos can stutter on a TV; say so and suggest a lighter format. */
    private fun warnIfHeavy(item: Media.Item) {
        Thread {
            val info = Media.videoInfo(this, item.uri) ?: return@Thread
            val reasons = buildList {
                if (info.height > 1440) add("4K-Auflösung")
                if (info.fps > 31) add("${info.fps.toInt()} Bilder pro Sekunde")
                if (info.hdr) add("HDR")
            }
            if (reasons.isEmpty()) return@Thread
            runOnUiThread {
                GlassMenu.show(this, "Hinweis zu „${item.name}“", listOf(GlassMenu.Item("Verstanden") {}),
                    footer = "Dieses Video hat ${reasons.joinToString(", ")}. Das kann als Hintergrund ruckeln. " +
                        "Am flüssigsten laufen Videos in 1080p mit 25 oder 30 Bildern pro Sekunde, ohne HDR " +
                        "(H.264 oder HEVC als MP4).")
            }
        }.start()
    }

    private fun rateLabel(rate: Int) = if (rate <= 0) "Standbild" else "$rate× pro Sekunde"

    private fun chooseGlassRate() {
        val options = listOf(0, 1, 2, 4, 8, 15)
        GlassMenu.show(this, "Milchglas über Videos", options.map { r ->
            GlassMenu.Item(rateLabel(r) + if (r == 2) "  (Standard)" else "") { prefs.glassVideoRate = r; render() }
        }, focus = options.indexOf(prefs.glassVideoRate).coerceAtLeast(0),
            footer = "Wie oft das Milchglas das Bild eines Hintergrundvideos übernimmt. Höhere Werte sehen lebendiger aus, " +
                "kosten aber Leistung. Ruckelt das Video, einen kleineren Wert wählen.")
    }

    private fun chooseGlass() {
        GlassMenu.show(this, "Milchglas", Ui.glassNames.mapIndexed { i, name ->
            GlassMenu.Item(name) { prefs.glass = i; render() }
        }, focus = prefs.glass)
    }

    // ---- Parental lock ----

    private fun changePin() {
        if (prefs.pinHash == null) {
            Pin.create(this, prefs) { render() }
            return
        }
        withPin {
            GlassMenu.show(
                this, "PIN",
                listOf(
                    GlassMenu.Item("PIN ändern") { Pin.create(this, prefs) { render() } },
                    GlassMenu.Item("PIN entfernen und alle Sperren aufheben") {
                        prefs.pinHash = null
                        prefs.locked = emptySet()
                        prefs.protectSettings = false
                        prefs.lockAllApps = false
                        toast("PIN entfernt")
                        render()
                    },
                ),
            )
        }
    }

    /** Switching a protection needs the PIN; the first one sets it up. */
    private fun toggleProtection(toggle: () -> Unit) {
        if (prefs.pinHash == null) Pin.create(this, prefs) { toggle(); render() }
        else withPin { toggle(); render() }
    }

    private fun chooseLocked(focus: Int) {
        val apps = Apps.installed(this)
        val locked = prefs.locked
        GlassMenu.show(this, "Gesperrte Apps", apps.mapIndexed { i, app ->
            val on = app.pkg in locked
            GlassMenu.Item((if (on) "🔒  " else "      ") + app.label) {
                if (!on && prefs.pinHash == null) {
                    Pin.create(this, prefs) { prefs.locked = prefs.locked + app.pkg; chooseLocked(i) }
                } else {
                    prefs.locked = if (on) locked - app.pkg else locked + app.pkg
                    chooseLocked(i)
                }
                render()
            }
        }, focus = focus, footer = "OK schaltet die Sperre für eine App an oder aus. Zurück schließt die Liste.")
    }

    private fun chooseTimeApp(focus: Int) {
        val apps = Apps.installed(this)
        val windows = prefs.timeWindows
        GlassMenu.show(this, "Zeitfenster", apps.mapIndexed { i, app ->
            val w = windows[app.pkg]
            GlassMenu.Item((if (w != null) "⏰  " else "      ") + app.label + (w?.let { "  ·  " + TimeLimits.label(it) } ?: "")) {
                TimeLimits.choose(this, prefs, app.label, app.pkg) { render(); chooseTimeApp(i) }
            }
        }, focus = focus, footer = "Wähle eine App, um ihr Zeitfenster festzulegen.")
    }

    // ---- Performance ----

    private fun freeMemory() {
        toast("Gebe Arbeitsspeicher frei …")
        Thread {
            val gained = runCatching { Memory.free(this) }.getOrDefault(0L)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (gained > 20_000_000) "${Memory.mb(gained)} freigegeben" else "Der Speicher war schon weitgehend frei")
                render()
            }
        }.start()
    }

    // ---- Backup ----

    private fun storagePermission(action: () -> Unit) =
        if (Build.VERSION.SDK_INT <= 28) withPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, action) else action()

    private fun exportSettings() = storagePermission {
        Thread {
            val where = Backup.export(this)
            runOnUiThread { toast(where?.let { "Gesichert in $it" } ?: "Sichern hat nicht geklappt") }
        }.start()
    }

    private fun importSettings() = storagePermission {
        GlassMenu.show(this, "Einstellungen wiederherstellen?", listOf(
            GlassMenu.Item("Abbrechen") {},
            GlassMenu.Item("Wiederherstellen") {
                Thread {
                    val ok = Backup.import(this)
                    runOnUiThread {
                        if (ok) { prefs = Prefs(this); toast("Einstellungen wiederhergestellt"); render() }
                        else toast("Keine Sicherung gefunden (${Backup.FILE})")
                    }
                }.start()
            },
        ), footer = "Die aktuellen Einstellungen werden durch die gesicherten ersetzt.")
    }

    // ---- Updates ----

    private fun chooseUpdateRepo() = withPin {
        TextPrompt.ask(this, "Update-Quelle", "GitHub-Name/Projekt, z. B. name/glas-launcher") { text ->
            val repo = text.trim().removePrefix("https://github.com/").trim('/')
            prefs.updateRepo = repo.takeIf { it.count { c -> c == '/' } == 1 }
            if (repo.isNotEmpty() && prefs.updateRepo == null) toast("Bitte im Format Name/Projekt eingeben")
            render()
        }
    }

    private fun checkUpdate() {
        val repo = prefs.updateRepo ?: return chooseUpdateRepo()
        toast("Suche nach Updates …")
        Thread {
            val result = runCatching { Updater.check(this, repo) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val update = result.getOrNull()
                when {
                    result.isFailure -> toast("GitHub ist nicht erreichbar oder das Projekt hat keine Versionen")
                    update == null -> toast("Du hast die neueste Version (${Updater.installedVersion(this)})")
                    else -> GlassMenu.show(this, "Version ${update.version} ist da", listOf(
                        GlassMenu.Item("Jetzt installieren") { Updater.install(this, repo) },
                        GlassMenu.Item("Später") {},
                    ), footer = "Deine Einstellungen bleiben beim Update erhalten.")
                }
            }
        }.start()
    }

    // ---- Home screen ----

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun redirectOn(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true

    private fun homeStatus() = when {
        isDefaultHome() -> "Aktiv"
        redirectOn() -> "Aktiv über Umleitung"
        else -> "Nicht eingerichtet"
    }

    private fun makeDefault() {
        if (isDefaultHome()) {
            toast("Der Glas Launcher ist bereits dein Startbildschirm")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME)
                    return
                } catch (e: ActivityNotFoundException) {
                    // Falls through to the home settings below
                }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            GlassMenu.show(
                this, "Als Standard festlegen",
                listOf(GlassMenu.Item("Home-Taste umleiten einschalten") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
                footer = "Dein Fernseher erlaubt keine direkte Auswahl. Schalte die Home-Taste-Umleitung ein " +
                    "oder deaktiviere die Google-Oberfläche per adb (siehe Hinweis in den Einstellungen).",
            )
        }
    }

    // ---- Screensaver ----

    private fun idleLabel(min: Int) = if (min == 0) "Aus" else "$min Minuten"

    private fun chooseIdle() {
        val options = listOf(0, 2, 5, 10, 15, 30)
        GlassMenu.show(this, "Bildschirmschoner", options.map { m ->
            GlassMenu.Item(idleLabel(m)) { prefs.idleMinutes = m; render() }
        }, focus = options.indexOf(prefs.idleMinutes))
    }

    // ---- Permissions ----

    // ---- Own pictures and videos ----

    private fun megabytes(bytes: Long) = if (bytes < 1_000_000) "leer" else "${bytes / 1_000_000} MB"

    private fun manageMedia() = withPermission(Media.permission(video = false)) {
        withPermission(Media.permission(video = true)) {
            val items = Media.list(this, video = false).map { "🖼  ${it.name}" to it } +
                Media.list(this, video = true).map { "🎬  ${it.name}" to it }
            if (items.isEmpty()) {
                toast("Keine eigenen Bilder oder Videos gefunden")
                return@withPermission
            }
            GlassMenu.show(this, "Eigene Bilder und Videos", items.map { (label, item) ->
                GlassMenu.Item(label) { confirmDelete(item) }
            }, footer = "Wähle eine Datei aus, um sie vom Fernseher zu löschen.")
        }
    }

    private fun confirmDelete(item: Media.Item) {
        GlassMenu.show(this, "„${item.name}“ löschen?", listOf(
            GlassMenu.Item("Abbrechen") { manageMedia() },
            GlassMenu.Item("Löschen") { delete(item) },
        ), footer = "Die Datei wird endgültig vom Fernseher gelöscht.")
    }

    private var deleting: Media.Item? = null

    private fun delete(item: Media.Item) {
        // Before Android 10 deleting needs the storage write permission
        if (Build.VERSION.SDK_INT <= 28) {
            return withPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) { deleteNow(item) }
        }
        deleteNow(item)
    }

    private fun deleteNow(item: Media.Item) {
        Thread {
            val result = Media.delete(this, item.uri)
            runOnUiThread {
                when (result) {
                    Media.Deletion.Done -> deleted(item)
                    Media.Deletion.Failed -> toast("„${item.name}“ ließ sich nicht löschen")
                    is Media.Deletion.Confirm -> {
                        // Android asks the user itself; the answer comes back in onActivityResult
                        deleting = item
                        runCatching {
                            @Suppress("DEPRECATION")
                            startIntentSenderForResult(result.sender, REQUEST_DELETE, null, 0, 0, 0)
                        }.onFailure { toast("„${item.name}“ ließ sich nicht löschen") }
                    }
                }
            }
        }.start()
    }

    private fun deleted(item: Media.Item) {
        toast("„${item.name}“ gelöscht")
        // The deleted file was the background: fall back to the weather
        val name = item.name
        val usedAsImage = prefs.background == "image" && prefs.imageUri?.let { it == item.uri || it.endsWith(name) } == true
        val usedAsVideo = prefs.background == "video" && prefs.videoUri?.let { it == item.uri || it.endsWith(name) } == true
        if (usedAsImage || usedAsVideo) {
            prefs.background = "weather"
            toast("Der Hintergrund zeigt wieder das Wetter")
        }
        render()
        manageMedia()
    }

    @Deprecated("Needed for the delete confirmation on older APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DELETE) return
        val item = deleting ?: return
        deleting = null
        when {
            resultCode != RESULT_OK -> toast("Nicht gelöscht")
            // On Android 10 the confirmation only grants access; the delete itself still has to happen
            Build.VERSION.SDK_INT == 29 -> deleteNow(item)
            else -> deleted(item)
        }
    }

    private fun clearDownloads() {
        val size = MediaCache.size(this)
        if (size < 1_000_000) return toast("Es sind keine heruntergeladenen Videos gespeichert")
        GlassMenu.show(this, "Heruntergeladene Videos löschen?", listOf(
            GlassMenu.Item("Abbrechen") {},
            GlassMenu.Item("Löschen (${megabytes(size)})") {
                MediaCache.clear(this)
                toast("Gelöscht. Videos werden beim nächsten Abspielen neu geladen.")
                render()
            },
        ), footer = "Betrifft Videos aus Erweiterungen wie „Glas Natur-Videos“ und zwischengespeicherte Bilder. Deine eigenen Dateien bleiben erhalten.")
    }

    private fun withPermission(permission: String, action: () -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return action()
        afterPermission = action
        requestPermissions(arrayOf(permission), REQUEST_MEDIA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = afterPermission
        afterPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
        else toast("Ohne diese Berechtigung geht das leider nicht")
    }

    private companion object {
        const val REQUEST_HOME = 1
        const val REQUEST_MEDIA = 2
        const val REQUEST_DELETE = 3
    }
}
