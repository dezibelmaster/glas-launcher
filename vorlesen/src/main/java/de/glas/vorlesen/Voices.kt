package de.glas.vorlesen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.app.Activity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** How the story is told: speed, pitch and the pauses between sentences, on top of the chosen voice. */
enum class Style(
    val title: String,
    val description: String,
    val rate: Float,
    val pitch: Float,
    val sentencePause: Long,
    val paragraphPause: Long,
) {
    ERZAEHLER("Märchenerzähler", "ruhig, etwas tiefer, mit Pausen zum Mitfiebern", 0.88f, 0.9f, 450, 1000),
    OMA("Großmutter", "langsam und gemütlich", 0.8f, 1.05f, 500, 1100),
    BAER("Großer Bär", "tief und brummig", 0.85f, 0.65f, 400, 900),
    FEE("Kleine Fee", "hell und leicht", 1.0f, 1.45f, 300, 700),
    NORMAL("Normal", "wie die Stimme von sich aus spricht", 1.0f, 1.0f, 150, 400);

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: ERZAEHLER
    }
}

/** A German voice of the speech engine, with a name a person can tell apart. */
class VoiceChoice(val voice: Voice, val label: String)

object Voices {

    const val SAMPLE = "Es war einmal, vor langer, langer Zeit, ein kleiner Fuchs, der wohnte tief im Wald. " +
        "„Wer bist du denn?“, fragte die alte Eule."

    /** German voices, numbered so that the offline and the internet version of one voice share a number. */
    fun german(tts: TextToSpeech): List<VoiceChoice> {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "de" }
            .sortedWith(compareBy({ base(it) }, { it.isNetworkConnectionRequired }))
        val numbers = voices.map { base(it) }.distinct()
        return voices.map { v ->
            val parts = mutableListOf("Stimme ${numbers.indexOf(base(v)) + 1}")
            if (v.locale.country.isNotEmpty() && v.locale.country != "DE") parts += v.locale.displayCountry
            parts += if (v.isNetworkConnectionRequired) "Internet, natürlicher Klang" else "offline"
            if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in v.features) parts += "wird erst geladen"
            VoiceChoice(v, parts.joinToString(" · "))
        }
    }

    private fun base(v: Voice) = v.name.removeSuffix("-local").removeSuffix("-network")
}

