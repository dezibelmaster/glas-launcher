package de.glas.vorlesen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
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

/** The "Stimme" window: storytelling style, voice for quoted speech, voice and speech engine. */
class VoiceDialog(
    private val context: Context,
    private val tts: TextToSpeech,
    private val style: Style,
    private val quoteVoice: Boolean,
    private val engineName: String?,
    private val onStyle: (Style) -> Unit,
    private val onQuoteVoice: (Boolean) -> Unit,
    private val onVoice: (Voice) -> Unit,
    private val onEngine: (String) -> Unit,
    private val onSample: () -> Unit,
    private val onInstallVoices: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        list.addView(heading("Erzählstil"))
        val styles = RadioGroup(context)
        for (s in Style.entries) {
            styles.addView(radio("${s.title}\n", s.description, s == style) { onStyle(s); onSample() })
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

        list.addView(heading("Stimme"))
        val voices = Voices.german(tts)
        if (voices.isEmpty()) {
            list.addView(note("Keine deutsche Stimme gefunden."))
        } else {
            val current = runCatching { tts.voice?.name }.getOrNull()
            val group = RadioGroup(context)
            for (choice in voices) {
                group.addView(radio(choice.label, null, choice.voice.name == current) { onVoice(choice.voice); onSample() })
            }
            list.addView(group)
        }
        list.addView(note("Antippen spielt eine Hörprobe. Stimmen mit Internet klingen meist am natürlichsten."))

        val engines = runCatching { tts.engines }.getOrNull().orEmpty()
        if (engines.size > 1) {
            list.addView(heading("Sprachprogramm"))
            val group = RadioGroup(context)
            val active = engineName ?: tts.defaultEngine
            for (e in engines) {
                group.addView(radio(e.label, null, e.name == active) { onEngine(e.name) })
            }
            list.addView(group)
            list.addView(note("Nach dem Wechsel die Stimme hier neu wählen."))
        }

        list.addView(note("Mehr Stimmen: auf „Stimmen laden“ tippen und dort die deutschen Sprachdaten installieren. " +
            "Andere Sprachprogramme aus dem Play Store erscheinen oben unter „Sprachprogramm“."))

        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Stimme")
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Stimmen laden") { _, _ -> onInstallVoices() }
            .show()
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

    private fun radio(title: String, description: String?, checked: Boolean, onPick: () -> Unit): View =
        RadioButton(context).apply {
            id = View.generateViewId()
            text = if (description == null) title else title + description
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(8), dp(10), 0, dp(10))
            isChecked = checked
            setOnClickListener { onPick() }
        }
}
