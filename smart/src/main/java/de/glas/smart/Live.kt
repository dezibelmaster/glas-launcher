package de.glas.smart

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout

/** Opens the Smart Life app, for what only it can do (HD picture, talking through the doorbell). */
fun Context.openSmartLife() {
    val launch = packageManager.getLaunchIntentForPackage("com.tuya.smartlife")
    if (launch != null) startActivity(launch) else toast("Die Smart-Life-App ist nicht installiert.")
}

/**
 * Live picture of a Tuya camera or video doorbell. The cloud hands out a short-lived HLS address
 * (needs the "IoT Video Live Stream" service in the cloud project), which ExoPlayer plays.
 * While it runs, a still picture is saved now and then for the camera widget.
 */
class LiveView(ctx: Context, private val deviceId: String, private val fullscreen: Boolean = false) : FrameLayout(ctx) {
    private var player: ExoPlayer? = null
    private var proxy: RtspsProxy? = null
    private var muted = true

    // A TextureView, unlike a SurfaceView, can hand out the current picture for the widget
    private val texture = TextureView(ctx)
    private val frame = AspectRatioFrameLayout(ctx).apply {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setAspectRatio(16f / 9f)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private val message = ctx.text(15f).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        setShadowLayer(ctx.dp(6f), 0f, 0f, Color.BLACK)
    }
    private val sound = overlayButton("🔇") { toggleSound() }

