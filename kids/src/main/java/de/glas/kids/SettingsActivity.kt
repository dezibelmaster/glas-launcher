package de.glas.kids

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Settings for the parents. Only reachable with the PIN, from the start screen of Glas Kids. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
        }
        val scroll = ScrollView(this).apply {
            background = Ui.glass(this@SettingsActivity, 28f)
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        setContentView(FrameLayout(this).apply {
            background = Ui.sky()
            addView(scroll, FrameLayout.LayoutParams(dp(680), MATCH_PARENT, Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(36); bottomMargin = dp(36)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        build(0)
    }

    /** Fills the list and puts the focus back on row [focus]. */
    private fun build(focus: Int) {
        list.removeAllViews()
        list.addView(Ui.text(this, 30f, Ui.medium).apply {
            text = tr("Glas Kids · Einstellungen")
            setPadding(dp(4), 0, 0, dp(16))
        })
        val rows = mutableListOf<View>()
        fun row(title: String, value: String?, action: () -> Unit) {
            val index = rows.size
            val r = Ui.row(this, title, value) { action(); }
            r.tag = index
            rows += r
            list.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        fun refresh(index: Int) = build(index)

        val guardOn = GuardService.isOn(this)
        row(tr("Kindermodus"), if (prefs.enabled) tr("An") else tr("Aus")) {
            if (!prefs.enabled && !guardOn) {
                GlassMenu.info(this, tr("Wächter einschalten"), HomeActivity.guardHelp(),
                    listOf(GlassMenu.Item(tr("Bedienungshilfen öffnen")) { HomeActivity.openAccessibility(this) }))
            } else {
                prefs.enabled = !prefs.enabled
                refresh(0)
            }
        }
        row(tr("Wächter (Bedienungshilfe)"), if (guardOn) tr("An") else tr("Aus")) {
            GlassMenu.info(this, tr("Wächter"), HomeActivity.guardHelp(),
                listOf(GlassMenu.Item(tr("Bedienungshilfen öffnen")) { HomeActivity.openAccessibility(this) }))
        }
        row(tr("Fernsehzeit pro Tag"), ScreenTime.dailyLabel(prefs.dailyMinutes)) {
            choose(tr("Fernsehzeit pro Tag"), ScreenTime.dailyPresets, prefs.dailyMinutes, ScreenTime::dailyLabel,
                tr("Gezählt wird nur, solange YouTube Kids läuft.")) { prefs.dailyMinutes = it; refresh(2) }
        }
        row(tr("Erlaubte Uhrzeit"), ScreenTime.windowLabel(prefs.window)) {
            choose(tr("Erlaubte Uhrzeit"), ScreenTime.windowPresets, prefs.window, ScreenTime::windowLabel,
                tr("Außerhalb dieser Zeit zeigt der Fernseher „Fernsehzeit ist vorbei“.")) { prefs.window = it; refresh(3) }
        }
        row(tr("Restzeit einblenden"), ScreenTime.warnLabel(prefs.warnMinutes)) {
            choose(tr("Restzeit einblenden"), ScreenTime.warnPresets, prefs.warnMinutes, ScreenTime::warnLabel, null) {
                prefs.warnMinutes = it; refresh(4)
            }
        }
        row(tr("Heute geschaut"), ScreenTime.durationLabel((ScreenTime.usedMs(prefs) / 60_000).toInt())) {
            GlassMenu.show(this, tr("Heute geschaut"), listOf(
                GlassMenu.Item(tr("Auf 0 zurücksetzen")) { ScreenTime.resetToday(prefs); refresh(5) },
                GlassMenu.Item(tr("Abbrechen")) {},
            ))
        }
        row(tr("Zusätzlich erlaubte Apps"), prefs.extraApps.size.let { if (it == 0) tr("keine") else it.toString() }) {
            chooseApps()
        }
        row(tr("PIN ändern"), "••••") { Pin.create(this, prefs) {} }
        row(tr("Sprache"), I18n.nameOf(prefs.language)) {
            val codes = listOf<String?>(null) + I18n.languages.map { it.code }
            choose(tr("Sprache"), codes, prefs.language, { I18n.nameOf(it) }, null) {
                prefs.language = it
                I18n.init(this)
                refresh(8)
            }
        }
        row(tr("Version"), packageManager.getPackageInfo(packageName, 0).versionName) {}
        row(tr("Fertig"), null) { finish() }

        rows.getOrNull(focus)?.requestFocus()
    }

    private fun <T> choose(title: String, options: List<T>, current: T, label: (T) -> String, footer: String?, set: (T) -> Unit) {
        GlassMenu.show(this, title, options.map { o ->
            GlassMenu.Item(label(o) + if (o == current) "  ✓" else "") { set(o) }
        }, focus = options.indexOf(current).coerceAtLeast(0), footer = footer)
    }

    /** Lets the parents allow apps besides YouTube Kids, e.g. an audio book app. */
    private fun chooseApps() {
        val apps = Apps.launchable(this)
        val allowed = prefs.extraApps
        GlassMenu.show(this, tr("Zusätzlich erlaubte Apps"), apps.map { app ->
            GlassMenu.Item(app.label + if (app.pkg in allowed) "  ✓" else "") {
                prefs.extraApps = if (app.pkg in allowed) allowed - app.pkg else allowed + app.pkg
                chooseApps()
            }
        }, footer = tr("Erlaubte Apps erscheinen im Kindermodus klein unter YouTube Kids. Ihre Zeit zählt zur Fernsehzeit.")).also {
            it.setOnDismissListener { build(6) }
        }
    }
}
