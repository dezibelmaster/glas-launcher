package de.glas.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import kotlin.math.max

class MediaItem(val uri: String, val title: String)

/**
 * Muted video background. One video loops; several play one after another unless one is
 * pinned, which then loops. With [cache] online videos are downloaded once by [MediaCache]
 * before they play, otherwise they stream. [sources] is called on a background thread.
 *
 * For the frosted glass a small copy of the picture is taken into reused bitmaps, as often
 * as set under "Milchglas über Videos" (twice a second by default). (A SurfaceView with PixelCopy made videos stutter on the TV, so the video stays
 * on a TextureView.)
 */
class VideoBackdrop(
    context: Context,
    private val cache: Boolean = true,
    private val start: String? = null,
    private val sources: () -> List<MediaItem>,
) : Backdrop(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var playlist = emptyList<MediaItem>()
    private var index = 0
    private var isPinned = false
    private var released = false
    private var prepared = false
    private var paused = false
    private var resumeAt = 0
    private var failed = false
    private var retries = 0
    private var waitingForNetwork: ConnectivityManager.NetworkCallback? = null
    private var sx = 1f
    private var sy = 1f
    private var grab: Bitmap? = null
    private var small: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private var lastCapture = 0L
    private val prefs = Prefs(context)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    init {
        setBackgroundColor(Color.BLACK)
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override val titles get() = if (playlist.size > 1) playlist.map { it.title } else emptyList()
    override val current get() = if (playlist.size > 1) index else -1
    override val pinned get() = isPinned

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        released = false
        surface = Surface(st)
        if (playlist.isNotEmpty()) return if (paused) Unit else play()
        Thread {
            val list = sources()
            main.post {
                if (released) return@post
                playlist = list
                if (list.isEmpty()) {
                    context.toast(tr("Kein Video gefunden"))
                    return@post
                }
                val startAt = list.indexOfFirst { it.uri == start }
                isPinned = startAt >= 0
                index = startAt.coerceAtLeast(0)
                // Switching through favourites: start with one of them
                if (!isPinned && prefs.favoritesOnly && uriAt(index) !in prefs.favorites) index = step(index, list.size, forward = true)
                play()
            }
        }.start()
    }

    override fun select(index: Int, pin: Boolean) {
        if (index !in playlist.indices) return
        isPinned = pin
        if (index == this.index && player != null) {
            player?.isLooping = isPinned || playlist.size == 1
            onChange?.invoke()
            return
        }
        this.index = index
        play()
    }

    override fun uriAt(index: Int) = playlist.getOrNull(index)?.uri

    /**
     * Reads the videos again, e.g. after some were deleted or added. What is playing goes on
     * if it is still there; otherwise the next one starts.
     */
    fun refresh() {
        if (playlist.isEmpty()) return
        Thread {
            val list = runCatching { sources() }.getOrNull() ?: return@Thread
            main.post {
                if (released || list.isEmpty()) return@post
                if (list.map { it.uri }.toSet() == playlist.map { it.uri }.toSet()) return@post
                val keep = list.indexOfFirst { it.uri == uriAt(index) }
                playlist = list
                if (keep >= 0) {
                    index = keep
                    player?.isLooping = isPinned || list.size == 1
                } else {
                    isPinned = false
                    index = 0
                    play()
                }
                onChange?.invoke()
            }
        }.start()
    }

    override fun unpin() {
        isPinned = false
        player?.isLooping = playlist.size == 1
        onChange?.invoke()
    }

    override fun next() {
        if (playlist.size < 2) return
        index = step(index, playlist.size, forward = true)
        play()
    }

    override fun previous() {
        if (playlist.size < 2) return
        index = step(index, playlist.size, forward = false)
        play()
    }

    private fun play() {
        // Another app may be using the video decoder right now
        if (paused || released) return
        onChange?.invoke()
        val item = playlist[index]
        if (!cache) return start(item.uri)
        if (!MediaCache.isCached(context, item.uri)) context.toast(tr("„{0}“ wird geladen …", item.title))
        val wanted = index
        MediaCache.fetch(context, item.uri) { local ->
            // Ignore a download that finished after the user moved on
            if (released || wanted != index) return@fetch
            if (local == null) return@fetch failed(tr("Das Video lässt sich nicht laden"))
            start(local)
            // Fetch the next one now, so it is ready when this one ends
            if (playlist.size > 1 && !isPinned) MediaCache.fetch(context, playlist[step(index, playlist.size, forward = true)].uri) {}
        }
    }

    private fun start(uri: String) {
        val s = surface ?: return
        runCatching { player?.release() }
        prepared = false
        failed = false
        player = MediaPlayer().apply {
            try {
                setDataSource(context, Uri.parse(uri))
                setSurface(s)
                isLooping = isPinned || playlist.size == 1
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, vw, vh -> fit(vw, vh) }
                setOnPreparedListener {
                    prepared = true
                    retries = 0
                    if (resumeAt > 0) it.seekTo(resumeAt)
                    resumeAt = 0
                    it.start()
                }
                setOnCompletionListener { if (playlist.size > 1 && !isPinned) next() }
                setOnErrorListener { _, _, _ ->
                    failed(tr("Das Video lässt sich nicht abspielen"))
                    true
                }
                prepareAsync()
            } catch (e: Exception) {
                failed(tr("Das Video lässt sich nicht öffnen"))
            }
        }
    }

    /** Crops the video to fill the screen instead of stretching it. */
    private fun fit(vw: Int, vh: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (vw == 0 || vh == 0 || w == 0f) return
        val scale = max(w / vw, h / vh)
        sx = vw * scale / w
        sy = vh * scale / h
        texture.setTransform(Matrix().apply { setScale(sx, sy, w / 2, h / 2) })
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun drawBackdrop(c: Canvas) {
        val w = width
        val h = height
        if (w == 0 || !prepared) return
        val now = SystemClock.uptimeMillis()
        // Rate from the settings; 0 picks up one still picture and keeps it
        val rate = prefs.glassVideoRate
        val due = if (rate <= 0) small == null else now - lastCapture >= 1000L / rate
        if (due) {
            lastCapture = now
            // Grab at a quarter of the screen size, then shrink into the small bitmap; both are reused
            val g = grab ?: Bitmap.createBitmap(w / 8, h / 8, Bitmap.Config.ARGB_8888).also { grab = it }
            texture.getBitmap(g)
            val sb = small ?: Bitmap.createBitmap(w / 16, h / 16, Bitmap.Config.ARGB_8888).also {
                small = it
                smallCanvas = Canvas(it)
            }
            src.set(0, 0, g.width, g.height)
            dst.set(0f, 0f, sb.width.toFloat(), sb.height.toFloat())
            smallCanvas?.drawBitmap(g, src, dst, paint)
        }
        val sb = small ?: return
        // The captured frame ignores the crop transform, so crop it here the same way
        val cw = (sb.width / sx).toInt()
        val ch = (sb.height / sy).toInt()
        src.set((sb.width - cw) / 2, (sb.height - ch) / 2, (sb.width + cw) / 2, (sb.height + ch) / 2)
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        c.drawBitmap(sb, src, dst, paint)
    }

    /** Releases the player completely, so the decoder is free for the app that opens next. */
    override fun pause() {
        paused = true
        val p = player ?: return
        // Remember the position of a looping video to carry on there later
        resumeAt = if (prepared) runCatching { p.currentPosition }.getOrDefault(0) else 0
        runCatching { p.release() }
        player = null
        prepared = false
    }

    /**
     * Right after the TV is switched on the network is often not back yet. Then wait for it;
     * otherwise try again a few times with growing pauses before giving up on this video.
     */
    private fun failed(message: String) {
        failed = true
        prepared = false
        if (released) return
        // Stored videos don't need the network, only streamed or not yet downloaded ones do
        val item = playlist.getOrNull(index)
        val needsNetwork = item != null && (!cache || !MediaCache.isCached(context, item.uri))
        if (needsNetwork && !Network.isOnline(context)) return waitForNetwork()
        if (retries < 4) {
            retries++
            main.postDelayed({ if (!released && failed) play() }, 3_000L * retries)
            return
        }
        retries = 0
        if (playlist.size > 1 && !isPinned) next() else context.toast(message)
    }

    private fun waitForNetwork() {
        if (waitingForNetwork != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                main.post {
                    stopWaiting()
                    // Give the connection a moment to settle before streaming
                    main.postDelayed({ if (!released && failed) play() }, 1_500)
                }
            }
        }
        waitingForNetwork = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onFailure { waitingForNetwork = null }
    }

    private fun stopWaiting() {
        val cb = waitingForNetwork ?: return
        waitingForNetwork = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    override fun resume() {
        if (paused) {
            paused = false
            if (surface != null && playlist.isNotEmpty()) play()
            return
        }
        when {
            prepared -> runCatching { player?.start() }
            // Coming back to the home screen with a video that never started: try it again
            failed && playlist.isNotEmpty() && waitingForNetwork == null -> {
                retries = 0
                play()
            }
        }
    }

    override fun release() {
        released = true
        stopWaiting()
        runCatching { player?.release() }
        player = null
        surface?.release()
        surface = null
    }
}
