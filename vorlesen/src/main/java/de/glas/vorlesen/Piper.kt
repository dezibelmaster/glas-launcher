package de.glas.vorlesen

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A natural-sounding German voice from the open Piper collection, downloaded once and then used offline. */
class PiperVoice(
    val id: String,
    val title: String,
    val description: String,
    val sizeMb: Int,
    val license: String,
    /** Speaker number and name, for voices that can speak in several moods. */
    val moods: List<Pair<Int, String>> = emptyList(),
) {
    // Ready-made packages of the sherpa-onnx project: model, tokens and pronunciation data
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-de_DE-$id.tar.bz2"

    fun dir(context: Context) = File(context.filesDir, "piper/$id")

    fun isInstalled(context: Context) = File(dir(context), DONE).exists()

    companion object {
        const val DONE = ".fertig"

        val ALL = listOf(
            PiperVoice("thorsten-medium", "Thorsten", "männlich, warm und klar – die Empfehlung", 67, "frei (CC0)"),
            PiperVoice(
                "thorsten_emotional-medium", "Thorsten mit Gefühl",
                "männlich, in verschiedenen Stimmungen – „schläfrig“ passt gut zur Gute-Nacht-Geschichte", 80, "frei (CC0)",
                moods = listOf(4 to "ruhig", 5 to "schläfrig", 7 to "flüsternd", 0 to "fröhlich", 6 to "überrascht"),
            ),
            PiperVoice("dii-high", "Dii", "weiblich, hohe Qualität", 67, "nur privat (CC BY-NC-SA)"),
            PiperVoice("miro-high", "Miro", "männlich, tief, hohe Qualität", 67, "nur privat (CC BY-NC-SA)"),
            PiperVoice("kerstin-low", "Kerstin", "weiblich", 67, "frei (CC0)"),
            PiperVoice("ramona-low", "Ramona", "weiblich", 67, "frei (M-AILABS)"),
            PiperVoice("eva_k-x_low", "Eva", "weiblich, einfache Qualität, kleiner Download", 27, "frei (M-AILABS)"),
            PiperVoice("karlsson-low", "Karlsson", "männlich, tief", 67, "frei (M-AILABS)"),
            PiperVoice("pavoque-low", "Pavoque", "männlich", 67, "nur privat (CC BY-NC-SA)"),
            PiperVoice("thorsten-high", "Thorsten, beste Qualität", "braucht ein schnelles Handy, sonst stockt es", 116, "frei (CC0)"),
        )

        fun byId(id: String?) = ALL.firstOrNull { it.id == id }
    }
}

/** Downloads and unpacks Piper voices in the background; progress is reported on the main thread. */
object PiperStore {

    interface Listener {
        /** [percent] from 0 to 100, or -1 when finished; [error] is set when it failed. */
        fun onProgress(voice: PiperVoice, percent: Int, error: String?)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val running = mutableMapOf<String, Int>()
    var listener: Listener? = null

    fun progress(voice: PiperVoice): Int? = running[voice.id]

    fun download(context: Context, voice: PiperVoice) {
        if (voice.id in running) return
        running[voice.id] = 0
        val app = context.applicationContext
        Thread {
            val error = try {
                fetch(app, voice)
                null
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            main.post {
                running.remove(voice.id)
                listener?.onProgress(voice, -1, error)
            }
        }.start()
    }

    fun delete(context: Context, voice: PiperVoice) {
        voice.dir(context).deleteRecursively()
    }

    private fun fetch(context: Context, voice: PiperVoice) {
        val target = voice.dir(context)
        val temp = File(target.parentFile, "${voice.id}.tmp")
        temp.deleteRecursively()
        temp.mkdirs()
        val conn = URL(voice.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) error("Download fehlgeschlagen (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (voice.sizeMb * 1_000_000L)
            var lastPercent = -1
            val counting = object : FilterInputStream(conn.inputStream.buffered(1 shl 16)) {
                var read = 0L
                private fun count(n: Int) {
                    if (n <= 0) return
                    read += n
                    val percent = (read * 100 / total).toInt().coerceIn(0, 99)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        main.post {
                            running[voice.id] = percent
                            listener?.onProgress(voice, percent, null)
                        }
                    }
                }
                override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }
            }
            unpack(counting, temp)
        } finally {
            conn.disconnect()
        }
        // Must hold everything the engine needs before it counts as installed
        if (!complete(temp)) {
            temp.deleteRecursively()
            error("Stimme unvollständig")
        }
        File(temp, PiperVoice.DONE).writeText("ok")
        target.deleteRecursively()
        if (!temp.renameTo(target)) error("Speichern fehlgeschlagen")
    }

    /** Checks that [dir] holds everything the engine needs. */
    internal fun complete(dir: File) =
        dir.listFiles()?.any { it.name.endsWith(".onnx") } == true &&
            File(dir, "tokens.txt").exists() && File(dir, "espeak-ng-data").isDirectory

    /** Unpacks the archive into [dir], leaving out its top folder (vits-piper-…/). */
    internal fun unpack(input: InputStream, dir: File) {
        val root = dir.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val out = File(dir, name)
                // Never write outside the voice folder
                if (!out.canonicalPath.startsWith(root)) continue
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }
}
