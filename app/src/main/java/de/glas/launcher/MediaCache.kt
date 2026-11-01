package de.glas.launcher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Online videos and pictures are downloaded once and then played from the TV's
 * storage, so they loop without buffering. Keeps at most [LIMIT] bytes.
 */
object MediaCache {
    private const val LIMIT = 800L * 1024 * 1024
    private val running = mutableSetOf<String>()
    private val waiting = mutableMapOf<String, MutableList<(String?) -> Unit>>()
    private val main = Handler(Looper.getMainLooper())

    private fun dir(ctx: Context) = File(ctx.cacheDir, "media").apply { mkdirs() }

    private fun fileFor(ctx: Context, uri: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        val ext = Uri.parse(uri).lastPathSegment?.substringAfterLast('.', "")?.take(5).orEmpty()
        return File(dir(ctx), if (ext.isEmpty()) hash else "$hash.$ext")
    }

    private fun isRemote(uri: String) = uri.startsWith("http://") || uri.startsWith("https://")

    fun isCached(ctx: Context, uri: String) = !isRemote(uri) || fileFor(ctx, uri).exists()

    /** Local address for [uri]; downloads it first if needed. [done] runs on the main thread, with null on failure. */
    fun fetch(ctx: Context, uri: String, done: (String?) -> Unit) {
        if (!isRemote(uri)) return done(uri)
        val file = fileFor(ctx, uri)
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return done(Uri.fromFile(file).toString())
        }
        synchronized(running) {
            waiting.getOrPut(uri) { mutableListOf() } += done
            if (!running.add(uri)) return
        }
        Thread {
            val ok = download(uri, file)
            if (ok) trim(ctx, keep = file)
            val callbacks = synchronized(running) {
                running.remove(uri)
                waiting.remove(uri).orEmpty()
            }
            main.post { callbacks.forEach { it(if (ok) Uri.fromFile(file).toString() else null) } }
        }.start()
    }

    /** Blocking variant for background threads. */
    fun fetchBlocking(ctx: Context, uri: String): String? {
        if (!isRemote(uri)) return uri
        val file = fileFor(ctx, uri)
        if (file.exists() || download(uri, file)) return Uri.fromFile(file).toString()
        return null
    }

    private fun download(uri: String, file: File): Boolean {
        val part = File(file.path + ".part")
        return try {
            var url = URL(uri)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "GlasLauncher/2.3 (Android TV)")
                val code = conn.responseCode
                if (code in 300..399 && redirects++ < 5) {
                    url = URL(url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    continue
                }
                if (code != 200) return false
                break
            }
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
            conn.disconnect()
            part.renameTo(file)
        } catch (e: Exception) {
            part.delete()
            false
        }
    }

    /** Space the downloaded videos and pictures take, in bytes. */
    fun size(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes all downloads; they are fetched again when next needed. */
    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { if (!it.name.endsWith(".part")) it.delete() }
    }

    /** Deletes the least recently used files once the cache is over its limit. */
    private fun trim(ctx: Context, keep: File) {
        val files = dir(ctx).listFiles()?.filter { !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= LIMIT) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }
}
