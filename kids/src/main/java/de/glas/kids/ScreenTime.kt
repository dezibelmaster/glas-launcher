package de.glas.kids

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Daily viewing time and the allowed times of day. */
object ScreenTime {
    enum class State { OK, DAY_USED_UP, OUTSIDE_WINDOW }

    val dailyPresets = listOf(0, 30, 45, 60, 90, 120, 180)
    val windowPresets = listOf(null, "07:00-19:00", "07:00-19:30", "07:00-20:00", "06:00-20:00", "08:00-19:00", "14:00-19:00")
    val warnPresets = listOf(0, 5, 10)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun minutes(t: String) = t.substringBefore(':').toInt() * 60 + t.substringAfter(':').toInt()

    private fun msSinceMidnight(): Long = Calendar.getInstance().let {
        ((it.get(Calendar.HOUR_OF_DAY) * 60L + it.get(Calendar.MINUTE)) * 60 + it.get(Calendar.SECOND)) * 1000
    }

    fun usedMs(p: Prefs) = if (p.usedDay == today()) p.usedMs else 0L

    fun addUsage(p: Prefs, ms: Long) {
        val day = today()
        if (p.usedDay != day) {
            p.usedDay = day
            p.usedMs = 0
        }
        p.usedMs += ms
    }

    fun resetToday(p: Prefs) {
        p.usedDay = today()
        p.usedMs = 0
    }

    fun granted(p: Prefs) = p.grantedUntil > System.currentTimeMillis()

    fun grant(p: Prefs, minutes: Int) {
        p.grantedUntil = System.currentTimeMillis() + minutes * 60_000L
    }

    private fun inWindow(window: String): Boolean {
        val now = msSinceMidnight() / 60_000
        return now >= minutes(window.substringBefore('-')) && now < minutes(window.substringAfter('-'))
    }

    fun state(p: Prefs): State {
        if (granted(p)) return State.OK
        p.window?.let { if (!inWindow(it)) return State.OUTSIDE_WINDOW }
        if (p.dailyMinutes > 0 && usedMs(p) >= p.dailyMinutes * 60_000L) return State.DAY_USED_UP
        return State.OK
    }

    /** Time left until watching stops, or null if there is no limit at all. */
    fun remainingMs(p: Prefs): Long? {
        if (granted(p)) return p.grantedUntil - System.currentTimeMillis()
        val limits = mutableListOf<Long>()
        if (p.dailyMinutes > 0) limits += p.dailyMinutes * 60_000L - usedMs(p)
        p.window?.let { limits += minutes(it.substringAfter('-')) * 60_000L - msSinceMidnight() }
        return limits.minOrNull()?.coerceAtLeast(0)
    }

    /** Whole minutes, rounded up, so "1 Min." is shown until the very end. */
    fun minutesLeft(ms: Long) = ((ms + 59_999) / 60_000).toInt()

    /** When watching is allowed again, for the "time is up" screen. */
    fun nextStart(p: Prefs): String {
        val window = p.window ?: return tr("Morgen geht es weiter.")
        val start = window.substringBefore('-')
        val beforeStart = msSinceMidnight() / 60_000 < minutes(start)
        return if (state(p) == State.OUTSIDE_WINDOW && beforeStart) tr("Ab {0} Uhr geht es weiter.", start)
            else tr("Morgen ab {0} Uhr geht es weiter.", start)
    }

    fun dailyLabel(m: Int) = if (m == 0) tr("unbegrenzt") else durationLabel(m)

    fun durationLabel(m: Int) = when {
        m < 60 -> tr("{0} Min.", m)
        m % 60 == 0 -> tr("{0} Std.", m / 60)
        else -> tr("{0} Std. {1} Min.", m / 60, m % 60)
    }

    fun windowLabel(w: String?) = w?.let { tr("{0} bis {1} Uhr", it.substringBefore('-'), it.substringAfter('-')) } ?: tr("den ganzen Tag")

    fun warnLabel(m: Int) = if (m == 0) tr("nie") else tr("{0} Min. vorher", m)
}
