package de.glas.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
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

    companion object {
        val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            // Fire TV
            "com.amazon.tv.launcher",
        )
    }
}
