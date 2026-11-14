package de.glas.vorlesen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** One thing to say, or a pause, with an id that comes back in the listener. */
sealed class Utterance(val id: String) {
    class Speech(id: String, val text: String, val rate: Float, val pitch: Float) : Utterance(id)
    class Silence(id: String, val millis: Long) : Utterance(id)
}

/** Called from any thread when an utterance starts and when it is over. */
interface NarratorListener {
    fun onStart(id: String)
    fun onDone(id: String)
}

/** Something that reads a list of utterances aloud: the phone's voice or a Piper voice. */
interface Narrator {
    /** Replaces whatever is being read with these utterances. */
    fun play(items: List<Utterance>)
    fun stop()
    fun release()
}

/** The phone's own speech engine. Its listener is set up by whoever created the engine. */
class AndroidNarrator(private val tts: TextToSpeech) : Narrator {
    override fun play(items: List<Utterance>) {
        items.forEachIndexed { i, item ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            when (item) {
                is Utterance.Speech -> {
                    // Pitch and speed are taken along with each piece when it is queued
                    tts.setSpeechRate(item.rate)
                    tts.setPitch(item.pitch)
                    tts.speak(item.text, mode, null, item.id)
                }
                is Utterance.Silence -> tts.playSilentUtterance(item.millis, mode, item.id)
            }
        }
    }

    override fun stop() {
        tts.stop()
    }

    override fun release() {
        tts.stop()
    }
}

/**
 * A downloaded Piper voice, computed on the phone with sherpa-onnx.
 * One thread computes the next sentences while another one plays, so there are no gaps between sentences.
 */
class PiperNarrator(
    private val voice: PiperVoice,
    private val modelDir: java.io.File,
    private val speaker: Int,
    private val listener: NarratorListener,
    private val onLoaded: (Throwable?) -> Unit,
) : Narrator {

    private class Chunk(val token: Int, val id: String, val samples: FloatArray, val last: Boolean)

    private val compute = Executors.newSingleThreadExecutor()
    private val player = Executors.newSingleThreadExecutor()
    private val queue = LinkedBlockingQueue<Chunk>(3)
    private val token = AtomicInteger()
    @Volatile private var engine: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var released = false

    init {
        compute.execute {
            try {
                val model = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
                    ?: error("Stimme unvollständig")
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.absolutePath,
                            tokens = java.io.File(modelDir, "tokens.txt").absolutePath,
                            dataDir = java.io.File(modelDir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = 2,
                        provider = "cpu",
                    ),
                )
                val tts = OfflineTts(config = config)
                engine = tts
                track = makeTrack(tts.sampleRate())
                onLoaded(null)
            } catch (t: Throwable) {
                onLoaded(t)
            }
        }
        player.execute { playLoop() }
    }

    private fun makeTrack(sampleRate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // A small buffer keeps the marked sentence in step with what is heard
            .setBufferSizeInBytes(maxOf(min, sampleRate / 5 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun play(items: List<Utterance>) {
        val my = token.incrementAndGet()
        queue.clear()
        flushTrack()
        compute.execute {
            val tts = engine ?: return@execute
            items.forEachIndexed { i, item ->
                if (token.get() != my || released) return@execute
                val samples = try {
                    render(tts, item)
                } catch (t: Throwable) {
                    FloatArray(0)
                }
                val chunk = Chunk(my, item.id, samples, i == items.size - 1)
                // Wait for room, but give up as soon as something else is to be read
                while (!queue.offer(chunk, 200, TimeUnit.MILLISECONDS)) {
                    if (token.get() != my || released) return@execute
                }
            }
        }
    }

    private fun render(tts: OfflineTts, item: Utterance): FloatArray = when (item) {
        is Utterance.Silence -> FloatArray((tts.sampleRate() * item.millis / 1000).toInt())
        is Utterance.Speech -> {
            // Pitch: speak slower by the same amount, then play the result faster (or the other way round).
            // That raises or lowers the voice without changing the speed.
            val pitch = item.pitch.coerceIn(0.7f, 1.4f)
            val audio = tts.generate(item.text, sid = speaker, speed = item.rate / pitch)
            if (kotlin.math.abs(pitch - 1f) < 0.02f) audio.samples else resample(audio.samples, pitch)
        }
    }

    private fun playLoop() {
        try {
            playChunks()
        } catch (_: Throwable) {
            // Audio output gone (app closing): nothing left to play
        } finally {
            runCatching { track?.release() }
        }
    }

    private fun playChunks() {
        while (!released) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val out = track ?: continue
            if (chunk.token != token.get()) continue
            listener.onStart(chunk.id)
            if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
            var pos = 0
            var interrupted = false
            while (pos < chunk.samples.size) {
                if (chunk.token != token.get() || released) {
                    interrupted = true
                    break
                }
                val n = minOf(2048, chunk.samples.size - pos)
                val written = out.write(chunk.samples, pos, n, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                pos += written
            }
            if (interrupted) continue
            if (chunk.last) {
                // Let the end of the story be heard before reporting it done
                Thread.sleep(250)
            }
            if (chunk.token == token.get()) listener.onDone(chunk.id)
        }
    }

    private fun flushTrack() {
        val out = track ?: return
        runCatching {
            out.pause()
            out.flush()
        }
    }

    override fun stop() {
        token.incrementAndGet()
        queue.clear()
        flushTrack()
    }

    override fun release() {
        released = true
        stop()
        compute.execute {
            runCatching { engine?.release() }
            engine = null
        }
        compute.shutdown()
        // The player thread notices "released" within 200 ms and frees the audio output itself
        player.shutdown()
    }

    companion object {
        /** Plays [samples] [factor] times faster by simple interpolation (higher and shorter for factor > 1). */
        fun resample(samples: FloatArray, factor: Float): FloatArray {
            val n = (samples.size / factor).roundToInt()
            if (n <= 1 || samples.size < 2) return samples
            val out = FloatArray(n)
            for (i in 0 until n) {
                val x = i * factor
                val j = x.toInt()
                if (j >= samples.size - 1) {
                    out[i] = samples[samples.size - 1]
                } else {
                    val f = x - j
                    out[i] = samples[j] * (1 - f) + samples[j + 1] * f
                }
            }
            return out
        }
    }
}
