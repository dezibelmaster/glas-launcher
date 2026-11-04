package de.glas.launcher

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves all settings (dock, PIN, places, favourites, background and so on) to a file and
 * reads them back. The file goes to the Download folder and to the launcher's own folder,
 * where another device's copy can also be placed with adb push.
 */
object Backup {
    const val FILE = "GlasLauncher-Einstellungen.json"

    /** Folder for a copy brought from another device: /sdcard/Android/data/de.glas.launcher/files */
    fun ownFolder(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    /** Writes the settings file. Returns where it went, or null on failure. Blocking. */
    fun export(ctx: Context): String? {
        val json = toJson(ctx).toString(2)
        runCatching { File(ownFolder(ctx), FILE).writeText(json) }
        val download = runCatching { writeDownload(ctx, json) }.getOrDefault(false)
        return if (download) "Download/$FILE" else if (File(ownFolder(ctx), FILE).exists()) File(ownFolder(ctx), FILE).path else null
    }

    /** Reads the settings file back, preferring a copy placed in the launcher's own folder. Blocking. */
    fun import(ctx: Context): Boolean {
        val text = runCatching { File(ownFolder(ctx), FILE).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { readDownload(ctx) }.getOrNull()
            ?: return false
        return runCatching { fromJson(ctx, JSONObject(text)) }.isSuccess
    }

    /** The settings as the text of a backup file. */
    fun text(ctx: Context): String = toJson(ctx).toString(2)

    /** Applies a backup file's text; false if it isn't one. */
    fun restore(ctx: Context, text: String): Boolean = runCatching {
        val json = JSONObject(text)
        if (json.optString("app") != "Glas Launcher" || !json.has("prefs")) return false
        fromJson(ctx, json)
    }.isSuccess

    private fun toJson(ctx: Context): JSONObject {
        val prefs = JSONObject()
        for ((key, value) in ctx.getSharedPreferences("glas", Context.MODE_PRIVATE).all) {
            // Weather is fetched again anyway
            if (key.startsWith("weather.") || key.startsWith("w.")) continue
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            prefs.put(key, entry)
        }
        return JSONObject().put("app", "Glas Launcher").put("version", 1).put("prefs", prefs)
    }

    private fun fromJson(ctx: Context, json: JSONObject) {
        val prefs = json.getJSONObject("prefs")
        val edit = ctx.getSharedPreferences("glas", Context.MODE_PRIVATE).edit()
        for (key in prefs.keys()) {
            val e = prefs.getJSONObject(key)
            when (e.getString("t")) {
                "s" -> edit.putString(key, e.getString("v"))
                "b" -> edit.putBoolean(key, e.getBoolean("v"))
                "i" -> edit.putInt(key, e.getInt("v"))
                "l" -> edit.putLong(key, e.getLong("v"))
                "f" -> edit.putFloat(key, e.getDouble("v").toFloat())
                "set" -> {
                    val arr = e.getJSONArray("v")
                    edit.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        edit.apply()
    }

    @Suppress("DEPRECATION")
    private fun writeDownload(ctx: Context, json: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            File(dir, FILE).writeText(json)
            return true
        }
        val resolver = ctx.contentResolver
        downloadUri(ctx)?.let { resolver.delete(it, null, null) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: return false
        return true
    }

    @Suppress("DEPRECATION")
    private fun readDownload(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 29) {
            val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE)
            return if (f.exists()) f.readText() else null
        }
        val uri = downloadUri(ctx) ?: return null
        return ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    }

    /** The launcher's own settings file in Download, if it made one. */
    private fun downloadUri(ctx: Context): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(FILE), null)?.use { c ->
            if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString())
        }
        return null
    }
}
