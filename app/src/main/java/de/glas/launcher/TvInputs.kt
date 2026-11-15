package de.glas.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** The TV's inputs (HDMI, antenna or cable, …) as the system lists them, and switching to one. */
object TvInputs {
    class Input(val id: String, val label: String, val tuner: Boolean)

    fun list(ctx: Context): List<Input> = runCatching {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        tim.tvInputList
            // Devices behind an HDMI port (via CEC) show up as their own entries: the port is enough
            .filter { it.parentId == null && !it.isHidden(ctx) }
            .map { info ->
                val label = (info.loadCustomLabel(ctx) ?: info.loadLabel(ctx))?.toString()?.trim().orEmpty()
                Input(info.id, label.ifEmpty { info.id.substringAfterLast('/') }, !info.isPassthroughInput)
            }
            // Ports first (HDMI 1, HDMI 2 …), then aerial and other sources
            .sortedWith(compareBy<Input> { it.tuner }.thenBy { it.label })
    }.getOrDefault(emptyList())

    fun kind(input: Input) = if (input.tuner) Glyph.Kind.ANTENNA else Glyph.Kind.HDMI

    /** Switches the TV to [input]. Returns false if no app on the TV takes the request. */
    fun open(ctx: Context, input: Input): Boolean {
        val intents = if (!input.tuner) {
            listOf(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)))
        } else {
            listOf(
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(input.id)),
                Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI),
                serviceApp(ctx, input),
            ).filterNotNull()
        }
        for (intent in intents) {
            val ok = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** The app that provides a tuner input, as a last resort for watching TV. */
    private fun serviceApp(ctx: Context, input: Input): Intent? = runCatching {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        val info: TvInputInfo = tim.getTvInputInfo(input.id) ?: return null
        val pkg = info.serviceInfo.packageName
        ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: ctx.packageManager.getLaunchIntentForPackage(pkg)
    }.getOrNull()
}
