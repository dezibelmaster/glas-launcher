package de.glas.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Process

/** Memory of the TV: how much is free, how much the launcher takes, and freeing apps resting in the background. */
object Memory {
    class Info(val total: Long, val available: Long, val launcher: Long)

    fun info(ctx: Context): Info {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val own = runCatching { am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss * 1024L }.getOrDefault(0L)
        return Info(mi.totalMem, mi.availMem, own)
    }

    /**
     * Ends apps that rest in the background so their memory is free again; Android starts
     * them anew when they are next opened. Returns the bytes gained. Blocking.
     */
    fun free(ctx: Context): Long {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val before = info(ctx).available
        for (app in Apps.installed(ctx)) runCatching { am.killBackgroundProcesses(app.pkg) }
        System.gc()
        // Give the system a moment to hand the memory back
        Thread.sleep(1500)
        return (info(ctx).available - before).coerceAtLeast(0)
    }

    fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0).replace('.', ',')
    fun mb(bytes: Long) = "${bytes / 1_048_576} MB"
}
