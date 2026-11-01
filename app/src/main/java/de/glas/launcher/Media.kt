package de.glas.launcher

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pictures and videos on the TV that can be used as background. */
object Media {
    class Item(val name: String, val uri: String)

    private val imageTypes = setOf("jpg", "jpeg", "png", "webp")
    private val videoTypes = setOf("mp4", "mkv", "webm", "mov", "m4v")

    fun permission(video: Boolean): String = when {
        Build.VERSION.SDK_INT >= 33 && video -> Manifest.permission.READ_MEDIA_VIDEO
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_IMAGES
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    /** Outcome of a delete: done, failed, or Android wants the user to confirm in its own dialog. */
    sealed class Deletion {
        object Done : Deletion()
        object Failed : Deletion()
        class Confirm(val sender: IntentSender) : Deletion()
    }

    /**
     * Deletes a picture or video. Blocking, so call it from a background thread.
     * Files the launcher did not create (e.g. copied with adb push) may only be deleted
     * after the user confirms in Android's own dialog from Android 10 on.
     */
    fun delete(ctx: Context, uri: String): Deletion {
        val u = Uri.parse(uri)
        val content = if (u.scheme == "file") {
            val file = File(u.path ?: return Deletion.Failed)
            if (runCatching { file.delete() }.getOrDefault(false)) return Deletion.Done
            mediaStoreUri(ctx, file) ?: return Deletion.Failed
        } else {
            u
        }
        return try {
            if (ctx.contentResolver.delete(content, null, null) > 0) Deletion.Done else Deletion.Failed
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= 30 ->
                    Deletion.Confirm(MediaStore.createDeleteRequest(ctx.contentResolver, listOf(content)).intentSender)
                Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException ->
                    Deletion.Confirm(e.userAction.actionIntent.intentSender)
                else -> Deletion.Failed
            }
        }
    }

    /** The media index entry for a file, scanning it in first if it isn't indexed yet. */
    @Suppress("DEPRECATION")
    private fun mediaStoreUri(ctx: Context, file: File): Uri? {
        val files = MediaStore.Files.getContentUri("external")
        runCatching {
            ctx.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(file.absolutePath), null)
                ?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(files, c.getLong(0)) }
        }
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return scanned
    }

    class VideoInfo(val width: Int, val height: Int, val fps: Float, val hdr: Boolean)

    /** Resolution, frame rate and HDR of a video, or null if they can't be read. Blocking. */
    fun videoInfo(ctx: Context, uri: String): VideoInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, Uri.parse(uri))
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: frameRate(r)
            val hdr = Build.VERSION.SDK_INT >= 30 &&
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() in setOf(6, 7)
            // Portrait phone videos report their size before rotation
            if (rotation % 180 == 0) VideoInfo(w, h, fps, hdr) else VideoInfo(h, w, fps, hdr)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Frame rate from frame count and duration, where the file doesn't state it. */
    private fun frameRate(r: MediaMetadataRetriever): Float {
        if (Build.VERSION.SDK_INT < 28) return 0f
        val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull() ?: return 0f
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull() ?: return 0f
        return if (ms > 0) frames / (ms / 1000f) else 0f
    }

    fun list(ctx: Context, video: Boolean): List<Item> {
        val found = LinkedHashMap<String, Item>()
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        runCatching {
            ctx.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    found.putIfAbsent(name, Item(name, ContentUris.withAppendedId(collection, c.getLong(0)).toString()))
                }
            }
        }
        // Files copied with adb push don't always show up in the media index right away
        val types = if (video) videoTypes else imageTypes
        @Suppress("DEPRECATION")
        val storage = Environment.getExternalStorageDirectory()
        for (dir in listOf("Pictures", "Pictures/GlasLauncher", "Movies", "Download", "DCIM")) {
            File(storage, dir).listFiles()?.forEach { f ->
                if (f.isFile && f.extension.lowercase() in types) found.putIfAbsent(f.name, Item(f.name, Uri.fromFile(f).toString()))
            }
        }
        return found.values.toList()
    }
}
