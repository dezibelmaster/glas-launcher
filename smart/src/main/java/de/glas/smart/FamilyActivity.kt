package de.glas.smart

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Family sharing: who belongs to the home in Smart Life (invite, admin rights, remove),
 * and setting up Glas Smart on another phone of the family.
 */
class FamilyActivity : Activity() {
    private lateinit var store: Store
    private lateinit var homesBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Repo.load(store)

        val scroll = ScrollView(this)
        val root = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(root)
        fitBars(scroll)
        setContentView(scroll)

        val head = horizontal()
        head.addView(text(28f).apply {
            text = "‹"
            setPadding(dp(4), 0, dp(16), dp(4))
            setOnClickListener { finish() }
        })
        head.addView(text(28f, face = light).apply { text = "Familie" }, weight())
        root.addView(head, matchWrap().margins(t = dp(8), b = dp(6)))

        root.addView(text(15f, MUTED).apply {
            text = "Mitglieder deines Zuhauses sehen und schalten alle Geräte, wie in Smart Life. " +
                "Admins dürfen zusätzlich Geräte und Mitglieder verwalten."
        }, matchWrap().margins(b = dp(8)))

        homesBox = vertical()
        root.addView(homesBox, matchWrap())

        root.addView(sectionTitle("Glas Smart auf einem weiteren Handy"))
        root.addView(panel(null, otherPhoneBox()), matchWrap())

