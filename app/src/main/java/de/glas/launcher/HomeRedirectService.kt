package de.glas.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV and Fire TV don't let you pick another home app. When this service is switched on
 * under Accessibility, their home screen is replaced by the Glas Launcher as
 * soon as it appears, so the Home button lands here.
 */
class HomeRedirectService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in STOCK_LAUNCHERS) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    /**
     * After a restart this service connects only after the Google home screen is already up,
     * so that first appearance is missed. Open the Glas Launcher once here instead.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        if (SystemClock.elapsedRealtime() < 5 * 60_000L) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onInterrupt() {}

    // ---- New jobs for remote keys (Einstellungen → Fernbedienung) ----

    private val main = Handler(Looper.getMainLooper())
    private var longDone = false
    private var longPending: Runnable? = null
    private var singlePending: Runnable? = null
    private var singleKey = 0
    /** A key sent again on purpose (its normal job) passes until then. */
    private val passUntil = HashMap<Int, Long>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (SystemClock.uptimeMillis() < (passUntil[code] ?: 0L)) return false
        val m = KeyRemap.get(this, code) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longDone = false
                longPending?.let { main.removeCallbacks(it) }
                if (m.long != null) {
                    longPending = Runnable { longDone = true; act(m.long, code) }.also { main.postDelayed(it, LONG_MS) }
                }
            }
            KeyEvent.ACTION_UP -> {
                longPending?.let { main.removeCallbacks(it) }
                longPending = null
                if (longDone) return true
                if (m.double != null) {
                    val pending = singlePending
                    if (pending != null && singleKey == code) {
                        // Second press in time: the double-press job instead of two single ones
                        main.removeCallbacks(pending)
                        singlePending = null
                        act(m.double, code)
                    } else {
                        singleKey = code
                        singlePending = Runnable { singlePending = null; act(m.press, code) }.also { main.postDelayed(it, DOUBLE_MS) }
                    }
                } else {
                    act(m.press, code)
                }
            }
        }
        return true
    }

    /** Runs [action]; without one, the key does its normal job again, sent through the TV's debugging access. */
    private fun act(action: String?, code: Int) {
        if (action == "none") return
        if (action != null) return Actions.run(this, action)
        passUntil[code] = SystemClock.uptimeMillis() + 3_000
        val app = applicationContext
        Thread { TvShell.run(app, listOf("input keyevent $code"), waitForApproval = 15_000) }.start()
    }

    companion object {
        private const val LONG_MS = 600L
        private const val DOUBLE_MS = 350L

        val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            // Fire TV
            "com.amazon.tv.launcher",
        )
    }
}
