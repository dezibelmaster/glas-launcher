package de.glas.vorlesen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Glas Vorleser: hold a book page in front of the camera and the phone reads the story aloud.
 * The text is recognized on the phone itself, nothing is sent anywhere.
 */
class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var readButton: Button
    private lateinit var busy: ProgressBar
    private lateinit var status: TextView
    private lateinit var textScroll: ScrollView
    private lateinit var pageText: TextView
    private lateinit var controls: View
    private lateinit var pauseButton: TextView
    private lateinit var autoButton: TextView
    private lateinit var lightButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var speedLabel: TextView

    private val prefs by lazy { getSharedPreferences("vorleser", MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var torchOn = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var style = Style.ERZAEHLER
    private var quoteVoice = true

    // What is being read: the page text, its sentences, which one is spoken now
    private var text = ""
    private var sentences = emptyList<PageText.Sentence>()
    private var current = 0
    @Volatile private var speaking = false
    @Volatile private var paused = false
    // Raised each time reading (re)starts, so callbacks from an older run are ignored
    private var generation = 0

    // Picture analysis, touched only on the main thread
    @Volatile private var wantPage = false
    @Volatile private var recognizing = false
    private var manualTries = 0
    @Volatile private var autoMode = false
    private var lastAutoCheck = 0L
    private var autoCandidate = ""
    private var lastRead = ""

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else setStatus(getString(R.string.camera_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        preview = findViewById(R.id.preview)
        readButton = findViewById(R.id.readButton)
        busy = findViewById(R.id.busy)
        status = findViewById(R.id.status)
        textScroll = findViewById(R.id.textScroll)
        pageText = findViewById(R.id.pageText)
        controls = findViewById(R.id.controls)
        pauseButton = findViewById(R.id.pauseButton)
        autoButton = findViewById(R.id.autoButton)
        lightButton = findViewById(R.id.lightButton)
        voiceButton = findViewById(R.id.voiceButton)
        speedLabel = findViewById(R.id.speedLabel)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Keep the bars clear of the phone's status and navigation bars
        val top = findViewById<View>(R.id.topBar)
        val panel = status.parent as View
        val topPad = top.paddingTop
        val bottomPad = panel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(top.rootView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(top.paddingLeft, topPad + bars.top, top.paddingRight, top.paddingBottom)
            panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, bottomPad + bars.bottom)
            insets
        }

        readButton.setOnClickListener { readPage() }
        findViewById<View>(R.id.againButton).setOnClickListener { speakFrom(0) }
        findViewById<View>(R.id.stopButton).setOnClickListener { stopReading() }
        pauseButton.setOnClickListener { if (speaking) pauseReading() else speakFrom(current) }

        autoMode = prefs.getBoolean("auto", false)
        autoButton.isActivated = autoMode
        autoButton.setTextColor(if (autoMode) Color.parseColor("#1E2A3A") else Color.WHITE)
        autoButton.setOnClickListener { setAuto(!autoMode) }
        lightButton.setOnClickListener { setTorch(!torchOn) }
        style = Style.from(prefs.getString("style", null))
        quoteVoice = prefs.getBoolean("quoteVoice", true)
        voiceButton.setOnClickListener { showVoices() }

        val speed = findViewById<SeekBar>(R.id.speed)
        speed.progress = prefs.getInt("speed", 5)
        applySpeed(speed.progress)
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                applySpeed(progress)
                prefs.edit().putInt("speed", progress).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            // The new speed applies from the sentence being read now
            override fun onStopTrackingTouch(bar: SeekBar) { if (speaking) speakFrom(current) }
        })

        setStatus(getString(if (autoMode) R.string.hint_auto else R.string.hint_start))
        startSpeech()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app pauses the story; "Weiter" picks it up again
        if (speaking) pauseReading()
    }

    override fun onDestroy() {
        tts?.shutdown()
        recognizer.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ---------- Camera ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                // Sharp enough pictures for small print, without making recognition slow
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { frame -> onFrame(frame) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
                lightButton.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE

                // Tap the picture to focus there
                preview.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = preview.meteringPointFactory.createPoint(event.x, event.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                    true
                }
            } catch (e: Exception) {
                setStatus(getString(R.string.camera_failed) + " (" + e.message + ")")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
        lightButton.isActivated = on
        lightButton.setTextColor(if (on) Color.parseColor("#1E2A3A") else Color.WHITE)
    }

    private fun setAuto(on: Boolean) {
        autoMode = on
        autoCandidate = ""
        prefs.edit().putBoolean("auto", on).apply()
        autoButton.isActivated = on
        autoButton.setTextColor(if (on) Color.parseColor("#1E2A3A") else Color.WHITE)
        if (!speaking) setStatus(getString(if (on) R.string.hint_auto else R.string.hint_start))
    }

    /** Runs on the camera thread for every picture; most are skipped. */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun onFrame(frame: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val manual = wantPage
        val auto = autoMode && !speaking && !paused && now - lastAutoCheck > 700
        val image = frame.image
        if (recognizing || (!manual && !auto) || image == null) {
            frame.close()
            return
        }
        recognizing = true
        if (manual) wantPage = false else lastAutoCheck = now

        val rotation = frame.imageInfo.rotationDegrees
        val upright = rotation == 90 || rotation == 270
        val w = if (upright) frame.height else frame.width
        val h = if (upright) frame.width else frame.height
        recognizer.process(InputImage.fromMediaImage(image, rotation))
            .addOnCompleteListener(ContextCompat.getMainExecutor(this)) { task ->
                frame.close()
                recognizing = false
                val found = if (task.isSuccessful) toText(task.result, w, h) else ""
                if (manual) onManualResult(found) else onAutoResult(found)
            }
    }

    private fun toText(result: Text, w: Int, h: Int): String {
        val blocks = result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            PageText.Block(box.left, box.top, box.right, box.bottom, block.lines.map { it.text })
        }
        return PageText.build(blocks, w, h)
    }

    // ---------- Reading a page ----------

    private fun readPage() {
        if (recognizing && wantPage) return
        stopSpeech()
        manualTries = 0
        wantPage = true
        showBusy(true)
        setStatus(getString(R.string.reading_page))
    }

    private fun onManualResult(found: String) {
        if (found.length < 3) {
            // The camera may still be focusing: try a few more pictures before giving up
            if (++manualTries < 4) {
                wantPage = true
                return
            }
            showBusy(false)
            setStatus(getString(R.string.no_text))
            return
        }
        showBusy(false)
        startPage(found)
    }

    private fun onAutoResult(found: String) {
        if (speaking || paused || wantPage) return
        if (found.length < 40) {
            autoCandidate = ""
            return
        }
        // Read only when the same text is seen twice (the page is held still) and it is not the page just read
        val steady = PageText.similarity(found, autoCandidate) >= 0.6
        autoCandidate = found
        if (steady && PageText.similarity(found, lastRead) < 0.5) {
            autoCandidate = ""
            startPage(found)
        }
    }

    private fun startPage(found: String) {
        text = found
        lastRead = found
        sentences = PageText.sentences(found)
        textScroll.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        textScroll.scrollTo(0, 0)
        highlight(-1)
        speakFrom(0)
    }

    // ---------- Speech ----------

    private fun startSpeech() {
        ttsReady = false
        tts = TextToSpeech(this, { result ->
            if (result != TextToSpeech.SUCCESS) {
                setStatus(getString(R.string.tts_failed))
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            var lang = engine.setLanguage(Locale.GERMANY)
            if (lang < TextToSpeech.LANG_AVAILABLE) lang = engine.setLanguage(Locale.GERMAN)
            if (lang < TextToSpeech.LANG_AVAILABLE) {
                setStatus(getString(R.string.tts_missing))
                status.setOnClickListener {
                    runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                }
                return@TextToSpeech
            }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // The voice picked earlier, if this engine still has it
            val saved = prefs.getString("voice", null)
            runCatching { engine.voices?.firstOrNull { it.name == saved }?.let { engine.voice = it } }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = runOnUiThread { onSentenceStart(id) }
                override fun onDone(id: String) = runOnUiThread { onSentenceDone(id) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) = runOnUiThread { onSentenceDone(id) }
            })
            ttsReady = true
        }, prefs.getString("engine", null))
    }

    // ---------- Voices ----------

    private fun showVoices() {
        val engine = tts
        if (!ttsReady || engine == null) {
            setStatus(getString(R.string.tts_failed))
            return
        }
        if (speaking) pauseReading()
        VoiceDialog(
            this, engine, style, quoteVoice, prefs.getString("engine", null),
            onStyle = { style = it; prefs.edit().putString("style", it.name).apply() },
            onQuoteVoice = { quoteVoice = it; prefs.edit().putBoolean("quoteVoice", it).apply() },
            onVoice = { voice ->
                runCatching { engine.voice = voice }
                prefs.edit().putString("voice", voice.name).apply()
            },
            onEngine = { name ->
                prefs.edit().putString("engine", name).remove("voice").apply()
                tts?.shutdown()
                startSpeech()
            },
            onSample = { playSample() },
            onInstallVoices = {
                runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
            },
        ).show()
    }

    private fun playSample() {
        val engine = tts ?: return
        if (!ttsReady) return
        if (speaking) pauseReading()
        engine.stop()
        queueSentence(engine, Voices.SAMPLE, "sample", TextToSpeech.QUEUE_FLUSH)
    }

    /** Queues one sentence in the chosen style; quoted speech gets a slightly different voice. */
    private fun queueSentence(engine: TextToSpeech, sentence: String, id: String, firstMode: Int) {
        val rate = speechRate(prefs.getInt("speed", 5)) * style.rate
        val parts = if (quoteVoice) PageText.speechParts(sentence) else listOf(PageText.Part(sentence, false))
        parts.forEachIndexed { p, part ->
            // Pitch and speed are taken along with each piece when it is queued
            engine.setSpeechRate(rate * if (part.quoted) 1.06f else 1f)
            engine.setPitch(style.pitch * if (part.quoted) 1.18f else 1f)
            val last = if (p == parts.size - 1) 1 else 0
            engine.speak(part.text, if (p == 0) firstMode else TextToSpeech.QUEUE_ADD, null, "$id:$last")
        }
    }

    private fun speakFrom(index: Int) {
        val engine = tts
        if (!ttsReady || engine == null) {
            setStatus(getString(R.string.tts_failed))
            return
        }
        if (sentences.isEmpty()) return
        generation++
        engine.stop()
        current = index.coerceIn(0, sentences.size - 1)
        speaking = true
        paused = false
        for (i in current until sentences.size) {
            val mode = if (i == current) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            queueSentence(engine, sentences[i].text, "$generation:$i", mode)
            // A breath after each sentence, a longer one between paragraphs
            val next = sentences.getOrNull(i + 1) ?: continue
            val newParagraph = text.substring(sentences[i].end, next.start).contains("\n\n")
            val pause = if (newParagraph) style.paragraphPause else style.sentencePause
            if (pause > 0) engine.playSilentUtterance(pause, TextToSpeech.QUEUE_ADD, "pause")
        }
        pauseButton.text = "⏸  Pause"
        setStatus(getString(R.string.speaking))
    }

    /** Utterance ids are "generation:sentence:lastPiece"; others (pauses, samples, older runs) give null. */
    private fun parseId(id: String): Pair<Int, Boolean>? {
        val parts = id.split(':')
        if (parts.size != 3 || parts[0] != generation.toString()) return null
        val index = parts[1].toIntOrNull() ?: return null
        return index to (parts[2] == "1")
    }

    private fun onSentenceStart(id: String) {
        val index = parseId(id)?.first ?: return
        current = index
        highlight(index)
    }

    private fun onSentenceDone(id: String) {
        val (index, lastPiece) = parseId(id) ?: return
        if (index == sentences.size - 1 && lastPiece && speaking) {
            speaking = false
            current = 0
            highlight(-1)
            pauseButton.text = "▶  Vorlesen"
            setStatus(getString(if (autoMode) R.string.hint_auto else R.string.done))
        }
    }

    private fun pauseReading() {
        generation++
        tts?.stop()
        speaking = false
        paused = true
        pauseButton.text = "▶  Weiter"
        setStatus(getString(R.string.paused))
    }

    private fun stopReading() {
        stopSpeech()
        current = 0
        highlight(-1)
        setStatus(getString(if (autoMode) R.string.hint_auto else R.string.hint_start))
    }

    private fun stopSpeech() {
        generation++
        tts?.stop()
        speaking = false
        paused = false
        pauseButton.text = "▶  Vorlesen"
    }

    private fun speechRate(progress: Int) = 0.5f + progress * 0.1f

    private fun applySpeed(progress: Int) {
        val rate = speechRate(progress)
        speedLabel.text = String.format(Locale.GERMAN, "%.1f×", rate)
    }

    // ---------- Screen ----------

    /** Shows the page text with the sentence being read marked, and keeps it in view. */
    private fun highlight(index: Int) {
        val span = SpannableString(text)
        val sentence = sentences.getOrNull(index)
        if (sentence != null) {
            span.setSpan(BackgroundColorSpan(0x55FFC24B), sentence.start, sentence.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(Color.WHITE), sentence.start, sentence.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        pageText.text = span
        if (sentence != null) {
            pageText.post {
                val layout = pageText.layout ?: return@post
                val y = layout.getLineTop(layout.getLineForOffset(sentence.start))
                textScroll.smoothScrollTo(0, maxOf(0, y - pageText.lineHeight))
            }
        }
    }

    private fun showBusy(on: Boolean) {
        busy.visibility = if (on) View.VISIBLE else View.GONE
        readButton.isEnabled = !on
    }

    private fun setStatus(message: String) {
        status.text = message
    }
}
