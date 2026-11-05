package de.glas.smart

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Connect to the Tuya cloud project, plus the few settings the app has. */
class SetupActivity : Activity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val scroll = ScrollView(this)
        val root = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(root)
        fitBars(scroll)
        setContentView(scroll)

        val head = horizontal()
        if (store.configured) head.addView(text(28f).apply {
            text = "‹"
            setPadding(dp(4), 0, dp(16), dp(4))
            setOnClickListener { finish() }
        })
        head.addView(text(28f, face = light).apply { text = if (store.configured) "Einstellungen" else "Willkommen" }, weight())
        root.addView(head, matchWrap().margins(t = dp(8), b = dp(6)))

        if (!store.configured) {
            root.addView(text(16f, MUTED).apply {
                text = "Glas Smart steuert deine Smart-Life-Geräte ohne Werbung. Die Geräte bleiben in der Smart-Life-App " +
                    "angelernt; Glas Smart greift über einen eigenen, kostenlosen Zugang zur Tuya-Cloud darauf zu. " +
                    "Das musst du nur einmal einrichten (etwa 10 Minuten, am besten am Computer)."
            }, matchWrap().margins(b = dp(12)))
        }

        root.addView(panel("Zugang zur Tuya-Cloud", connectBox()), matchWrap().margins(t = dp(6), b = dp(12)))
        root.addView(panel("So bekommst du Access ID und Secret", guide()), matchWrap().margins(b = dp(12)))

        if (store.configured) {
            val box = vertical()
            box.addView(switchRow("Nach Räumen sortieren", store.byRoom, true) { store.byRoom = it })
            box.addView(text(13f, MUTED).apply {
                text = "Räume und Szenen kommen aus Smart Life. Fehlen sie, gruppiert die App nach Art der Geräte."
            }, matchWrap().margins(b = dp(8)))
            box.addView(switchRow("Ausgeblendete Geräte zeigen", store.showHidden, true) { store.showHidden = it })
            box.addView(text(13f, MUTED).apply {
                text = "Geräte blendest du in der Übersicht aus, indem du sie lange gedrückt hältst."
            })
            root.addView(panel("Ansicht", box), matchWrap().margins(b = dp(12)))

            root.addView(pill("Zugangsdaten löschen", RED) {
                AlertDialog.Builder(this).setTitle("Zugangsdaten löschen?")
                    .setMessage("Access ID, Secret und alle zwischengespeicherten Daten werden von diesem Handy entfernt. " +
                        "Deine Geräte in Smart Life bleiben unverändert.")
                    .setPositiveButton("Löschen") { _, _ ->
                        store.clear()
                        Repo.devices = emptyList()
                        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    .setNegativeButton("Abbrechen", null).show()
            }, matchWrap().margins(t = dp(8)))
        }

        root.addView(text(12f, 0x66F3F6FA).apply {
            text = "Glas Smart ${packageManager.getPackageInfo(packageName, 0).versionName} · ohne Werbung, ohne Tracking. " +
                "Die Zugangsdaten bleiben auf diesem Handy und gehen nur an die Tuya-Cloud."
        }, matchWrap().margins(t = dp(20)))
    }

    private fun field(label: String, value: String, secret: Boolean) = EditText(this).apply {
        hint = label
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(0x66F3F6FA)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = glass(14f, 0x14FFFFFF, 0x2EFFFFFF)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun connectBox(): View {
        val box = vertical()
        val id = field("Access ID / Client ID", store.clientId, false)
        val secret = field("Access Secret / Client Secret", store.secret, true)
        box.addView(id, matchWrap().margins(t = dp(4), b = dp(10)))
        box.addView(secret, matchWrap().margins(b = dp(10)))

        var region = store.region
        val regionText = text(16f).apply {
            background = glass(14f, 0x14FFFFFF, 0x2EFFFFFF)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        fun showRegion() {
            regionText.text = "Rechenzentrum: ${Tuya.region(region).label}  ▾"
        }
        showRegion()
        regionText.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Rechenzentrum")
                .setSingleChoiceItems(Tuya.regions.map { it.label }.toTypedArray(), Tuya.regions.indexOfFirst { it.key == region }) { d, i ->
                    region = Tuya.regions[i].key
                    showRegion()
                    d.dismiss()
                }.show()
        }
        box.addView(regionText, matchWrap().margins(b = dp(12)))

        val status = text(14f, MUTED).apply { visibility = View.GONE }
        val connect = pill(if (store.configured) "Speichern und neu verbinden" else "Verbinden", AMBER) {}
        connect.setOnClickListener {
            val api = Tuya(id.text.toString().trim(), secret.text.toString().trim(), Tuya.region(region).host)
            if (id.text.isBlank() || secret.text.isBlank()) {
                status.text = "Bitte Access ID und Access Secret eintragen."
                status.visibility = View.VISIBLE
                return@setOnClickListener
            }
            status.text = "Verbinde …"
            status.setTextColor(MUTED)
            status.visibility = View.VISIBLE
            connect.isEnabled = false
            Tuya.forgetToken()
            async({ api.devices() }) { r ->
                connect.isEnabled = true
                r.onSuccess { list ->
                    val changed = store.clientId != id.text.toString().trim() || store.region != region
                    store.clientId = id.text.toString()
                    store.secret = secret.text.toString()
                    store.region = region
                    if (changed) {
                        store.rooms = org.json.JSONArray()
                        store.scenes = org.json.JSONArray()
                        store.homeName = ""
                    }
                    store.roomsLoadedAt = 0
                    Repo.devices = list
                    Repo.save(store)
                    if (list.isEmpty()) {
                        status.setTextColor(AMBER)
                        status.text = "Verbunden, aber keine Geräte gefunden. Ist dein Smart-Life-Konto im Projekt verknüpft " +
                            "(Schritt 5) und das richtige Rechenzentrum gewählt?"
                    } else {
                        toast("Verbunden: ${list.size} Geräte")
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                }
                r.onFailure {
                    status.setTextColor(RED)
                    status.text = Tuya.explain(it)
                }
            }
        }
        box.addView(connect, matchWrap())
        box.addView(status, matchWrap().margins(t = dp(10)))
        return box
    }

    private fun guide(): View {
        val box = vertical()
        val steps = listOf(
            "Auf iot.tuya.com ein kostenloses Konto anlegen (Land: Deutschland). Das ist ein Entwicklerkonto, " +
                "getrennt von deinem Smart-Life-Konto.",
            "Cloud → Development → Create Cloud Project. Name beliebig, Industry „Smart Home“, Development Method " +
                "„Smart Home“, Data Center „Central Europe Data Center“.",
            "Beim Anlegen werden die nötigen API-Dienste vorgeschlagen (u. a. IoT Core, Authorization). Einfach bestätigen. " +
                "Für Räume und Szenen zusätzlich „Smart Home Basic Service“ und „Smart Home Scene Linkage“ hinzufügen, falls angeboten.",
            "Im Projekt unter Overview stehen Access ID und Access Secret. Beide oben eintragen.",
            "Im Projekt: Devices → Link App Account → Add App Account. Es erscheint ein QR-Code. In der Smart-Life-App " +
                "unter „Ich“ oben rechts auf das Scan-Symbol tippen, den Code scannen und bestätigen.",
            "Hier auf „Verbinden“ tippen. Danach kannst du die Smart-Life-App behalten (zum Anlernen neuer Geräte) " +
                "und im Alltag Glas Smart nutzen.",
        )
        steps.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text(15f, AMBER, medium).apply { text = "${i + 1}." }, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(text(15f).apply { text = s; setLineSpacing(0f, 1.15f) }, weight())
            box.addView(row, matchWrap().margins(b = dp(10)))
        }
        box.addView(text(13f, MUTED).apply {
            text = "Hinweis: Der kostenlose IoT-Core-Dienst läuft nach einer Weile ab. Dann zeigt die App einen Hinweis; " +
                "auf iot.tuya.com unter Cloud → Cloud Services → IoT Core lässt er sich kostenlos verlängern."
            setLineSpacing(0f, 1.15f)
        }, matchWrap().margins(b = dp(12)))
        box.addView(pill("iot.tuya.com öffnen", null) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iot.tuya.com")))
        }, wrap())
        return box
    }
}
