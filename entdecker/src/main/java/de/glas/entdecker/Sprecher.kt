package de.glas.entdecker

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * Sagt, was erkannt wurde, und spielt das Geräusch:
 * "Das ist ein Hund!" – Geräusch – "Der Hund macht Wau wau!"
 *
 * Eigene Aufnahmen: Liegt im Ordner Android/data/de.glas.entdecker/files/Geraeusche eine Datei mit dem Namen des
 * Geräuschs (z. B. bellen.mp3, .ogg, .wav oder .m4a), wird sie statt des eingebauten Geräuschs gespielt.
 */
class Sprecher(private val context: Context, private val bereit: (sprache: Boolean) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var player: MediaPlayer? = null
    private var lauf = 0 // jede neue Erzählung bricht die alte ab
    private val schritte = HashMap<String, () -> Unit>()

    var spricht = false
        private set

    private val attribute = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                val r = t.setLanguage(Locale.GERMANY).let {
                    if (it < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.GERMAN) else it
                }
                ttsOk = r >= TextToSpeech.LANG_AVAILABLE
                t.setSpeechRate(0.9f)
                t.setPitch(1.1f)
                t.setAudioAttributes(attribute)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {}
                    override fun onDone(id: String) = weiter(id)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String) = weiter(id)
                    override fun onError(id: String, code: Int) = weiter(id)
                })
            }
            main.post { bereit(ttsOk) }
        }
    }

    private fun weiter(id: String) {
        main.post { schritte.remove(id)?.invoke() }
    }

    /** Ansage, Geräusch, Spruch. [fertig] kommt auf dem Hauptthread, wenn alles vorbei ist (nicht bei Abbruch). */
    fun erzaehle(ding: Ding, fertig: () -> Unit = {}) {
        stopp()
        val meiner = ++lauf
        spricht = true
        val ende = {
            if (meiner == lauf) {
                spricht = false
                fertig()
            }
        }
        sage(ding.ansage, meiner) {
            spiele(ding.geraeusch, meiner) {
                sage(ding.spruch, meiner) { ende() }
            }
        }
    }

    private fun sage(text: String, meiner: Int, danach: () -> Unit) {
        if (meiner != lauf) return
        val t = tts
        if (!ttsOk || t == null) {
            // Ohne Sprachausgabe: kurz warten, damit der Text auf dem Bildschirm gelesen werden kann
            main.postDelayed({ if (meiner == lauf) danach() }, 900)
            return
        }
        val id = "e$meiner-${text.hashCode()}"
        schritte[id] = { if (meiner == lauf) danach() }
        val ok = t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.SUCCESS
        // Manche Sprachausgaben melden nie "fertig": dann nach einer großzügigen Sprechdauer trotzdem weitermachen
        val warte = if (ok) 2500L + text.length * 110L else 0L
        main.postDelayed({ schritte.remove(id)?.invoke() }, warte)
    }

    private fun spiele(name: String, meiner: Int, danach: () -> Unit) {
        if (meiner != lauf) return
        val mp = MediaPlayer()
        player = mp
        var erledigt = false
        val weiter = {
            if (!erledigt) {
                erledigt = true
                mp.release()
                if (player === mp) player = null
                if (meiner == lauf) main.postDelayed({ if (meiner == lauf) danach() }, 150)
            }
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            val eigene = eigeneDatei(name)
            if (eigene != null) {
                mp.setDataSource(eigene.absolutePath)
            } else {
                context.assets.openFd("geraeusche/$name.ogg").use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            }
            mp.setOnCompletionListener { weiter() }
            mp.setOnErrorListener { _, _, _ -> weiter(); true }
            mp.prepare()
            mp.start()
            // Lange eigene Aufnahmen nach 8 Sekunden beenden
            main.postDelayed({ if (!erledigt) weiter() }, 8000)
        } catch (e: Exception) {
            weiter()
        }
    }

    private fun eigeneDatei(name: String): File? {
        val ordner = context.getExternalFilesDir("Geraeusche") ?: return null
        return listOf("mp3", "ogg", "wav", "m4a").map { File(ordner, "$name.$it") }.firstOrNull { it.isFile }
    }

    fun stopp() {
        lauf++
        spricht = false
        schritte.clear()
        tts?.stop()
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    fun close() {
        stopp()
        tts?.shutdown()
        tts = null
    }
}
