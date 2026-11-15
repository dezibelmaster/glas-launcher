package de.glas.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Puts the TV to sleep after a while. An ordinary app can't switch the screen off, so this
 * goes through the TV's own debugging access, like switching the Google TV home screen.
 */
object SleepTimer {
    val choices = listOf(0, 15, 30, 60, 90, 120)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("glas", Context.MODE_PRIVATE)

    /** When the TV goes to sleep (elapsed realtime), or 0 if the timer is off. */
    fun endsAt(ctx: Context): Long = prefs(ctx).getLong("sleepAt", 0L).takeIf { it > SystemClock.elapsedRealtime() } ?: 0L

    fun minutesLeft(ctx: Context): Int {
        val end = endsAt(ctx)
        return if (end == 0L) 0 else ((end - SystemClock.elapsedRealtime() + 59_999) / 60_000).toInt()
    }

    fun set(ctx: Context, minutes: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx))
        if (minutes <= 0) {
            prefs(ctx).edit().remove("sleepAt").apply()
            return
        }
        val at = SystemClock.elapsedRealtime() + minutes * 60_000L
        prefs(ctx).edit().putLong("sleepAt", at).apply()
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Screen off now, as with the remote's power key. Blocking. */
    fun sleepNow(ctx: Context): GoogleTv.Result = TvShell.run(ctx, listOf("input keyevent 223"), waitForApproval = 30_000)
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        app.getSharedPreferences("glas", Context.MODE_PRIVATE).edit().remove("sleepAt").apply()
        val done = goAsync()
        Thread {
            runCatching { SleepTimer.sleepNow(app) }
            done.finish()
        }.start()
    }
}