/** The "Stimme" window: storytelling style, quoted speech, Piper voices to download, and the phone's voices. */
class VoiceDialog(
    private val activity: Activity,
    private val tts: TextToSpeech?,
    private val style: Style,
    private val quoteVoice: Boolean,
    private val engineName: String?,
    /** "voiceId:speaker" of the chosen Piper voice, or null when a voice of the phone is used. */
    private var piperChoice: String?,
    private val onStyle: (Style) -> Unit,
    private val onQuoteVoice: (Boolean) -> Unit,
    private val onPiper: (PiperVoice, Int) -> Unit,
    private val onPiperDeleted: (PiperVoice) -> Unit,
    private val onVoice: (Voice) -> Unit,
    private val onEngine: (String) -> Unit,
    private val onSample: () -> Unit,
    private val onInstallVoices: () -> Unit,
) {
    private val context: Context = activity
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // All voice choices, kept exclusive by hand because they sit in different sections
    private val voiceRadios = mutableListOf<RadioButton>()
    private val piperSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val progressLabels = mutableMapOf<String, TextView>()

    fun show() {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        list.addView(heading("Erzählstil"))
        val styles = RadioGroup(context)
        for (s in Style.entries) {
            styles.addView(radio("${s.title}\n${s.description}", s == style) { onStyle(s); onSample() })
        }
        list.addView(styles)

        list.addView(CheckBox(context).apply {
            text = "Wörtliche Rede („…“) mit etwas anderer Stimme sprechen"
            isChecked = quoteVoice
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(8), dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, on -> onQuoteVoice(on); onSample() }
        })

        list.addView(heading("Natürliche Stimmen (Piper)"))
        list.addView(note("Einmal herunterladen (am besten im WLAN), danach lesen sie ohne Internet vor."))
        list.addView(piperSection)
        renderPiper()

        list.addView(heading("Stimmen des Handys"))
        addPhoneVoices(list)

        list.addView(note("Mehr Handy-Stimmen: auf „Stimmen laden“ tippen und dort die deutschen Sprachdaten installieren."))

        PiperStore.listener = object : PiperStore.Listener {
            override fun onProgress(voice: PiperVoice, percent: Int, error: String?) {
                if (percent >= 0) {
                    progressLabels[voice.id]?.text = "Wird geladen … $percent %"
                    return
                }
                if (error != null) {
                    android.widget.Toast.makeText(context, "${voice.title}: $error", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    // Freshly downloaded: take it straight away
                    choosePiper(voice, voice.moods.firstOrNull()?.first ?: 0)
                }
                renderPiper()
            }
        }

        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Stimme")
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Stimmen laden") { _, _ -> onInstallVoices() }
            .setOnDismissListener { PiperStore.listener = null }
            .show()
    }

    private fun choosePiper(voice: PiperVoice, speaker: Int) {
        piperChoice = "${voice.id}:$speaker"
        onPiper(voice, speaker)
    }

    private fun renderPiper() {
        piperSection.removeAllViews()
        progressLabels.clear()
        voiceRadios.removeAll { it.tag is String }
        for (voice in PiperVoice.ALL) {
            val installed = voice.isInstalled(context)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(2))
            }
            row.addView(TextView(context).apply {
                text = "${voice.title}\n${voice.description} · ${voice.license}"
                setTextColor(Color.WHITE)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val progress = PiperStore.progress(voice)
            val action = TextView(context).apply {
                textSize = 14f
                setTextColor(Color.parseColor("#FFC24B"))
                setPadding(dp(12), dp(8), 0, dp(8))
                when {
                    progress != null -> text = "Wird geladen … $progress %"
                    installed -> {
                        text = "Löschen"
                        setTextColor(Color.parseColor("#99FFFFFF"))
                        setOnClickListener {
                            PiperStore.delete(context, voice)
                            if (piperChoice?.substringBefore(':') == voice.id) piperChoice = null
                            onPiperDeleted(voice)
                            renderPiper()
                        }
                    }
                    else -> {
                        text = "Laden · ${voice.sizeMb} MB"
                        setOnClickListener {
                            PiperStore.download(context, voice)
                            renderPiper()
                        }
                    }
                }
            }
            if (progress != null) progressLabels[voice.id] = action
            row.addView(action)
            piperSection.addView(row)

            if (installed) {
                val options = voice.moods.ifEmpty { listOf(0 to "Diese Stimme nehmen") }
                for ((speaker, label) in options) {
                    val key = "${voice.id}:$speaker"
                    piperSection.addView(voiceRadio(if (voice.moods.isEmpty()) label else "Stimmung: $label", key, piperChoice == key) {
                        choosePiper(voice, speaker)
                        onSample()
                    })
                }
            }
        }
    }

    private fun addPhoneVoices(list: LinearLayout) {
        val tts = this.tts
        if (tts == null) {
            list.addView(note("Die Sprachausgabe des Handys ist nicht bereit."))
            return
        }
        val voices = Voices.german(tts)
        if (voices.isEmpty()) {
            list.addView(note("Keine deutsche Stimme gefunden."))
        } else {
            val current = if (piperChoice == null) runCatching { tts.voice?.name }.getOrNull() else null
            for (choice in voices) {
                list.addView(voiceRadio(choice.label, null, choice.voice.name == current) {
                    piperChoice = null
                    onVoice(choice.voice)
                    onSample()
                })
            }
        }
        list.addView(note("Antippen spielt eine Hörprobe. Handy-Stimmen mit Internet klingen meist natürlicher."))

        val engines = runCatching { tts.engines }.getOrNull().orEmpty()
        if (engines.size > 1) {
            list.addView(heading("Sprachprogramm des Handys"))
            val group = RadioGroup(context)
            val active = engineName ?: tts.defaultEngine
            for (e in engines) {
                group.addView(radio(e.label, e.name == active) { onEngine(e.name) })
            }
            list.addView(group)
            list.addView(note("Nach dem Wechsel die Stimme hier neu wählen."))
        }
    }

    private fun heading(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#FFC24B"))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#99FFFFFF"))
        textSize = 13f
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun radio(text: String, checked: Boolean, onPick: () -> Unit) = RadioButton(context).apply {
        id = View.generateViewId()
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(8), dp(10), 0, dp(10))
        isChecked = checked
        setOnClickListener { onPick() }
    }

    /** A voice choice; checking it unchecks every other voice in the window. [tag] marks Piper rows. */
    private fun voiceRadio(text: String, tag: String?, checked: Boolean, onPick: () -> Unit): RadioButton {
        val button = radio(text, checked) {}
        button.tag = tag
        button.setOnClickListener {
            voiceRadios.forEach { it.isChecked = it === button }
            onPick()
        }
        voiceRadios += button
        return button
    }
}
