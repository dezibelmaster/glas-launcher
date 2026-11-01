package de.glas.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Background extensions: separate apps that offer videos or pictures.
 *
 * An extension declares an exported content provider with an intent filter for
 * [ACTION]. The launcher queries content://<authority>/wallpapers and reads the
 * columns title, subtitle, type ("video" or "image") and uri (https, content or file).
 */
object Extensions {
    const val ACTION = "de.glas.launcher.WALLPAPER_PROVIDER"

    class Extension(val authority: String, val label: String)
    class Wallpaper(val title: String, val subtitle: String, val type: String, val uri: String)

    fun installed(ctx: Context): List<Extension> {
        val pm = ctx.packageManager
        return pm.queryIntentContentProviders(Intent(ACTION), 0).mapNotNull { ri ->
            val info = ri.providerInfo ?: return@mapNotNull null
            val authority = info.authority?.split(';')?.firstOrNull() ?: return@mapNotNull null
            Extension(authority, info.applicationInfo.loadLabel(pm).toString())
        }
    }

    /** Blocking; extensions may go online, so call from a background thread. */
    fun wallpapers(ctx: Context, authority: String): List<Wallpaper> = runCatching {
        ctx.contentResolver.query(Uri.parse("content://$authority/wallpapers"), null, null, null, null)?.use { c ->
            val title = c.getColumnIndex("title")
            val subtitle = c.getColumnIndex("subtitle")
            val type = c.getColumnIndex("type")
            val uri = c.getColumnIndex("uri")
            if (title < 0 || uri < 0) return@use emptyList()
            buildList {
                while (c.moveToNext()) {
                    val u = c.getString(uri) ?: continue
                    add(Wallpaper(
                        c.getString(title) ?: u,
                        if (subtitle >= 0) c.getString(subtitle).orEmpty() else "",
                        if (type >= 0) c.getString(type) ?: "video" else "video",
                        u,
                    ))
                }
            }
        }
    }.getOrNull().orEmpty()
}
