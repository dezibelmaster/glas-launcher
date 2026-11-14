package de.glas.entdecker

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var vorschau: PreviewView
    private lateinit var sucher: View
    private lateinit var hinweis: TextView
    private lateinit var albumKnopf: TextView
    private lateinit var wechselKnopf: TextView
    private lateinit var leiste: LinearLayout
    private lateinit var karte: LinearLayout
    private lateinit var karteStreifen: View
    private lateinit var karteEmoji: TextView
    private lateinit var karteName: TextView
    private lateinit var karteSpruch: TextView
    private lateinit var karteNeu: TextView
    private var album: View? = null
    private var erlaubnisAnsicht: View? = null

    private lateinit var sprecher: Sprecher
    private val analyse: ExecutorService = Executors.newSingleThreadExecutor()
    private var erkenner: Erkenner? = null // nur im Analyse-Thread benutzen
    private val beruhiger = Beruhiger()

    private var kameraProvider: ProcessCameraProvider? = null
    private var vorschauFall: Preview? = null
    private var analyseFall: ImageAnalysis? = null
    private var vorne = false

    @Volatile private var beschaeftigt = false // spricht gerade oder Album offen
    @Volatile private var vergessen = false
    @Volatile private var zerstoert = false
    @Volatile private var vorschauBreite = 0
    @Volatile private var vorschauHoehe = 0
    @Volatile private var sucherSeite = 0
    private var letzteAnalyse = 0L
    private var aktuell: Ding? = null
    private var zuletztGesagt: Ding? = null
    private var zuletztZeit = 0L
    private var endeZeit = 0L

    private val prefs by lazy { getSharedPreferences("entdecker", MODE_PRIVATE) }

    private val erlaubnis = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) starteKamera() else zeigeErlaubnisBitte()
    }

    private fun dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        baueOberflaeche()
        sprecher = Sprecher(this) { sprache ->
            if (!sprache) hinweis.text = "Zeig mir etwas! 🔍\n(Ohne deutsche Sprachausgabe: nur Geräusche)"
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) starteKamera()
        else erlaubnis.launch(Manifest.permission.CAMERA)
    }

    // ---------- Oberfläche ----------

    private fun rund(farbe: Int, radius: Number, rand: Int = 0, randFarbe: Int = 0) = GradientDrawable().apply {
        setColor(farbe)
        cornerRadius = dp(radius).toFloat()
        if (rand > 0) setStroke(dp(rand), randFarbe)
    }

    private fun text(s: String, sp: Float, farbe: Int = Color.WHITE, fett: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(farbe)
        gravity = Gravity.CENTER
        if (fett) typeface = Typeface.DEFAULT_BOLD
    }

    private fun knopf(s: String) = text(s, 24f, Color.WHITE, true).apply {
        background = rund(0x99000000.toInt(), 28)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        minWidth = dp(56)
        minHeight = dp(56)
        isClickable = true
        isFocusable = true
    }

    private fun baueOberflaeche() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        vorschau = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(vorschau, FrameLayout.LayoutParams(-1, -1))
        vorschau.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            vorschauBreite = v.width
            vorschauHoehe = v.height
        }

        // Rahmen in der Mitte: dort hinein halten
        sucher = View(this).apply { background = rund(0x22FFFFFF, 36, 5, 0xEEFFFFFF.toInt()) }
        root.addView(sucher, FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER))
        sucher.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> sucherSeite = v.width }
        ObjectAnimator.ofFloat(sucher, "alpha", 1f, 0.45f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        hinweis = text("Zeig mir etwas! 🔍", 24f, Color.WHITE, true).apply {
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        root.addView(hinweis, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        leiste = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        albumKnopf = knopf("⭐ 0").apply { setOnClickListener { zeigeAlbum() } }
        wechselKnopf = knopf("🔄").apply { setOnClickListener { wechsleKamera() } }
        leiste.addView(albumKnopf)
        leiste.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        leiste.addView(wechselKnopf)
        root.addView(leiste, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        karte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rund(Color.WHITE, 32)
            clipToOutline = true
            elevation = dp(12).toFloat()
            visibility = View.INVISIBLE
            setOnClickListener { aktuell?.let { nochmal(it) } }
        }
        karteStreifen = View(this)
        karte.addView(karteStreifen, LinearLayout.LayoutParams(-1, dp(14)))
        karteNeu = text("⭐ Neu im Album!", 16f, 0xFFE65100.toInt(), true).apply { setPadding(0, dp(8), 0, 0) }
        karte.addView(karteNeu)
        karteEmoji = text("", 80f)
        karte.addView(karteEmoji)
        karteName = text("", 38f, 0xFF212121.toInt(), true)
        karte.addView(karteName)
        karteSpruch = text("", 21f, 0xFF424242.toInt()).apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        karte.addView(karteSpruch)
        karte.addView(text("👆 Antippen: nochmal hören", 14f, 0xFF9E9E9E.toInt()).apply { setPadding(0, dp(6), 0, dp(16)) })
        root.addView(karte, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(16), 0, dp(16), dp(16))
        })

        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { _, ins ->
            leiste.setPadding(dp(16) + ins.systemWindowInsetLeft, dp(16) + ins.systemWindowInsetTop, dp(16) + ins.systemWindowInsetRight, dp(16))
            (karte.layoutParams as FrameLayout.LayoutParams).setMargins(
                dp(16) + ins.systemWindowInsetLeft, 0, dp(16) + ins.systemWindowInsetRight, dp(16) + ins.systemWindowInsetBottom
            )
            karte.requestLayout()
            album?.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(root)
        passeGroesseAn()
        zeigeZahl()
    }

    /** Karte und Rahmen an Hoch- und Querformat anpassen. */
    private fun passeGroesseAn() {
        val m = resources.displayMetrics
        val quer = m.widthPixels > m.heightPixels
        val seite = (min(m.widthPixels, m.heightPixels) * if (quer) 0.55f else 0.7f).toInt()
        sucher.layoutParams = (sucher.layoutParams as FrameLayout.LayoutParams).apply { width = seite; height = seite }
        val lp = karte.layoutParams as FrameLayout.LayoutParams
        lp.width = if (quer) dp(420) else -1
        lp.gravity = if (quer) Gravity.END or Gravity.BOTTOM else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        karteEmoji.textSize = if (quer) 56f else 80f
        root.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        passeGroesseAn()
        val r = vorschau.display?.rotation ?: return
        analyseFall?.targetRotation = r
        vorschauFall?.targetRotation = r
        if (album != null) { schliesseAlbum(); zeigeAlbum() }
    }

    /** Nach der Ansage die Karte wieder wegnehmen, damit das Kind das Nächste suchen kann. */
    private val versteckeKarte = Runnable {
        if (sprecher.spricht) return@Runnable
        karte.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(250).withEndAction {
            karte.visibility = View.INVISIBLE
            hinweis.visibility = View.VISIBLE
        }.start()
    }

    private var erzaehlNummer = 0

    private fun zeigeKarte(ding: Ding, neu: Boolean) {
        root.removeCallbacks(versteckeKarte)
        aktuell = ding
        karteStreifen.setBackgroundColor(ding.gruppe.farbe)
        karteEmoji.text = ding.emoji
        karteName.text = ding.name
        karteSpruch.text = ding.spruch
        karteNeu.visibility = if (neu) View.VISIBLE else View.GONE
        hinweis.visibility = View.GONE
        karte.visibility = View.VISIBLE
        karte.scaleX = 0.6f
        karte.scaleY = 0.6f
        karte.alpha = 0f
        karte.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(2.2f)).start()
        karteEmoji.animate().cancel()
        karteEmoji.rotation = -12f
        karteEmoji.animate().rotation(0f).setDuration(600).setInterpolator(OvershootInterpolator(4f)).start()
    }

    // ---------- Kamera ----------

    private fun starteKamera() {
        erlaubnisAnsicht?.let { root.removeView(it) }
        erlaubnisAnsicht = null
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            kameraProvider = f.get()
            binde()
        }, mainExecutor)
    }

    private fun binde() {
        val p = kameraProvider ?: return
        val rotation = vorschau.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider = vorschau.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .build()
        analysis.setAnalyzer(analyse) { analysiere(it) }
        var auswahl = if (vorne) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            if (!p.hasCamera(auswahl)) {
                vorne = !vorne
                auswahl = if (vorne) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            }
            p.unbindAll()
            p.bindToLifecycle(this, auswahl, preview, analysis)
            vorschauFall = preview
            analyseFall = analysis
            val beide = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            wechselKnopf.visibility = if (beide) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            hinweis.text = "Die Kamera geht gerade nicht. 📷"
        }
    }

    private fun wechsleKamera() {
        vorne = !vorne
        vergessen = true
        binde()
    }

    /** Läuft im Analyse-Thread. */
    private fun analysiere(bild: ImageProxy) {
        val jetzt = SystemClock.uptimeMillis()
        if (zerstoert || beschaeftigt || jetzt - letzteAnalyse < 200) {
            bild.close()
            return
        }
        letzteAnalyse = jetzt
        val drehung = bild.imageInfo.rotationDegrees
        val ganz: Bitmap
        try {
            ganz = bild.toBitmap()
        } catch (e: Exception) {
            return
        } finally {
            bild.close()
        }
        // Genau das anschauen, was im Rahmen auf dem Bildschirm zu sehen ist (etwas großzügiger)
        val gedreht = drehung % 180 != 0
        val iw = (if (gedreht) ganz.height else ganz.width).toFloat()
        val ih = (if (gedreht) ganz.width else ganz.height).toFloat()
        val kurz = min(ganz.width, ganz.height)
        val vw = vorschauBreite
        val vh = vorschauHoehe
        val sw = sucherSeite
        val seite = if (vw > 0 && vh > 0 && sw > 0) {
            val massstab = maxOf(vw / iw, vh / ih) // so skaliert die Vorschau das Bild (FILL_CENTER)
            (sw / massstab * 1.15f).toInt().coerceIn(64, kurz)
        } else {
            (kurz * 0.6f).toInt()
        }
        val mitte = Bitmap.createBitmap(ganz, (ganz.width - seite) / 2, (ganz.height - seite) / 2, seite, seite)

        val e = erkenner ?: try {
            Erkenner(applicationContext).also { erkenner = it }
        } catch (ex: Throwable) {
            runOnUiThread { hinweis.text = "Die Erkennung lässt sich nicht starten. 😕" }
            return
        }
        if (vergessen) {
            beruhiger.vergiss()
            vergessen = false
        }
        val treffer = try { e.erkenne(mitte, drehung) } catch (ex: Exception) { null }
        val sicher = beruhiger.neu(treffer) ?: return
        runOnUiThread { gefunden(sicher.ding) }
    }

    private fun gefunden(ding: Ding) {
        if (zerstoert || beschaeftigt || sprecher.spricht) return
        val jetzt = SystemClock.uptimeMillis()
        if (jetzt - endeZeit < 1000) return
        if (ding == zuletztGesagt && jetzt - zuletztZeit < 15_000) return
        zuletztGesagt = ding
        zuletztZeit = jetzt
        val neu = merke(ding)
        zeigeKarte(ding, neu)
        erzaehle(ding)
    }

    private fun erzaehle(ding: Ding) {
        beschaeftigt = true
        val nummer = ++erzaehlNummer
        val fertig = {
            if (nummer == erzaehlNummer) {
                beschaeftigt = album != null
                endeZeit = SystemClock.uptimeMillis()
                zuletztZeit = endeZeit
                vergessen = true
                root.postDelayed(versteckeKarte, 3000)
            }
        }
        sprecher.erzaehle(ding) { fertig() }
        // Notbremse: Hängt die Ausgabe trotzdem, nach 25 Sekunden wieder suchen
        root.postDelayed({
            if (nummer == erzaehlNummer && sprecher.spricht) {
                sprecher.stopp()
                fertig()
            }
        }, 25_000)
    }

    private fun nochmal(ding: Ding) {
        zeigeKarte(ding, false)
        erzaehle(ding)
    }

    // ---------- Album ----------

    private fun gefundene(): Set<String> = prefs.getStringSet("gefunden", emptySet()) ?: emptySet()

    /** @return true, wenn es zum ersten Mal entdeckt wurde */
    private fun merke(ding: Ding): Boolean {
        val alt = gefundene()
        if (ding.id in alt) return false
        prefs.edit().putStringSet("gefunden", alt + ding.id).apply()
        zeigeZahl()
        return true
    }

    private fun zeigeZahl() {
        albumKnopf.text = "⭐ ${gefundene().count { Katalog.byId(it) != null }}"
    }

    private fun zeigeAlbum() {
        if (album != null) return
        sprecher.stopp()
        beschaeftigt = true
        val gef = gefundene()
        val breite = resources.displayMetrics.widthPixels
        val spalten = maxOf(3, breite / dp(104))

        val inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        val kopf = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val anzahl = Katalog.alle.count { it.id in gef }
        kopf.addView(text("⭐ Mein Album\n$anzahl von ${Katalog.alle.size} entdeckt", 22f, 0xFF212121.toInt(), true).apply {
            gravity = Gravity.START
        }, LinearLayout.LayoutParams(0, -2, 1f))
        kopf.addView(knopf("✕").apply { setOnClickListener { schliesseAlbum() } })
        inhalt.addView(kopf)

        for (gruppe in Gruppe.entries) {
            val dinge = Katalog.alle.filter { it.gruppe == gruppe }
            val zahl = dinge.count { it.id in gef }
            inhalt.addView(text("${gruppe.titel}  ($zahl/${dinge.size})", 20f, gruppe.farbe, true).apply {
                gravity = Gravity.START
                setPadding(dp(4), dp(20), 0, dp(8))
            })
            val gitter = GridLayout(this).apply { columnCount = spalten }
            // Entdeckte zuerst, dann die Fragezeichen
            for (d in dinge.sortedBy { if (it.id in gef) 0 else 1 }) {
                val da = d.id in gef
                val zelle = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = rund(if (da) Color.WHITE else 0xFFE0E0E0.toInt(), 18, 2, if (da) gruppe.farbe else 0xFFE0E0E0.toInt())
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    addView(text(if (da) d.emoji else "❔", 34f))
                    addView(text(if (da) d.name else "???", 12f, 0xFF424242.toInt(), da).apply { maxLines = 2 })
                    if (da) setOnClickListener {
                        aktuell = d
                        sprecher.erzaehle(d)
                    }
                }
                val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0
                    height = dp(96)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                gitter.addView(zelle, lp)
            }
            inhalt.addView(gitter)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFFFF8E1.toInt())
            addView(inhalt)
            isClickable = true
            requestApplyInsets()
        }
        album = scroll
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.requestApplyInsets()
    }

    private fun schliesseAlbum() {
        album?.let { root.removeView(it) }
        album = null
        sprecher.stopp()
        beschaeftigt = false
        vergessen = true
        root.postDelayed(versteckeKarte, 1000)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (album != null) schliesseAlbum() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- Kamera-Erlaubnis ----------

    private fun zeigeErlaubnisBitte() {
        if (erlaubnisAnsicht != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFFFF8E1.toInt())
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true
        }
        box.addView(text("📷", 72f))
        box.addView(text("Ich brauche die Kamera, damit ich sehen kann, was du mir zeigst!", 22f, 0xFF212121.toInt(), true))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
        box.addView(knopf("Kamera erlauben").apply {
            background = rund(0xFFFFB300.toInt(), 28)
            setOnClickListener {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    erlaubnis.launch(Manifest.permission.CAMERA)
                } else {
                    // Schon zweimal abgelehnt: Android fragt nicht mehr, also die App-Einstellungen öffnen
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
        })
        erlaubnisAnsicht = box
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onResume() {
        super.onResume()
        if (erlaubnisAnsicht != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            starteKamera()
        }
    }

    override fun onPause() {
        super.onPause()
        sprecher.stopp()
        beschaeftigt = album != null
        root.post(versteckeKarte)
    }

    override fun onDestroy() {
        super.onDestroy()
        zerstoert = true
        sprecher.close()
        analyse.execute {
            erkenner?.close()
            erkenner = null
        }
        analyse.shutdown()
    }
}
