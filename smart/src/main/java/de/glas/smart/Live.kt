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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Live picture of a Tuya camera or video doorbell. The cloud hands out a short-lived HLS address
 * (needs the "IoT Video Live Stream" service in the cloud project), which ExoPlayer plays.
 */
class LiveView(ctx: Context, private val deviceId: String, private val fullscreen: Boolean = false) : FrameLayout(ctx) {
    private var player: ExoPlayer? = null
    private var muted = true
    private val video = PlayerView(ctx).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
    }
    private val message = ctx.text(15f).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        setShadowLayer(ctx.dp(6f), 0f, 0f, Color.BLACK)
    }
    private val sound = overlayButton("🔇") { toggleSound() }

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        setOnClickListener { if (player == null) start() }
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

    fun start() {
        if (player != null) return
        message.text = "Verbinde mit der Kamera …"
        message.visibility = View.VISIBLE
        val api = Store(context).api()
        async({ api.streamUrl(deviceId) }) { r ->
            r.onFailure { message.text = "Kein Live-Bild\n\n${explain(it)}\n\nAntippen zum Wiederholen" }
            r.onSuccess { url -> play(url) }
        }
    }

    private fun explain(e: Throwable) =
        if (e is TuyaException && (e.code == 28841105 || e.code == 1106))
            "Im Tuya-Projekt unter Cloud services den Dienst „IoT Video Live Stream“ hinzufügen (kostenlos)."
        else Tuya.explain(e)

    private fun play(url: String) {
        val p = ExoPlayer.Builder(context).build()
        player = p
        video.player = p
        p.volume = if (muted) 0f else 1f
        p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    message.visibility = View.GONE
                    sound.visibility = View.VISIBLE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                stop()
                message.text = "Das Live-Bild ist abgebrochen.\nAntippen zum Wiederholen"
                message.visibility = View.VISIBLE
            }
        })
        p.playWhenReady = true
        p.prepare()
    }

    private fun toggleSound() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        sound.text = if (muted) "🔇" else "🔊"
    }

    fun stop() {
        player?.release()
        player = null
        video.player = null
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
