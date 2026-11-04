package com.honor.systemcontroller.core.process

import android.app.ActivityManager
import android.content.Context
import com.honor.systemcontroller.core.privilege.PrivilegeManager

data class ProcInfo(val pid: Int, val user: String, val rssKb: Long, val cpu: Double, val name: String) {
    /** Process names look like "com.foo" or "com.foo:remote"; the part before ':' is the package. */
    val packageName get() = name.substringBefore(':')
}

data class MemorySummary(val totalMb: Long, val availMb: Long, val lowMemory: Boolean, val thresholdMb: Long) {
    val usedFraction get() = if (totalMb == 0L) 0f else (totalMb - availMb).toFloat() / totalMb
}

/**
 * Android 7+ hides other apps' processes from normal apps, so a full process list requires
 * a shell identity (Shizuku/ADB uid 2000 or root). Without it we report memory only.
 */
class ProcessInspector(private val context: Context, private val privilege: PrivilegeManager) {

    fun memory(): MemorySummary {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return MemorySummary(mi.totalMem / 1_048_576, mi.availMem / 1_048_576, mi.lowMemory, mi.threshold / 1_048_576)
    }

    /** Returns null when no shell is available (Standard mode). */
    suspend fun processes(): List<ProcInfo>? {
        val shell = privilege.executor() ?: return null
        val r = shell.run("ps -A -o USER,PID,RSS,%CPU,NAME")
        if (r.exitCode != 0) return null
        return r.stdout.lineSequence().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) return@mapNotNull null
            ProcInfo(
                pid = parts[1].toIntOrNull() ?: return@mapNotNull null,
                user = parts[0],
                rssKb = parts[2].toLongOrNull() ?: 0,
                cpu = parts[3].toDoubleOrNull() ?: 0.0,
                name = parts.subList(4, parts.size).joinToString(" ")
            )
        }.filter { it.name.contains('.') && !it.name.startsWith("[") }.toList()
    }

    /** package -> total RSS kB across that package's processes. */
    suspend fun runningPackages(): Map<String, Long>? =
        processes()?.groupBy { it.packageName }?.mapValues { e -> e.value.sumOf { it.rssKb } }
}
