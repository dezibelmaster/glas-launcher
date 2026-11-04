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
        val result = TvShell.run(ctx, packages.filter { installed(ctx, it) }.map { pkg ->
            if (on) "pm enable $pkg" else "pm disable-user --user 0 $pkg"
        })
        if (result != Result.Done) return result
        return if (isOn(ctx) == on) Result.Done else Result.Failed(tr("Der Fernseher hat die Änderung nicht übernommen"))
    }
}

/**
 * Commands on the TV's own debugging port (127.0.0.1:5555, like Bugjaeger), for things an ordinary
 * app may not do. The first time, the TV asks whether to allow debugging from the Glas Launcher.
 */
object TvShell {
    /** Runs the commands one after the other and stops at the first that fails. Blocking. */
    fun run(ctx: Context, commands: List<String>, waitForApproval: Int = 60_000): GoogleTv.Result {
        if (Build.VERSION.SDK_INT < 26) return GoogleTv.Result.NoDebugging
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        return try {
            if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
            val keys = AdbKeyPair.read(private, public)
            Dadb.create("127.0.0.1", 5555, keys, 5_000, waitForApproval).use { adb ->
                for (command in commands) {
                    val r = adb.shell(command)
                    if (r.exitCode != 0) return GoogleTv.Result.Failed(r.allOutput.trim().ifBlank { "Fehler ${r.exitCode}" })
                }
            }
            GoogleTv.Result.Done
        } catch (e: ConnectException) {
            GoogleTv.Result.NoDebugging
        } catch (e: SocketTimeoutException) {
            GoogleTv.Result.NotAllowed
        } catch (e: Exception) {
            GoogleTv.Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** A path in single quotes for the shell. */
    fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"
}
