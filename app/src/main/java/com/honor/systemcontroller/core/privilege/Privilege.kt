package com.honor.systemcontroller.core.privilege

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/** The four operating modes from the specification. */
enum class PrivilegeMode(val title: String, val description: String) {
    STANDARD(
        "Standard",
        "No elevated access. Uses public Android APIs and opens system settings screens for actions Android reserves for the system."
    ),
    ADB(
        "ADB (from a computer)",
        "Generates exact, validated adb commands and restore scripts to run from a PC with USB/wireless debugging. Executes on-device through Shizuku when it is running."
    ),
    SHIZUKU(
        "Shizuku",
        "Runs package-manager commands with the ADB shell identity (uid 2000) through Shizuku. This is NOT root: protected system packages may still refuse changes."
    ),
    ROOT(
        "Root (optional)",
        "Uses an existing su binary (Magisk/KernelSU) after your explicit approval. This app never roots the device."
    )
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0 && !stdout.contains("Exception") && !stderr.contains("Exception")
    val combined get() = (stdout + "\n" + stderr).trim()
}

interface ShellExecutor {
    val mode: PrivilegeMode
    suspend fun run(command: String, timeoutSec: Long = 20): ShellResult
}

private fun Process.collect(timeoutSec: Long): ShellResult {
    var out = ""
    var err = ""
    val tOut = Thread { out = runCatching { inputStream.bufferedReader().readText() }.getOrDefault("") }
    val tErr = Thread { err = runCatching { errorStream.bufferedReader().readText() }.getOrDefault("") }
    tOut.start(); tErr.start()
    val finished = runCatching { waitFor(timeoutSec, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!finished) {
        runCatching { destroy() }
        return ShellResult(-1, out, "Timed out after ${timeoutSec}s")
    }
    tOut.join(2000); tErr.join(2000)
    return ShellResult(runCatching { exitValue() }.getOrDefault(-1), out.trim(), err.trim())
}

/**
 * Shizuku executor. Shizuku 13 made newProcess() non-public; it still exists and is the
 * mechanism used by most package managers built on Shizuku, so we call it reflectively and
 * report a clear error if a future Shizuku removes it.
 */
class ShizukuExecutor : ShellExecutor {
    override val mode = PrivilegeMode.SHIZUKU

    override suspend fun run(command: String, timeoutSec: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!ShizukuState.isReady()) return@withContext ShellResult(-1, "", "Shizuku is not running or permission is not granted")
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            p.collect(timeoutSec)
        } catch (t: Throwable) {
            Log.e("HSC", "Shizuku exec failed", t)
            ShellResult(-1, "", "Shizuku execution failed: ${t.cause?.message ?: t.message}")
        }
    }
}

class RootExecutor : ShellExecutor {
    override val mode = PrivilegeMode.ROOT
    override suspend fun run(command: String, timeoutSec: Long): ShellResult = withContext(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            p.collect(timeoutSec)
        } catch (t: Throwable) {
            ShellResult(-1, "", "su failed: ${t.message}")
        }
    }

    companion object {
        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
            "/data/adb/magisk/su", "/data/adb/ksu/bin/su", "/su/bin/su"
        )
        /** Passive check only – never triggers a root prompt. */
        fun suBinaryPresent(): Boolean = SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }
}

object ShizukuState {
    const val REQUEST_CODE = 4211
    private val _status = MutableStateFlow("Not detected")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isReady(): Boolean = isRunning() && !Shizuku.isPreV11() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun uidDescription(): String = runCatching {
        when (Shizuku.getUid()) { 0 -> "root (uid 0)"; 2000 -> "adb shell (uid 2000)"; else -> "uid ${Shizuku.getUid()}" }
    }.getOrDefault("unknown")

    fun refresh() {
        _ready.value = isReady()
        _status.value = when {
            !isRunning() -> "Shizuku service not running"
            Shizuku.isPreV11() -> "Shizuku version too old (update Shizuku)"
            !isReady() -> "Running – permission not granted yet"
            else -> "Connected as ${uidDescription()}"
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun install() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
        refresh()
    }

    /** Only ever called from an explicit user tap. */
    fun requestPermission() {
        if (isRunning() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        refresh()
    }
}

/**
 * Holds the user-selected mode and hands out the matching executor.
 * Elevated modes are only active after the user explicitly picks them; nothing is acquired silently.
 */
class PrivilegeManager(private val prefs: com.honor.systemcontroller.core.Settings) {
    private val shizuku = ShizukuExecutor()
    private val root = RootExecutor()
    private val _mode = MutableStateFlow(runCatching { PrivilegeMode.valueOf(prefs.mode) }.getOrDefault(PrivilegeMode.STANDARD))
    val mode: StateFlow<PrivilegeMode> = _mode.asStateFlow()

    private val _rootVerified = MutableStateFlow(false)
    val rootVerified: StateFlow<Boolean> = _rootVerified.asStateFlow()

    fun setMode(m: PrivilegeMode) { _mode.value = m; prefs.mode = m.name }

    /** Executor able to run shell commands right now, or null in Standard mode / when unavailable. */
    fun executor(): ShellExecutor? = when (_mode.value) {
        PrivilegeMode.STANDARD -> null
        PrivilegeMode.ADB, PrivilegeMode.SHIZUKU -> if (ShizukuState.isReady()) shizuku else null
        PrivilegeMode.ROOT -> if (_rootVerified.value) root else null
    }

    /** Explicitly requested by the user; this is what makes Magisk/KernelSU show its prompt. */
    suspend fun verifyRoot(): ShellResult {
        val r = root.run("id", 30)
        _rootVerified.value = r.exitCode == 0 && r.stdout.contains("uid=0")
        return r
    }

    fun capabilityLine(): String = when (_mode.value) {
        PrivilegeMode.STANDARD -> "Standard: read-only inspection + system settings shortcuts"
        PrivilegeMode.ADB, PrivilegeMode.SHIZUKU ->
            if (ShizukuState.isReady()) "Shell access via Shizuku (${ShizukuState.uidDescription()})" else "Shizuku not connected – commands can only be copied/exported"
        PrivilegeMode.ROOT -> if (_rootVerified.value) "Root shell verified" else "Root not verified – tap Verify root"
    }
}
