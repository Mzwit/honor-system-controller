package com.honor.systemcontroller.core.packages

import com.honor.systemcontroller.core.classify.Risk
import com.honor.systemcontroller.core.privilege.PrivilegeManager
import com.honor.systemcontroller.core.privilege.PrivilegeMode
import com.honor.systemcontroller.data.AppDatabase
import com.honor.systemcontroller.data.OperationLog
import com.honor.systemcontroller.data.PackageSnapshot

/**
 * Root-only removal of system APKs.
 *
 * Android 15 / MagicOS 9 system partitions are read-only EROFS images protected by dm-verity:
 * they cannot be remounted writable even with root, and patching them breaks verified boot.
 * The working method (used by Magisk/KernelSU debloaters) is a systemless module that replaces
 * the APK's directory with an empty one at boot. The real file stays in the image, but
 * PackageManager never sees it again, so for every practical purpose it is deleted.
 *
 * Fully reversible: delete the module folder (or the per-app sub-folder) and reboot.
 */
class RootDebloat(
    private val db: AppDatabase,
    private val privilege: PrivilegeManager,
    private val discovery: AppDiscovery
) {
    companion object {
        const val MODULE = "/data/adb/modules/hsc_debloat"
        const val BACKUP = "/sdcard/HonorSystemController/apk-backup"
        private val ALLOWED_DIR = Regex("^/(system|product|system_ext)/(app|priv-app|preload)/[A-Za-z0-9_.\\-]+$")
        private val MODULE_PROP = """
            id=hsc_debloat
            name=HONOR System Controller - Debloat
            version=1.0
            versionCode=1
            author=HONOR System Controller
            description=Systemless removal of non-essential system apps. Remove this module and reboot to restore them all.
        """.trimIndent()
    }

    data class Result(val ok: Boolean, val message: String)

    private fun moduleDirFor(apkDir: String) =
        if (apkDir.startsWith("/system/")) MODULE + apkDir else "$MODULE/system$apkDir"

    fun precheck(entry: AppEntry): String? = when {
        privilege.mode.value != PrivilegeMode.ROOT || !privilege.rootVerified.value -> "Requires Root mode with root verified."
        !entry.isSystem -> "Only for preinstalled system apps. Use Uninstall for user apps."
        entry.classification.risk != Risk.SAFE -> "Blocked: only packages positively classified as non-essential can be removed from the system partition (${entry.classification.risk.title})."
        else -> null
    }

    /** Finds the original APK directory on the system image (not an /data update). */
    suspend fun systemApkDir(pkg: String): String? {
        val sh = privilege.executor() ?: return null
        val r = sh.run("dumpsys package $pkg | grep -E 'codePath=|resourcePath='")
        val paths = r.stdout.lineSequence().map { it.substringAfter('=').trim() }
            .filter { it.startsWith("/system/") || it.startsWith("/product/") || it.startsWith("/system_ext/") }
            .toList()
        return paths.firstOrNull { ALLOWED_DIR.matches(it) }
    }

    suspend fun remove(entry: AppEntry): Result {
        precheck(entry)?.let { return Result(false, it) }
        val pkg = entry.packageName
        Validators.pkg(pkg)
        val sh = privilege.executor() ?: return Result(false, "Root shell not available")
        val dir = systemApkDir(pkg) ?: return Result(false, "Could not find an APK folder for $pkg in /system, /product or /system_ext app folders – refusing.")
        val mdir = moduleDirFor(dir)
        val script = buildString {
            append("set -e; ")
            append("mkdir -p '$BACKUP/$pkg'; cp -a '$dir/.' '$BACKUP/$pkg/'; ")
            append("mkdir -p '$MODULE'; [ -f '$MODULE/module.prop' ] || printf '%s\\n' ")
            MODULE_PROP.lines().forEach { append("'").append(it).append("' ") }
            append("> '$MODULE/module.prop'; ")
            append("mkdir -p '$mdir'; touch '$mdir/.replace'; ")
            // KernelSU/overlayfs equivalent of .replace
            append("(command -v setfattr >/dev/null && setfattr -n trusted.overlay.opaque -v y '$mdir') || true; ")
            append("echo DONE")
        }
        val r = sh.run(script, 120)
        val verify = sh.run("[ -f '$mdir/.replace' ] && [ -n \"\$(ls -A '$BACKUP/$pkg' 2>/dev/null)\" ] && echo OK")
        val ok = r.stdout.contains("DONE") && verify.stdout.contains("OK")
        if (ok) {
            // Immediate effect for the current boot; the module makes it permanent from the next boot.
            sh.run(Commands.uninstallForUser(pkg))
            val snap = db.snapshots().get(pkg)
            if (snap == null) db.snapshots().upsert(
                PackageSnapshot(pkg, entry.label, true, entry.enabledSetting, entry.suspended, entry.stopped,
                    null, null, null, rootRemovedDir = dir)
            ) else db.snapshots().upsert(snap.copy(rootRemovedDir = dir))
        }
        val msg = if (ok) "Removed from system partition (systemless). APK backed up to $BACKUP/$pkg. Reboot to complete."
        else "FAILED – nothing confirmed. ${r.combined.take(400)}"
        db.logs().insert(OperationLog(packageName = pkg, action = "ROOT_REMOVE_SYSTEM_APK", mode = "ROOT", command = script,
            success = ok, verified = ok, message = msg, beforeState = dir, afterState = if (ok) "hidden by $mdir" else "unchanged"))
        return Result(ok, msg)
    }

    /** Un-hide the APK. Takes effect after reboot; then the package is re-installed for user 0. */
    suspend fun undo(pkg: String): Result {
        Validators.pkg(pkg)
        val sh = privilege.executor() ?: return Result(false, "Needs root. Or delete $MODULE in your root manager and reboot.")
        val snap = db.snapshots().get(pkg)
        if (snap == null || snap.rootRemovedDir == null) return Result(false, "$pkg was not removed with root by this app.")
        val dir: String = snap.rootRemovedDir
        if (!ALLOWED_DIR.matches(dir)) return Result(false, "Stored path invalid: $dir")
        val mdir = moduleDirFor(dir)
        val r = sh.run("rm -rf '$mdir' && echo OK")
        val ok = r.stdout.contains("OK")
        if (ok) db.snapshots().upsert(snap.copy(rootRemovedDir = null))
        val msg = if (ok) "Module entry removed. Reboot, then press Restore/Reinstall to install it for your user again."
        else "Failed: ${r.combined.take(300)}"
        db.logs().insert(OperationLog(packageName = pkg, action = "ROOT_UNDO_REMOVE", mode = "ROOT", command = "rm -rf '$mdir'",
            success = ok, verified = ok, message = msg, beforeState = "hidden", afterState = if (ok) "visible after reboot" else "hidden"))
        return Result(ok, msg)
    }

    suspend fun reboot() { privilege.executor()?.run("svc power reboot || reboot", 10) }
}