    init {
        setBackgroundColor(Color.BLACK)
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(sound, LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(ctx.dp(10), 0, 0, ctx.dp(10))
        })
        if (!fullscreen) {
            addView(overlayButton("⛶") {
                stop()
                ctx.startActivity(Intent(ctx, LiveActivity::class.java).putExtra("id", deviceId))
            }, LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, ctx.dp(10), ctx.dp(10))
            })
        }
        idle()
        setOnClickListener { if (player == null && message.text.let { it.startsWith("▶") || it.contains("Antippen") }) start() }
    }

    private fun overlayButton(label: String, onClick: () -> Unit) = context.text(18f).apply {
        text = label
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x8C000000.toInt())
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun idle() {
        frame.visibility = View.INVISIBLE
        message.text = "▶  Live-Bild anzeigen"
        message.visibility = View.VISIBLE
        sound.visibility = View.GONE
    }

    // HLS works on most cameras, RTSP is the fallback; the way that worked last time is tried first
    private val prefs = ctx.getSharedPreferences("glas_smart", Context.MODE_PRIVATE)
    private var types = listOf("hls", "rtsp")
    private var attempt = 0
    private var gotFrame = false
    private var reconnects = 0
    private val stalls = ArrayList<Long>()
    private val notes = ArrayList<String>()
    private var info = ""
    private var lastPos = -1L
    private var stuck = 0
    private var lastSnapshot = 0L

    // Live streams from the cloud stall or end after a while: watch the position and reconnect
    private val health = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition
            if (p.playbackState == Player.STATE_BUFFERING || pos == lastPos) stuck++ else stuck = 0
            lastPos = pos
            if (stuck == 0) snapshot()
            if (stuck >= 3) reconnect() else later(4000, this)
        }
    }
    private val watchdog = Runnable { failed("kein Bild nach 20 s${if (info.isNotEmpty()) " ($info)" else ""}") }

    fun start() {
        if (player != null) return
        val preferred = prefs.getString("stream_$deviceId", null)
        types = listOf("hls", "rtsp").sortedBy { if (it == preferred) 0 else 1 }
        reconnects = 0
        stalls.clear()
        attempt = 0
        notes.clear()
        connect()
    }

    private fun connect() {
        val type = types[attempt]
        gotFrame = false
        info = ""
        message.text = "Verbinde mit der Kamera …"
        message.visibility = View.VISIBLE
        val api = Store(context).api()
        val device = Repo.find(deviceId)
        async({
            // Battery cameras sleep: wake them first if they offer it
            if (device != null && "wireless_awake" in device.status && device.status["wireless_awake"] != true) {
                runCatching { api.send(deviceId, listOf("wireless_awake" to true)) }
                Thread.sleep(3000)
            }
            api.streamUrl(deviceId, type)
        }) { r ->
            r.onFailure {
                if (it is TuyaException && (it.code == 28841105 || it.code == 1106) && attempt == 0) {
                    stop()
                    message.text = "Kein Live-Bild\n\nIm Tuya-Projekt unter Cloud services den Dienst " +
                        "„IoT Video Live Stream“ hinzufügen (kostenlos).\n\nAntippen zum Wiederholen"
                } else failed(Tuya.explain(it))
            }
            r.onSuccess { url -> play(url, type) }
        }
    }

    private fun play(url: String, type: String) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(context, renderers).build()
        player = p
        p.setVideoTextureView(texture)
        frame.visibility = View.VISIBLE
        p.volume = if (muted) 0f else 1f
        if (type == "rtsp") {
            // rtsps:// goes through a small TLS proxy on the phone; media is carried inside the RTSP connection
            var playUrl = url
            if (url.startsWith("rtsps://", ignoreCase = true)) {
                val px = RtspsProxy(url)
                proxy = px
                playUrl = px.localUrl
            }
            p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(15_000)
                .createMediaSource(MediaItem.fromUri(playUrl)))
        } else {
            p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        }
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!gotFrame) {
                    reconnects = 0
                    prefs.edit().putString("stream_$deviceId", type).apply()
                    stuck = 0
                    lastPos = -1
                    cancel(health)
                    later(4000, health)
                    later(1500) { snapshot(force = true) }
                }
                gotFrame = true
                cancel(watchdog)
                message.visibility = View.GONE
                sound.visibility = View.VISIBLE
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) frame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!gotFrame && state == Player.STATE_BUFFERING) message.text = "Lade Bild …"
                // The cloud ends a stream after a while: just fetch a new one
                if (gotFrame && state == Player.STATE_ENDED) reconnect()
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Remember the video format, to explain a black picture (e.g. a codec the phone can't decode)
                for (g in tracks.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    val f = g.getTrackFormat(0)
                    info = "${f.sampleMimeType?.substringAfter('/') ?: "?"} ${f.width}×${f.height}" +
                        if (!g.isTrackSupported(0)) ", vom Handy nicht unterstützt" else ""
                }
                if (tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO } && tracks.groups.isNotEmpty()) info = "nur Ton, kein Video"
            }

            override fun onPlayerError(error: PlaybackException) {
                if (gotFrame) {
                    reconnect()
                    return
                }
                // The underlying cause tells more than the generic code, e.g. an HTTP status or a TLS problem
                var cause: Throwable? = error.cause
                while (cause?.cause != null && cause.message.isNullOrBlank()) cause = cause.cause
                val detail = cause?.let { c -> "${c.javaClass.simpleName}: ${c.message ?: ""}".take(140) } ?: ""
                failed(error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ') +
                    if (detail.isNotEmpty()) " ($detail)" else "")
            }
        })
        p.playWhenReady = true
        p.prepare()
        cancel(watchdog)
        later(20_000, watchdog)
    }

    /** Saves the current picture for the camera widget, at most every 30 seconds. */
    private fun snapshot(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSnapshot < 30_000) return
        if (!gotFrame || !texture.isAvailable) return
        val bmp = texture.getBitmap(640, (640 / frame.let { if (it.width > 0) it.width.toFloat() / it.height else 16f / 9f }).toInt())
            ?: return
        lastSnapshot = now
        Widgets.saveSnapshot(context, deviceId, bmp)
    }

    /** This way didn't work: note why and try the next one, or show all reasons. */
    private fun failed(reason: String) {
        notes += "${types[attempt].uppercase()}: $reason"
        release()
        attempt++
        if (attempt < types.size) {
            connect()
            return
        }
        giveUp()
    }

    private fun giveUp() {
        attempt = types.size
        message.text = "Kein Live-Bild\n\n" + notes.joinToString("\n") + "\n\nAntippen zum Wiederholen"
        message.visibility = View.VISIBLE
        frame.visibility = View.INVISIBLE
    }

    /** The picture was there but stopped: fetch a fresh stream address the same way. */
    private fun reconnect() {
        release()
        reconnects++
        val now = System.currentTimeMillis()
        stalls.removeAll { now - it > 90_000 }
        stalls += now
        // Freezes again and again: stop instead of loading forever
        if (stalls.size >= 3 || reconnects > 3) {
            notes += "${types[attempt].uppercase()}: Bild bleibt ständig hängen"
            giveUp()
            return
        }
        connect()
        message.text = "Verbindung wird erneuert …"
    }

    private fun release() {
        cancel(watchdog)
        cancel(health)
        proxy?.close()
        proxy = null
        player?.release()
        player = null
        sound.visibility = View.GONE
    }

    private fun toggleSound() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        sound.text = if (muted) "🔇" else "🔊"
    }

    fun stop() {
        release()
        attempt = types.size
        idle()
    }
}

/** Live picture across the whole screen, turned sideways. Also opened by the camera widget. */
class LiveActivity : Activity() {
    private lateinit var live: LiveView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        // Started from a widget, the device list may not be loaded yet
        Repo.load(Store(this))
        live = LiveView(this, intent.getStringExtra("id") ?: "", fullscreen = true)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(live, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(text(26f).apply {
            text = "✕"
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setShadowLayer(dp(6f), 0f, 0f, Color.BLACK)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        live.start()
    }

    override fun onStop() {
        super.onStop()
        live.stop()
    }
}
