package de.glas.launcher

import android.app.Activity

/** Asks for a town, looks it up and adds the chosen place to the weather places. */
object PlaceSearch {
    fun ask(activity: Activity, title: String = tr("Ort suchen"), added: (Place) -> Unit) {
        val prefs = Prefs(activity)
        TextPrompt.ask(activity, title, tr("z. B. Düsseldorf oder Palma")) { name ->
            if (name.isBlank()) return@ask
            Thread {
                val found = runCatching { WeatherApi.search(name) }
                activity.runOnUiThread {
                    if (activity.isDestroyed) return@runOnUiThread
                    val results = found.getOrNull()
                    when {
                        results == null -> activity.toast(tr("Keine Verbindung zur Ortssuche"))
                        results.isEmpty() -> activity.toast(tr("Keinen Ort „{0}“ gefunden", name))
                        else -> GlassMenu.show(activity, tr("Ort wählen"), results.map { p ->
                            GlassMenu.Item(if (p.region.isBlank()) p.name else "${p.name} · ${p.region}") {
                                if (prefs.places.none { it.key == p.key }) prefs.places = prefs.places + p
                                activity.toast(tr("{0} hinzugefügt", p.name))
                                added(p)
                            }
                        })
                    }
                }
            }.start()
        }
    }
}
