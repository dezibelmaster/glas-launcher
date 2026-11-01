package de.glas.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wallpaper plugins made for Projectivy Launcher. They expose an exported service for
 * [ACTION]; the launcher binds to it and asks for wallpapers with a "time elapsed" event,
 * just like Projectivy does (public plugin API, Apache License 2.0).
 */
@Suppress("DEPRECATION")
object ProjectivyPlugins {
    const val ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER"

    class Plugin(val component: ComponentName, val name: String, val settings: ComponentName?, val cacheMillis: Long)

    fun installed(ctx: Context): List<Plugin> {
        val pm = ctx.packageManager
        return pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA).mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val meta = si.metaData
            val name = meta?.get("name")?.toString()?.takeIf { it.isNotBlank() } ?: si.loadLabel(pm).toString()
            val settings = meta?.getString("settingsActivity")?.let {
                ComponentName(si.packageName, if (it.startsWith(".")) si.packageName + it else it)
            }
            val cache = (meta?.get("itemsCacheDurationMillis") as? Number)?.toLong() ?: (60 * 60_000L)
            Plugin(ComponentName(si.packageName, si.name), name, settings, cache)
        }.sortedBy { it.name.lowercase() }
    }

    fun find(ctx: Context, flat: String?): Plugin? = flat?.let { f -> installed(ctx).firstOrNull { it.component.flattenToString() == f } }

    /** Blocking: binds to the plugin, asks for its wallpapers and unbinds. Call from a background thread. */
    fun wallpapers(ctx: Context, component: ComponentName): List<Wallpaper> {
        val app = ctx.applicationContext
        val latch = CountDownLatch(1)
        var service: IWallpaperProviderService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IWallpaperProviderService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = runCatching {
            app.bindService(Intent(ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) return emptyList()
        return try {
            latch.await(15, TimeUnit.SECONDS)
            service?.getWallpapers(Event.timeElapsed()).orEmpty()
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { app.unbindService(connection) }
        }
    }

    /** Wallpapers the launcher can show: pictures, videos and plain colours. */
    fun isSupported(w: Wallpaper) = w.uri != null && w.type in setOf(Wallpaper.IMAGE, Wallpaper.DRAWABLE, Wallpaper.VIDEO, Wallpaper.COLOR)
}
