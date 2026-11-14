package de.glas.entdecker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/** Ein Treffer: was es ist und wie sicher (0..1). */
data class Treffer(val ding: Ding, val sicherheit: Float)

/**
 * Erkennt Dinge auf einem Kamerabild, ganz ohne Internet.
 *
 * Das Modell (OpenAI CLIP ViT-B/32) macht aus dem Bild einen Vektor. Für jedes Ding im Katalog gibt es einen
 * passenden Text-Vektor ("a photo of a dog", "a photo of a toy dog" ...), am Rechner vorberechnet
 * (entdecker/tools/texte.py). Das Ding, dessen Text am besten zum Bild passt, gewinnt. Dazu gibt es Texte für
 * Hintergründe wie Wand, Boden oder Teppich: gewinnt einer davon, wird nichts angesagt.
 * Dadurch klappt es auch mit Spielzeug, Bildern und Kuscheltieren, nicht nur mit Fotos echter Dinge.
 *
 * Nicht thread-sicher: nur aus einem Thread benutzen.
 */
class Erkenner(context: Context) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val texte: Array<FloatArray>
    private val dinge: Array<Ding?> // null = Hintergrund
    private val pixel = IntArray(GROESSE * GROESSE)
    private val eingabe = FloatBuffer.allocate(3 * GROESSE * GROESSE)

    init {
        // Modell einmal aus der APK in den App-Speicher kopieren, dann direkt von dort laden (spart Arbeitsspeicher)
        val datei = File(context.noBackupFilesDir, "erkenner.onnx")
        val laenge = context.assets.openFd("erkenner.onnx").use { it.length }
        if (!datei.isFile || datei.length() != laenge) {
            val tmp = File(datei.path + ".tmp")
            context.assets.open("erkenner.onnx").use { ein -> tmp.outputStream().use { ein.copyTo(it, 1 shl 20) } }
            tmp.renameTo(datei)
        }
        val optionen = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(datei.path, optionen)

        val namen = context.assets.open("texte.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        val bytes = context.assets.open("texte.bin").use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val dim = fb.remaining() / namen.size
        texte = Array(namen.size) { FloatArray(dim).also { v -> fb.get(v) } }
        dinge = Array(namen.size) { i -> if (namen[i].startsWith("#")) null else Katalog.byId(namen[i]) }
    }

    /**
     * @param bild quadratischer Ausschnitt aus dem Kamerabild, noch nicht gedreht
     * @param drehung um wie viel Grad das Bild gedreht werden muss, damit oben oben ist
     * @return der beste Treffer, oder null wenn ein Hintergrund (Wand, Boden ...) am besten passt
     */
    fun erkenne(bild: Bitmap, drehung: Int): Treffer? {
        val m = Matrix().apply {
            postScale(GROESSE.toFloat() / bild.width, GROESSE.toFloat() / bild.height)
            postRotate(drehung.toFloat())
        }
        // Gedreht und auf 224 x 224 verkleinert (das Bild ist quadratisch, also bleibt die Größe gleich)
        val klein = Bitmap.createBitmap(bild, 0, 0, bild.width, bild.height, m, true)
        val x0 = ((klein.width - GROESSE) / 2).coerceAtLeast(0)
        val y0 = ((klein.height - GROESSE) / 2).coerceAtLeast(0)
        val w = minOf(GROESSE, klein.width - x0)
        val h = minOf(GROESSE, klein.height - y0)
        pixel.fill(0)
        klein.getPixels(pixel, 0, GROESSE, x0, y0, w, h)

        // CLIP erwartet die Kanäle nacheinander (R, G, B), normalisiert mit festen Mittelwerten
        val n = GROESSE * GROESSE
        val a = eingabe.array()
        for (i in 0 until n) {
            val p = pixel[i]
            a[i] = (((p shr 16) and 0xFF) / 255f - MITTEL[0]) / STREUUNG[0]
            a[n + i] = (((p shr 8) and 0xFF) / 255f - MITTEL[1]) / STREUUNG[1]
            a[2 * n + i] = ((p and 0xFF) / 255f - MITTEL[2]) / STREUUNG[2]
        }
        eingabe.rewind()

        val vektor = OnnxTensor.createTensor(env, eingabe, longArrayOf(1, 3, GROESSE.toLong(), GROESSE.toLong())).use { t ->
            session.run(mapOf("pixel_values" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                (r[0].value as Array<FloatArray>)[0]
            }
        }
        var laenge = 0f
        for (v in vektor) laenge += v * v
        laenge = sqrt(laenge)

        // Ähnlichkeit zu jedem Text, daraus Wahrscheinlichkeiten (Softmax mit dem CLIP-Faktor 100)
        val sim = FloatArray(texte.size) { j ->
            var s = 0f
            val t = texte[j]
            for (k in vektor.indices) s += vektor[k] * t[k]
            s / laenge
        }
        val max = sim.max()
        var summe = 0f
        val p = FloatArray(sim.size) { j -> exp(100f * (sim[j] - max)).also { summe += it } }
        var best = 0
        for (j in p.indices) if (p[j] > p[best]) best = j
        val ding = dinge[best] ?: return null
        return Treffer(ding, p[best] / summe)
    }

    override fun close() = session.close()

    companion object {
        const val GROESSE = 224
        private val MITTEL = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STREUUNG = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }
}

/**
 * Damit nicht jedes Wackeln eine Ansage auslöst: Ein Ding gilt erst als erkannt, wenn es in mehreren Bildern
 * hintereinander vorne liegt.
 */
class Beruhiger(private val fenster: Int = 4, private val noetig: Int = 3, private val schwelle: Float = 0.4f) {
    private val verlauf = ArrayDeque<Treffer?>()

    fun neu(treffer: Treffer?): Treffer? {
        verlauf.addLast(treffer?.takeIf { it.sicherheit >= schwelle * 0.75f })
        while (verlauf.size > fenster) verlauf.removeFirst()
        val gruppen = verlauf.filterNotNull().groupBy { it.ding }
        val (ding, liste) = gruppen.maxByOrNull { it.value.size } ?: return null
        if (liste.size < noetig) return null
        val mittel = liste.map { it.sicherheit }.average().toFloat()
        return if (mittel >= schwelle) Treffer(ding, mittel) else null
    }

    fun vergiss() = verlauf.clear()
}
