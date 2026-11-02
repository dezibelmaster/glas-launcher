package de.glas.kids

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * The guard of kids mode. Switched on once under Accessibility, it sees every app that comes to the front.
 * In kids mode anything other than YouTube Kids or YouTube (and the apps the parents allowed) sends the TV back to
 * Glas Kids, which then returns to it. It also counts the viewing time and ends it when it is used up.
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var front: String? = null
    private var lastCount = 0L
    private var warned = false
    private var lastOpen = 0L
    private var overlay: View? = null

    private val tick = object : Runnable {
        override fun run() {
            count()
            check()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
        lastCount = SystemClock.elapsedRealtime()
        handler.post(tick)
        // After a restart the service connects only once the TV's home screen is up; go to kids mode from there
        if (prefs.enabled && SystemClock.elapsedRealtime() < 5 * 60_000L) openHome(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Only whole screens count; the volume bar, keyboards and pop-ups of the app in front are no reason to step in
        if (!isActivity(pkg, cls)) return
        count()
        front = pkg
        if (!prefs.enabled || SystemClock.elapsedRealtime() < pausedUntil) return
        when {
            pkg == packageName -> {}
            pkg in Apps.SYSTEM_ALLOWED -> {}
            countsAsWatching(pkg) -> if (ScreenTime.state(prefs) != ScreenTime.State.OK) openHome(null)
            pkg in Apps.HOME_SCREENS -> openHome(null)
            else -> openHome(HomeActivity.REASON_BLOCKED)
        }
    }

    private fun isActivity(pkg: String, cls: String) =
        runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess

    /** The app of kids mode, looked up again now and then in case YouTube Kids gets installed or removed. */
    private var target: String? = null
    private var targetCheckedAt = 0L

    private fun target(): String? {
        val now = SystemClock.elapsedRealtime()
        if (targetCheckedAt == 0L || now - targetCheckedAt > 60_000) {
            target = Apps.target(this)
            targetCheckedAt = now
        }
        return target
    }

    private fun countsAsWatching(pkg: String?) = pkg != null && (pkg == target() || pkg in prefs.extraApps)

    /** Adds the time since the last call to today's viewing time, if the kids mode app was in front and the screen on. */
    private fun count() {
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastCount
        lastCount = now
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (prefs.enabled && screenOn && countsAsWatching(front)) ScreenTime.addUsage(prefs, delta.coerceAtMost(2 * TICK_MS))
    }

    private fun check() {
        if (!prefs.enabled || !countsAsWatching(front) || SystemClock.elapsedRealtime() < pausedUntil) {
            warned = false
            return
        }
        if (ScreenTime.state(prefs) != ScreenTime.State.OK) {
            openHome(null)
            return
        }
        val left = ScreenTime.remainingMs(prefs)
        val warnMs = prefs.warnMinutes * 60_000L
        if (left == null || warnMs == 0L || left > warnMs) {
            warned = false
        } else if (!warned) {
            warned = true
            showRemaining(left)
        }
    }

    private fun openHome(reason: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpen < 800) return
        lastOpen = now
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(HomeActivity.EXTRA_REASON, reason),
        )
    }

    /** "5 Min. left" in the bottom right corner for ten seconds, over YouTube. */
    private fun showRemaining(ms: Long) {
        hideRemaining()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = Ui.text(this, 22f, Ui.medium).apply {
            text = tr("Noch {0} Min.", ScreenTime.minutesLeft(ms))
            background = Ui.glass(this@GuardService, 18f).apply { setColor(0xCC141A28.toInt()) }
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(48)
            y = dp(40)
        }
        if (runCatching { wm.addView(view, lp) }.isSuccess) {
            overlay = view
            handler.postDelayed({ hideRemaining() }, 10_000)
        }
    }

    private fun hideRemaining() {
        overlay?.let { v -> runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } }
        overlay = null
    }

    /** Sends the TV to sleep. Works from Android 9; returns false before. */
    fun sleep(): Boolean = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideRemaining()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 15_000L

        @Volatile var instance: GuardService? = null
            private set

        /** While a parent is in the TV settings (e.g. to switch the guard on), nothing is blocked. */
        @Volatile var pausedUntil = 0L

        fun pauseForParent(minutes: Int = 5) {
            pausedUntil = SystemClock.elapsedRealtime() + minutes * 60_000L
        }

        fun resume() {
            pausedUntil = 0L
        }

        /** Whether the guard is switched on under Accessibility. */
        fun isOn(ctx: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