        loadHomes()
    }

    // ---- Members ----

    private fun loadHomes() {
        homesBox.removeAllViews()
        homesBox.addView(text(15f, MUTED).apply { text = "Lade Mitglieder …" }, matchWrap().margins(t = dp(12)))
        val uids = Repo.devices.map { it.uid }.filter { it.isNotEmpty() }.distinct()
        val api = store.api()
        async({
            val homes = uids.flatMap { api.homes(it) }.distinctBy { it.id }
            homes.map { h -> h to runCatching { api.members(h.id) } }
        }) { r ->
            homesBox.removeAllViews()
            r.onFailure { error(Tuya.explain(it)) }
            r.onSuccess { list ->
                if (list.isEmpty()) error("Kein Zuhause gefunden. Ist dein Smart-Life-Konto im Tuya-Projekt verknüpft?")
                for ((home, members) in list) homeSection(home, members)
            }
        }
    }

    private fun error(msg: String) {
        homesBox.addView(text(15f, TEXT).apply {
            text = "⚠  $msg\n\nFür die Familienfreigabe braucht das Tuya-Projekt den Dienst „Smart Home Basic Service“ " +
                "(unter Cloud services). Antippen zum Wiederholen."
            background = glass(16f, 0x33FF6B6B, 0x66FF6B6B)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { loadHomes() }
        }, matchWrap().margins(t = dp(12)))
    }

    private fun homeSection(home: Tuya.Home, members: Result<List<Tuya.Member>>) {
        homesBox.addView(sectionTitle("🏠 ${home.name.ifBlank { "Zuhause" }}"))
        val box = vertical()
        members.onFailure {
            box.addView(text(15f, RED).apply { text = Tuya.explain(it) })
        }
        members.onSuccess { list ->
            for (m in list.sortedWith(compareBy({ !it.owner }, { !it.admin }, { it.name.lowercase() }))) {
                box.addView(memberRow(home, m), matchWrap().margins(b = dp(8)))
            }
        }
        box.addView(pill("＋ Mitglied einladen", AMBER) { invite(home) }, wrap().margins(t = dp(6)))
        homesBox.addView(panel(null, box), matchWrap())
    }

    private fun memberRow(home: Tuya.Home, m: Tuya.Member) = horizontal().apply {
        setPadding(0, dp(6), 0, dp(6))
        addView(text(20f).apply {
            text = m.name.take(1).uppercase().ifEmpty { "?" }
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (m.owner || m.admin) 0x40FFC857 else 0x1FFFFFFF)
            }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val texts = vertical()
        texts.addView(text(17f, face = medium).apply { text = m.name })
        texts.addView(text(13f, MUTED).apply {
            text = listOf(if (m.owner) "Besitzer" else if (m.admin) "Admin" else "Mitglied", m.account.takeIf { it != m.name })
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        })
        addView(texts, weight().margins(l = dp(12)))
        if (!m.owner) {
            addView(text(22f, MUTED).apply { text = "⋮"; setPadding(dp(12), 0, dp(4), 0) })
            isClickable = true
            setOnClickListener { memberMenu(home, m) }
        }
    }

    private fun memberMenu(home: Tuya.Home, m: Tuya.Member) {
        val items = arrayOf(if (m.admin) "Admin-Rechte entziehen" else "Zum Admin machen", "Aus dem Zuhause entfernen")
        AlertDialog.Builder(this).setTitle(m.name).setItems(items) { _, which ->
            if (which == 0) run("Gespeichert") { store.api().setAdmin(home.id, m.uid, !m.admin) }
            else AlertDialog.Builder(this).setTitle("${m.name} entfernen?")
                .setMessage("${m.name} kann danach keine Geräte in „${home.name}“ mehr sehen oder schalten.")
                .setPositiveButton("Entfernen") { _, _ -> run("${m.name} entfernt") { store.api().removeMember(home.id, m.uid) } }
                .setNegativeButton("Abbrechen", null).show()
        }.show()
    }

    private fun invite(home: Tuya.Home) {
        fun input(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint
            inputType = type
            isSingleLine = true
        }
        val name = input("Name, z. B. Anna", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val account = input("E-Mail oder Handynummer", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val country = input("Ländervorwahl", InputType.TYPE_CLASS_NUMBER).apply { setText("49") }
        val admin = CheckBox(this).apply { text = "Admin (darf Geräte und Mitglieder verwalten)" }
        val form = vertical().apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(text(14f, MUTED).apply {
                text = "Die Person braucht ein eigenes Smart-Life-Konto. Gib die E-Mail-Adresse oder Handynummer ein, " +
                    "mit der sie sich dort anmeldet."
            }, matchWrap().margins(b = dp(8)))
            addView(name, matchWrap())
            addView(account, matchWrap())
            addView(text(13f, MUTED).apply { text = "Ländervorwahl ohne + (Deutschland 49, Österreich 43, Schweiz 41)" }, matchWrap().margins(t = dp(8)))
            addView(country, matchWrap())
            addView(admin, matchWrap().margins(t = dp(6)))
        }
        AlertDialog.Builder(this).setTitle("Mitglied einladen in „${home.name}“").setView(form)
            .setPositiveButton("Einladen") { _, _ ->
                val acc = account.text.toString().trim().replace(" ", "")
                if (acc.isEmpty()) {
                    toast("Bitte E-Mail oder Handynummer eingeben.")
                    return@setPositiveButton
                }
                val n = name.text.toString().trim().ifEmpty { acc.substringBefore('@') }
                run("$n hinzugefügt. Zeigt Smart Life eine Einladung, muss $n sie dort annehmen.",
                    "Hat die Person schon ein Smart-Life-Konto mit dieser E-Mail/Nummer und derselben Ländervorwahl?") {
                    store.api().addMember(home.id, acc, country.text.toString().trim().removePrefix("+").ifEmpty { "49" }, n, admin.isChecked)
                }
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun run(success: String, failHint: String? = null, work: () -> Unit) {
        async(work) { r ->
            r.onSuccess {
                toast(success)
                loadHomes()
            }
            r.onFailure {
                val msg = Tuya.explain(it) + (failHint?.let { h -> "\n\n$h" } ?: "")
                AlertDialog.Builder(this).setTitle("Hat nicht geklappt").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    // ---- Another phone ----

    private fun otherPhoneBox(): View {
        val box = vertical()
        box.addView(text(15f).apply {
            text = "Damit z. B. dein Partner auch Glas Smart nutzen kann, ohne alles einzurichten: Glas Smart dort installieren " +
                "und den Einrichtungslink übertragen. Er enthält deinen Tuya-Zugang, also nur an Menschen geben, die " +
                "alle Geräte schalten dürfen."
            setLineSpacing(0f, 1.15f)
        }, matchWrap().margins(b = dp(12)))

        val qr = ImageView(this).apply { visibility = View.GONE }
        box.addView(qr, LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        val hint = text(13f, MUTED).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            text = "Auf dem anderen Handy mit der Kamera scannen und den Link mit Glas Smart öffnen, oder den Link kopieren " +
                "und in Glas Smart unter „Einrichtungslink einfügen“ einsetzen."
        }
        box.addView(hint, matchWrap().margins(b = dp(10)))

        val buttons = horizontal()
        buttons.addView(pill("QR-Code zeigen", AMBER) {
            qr.setImageBitmap(qrCode(setupLink(), dp(240)))
            qr.visibility = View.VISIBLE
            hint.visibility = View.VISIBLE
        }, wrap().margins(r = dp(8)))
        buttons.addView(pill("Link senden", null) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Einrichtungslink für Glas Smart (nicht weitergeben):\n${setupLink()}"), "Link senden"))
        })
        box.addView(buttons, matchWrap())
        return box
    }

    private fun setupLink() = Uri.Builder().scheme("glassmart").authority("setup")
        .appendQueryParameter("id", store.clientId)
        .appendQueryParameter("secret", store.secret)
        .appendQueryParameter("region", store.region)
        .build().toString()

    private fun qrCode(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { if (m.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
