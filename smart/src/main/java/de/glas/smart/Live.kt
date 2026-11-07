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

/** Opens the Smart Life app, for what only it can do (HD picture, talking through the doorbell). */
fun Context.openSmartLife() {
    val launch = packageManager.getLaunchIntentForPackage("com.tuya.smartlife")
    if (launch != null) startActivity(launch) else toast("Die Smart-Life-App ist nicht installiert.")
}

/**
 * Live picture of a Tuya camera or video doorbell. The cloud hands out a short-lived HLS address
 * (needs the "IoT Video Live Stream" service in the cloud project), which ExoPlayer plays.
 */
class LiveView(ctx: Context, private val deviceId: String, private val fullscreen: Boolean = false) : FrameLayout(ctx) {
    private var player: ExoPlayer? = null
    private var proxy: RtspsProxy? = null
    private var muted = true
    private val video = PlayerView(ctx).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
    }
    private val message = ctx.text(15f).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        setShadowLayer(ctx.dp(6f), 0f, 0f, Color.BLACK)
    }
    private val sound = overlayButton("🔇") { toggleSound() }

    // WebRTC picture (HD/SD selectable); HLS above stays as the fallback
    private var rtc: WebRtcLive? = null
    private var rtcTries = 0
    private var frameHeight = 0
    private val qualityCheck = Runnable { checkQuality() }
    // The doorbell refuses while an earlier session is still winding down: ask again a few times first
    private var rtcRefusals = 0
    private var rendererReady = false
    private val renderer = org.webrtc.SurfaceViewRenderer(ctx).apply { visibility = View.GONE }
    private val rtcWatchdog = Runnable { rtcFailed("kein Bild nach 25 s") }
    private val prefs = ctx.getSharedPreferences("glas_smart", Context.MODE_PRIVATE)

    /** HD (main stream) or SD (sub stream); remembered per camera. */
    var hd: Boolean
        get() = prefs.getBoolean("hd_$deviceId", true)
        private set(v) = prefs.edit().putBoolean("hd_$deviceId", v).apply()

    /** Short diagnostic line shown under the picture, e.g. why WebRTC fell back to HLS. */
    var onStatus: ((String) -> Unit)? = null

    /** Told whether HD/SD can be chosen right now (only over WebRTC). */
    var onQuality: ((available: Boolean) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        message.text = "▶  Live-Bild anzeigen"
        message.visibility = View.VISIBLE
        sound.visibility = View.GONE
    }

    // Tried in this order: HLS works on most cameras, RTSP is the fallback for those whose HLS stays black
    private val types = listOf("hls", "rtsp")
    private var attempt = 0
    private var gotFrame = false
    private val notes = ArrayList<String>()
    private var info = ""
    private var newStreams = 0
    private val snapshots = object : Runnable {
        override fun run() {
            snapshot()
            later(30_000, this)
        }
    }
    private val watchdog = Runnable { failed("kein Bild nach 20 s${if (info.isNotEmpty()) " ($info)" else ""}") }

    fun start() {
        if (player != null || rtc != null) return
        newStreams = 0
        rtcTries = 0
        rtcRefusals = 0
        attempt = 0
        notes.clear()
        val uid = Repo.find(deviceId)?.uid ?: ""
        if (uid.isNotEmpty()) startRtc(uid) else connect()
    }

    // ---- WebRTC ----

    private fun startRtc(uid: String) {
        if (!rendererReady) {
            renderer.init(WebRtcLive.egl.eglBaseContext, object : org.webrtc.RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() { post { rtcPicture() } }
                override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) {
                    post {
                        frameHeight = if (r % 180 == 0) h else w
                        // Show what really arrives, so HD/SD can be checked
                        if (rtc != null) onStatus?.invoke("Bild: ${if (r % 180 == 0) "$w×$h" else "$h×$w"} über WebRTC")
                    }
                }
            })
            renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            renderer.setEnableHardwareScaler(true)
            rendererReady = true
        }
        gotFrame = false
        message.text = "Verbinde mit der Kamera …"
        message.visibility = View.VISIBLE
        renderer.visibility = View.VISIBLE
        val session = WebRtcLive(context, deviceId, uid, renderer, object : WebRtcLive.Listener {
            override fun onFailed(reason: String) = rtcFailed(reason)
            override fun onLost(reason: String) {
                // Connection dropped after it ran: try again, a few times
                if (gotFrame && ++rtcTries <= 3) {
                    releaseRtc()
                    startRtc(uid)
                    message.text = "Verbindung wird erneuert …"
                } else rtcFailed(reason)
            }
        })
        session.muted = muted
        rtc = session
        session.start(hd, nudge = rtcRefusals > 0)
        cancel(rtcWatchdog)
        later(25_000, rtcWatchdog)
    }

    private fun rtcPicture() {
        if (rtc == null) return
        cancel(rtcWatchdog)
        gotFrame = true
        rtcTries = 0
        message.visibility = View.GONE
        sound.visibility = View.VISIBLE
        onQuality?.invoke(!hdUnsupported)
        if (hd && !hdUnsupported) {
            cancel(qualityCheck)
            later(8000, qualityCheck)
        }
        onStatus?.invoke(if (frameHeight > 0) "Bild: $frameHeight Zeilen über WebRTC" else "")
        cancel(snapshots)
        later(2000, snapshots)
    }

    /** WebRTC didn't work: note why and fall back to HLS. */
    private fun rtcFailed(reason: String) {
        if (rtc == null) return
        val info = rtc?.info ?: ""
        if (!gotFrame && (reason.startsWith("Kamera hat beendet") || reason.startsWith("Kamera lehnt ab")) && ++rtcRefusals <= 6) {
            releaseRtc()
            val uid = Repo.find(deviceId)?.uid ?: ""
            message.text = "Kamera wird geweckt, neuer Versuch ($rtcRefusals/6) …"
            message.visibility = View.VISIBLE
            onStatus?.invoke("WebRTC: $reason")
            // After a pause the doorbell refuses the first requests (Smart Life too). Asking the cloud for a
            // stream address makes it start its video service; the next WebRTC attempt also sends a wake-up call.
            if (rtcRefusals == 1 || rtcRefusals == 3) {
                val api = Store(context).api()
                async({ api.streamUrl(deviceId, "hls") }) { }
            }
            later(if (rtcRefusals == 1) 2000L else 3000L) {
                if (rtc == null && player == null && attempt == 0 && uid.isNotEmpty()) startRtc(uid)
            }
            return
        }
        notes += "WebRTC: $reason"
        // Show why, so a fallback to HLS isn't a mystery
        onStatus?.invoke("WebRTC: $reason" + (info.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""))
        releaseRtc()
        renderer.visibility = View.GONE
        onQuality?.invoke(false)
        connect()
    }

    private fun releaseRtc() {
        cancel(rtcWatchdog)
        cancel(qualityCheck)
        rtc?.stop()
        rtc = null
    }

    /** Switches HD/SD: directly if the camera supports it, otherwise by reconnecting. */
    fun setQuality(wantHd: Boolean) {
        if (wantHd == hd) return
        hd = wantHd
        val session = rtc ?: return
        // Ask the running connection to switch; if the picture stays SD, HD is not offered over WebRTC
        session.setHd(wantHd)
        cancel(qualityCheck)
        later(6000, qualityCheck)
    }

    /** Set once the camera ignored the HD request: then the HD/SD choice is hidden for good. */
    private var hdUnsupported: Boolean
        get() = prefs.getBoolean("hd_unsupported_$deviceId", false)
        set(v) = prefs.edit().putBoolean("hd_unsupported_$deviceId", v).apply()

    private fun checkQuality() {
        if (rtc == null || !gotFrame || frameHeight <= 0) return
        if (hd && frameHeight < 720) {
            // Reconnecting with the main stream gets refused, so don't: stay on SD and say why
            hdUnsupported = true
            hd = false
            onQuality?.invoke(false)
            onStatus?.invoke("Die Klingel liefert per WebRTC nur SD ($frameHeight Zeilen). HD gibt es nur in Smart Life.")
        }
    }

    private fun connect() {
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
                    newStreams = 0
                    cancel(snapshots)
                    later(2000, snapshots)
                }
                gotFrame = true
                cancel(watchdog)
                message.visibility = View.GONE
                sound.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!gotFrame && state == Player.STATE_BUFFERING) message.text = "Lade Bild (${type.uppercase()}) …"
                if (!gotFrame && state == Player.STATE_READY) message.text = "Warte auf das erste Bild (${type.uppercase()}) …"
                // The cloud ends a stream after a while: fetch a new one the same way
                if (gotFrame && state == Player.STATE_ENDED) newStream()
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
                    newStream()
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
    }

    /** The picture ran and then the stream ended: get a fresh one, unless that keeps failing. */
    private fun newStream() {
        release()
        if (++newStreams > 5) {
            failed("Stream endet immer wieder")
            return
        }
        connect()
    }

    /** Copies the current picture for the camera widget (works on the video surface since Android 7). */
    private fun snapshot() {
        rtc?.let { session ->
            session.snapshot { bmp -> Widgets.saveSnapshot(context, deviceId, bmp) }
            return
        }
        val surface = video.videoSurfaceView as? android.view.SurfaceView ?: return
        if (!gotFrame || surface.width <= 0 || surface.height <= 0) return
        val bmp = android.graphics.Bitmap.createBitmap(640, 640 * surface.height / surface.width, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(surface, bmp, { result ->
                if (result == android.view.PixelCopy.SUCCESS) Widgets.saveSnapshot(context, deviceId, bmp)
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    private fun release() {
        releaseRtc()
        cancel(watchdog)
        cancel(snapshots)
        proxy?.close()
        proxy = null
        player?.release()
        player = null
        video.player = null
        sound.visibility = View.GONE
    }

    private fun toggleSound() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        rtc?.muted = muted
        sound.text = if (muted) "🔇" else "🔊"
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
        if (rendererReady) {
            renderer.release()
            rendererReady = false
        }
    }

    fun stop() {
        release()
        renderer.visibility = View.GONE
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
