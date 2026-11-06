package de.glas.smart

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Live picture of a Tuya camera or video doorbell. The cloud hands out a short-lived HLS address
 * (needs the "IoT Video Live Stream" service in the cloud project), which ExoPlayer plays.
 */
class LiveView(ctx: Context, private val deviceId: String, private val fullscreen: Boolean = false) : FrameLayout(ctx) {
    private var player: ExoPlayer? = null
    private var proxy: RtspsProxy? = null
    private val smartLife = ctx.text(14f).apply {
        text = "In Smart Life öffnen"
        gravity = Gravity.CENTER
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
        background = ctx.glass(18f, 0x8C000000.toInt(), 0x40FFFFFF)
        visibility = View.GONE
        setOnClickListener {
            val launch = ctx.packageManager.getLaunchIntentForPackage("com.tuya.smartlife")
            if (launch != null) ctx.startActivity(launch) else ctx.toast("Die Smart-Life-App ist nicht installiert.")
        }
    }
    private var muted = true
    private val video = PlayerView(ctx).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
        findViewById<View>(androidx.media3.ui.R.id.exo_buffering)?.visibility = View.GONE
    }
    private val message = ctx.text(15f).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        setShadowLayer(ctx.dp(6f), 0f, 0f, Color.BLACK)
    }
    private val sound = overlayButton("🔇") { toggleSound() }
    // Quality badge like in Smart Life: shows the resolution, and switches HD/SD where the camera offers both
    private val quality = ctx.text(14f).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ctx.dp(22f)
            setColor(0x8C000000.toInt())
        }
        visibility = View.GONE
        setOnClickListener { toggleQuality() }
    }
    // Quality: automatic (the player picks what the connection carries) or forced HD
    private var hd = false
    private val stalls = ArrayList<Long>()
    private var heights: List<Int> = emptyList()
    private var videoTracks = 0

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(smartLife, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, ctx.dp(10), 0, 0)
        })
        addView(quality, LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(44), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(ctx.dp(62), 0, 0, ctx.dp(10))
        })
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
        video.visibility = View.INVISIBLE
        message.text = "▶  Live-Bild anzeigen"
        message.visibility = View.VISIBLE
        sound.visibility = View.GONE
        quality.visibility = View.GONE
    }

    // Tried in this order: HLS works on most cameras, RTSP is the fallback for those whose HLS stays black
    // HLS works on most cameras, RTSP is the fallback; the way that worked last time is tried first
    private val prefs = ctx.getSharedPreferences("glas_smart", Context.MODE_PRIVATE)
    private var types = listOf("hls", "rtsp")
    private var reconnects = 0
    private var lastPos = -1L
    private var stuck = 0
    // Live streams from the cloud sometimes stall after a while: watch the position and reconnect
    private val health = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition
            if (p.playbackState == Player.STATE_BUFFERING || pos == lastPos) stuck++ else stuck = 0
            lastPos = pos
            // A stretch of smooth picture forgives earlier drop-outs
            if (stuck >= 3) reconnect() else later(4000, this)
        }
    }
    private var attempt = 0
    private var gotFrame = false
    private val notes = ArrayList<String>()
    private var info = ""
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
        smartLife.visibility = View.GONE
        val type = types[attempt]
        gotFrame = false
        info = ""
        message.text = "Verbinde mit der Kamera (${type.uppercase()}) …"
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
                    message.text = "Kein Live-Bild\n\n${explain(it)}\n\nAntippen zum Wiederholen"
                } else failed(Tuya.explain(it))
            }
            r.onSuccess { url -> play(url, type) }
        }
    }

    private fun explain(e: Throwable) =
        if (e is TuyaException && (e.code == 28841105 || e.code == 1106))
            "Im Tuya-Projekt unter Cloud services den Dienst „IoT Video Live Stream“ hinzufügen (kostenlos)."
        else Tuya.explain(e)

    private fun play(url: String, type: String) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(context, renderers).build()
        player = p
        video.player = p
        video.visibility = View.VISIBLE
        p.volume = if (muted) 0f else 1f
        if (hd) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setForceHighestSupportedBitrate(true).build()
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
                }
                gotFrame = true
                cancel(watchdog)
                message.visibility = View.GONE
                sound.visibility = View.VISIBLE
                showQuality()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!gotFrame && state == Player.STATE_BUFFERING) message.text = "Lade Bild (${type.uppercase()}) …"
                if (!gotFrame && state == Player.STATE_READY) message.text = "Warte auf das erste Bild (${type.uppercase()}) …"
                // The cloud ends a stream after a while: just fetch a new one
                if (gotFrame && state == Player.STATE_ENDED) reconnect()
            }

            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) = showQuality()

            override fun onTracksChanged(tracks: Tracks) {
                heights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height } }
                    .filter { it > 0 }.distinct().sorted()
                // HLS variants often come without a stated resolution, so count the video tracks themselves
                videoTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    .sumOf { g -> (0 until g.length).count { g.isTrackSupported(it) } }
                showQuality()
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
                // The underlying cause tells more than the generic code, e.g. an HTTP status or a TLS problem
                var cause: Throwable? = error.cause
                while (cause?.cause != null && cause.message.isNullOrBlank()) cause = cause.cause
                val detail = cause?.let { c -> "${c.javaClass.simpleName}: ${c.message ?: ""}".take(140) } ?: ""
                if (gotFrame) {
                    reconnect()
                    return
                }
                failed(error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ') +
                    if (detail.isNotEmpty()) " ($detail)" else "")
            }
        })
        p.playWhenReady = true
        p.prepare()
        cancel(watchdog)
        later(20_000, watchdog)
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
        message.text = "Kein Live-Bild\n\n" + notes.joinToString("\n") + "\n\nAntippen zum Wiederholen"
        message.visibility = View.VISIBLE
        smartLife.visibility = View.VISIBLE
        video.visibility = View.INVISIBLE
    }

    /** The picture was there but stopped: fetch a fresh stream address the same way. */
    private fun reconnect() {
        release()
        reconnects++
        // Picture keeps freezing: in HD go back to automatic, otherwise stop instead of loading forever
        val now = System.currentTimeMillis()
        stalls.removeAll { now - it > 90_000 }
        stalls += now
        if (stalls.size >= 3) {
            stalls.clear()
            if (hd) {
                hd = false
                context.toast("HD läuft über die Cloud nicht stabil, zurück auf Automatisch.")
            } else {
                notes += "${types[attempt].uppercase()}: Bild bleibt ständig hängen"
                attempt = types.size
                message.text = "Kein Live-Bild\n\n" + notes.joinToString("\n") + "\n\nAntippen zum Wiederholen"
                message.visibility = View.VISIBLE
                smartLife.visibility = View.VISIBLE
                return
            }
        }
        if (reconnects > 3) {
            failed("Verbindung bricht immer wieder ab")
            return
        }
        connect()
        message.text = "Verbindung wird erneuert …"
    }

    /** "HD 1080p" or "SD 360p"; tappable only if the stream offers more than one resolution. */
    private fun showQuality() {
        val h = player?.videoSize?.height ?: 0
        if (h <= 0 || !gotFrame) return
        val switchable = videoTracks > 1 || heights.size > 1
        val way = types.getOrNull(attempt)?.uppercase() ?: ""
        quality.text = (if (h >= 720) "HD" else "SD") + " ${h}p · $way" +
            if (switchable) (if (hd) " · HD" else " · Auto") + "  ⇅" else ""
        quality.isClickable = true
        quality.visibility = View.VISIBLE
    }

    private fun toggleQuality() {
        val p = player ?: return
        if (videoTracks < 2 && heights.size < 2) {
            context.toast("Die Kamera liefert über die Cloud nur diese Auflösung.")
            return
        }
        hd = !hd
        stalls.clear()
        context.toast(if (hd) "HD erzwingen" else "Automatische Qualität")
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setForceHighestSupportedBitrate(hd).build()
    }

    private fun release() {
        cancel(watchdog)
        cancel(health)
        proxy?.close()
        proxy = null
        player?.release()
        player = null
        video.player = null
        sound.visibility = View.GONE
        quality.visibility = View.GONE
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

/** Live picture across the whole screen, turned sideways. */
class LiveActivity : Activity() {
    private lateinit var live: LiveView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
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
