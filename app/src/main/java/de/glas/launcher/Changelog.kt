package de.glas.launcher

import android.content.Context
import org.json.JSONArray

/** What each version brought, from assets/changelog.json, in the current language. */
object Changelog {
    class Entry(val version: String, val notes: List<String>)

    fun load(ctx: Context): List<Entry> = runCatching {
        val arr = JSONArray(ctx.assets.open("changelog.json").bufferedReader().use { it.readText() })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val notes = o.getJSONObject("notes")
            val list = notes.optJSONArray(I18n.lang) ?: notes.optJSONArray("en") ?: notes.getJSONArray("de")
            Entry(o.getString("version"), (0 until list.length()).map { list.getString(it) })
        }
    }.getOrDefault(emptyList())

    fun of(ctx: Context, version: String) = load(ctx).firstOrNull { it.version == version }

    /** A menu with the notes of [entry]; [more] adds a way to earlier versions. */
    fun show(ctx: Context, entry: Entry, more: Boolean) {
        val items = entry.notes.map { GlassMenu.Item("•  $it") {} }.toMutableList()
        if (more) items += GlassMenu.Item(tr("Frühere Versionen …")) { showAll(ctx) }
        GlassMenu.show(ctx, tr("Neu in Version {0}", entry.version), items)
    }

    private fun showAll(ctx: Context) {
        GlassMenu.show(ctx, tr("Frühere Versionen"), load(ctx).map { e ->
            GlassMenu.Item(tr("Version {0}", e.version) + "  ·  " + e.notes.first()) { show(ctx, e, more = false) }
        })
    }
}
