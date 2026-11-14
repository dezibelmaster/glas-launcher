package de.glas.entdecker

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier

/** Ein Treffer: was es ist und wie sicher (0..1). */
data class Treffer(val ding: Ding, val sicherheit: Float)

/**
 * Erkennt Dinge auf einem Kamerabild, ganz ohne Internet.
 * Das Modell (EfficientNet-Lite, ImageNet) kennt 1000 Bildklassen, z. B. 120 Hunderassen. Die Wahrscheinlichkeiten
 * aller Klassen, die zum selben Ding gehören, werden zusammengezählt: zehn Hunderassen mit je 8 % sind sicher ein Hund.
 * Nicht thread-sicher: nur aus einem Thread benutzen.
 */
class Erkenner(context: Context) : AutoCloseable {

    private val classifier: ImageClassifier = ImageClassifier.createFromOptions(
        context,
        ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("erkenner.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(25)
            .setScoreThreshold(0.01f)
            .build()
    )

    /**
     * @param bild Kamerabild, noch nicht gedreht
     * @param drehung um wie viel Grad das Bild gedreht werden muss, damit oben oben ist
     * @return Treffer, der sicherste zuerst
     */
    fun erkenne(bild: Bitmap, drehung: Int): List<Treffer> {
        val optionen = ImageProcessingOptions.builder().setRotationDegrees(drehung).build()
        val ergebnis = classifier.classify(BitmapImageBuilder(bild).build(), optionen)
        val summe = HashMap<Ding, Float>()
        val kategorien = ergebnis.classificationResult().classifications().firstOrNull()?.categories() ?: return emptyList()
        for (k in kategorien) {
            val ding = Katalog.zuKlasse.getOrNull(k.index()) ?: continue
            summe[ding] = (summe[ding] ?: 0f) + k.score()
        }
        return summe.map { Treffer(it.key, it.value.coerceAtMost(1f)) }.sortedByDescending { it.sicherheit }
    }

    override fun close() = classifier.close()
}

/**
 * Damit nicht jedes Wackeln eine Ansage auslöst: Ein Ding gilt erst als erkannt, wenn es in mehreren Bildern
 * hintereinander vorne liegt.
 */
class Beruhiger(private val fenster: Int = 4, private val noetig: Int = 3, private val schwelle: Float = 0.3f) {
    private val verlauf = ArrayDeque<Treffer?>()

    fun neu(treffer: List<Treffer>): Treffer? {
        verlauf.addLast(treffer.firstOrNull()?.takeIf { it.sicherheit >= schwelle * 0.7f })
        while (verlauf.size > fenster) verlauf.removeFirst()
        val gruppen = verlauf.filterNotNull().groupBy { it.ding }
        val (ding, liste) = gruppen.maxByOrNull { it.value.size } ?: return null
        if (liste.size < noetig) return null
        val mittel = liste.map { it.sicherheit }.average().toFloat()
        return if (mittel >= schwelle) Treffer(ding, mittel) else null
    }

    fun vergiss() = verlauf.clear()
}
