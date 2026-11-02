package de.glas.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Switches the Google TV home screen off and on, as "pm disable-user" would from a computer.
 * An ordinary app may not do that, so the launcher connects to the TV's own debugging port
 * (the one Bugjaeger uses) on 127.0.0.1:5555. The first time, the TV asks whether to allow
 * debugging from the Glas Launcher.
 */
object GoogleTv {
    private const val LAUNCHER = "com.google.android.apps.tv.launcherx"
    // Google TV's setup helper starts its home screen again if it stays on
    private val packages = listOf(LAUNCHER, "com.google.android.tungsten.setupwraith")

    sealed class Result {
        object Done : Result()
        object NoDebugging : Result()
        object NotAllowed : Result()
        class Failed(val reason: String) : Result()
    }

    /** Only on Google TV, and the library needs Android 8. */
    fun available(ctx: Context) = Build.VERSION.SDK_INT >= 26 && installed(ctx, LAUNCHER)

    fun isOn(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getApplicationInfo(LAUNCHER, PackageManager.MATCH_DISABLED_COMPONENTS).enabled }.getOrDefault(true)

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS); true }.getOrDefault(false)

    /** Blocking; waits up to a minute for the debugging question on the TV to be answered. */
    fun switch(ctx: Context, on: Boolean): Result {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        return try {
            if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
            val keys = AdbKeyPair.read(private, public)
            Dadb.create("127.0.0.1", 5555, keys, 5_000, 60_000).use { adb ->
                for (pkg in packages.filter { installed(ctx, it) }) {
                    val r = adb.shell(if (on) "pm enable $pkg" else "pm disable-user --user 0 $pkg")
                    if (r.exitCode != 0) return Result.Failed(r.allOutput.trim().ifBlank { "Fehler ${r.exitCode}" })
                }
            }
            if (isOn(ctx) == on) Result.Done else Result.Failed(tr("Der Fernseher hat die Änderung nicht übernommen"))
        } catch (e: ConnectException) {
            Result.NoDebugging
        } catch (e: SocketTimeoutException) {
            Result.NotAllowed
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
