package de.glas.launcher

import android.content.Context
import android.content.Intent

/**
 * Things the launcher can do on request: from a remote key that was given a new job, or from
 * the control centre. App launches and the settings go through the home screen, so the PIN
 * and time windows of the parental lock still apply.
 */
object Actions {
    const val EXTRA_LAUNCH = "de.glas.launcher.LAUNCH"
    const val EXTRA_OPEN = "de.glas.launcher.OPEN"

    /** Built-in actions with their names; apps and inputs are added as "app:…" and "input:…". */
    fun builtIn() = listOf(
        "home" to tr("Glas Launcher (Startbildschirm)"),
        "panel" to tr("Kontrollzentrum"),
        "inputs" to tr("Eingänge"),
        "weather" to tr("Wetter"),
        "screensaver" to tr("Bildschirmschoner"),
        "sleep" to tr("Bildschirm aus"),
        "settings" to tr("Einstellungen des Glas Launchers"),
        "none" to tr("Nichts (Taste sperren)"),
    )

    fun label(ctx: Context, action: String?): String = when {
        action.isNullOrEmpty() -> tr("Normal")
        action.startsWith("app:") -> Apps.installed(ctx).firstOrNull { it.pkg == action.removePrefix("app:") }?.label
            ?: action.removePrefix("app:")
        action.startsWith("input:") -> TvInputs.list(ctx).firstOrNull { it.id == action.removePrefix("input:") }?.label
            ?: tr("Eingang")
        else -> builtIn().firstOrNull { it.first == action }?.second ?: action
    }

    fun run(ctx: Context, action: String) {
        val home = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching {
            when {
                action == "home" -> ctx.startActivity(home)
                action == "panel" -> { Snapshot.keepPlaying = ctx is MainActivity; ctx.startActivity(PanelActivity.intent(ctx, PanelActivity.MODE_PANEL)) }
                action == "inputs" -> { Snapshot.keepPlaying = ctx is MainActivity; ctx.startActivity(PanelActivity.intent(ctx, PanelActivity.MODE_INPUTS)) }
                action == "weather" -> ctx.startActivity(home.putExtra(EXTRA_OPEN, "weather"))
                action == "settings" -> ctx.startActivity(home.putExtra(EXTRA_OPEN, "settings"))
                action == "screensaver" -> ctx.startActivity(
                    Intent(ctx, ScreensaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                action == "sleep" -> {
                    val app = ctx.applicationContext
                    Thread { SleepTimer.sleepNow(app) }.start()
                }
                action.startsWith("app:") -> ctx.startActivity(home.putExtra(EXTRA_LAUNCH, action.removePrefix("app:")))
                action.startsWith("input:") -> {
                    val input = TvInputs.list(ctx).firstOrNull { it.id == action.removePrefix("input:") }
                    if (input != null) TvInputs.open(ctx, input)
                }
            }
        }
    }
}
