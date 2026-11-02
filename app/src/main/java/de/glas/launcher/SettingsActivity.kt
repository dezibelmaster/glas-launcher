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
        PluginStore.resumePending(this)
    }

    private fun render() {
        val focused = list.indexOfChild(list.focusedChild)
        list.removeAllViews()

        list.addView(Ui.text(this, 34f, Ui.thin).apply {
            text = tr("Einstellungen")
            setPadding(dp(8), 0, 0, dp(8))
        })

        section(tr("Sprache") + if (I18n.lang != "en") " · Language" else "")
        row(tr("Sprache") + if (I18n.lang != "en") " · Language" else "", I18n.nameOf(prefs.language)) { chooseLanguage() }

        section(tr("Aussehen"))
        row(tr("Hintergrund"), backgroundLabel()) { chooseBackground() }
        row(tr("Milchglas"), Ui.glassNames[prefs.glass]) { chooseGlass() }
        row(tr("Milchglas über Videos"), rateLabel(prefs.glassVideoRate)) { chooseGlassRate() }
        row(tr("Eigene Bilder und Videos verwalten")) { manageMedia() }
        row(tr("Heruntergeladene Videos löschen"), megabytes(MediaCache.size(this))) { clearDownloads() }
        row(tr("Oben anzeigen"), if (prefs.topStyle == "classic") tr("Große Uhr und Wetterkarte") else tr("Statusleiste")) {
            GlassMenu.show(this, tr("Oben anzeigen"), listOf(
                GlassMenu.Item(tr("Statusleiste: Wetter, WLAN, Einstellungen, Datum, Uhrzeit")) { prefs.topStyle = "bar"; render() },
                GlassMenu.Item(tr("Große Uhr und Wetterkarte")) { prefs.topStyle = "classic"; render() },
            ), focus = if (prefs.topStyle == "classic") 1 else 0)
        }
        note(
            tr("Eigene Wetterfotos: Lege Bilder in den Ordner Pictures/GlasLauncher auf dem Fernseher, z. B. clear_day.jpg, partly_day.jpg, overcast_day.jpg, fog_day.jpg, rain_day.jpg, snow_day.jpg, storm_day.jpg und für die Nacht z. B. clear_night.jpg. Sie ersetzen die eingebauten Fotos."),
        )

        section(tr("Wetter"))
        val places = prefs.places
        places.forEachIndexed { i, place ->
            row(place.name, if (i == 0) tr("Hauptort") else "") { placeMenu(place, i) }
        }
        row(tr("Ort hinzufügen …")) { addPlace() }
        note(tr("Der Hauptort bestimmt den Wetter-Hintergrund. Weitere Orte erscheinen klein unter der Wetterkarte."))

        section(tr("Dock"))
        note(tr("Halte OK auf einer App gedrückt oder drücke die Menü-Taste, um sie zu verschieben, zu entfernen oder mit PIN zu sperren. Neue Apps fügst du unter „Alle Apps“ hinzu."))
        row(tr("Dock zurücksetzen")) {
            prefs.dock = null
            toast(tr("Das Dock zeigt wieder YouTube, Netflix, MagentaTV und Jellyfin"))
        }

        section(tr("Kindersicherung"))
        row("PIN", if (prefs.pinHash == null) tr("Festlegen") else tr("Ändern")) { changePin() }
        row(tr("Gesperrte Apps"), "${prefs.locked.size}") { withPin { chooseLocked(0) } }
        row(tr("„Alle Apps“ mit PIN schützen"), if (prefs.lockAllApps) tr("An") else tr("Aus")) {
            toggleProtection { prefs.lockAllApps = !prefs.lockAllApps }
        }
        row(tr("Einstellungen mit PIN schützen"), if (prefs.protectSettings) tr("An") else tr("Aus")) {
            toggleProtection { prefs.protectSettings = !prefs.protectSettings }
        }
        row(tr("Zeitfenster"), if (prefs.timeWindows.isEmpty()) tr("keine") else tr("{0} Apps", prefs.timeWindows.size)) { chooseTimeApp(0) }
        note(tr("Mit Zeitfenster lässt sich eine App nur zu bestimmten Uhrzeiten ohne PIN öffnen, z. B. 06:00 bis 20:00 Uhr."))

        section(tr("Startbildschirm"))
        row(tr("Status"), homeStatus()) {}
        row(tr("Als Standard festlegen")) { makeDefault() }
        row(tr("Home-Taste umleiten"), if (redirectOn()) tr("An") else tr("Aus")) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        note(
            tr("Google TV bietet keine Auswahl für den Startbildschirm. Zwei Wege:\n1. Home-Taste umleiten: In den Bedienungshilfen „Glas Launcher“ einschalten. Kommt eine Meldung „Eingeschränkte Einstellung“, zuerst unter Apps → Glas Launcher → ⋮ „Eingeschränkte Einstellungen zulassen“ wählen.\n2. Google-Oberfläche abschalten (am zuverlässigsten), am Computer:\nadb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx"),
        )

        section(tr("Bildschirmschoner"))
        row(tr("Im Launcher starten nach"), idleLabel(prefs.idleMinutes)) { chooseIdle() }
        row(tr("Uhrzeit anzeigen"), if (prefs.saverClock) tr("An") else tr("Aus")) { prefs.saverClock = !prefs.saverClock; render() }
        row(tr("Datum anzeigen"), if (prefs.saverDate) tr("An") else tr("Aus")) { prefs.saverDate = !prefs.saverDate; render() }
        row(tr("Wetter anzeigen"), if (prefs.saverWeather) tr("An") else tr("Aus")) { prefs.saverWeather = !prefs.saverWeather; render() }
        row(tr("Jetzt anzeigen")) { startActivity(Intent(this, ScreensaverActivity::class.java)) }
        row(tr("System-Bildschirmschoner öffnen")) { open(Intent(Settings.ACTION_DREAM_SETTINGS)) }
        note(
            tr("Damit der Glas-Bildschirmschoner auch in anderen Apps erscheint, am Computer:\nadb shell settings put secure screensaver_components de.glas.launcher/.ClockDream"),
        )

        section(tr("Leistung"))
        val mem = Memory.info(this)
        row(tr("Arbeitsspeicher frei"), tr("{0} von {1}", Memory.gb(mem.available), Memory.gb(mem.total))) { render() }
        row(tr("Davon nutzt Glas Launcher"), Memory.mb(mem.launcher)) {}
        row(tr("Arbeitsspeicher jetzt freigeben")) { freeMemory() }
        row(tr("Automatisch freigeben beim Bildschirmschoner"), if (prefs.autoFreeMemory) tr("An") else tr("Aus")) {
            prefs.autoFreeMemory = !prefs.autoFreeMemory; render()
        }
        note(
            tr("Freigeben beendet Apps, die im Hintergrund ruhen (z. B. YouTube nach dem Zurückkehren). Sie starten beim nächsten Öffnen etwas langsamer neu. Android räumt den Speicher auch selbst auf; Freigeben hilft vor allem, wenn eine App ruckelt oder abstürzt. Laufende Systemdienste bleiben unberührt."),
        )

        section(tr("Sichern"))
        row(tr("Einstellungen sichern")) { exportSettings() }
        row(tr("Einstellungen wiederherstellen")) { importSettings() }
        note(
            tr("Gesichert werden Dock, PIN und Sperren, Zeitfenster, Orte, Favoriten und Hintergrund in die Datei Download/{0}. Auf einen anderen Fernseher bringst du sie z. B. mit Bugjaeger nach /sdcard/Android/data/{1}/files/ und wählst dann „Wiederherstellen“.", Backup.FILE, packageName),
        )

        section(tr("Updates"))
        row(tr("Installierte Version"), Updater.installedVersion(this)) {}
        row(tr("Update-Quelle (GitHub)"), prefs.updateRepo ?: tr("nicht festgelegt")) { chooseUpdateRepo() }
        row(tr("Jetzt nach Updates suchen")) { checkUpdate() }
        note(tr("Ist eine Quelle festgelegt, sucht Glas Launcher einmal am Tag nach einer neuen Version und zeigt einen Hinweis."))

        section(tr("Fernseher"))
        row(tr("Fernseher-Einstellungen")) { open(Intent(Settings.ACTION_SETTINGS)) }

        section(tr("Info"))
        row("Glas Launcher", versionOf(packageName) ?: "") {}
        versionOf("de.glas.launcher.videos")?.let { row("Glas Natur-Videos", it) {} }

        val target = list.getChildAt(focused)?.takeIf { it.isFocusable } ?: (0 until list.childCount).map { list.getChildAt(it) }.first { it.isFocusable }
        target.requestFocus()
    }

    /** "Version 2.21" of an installed app, or null if it isn't installed. */
    private fun versionOf(pkg: String): String? =
        runCatching { packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()?.let { tr("Version {0}", it) }

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
            toast(tr("Diese Einstellung gibt es auf deinem Fernseher nicht"))
        }
    }

    private fun withPin(action: () -> Unit) = Pin.verify(this, prefs, tr("PIN eingeben"), action)

    // ---- Appearance ----

    private fun backgroundLabel() = when (prefs.background) {
        "image" -> tr("Eigenes Bild")
        "video" -> if (prefs.videoUri == null) tr("Eigene Videos · alle") else tr("Eigenes Video")
        "extension" -> if (prefs.extUri == null) tr("Erweiterung · alle Videos") else tr("Erweiterung")
        "plugin" -> ProjectivyPlugins.find(this, prefs.plugin)?.name ?: tr("Plugin nicht mehr installiert")
        else -> when (prefs.weatherStyle) {
            "modern" -> tr("Wetter · Modern")
            "abstract" -> tr("Wetter · Abstrakt")
            "lifelike" -> tr("Wetter · Realistisch animiert")
            else -> tr("Wetter · Realistisch")
        }
    }

    private fun chooseBackground() {
        fun weather(style: String) = { prefs.background = "weather"; prefs.weatherStyle = style; render() }
        GlassMenu.show(
            this, tr("Hintergrund"),
            listOf(
                GlassMenu.Item(tr("Wetter · Realistisch (Fotos)"), weather("realistic")),
                GlassMenu.Item(tr("Wetter · Realistisch animiert"), weather("lifelike")),
                GlassMenu.Item(tr("Wetter · Modern (gezeichnet)"), weather("modern")),
                GlassMenu.Item(tr("Wetter · Abstrakt (Farbverläufe)"), weather("abstract")),
                GlassMenu.Item(tr("Eigenes Bild …")) { chooseMedia(video = false) },
                GlassMenu.Item(tr("Eigenes Video …")) { chooseMedia(video = true) },
            ) + Extensions.installed(this).map { ext ->
                GlassMenu.Item("${ext.label} …") { chooseFromExtension(ext) }
            } + ProjectivyPlugins.installed(this).map { plugin ->
                GlassMenu.Item(tr("Plugin: {0} …", plugin.name)) { pluginMenu(plugin) }
            } + GlassMenu.Item(tr("Plugins installieren …")) { pluginStore() },
            footer = tr("Erweiterungen und Projectivy-Plugins sind eigene Apps mit Hintergründen. Installierte erscheinen hier automatisch."),
        )
    }

    private fun chooseFromExtension(ext: Extensions.Extension) {
        toast(tr("{0} wird geladen …", ext.label))
        Thread {
            val items = Extensions.wallpapers(this, ext.authority)
            runOnUiThread {
                if (items.isEmpty()) {
                    toast(tr("{0} bietet gerade keine Hintergründe an", ext.label))
                    return@runOnUiThread
                }
                fun use(uri: String?, type: String) {
                    prefs.background = "extension"
                    prefs.extAuthority = ext.authority
                    prefs.extUri = uri
                    prefs.extType = type
                    render()
                }
                val all = if (items.any { it.type == "video" }) listOf(GlassMenu.Item(tr("Alle Videos abwechselnd")) { use(null, "video") }) else emptyList()
                GlassMenu.show(this, ext.label, all + items.map { w ->
                    val label = if (w.subtitle.isBlank()) w.title else "${w.title} · ${w.subtitle}"
                    GlassMenu.Item(label) { use(w.uri, w.type) }
                }, footer = tr("Videos werden beim ersten Abspielen einmal heruntergeladen und danach vom Fernseher abgespielt."))
            }
        }.start()
    }

    // ---- Projectivy plugins ----

    private fun pluginMenu(plugin: ProjectivyPlugins.Plugin) {
        val items = mutableListOf(
            GlassMenu.Item(tr("Als Hintergrund verwenden")) {
                prefs.background = "plugin"
                prefs.plugin = plugin.component.flattenToString()
                prefs.pluginPin = null
                render()
            },
            GlassMenu.Item(tr("Wechseln alle {0} Minuten …", prefs.pluginMinutes)) { choosePluginMinutes() },
        )
        val settings = plugin.settings
        if (settings != null) items += GlassMenu.Item(tr("Einstellungen des Plugins")) {
            open(Intent().setComponent(settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        GlassMenu.show(this, plugin.name, items, footer = tr("Auf dem Startbildschirm im Dock nach oben drücken: dort schaltest du weiter oder wählst mit Vorschau aus. Manche Plugins brauchen zuerst Einstellungen, z. B. die Adresse deines Jellyfin-Servers."))
    }

    private fun choosePluginMinutes() {
        val options = listOf(1, 5, 10, 30, 60)
        GlassMenu.show(this, tr("Hintergrund wechseln alle"), options.map { m ->
            GlassMenu.Item(if (m == 1) tr("1 Minute") else tr("{0} Minuten", m)) { prefs.pluginMinutes = m; render() }
        }, focus = options.indexOf(prefs.pluginMinutes).coerceAtLeast(0))
    }

    private fun pluginStore() {
        GlassMenu.show(this, tr("Plugins installieren"), PluginStore.catalog.map { e ->
            val state = if (PluginStore.isInstalled(this, e)) tr("  ✓ installiert") else ""
            GlassMenu.Item("${e.name}$state") { PluginStore.install(this, e) }
        }, footer = tr("Wallpaper-Plugins für Projectivy Launcher, direkt von der GitHub-Seite ihrer Entwickler. Beim ersten Mal fragt der Fernseher, ob der Glas Launcher Apps installieren darf."))
    }

    // ---- Weather places ----

    private fun placeMenu(place: Place, index: Int) {
        val places = prefs.places
        val items = mutableListOf<GlassMenu.Item>()
        if (index > 0) items += GlassMenu.Item(tr("Als Hauptort festlegen")) {
            prefs.places = listOf(place) + (places - place)
            render()
        }
        if (places.size > 1) items += GlassMenu.Item(tr("Entfernen")) {
            prefs.places = places - place
            render()
        }
        if (items.isEmpty()) {
            toast(tr("Füge zuerst einen weiteren Ort hinzu"))
            return
        }
        GlassMenu.show(this, place.name, items, footer = place.region)
    }

    private fun addPlace() {
        TextPrompt.ask(this, tr("Ort suchen"), tr("z. B. Düsseldorf oder Palma")) { name ->
            if (name.isBlank()) return@ask
            Thread {
                val found = runCatching { WeatherApi.search(name) }
                runOnUiThread {
                    val results = found.getOrNull()
                    when {
                        results == null -> toast(tr("Keine Verbindung zur Ortssuche"))
                        results.isEmpty() -> toast(tr("Keinen Ort „{0}“ gefunden", name))
                        else -> GlassMenu.show(this, tr("Ort wählen"), results.map { p ->
                            GlassMenu.Item(if (p.region.isBlank()) p.name else "${p.name} · ${p.region}") {
                                if (prefs.places.none { it.key == p.key }) prefs.places = prefs.places + p
                                toast(tr("{0} hinzugefügt", p.name))
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
                tr("Keine {0} gefunden. Kopiere sie z. B. mit „adb push datei /sdcard/{1}/“ auf den Fernseher.", if (video) tr("Videos") else tr("Bilder"), if (video) "Movies" else "Pictures"),
            )
            return@withPermission
        }
        val all = if (video && items.size > 1) listOf(GlassMenu.Item(tr("Alle eigenen Videos abwechselnd")) {
            prefs.videoUri = null
            prefs.background = "video"
            render()
        }) else emptyList()
        GlassMenu.show(this, if (video) tr("Video wählen") else tr("Bild wählen"), all + items.map { item ->
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
                if (info.height > 1440) add(tr("4K-Auflösung"))
                if (info.fps > 31) add(tr("{0} Bilder pro Sekunde", info.fps.toInt()))
                if (info.hdr) add("HDR")
            }
            if (reasons.isEmpty()) return@Thread
            runOnUiThread {
                GlassMenu.show(this, tr("Hinweis zu „{0}“", item.name), listOf(GlassMenu.Item(tr("Verstanden")) {}),
                    footer = tr("Dieses Video hat {0}. Das kann als Hintergrund ruckeln. Am flüssigsten laufen Videos in 1080p mit 25 oder 30 Bildern pro Sekunde, ohne HDR (H.264 oder HEVC als MP4).", reasons.joinToString(", ")))
            }
        }.start()
    }

    private fun rateLabel(rate: Int) = if (rate <= 0) tr("Standbild") else tr("{0}× pro Sekunde", rate)

    private fun chooseGlassRate() {
        val options = listOf(0, 1, 2, 4, 8, 15)
        GlassMenu.show(this, tr("Milchglas über Videos"), options.map { r ->
            GlassMenu.Item(rateLabel(r) + if (r == 2) tr("  (Standard)") else "") { prefs.glassVideoRate = r; render() }
        }, focus = options.indexOf(prefs.glassVideoRate).coerceAtLeast(0),
            footer = tr("Wie oft das Milchglas das Bild eines Hintergrundvideos übernimmt. Höhere Werte sehen lebendiger aus, kosten aber Leistung. Ruckelt das Video, einen kleineren Wert wählen."))
    }

    private fun chooseGlass() {
        GlassMenu.show(this, tr("Milchglas"), Ui.glassNames.mapIndexed { i, name ->
            GlassMenu.Item(name) { prefs.glass = i; render() }
        }, focus = prefs.glass)
    }

    private fun chooseLanguage() {
        val codes = listOf<String?>(null) + I18n.languages.map { it.code }
        GlassMenu.show(this, tr("Sprache"), codes.map { code ->
            GlassMenu.Item(I18n.nameOf(code) + if (code == prefs.language) "  ✓" else "") {
                if (code == prefs.language) return@Item
                prefs.language = code
                I18n.init(this)
                recreate()
            }
        }, focus = codes.indexOf(prefs.language).coerceAtLeast(0))
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
                    GlassMenu.Item(tr("PIN ändern")) { Pin.create(this, prefs) { render() } },
                    GlassMenu.Item(tr("PIN entfernen und alle Sperren aufheben")) {
                        prefs.pinHash = null
                        prefs.locked = emptySet()
                        prefs.protectSettings = false
                        prefs.lockAllApps = false
                        toast(tr("PIN entfernt"))
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
        GlassMenu.show(this, tr("Gesperrte Apps"), apps.mapIndexed { i, app ->
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
        }, focus = focus, footer = tr("OK schaltet die Sperre für eine App an oder aus. Zurück schließt die Liste."))
    }

    private fun chooseTimeApp(focus: Int) {
        val apps = Apps.installed(this)
        val windows = prefs.timeWindows
        GlassMenu.show(this, tr("Zeitfenster"), apps.mapIndexed { i, app ->
            val w = windows[app.pkg]
            GlassMenu.Item((if (w != null) "⏰  " else "      ") + app.label + (w?.let { "  ·  " + TimeLimits.label(it) } ?: "")) {
                TimeLimits.choose(this, prefs, app.label, app.pkg) { render(); chooseTimeApp(i) }
            }
        }, focus = focus, footer = tr("Wähle eine App, um ihr Zeitfenster festzulegen."))
    }

    // ---- Performance ----

    private fun freeMemory() {
        toast(tr("Gebe Arbeitsspeicher frei …"))
        Thread {
            val gained = runCatching { Memory.free(this) }.getOrDefault(0L)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (gained > 20_000_000) tr("{0} freigegeben", Memory.mb(gained)) else tr("Der Speicher war schon weitgehend frei"))
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
            runOnUiThread { toast(where?.let { tr("Gesichert in {0}", it) } ?: tr("Sichern hat nicht geklappt")) }
        }.start()
    }

    private fun importSettings() = storagePermission {
        GlassMenu.show(this, tr("Einstellungen wiederherstellen?"), listOf(
            GlassMenu.Item(tr("Abbrechen")) {},
            GlassMenu.Item(tr("Wiederherstellen")) {
                Thread {
                    val ok = Backup.import(this)
                    runOnUiThread {
                        if (ok) { prefs = Prefs(this); I18n.init(this); toast(tr("Einstellungen wiederhergestellt")); recreate() }
                        else toast(tr("Keine Sicherung gefunden ({0})", Backup.FILE))
                    }
                }.start()
            },
        ), footer = tr("Die aktuellen Einstellungen werden durch die gesicherten ersetzt."))
    }

    // ---- Updates ----

    private fun chooseUpdateRepo() = withPin {
        TextPrompt.ask(this, tr("Update-Quelle"), tr("GitHub-Name/Projekt, z. B. name/glas-launcher")) { text ->
            val repo = text.trim().removePrefix("https://github.com/").trim('/')
            prefs.updateRepo = repo.takeIf { it.count { c -> c == '/' } == 1 }
            if (repo.isNotEmpty() && prefs.updateRepo == null) toast(tr("Bitte im Format Name/Projekt eingeben"))
            render()
        }
    }

    private fun checkUpdate() {
        val repo = prefs.updateRepo ?: return chooseUpdateRepo()
        toast(tr("Suche nach Updates …"))
        Thread {
            val result = runCatching { Updater.check(this, repo) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val update = result.getOrNull()
                when {
                    result.isFailure -> toast(tr("GitHub ist nicht erreichbar oder das Projekt hat keine Versionen"))
                    update == null -> toast(tr("Du hast die neueste Version ({0})", Updater.installedVersion(this)))
                    else -> GlassMenu.show(this, tr("Version {0} ist da", update.version), listOf(
                        GlassMenu.Item(tr("Jetzt installieren")) { Updater.install(this, repo) },
                        GlassMenu.Item(tr("Später")) {},
                    ), footer = tr("Deine Einstellungen bleiben beim Update erhalten."))
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
        isDefaultHome() -> tr("Aktiv")
        redirectOn() -> tr("Aktiv über Umleitung")
        else -> tr("Nicht eingerichtet")
    }

    private fun makeDefault() {
        if (isDefaultHome()) {
            toast(tr("Der Glas Launcher ist bereits dein Startbildschirm"))
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
                this, tr("Als Standard festlegen"),
                listOf(GlassMenu.Item(tr("Home-Taste umleiten einschalten")) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
                footer = tr("Dein Fernseher erlaubt keine direkte Auswahl. Schalte die Home-Taste-Umleitung ein oder deaktiviere die Google-Oberfläche per adb (siehe Hinweis in den Einstellungen)."),
            )
        }
    }

    // ---- Screensaver ----

    private fun idleLabel(min: Int) = if (min == 0) tr("Aus") else tr("{0} Minuten", min)

    private fun chooseIdle() {
        val options = listOf(0, 2, 5, 10, 15, 30)
        GlassMenu.show(this, tr("Bildschirmschoner"), options.map { m ->
            GlassMenu.Item(idleLabel(m)) { prefs.idleMinutes = m; render() }
        }, focus = options.indexOf(prefs.idleMinutes))
    }

    // ---- Permissions ----

    // ---- Own pictures and videos ----

    private fun megabytes(bytes: Long) = if (bytes < 1_000_000) tr("leer") else "${bytes / 1_000_000} MB"

    private fun manageMedia() = withPermission(Media.permission(video = false)) {
        withPermission(Media.permission(video = true)) {
            val items = Media.list(this, video = false).map { "🖼  ${it.name}" to it } +
                Media.list(this, video = true).map { "🎬  ${it.name}" to it }
            if (items.isEmpty()) {
                toast(tr("Keine eigenen Bilder oder Videos gefunden"))
                return@withPermission
            }
            GlassMenu.show(this, tr("Eigene Bilder und Videos"), items.map { (label, item) ->
                GlassMenu.Item(label) { confirmDelete(item) }
            }, footer = tr("Wähle eine Datei aus, um sie vom Fernseher zu löschen."))
        }
    }

    private fun confirmDelete(item: Media.Item) {
        GlassMenu.show(this, tr("„{0}“ löschen?", item.name), listOf(
            GlassMenu.Item(tr("Abbrechen")) { manageMedia() },
            GlassMenu.Item(tr("Löschen")) { delete(item) },
        ), footer = tr("Die Datei wird endgültig vom Fernseher gelöscht."))
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
                    Media.Deletion.Failed -> toast(tr("„{0}“ ließ sich nicht löschen", item.name))
                    is Media.Deletion.Confirm -> {
                        // Android asks the user itself; the answer comes back in onActivityResult
                        deleting = item
                        runCatching {
                            @Suppress("DEPRECATION")
                            startIntentSenderForResult(result.sender, REQUEST_DELETE, null, 0, 0, 0)
                        }.onFailure { toast(tr("„{0}“ ließ sich nicht löschen", item.name)) }
                    }
                }
            }
        }.start()
    }

    private fun deleted(item: Media.Item) {
        toast(tr("„{0}“ gelöscht", item.name))
        // The deleted file was the background: fall back to the weather
        val name = item.name
        val usedAsImage = prefs.background == "image" && prefs.imageUri?.let { it == item.uri || it.endsWith(name) } == true
        val usedAsVideo = prefs.background == "video" && prefs.videoUri?.let { it == item.uri || it.endsWith(name) } == true
        if (usedAsImage || usedAsVideo) {
            prefs.background = "weather"
            toast(tr("Der Hintergrund zeigt wieder das Wetter"))
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
            resultCode != RESULT_OK -> toast(tr("Nicht gelöscht"))
            // On Android 10 the confirmation only grants access; the delete itself still has to happen
            Build.VERSION.SDK_INT == 29 -> deleteNow(item)
            else -> deleted(item)
        }
    }

    private fun clearDownloads() {
        val size = MediaCache.size(this)
        if (size < 1_000_000) return toast(tr("Es sind keine heruntergeladenen Videos gespeichert"))
        GlassMenu.show(this, tr("Heruntergeladene Videos löschen?"), listOf(
            GlassMenu.Item(tr("Abbrechen")) {},
            GlassMenu.Item(tr("Löschen ({0})", megabytes(size))) {
                MediaCache.clear(this)
                toast(tr("Gelöscht. Videos werden beim nächsten Abspielen neu geladen."))
                render()
            },
        ), footer = tr("Betrifft Videos aus Erweiterungen wie „Glas Natur-Videos“ und zwischengespeicherte Bilder. Deine eigenen Dateien bleiben erhalten."))
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
        else toast(tr("Ohne diese Berechtigung geht das leider nicht"))
    }

    private companion object {
        const val REQUEST_HOME = 1
        const val REQUEST_MEDIA = 2
        const val REQUEST_DELETE = 3
    }
}
