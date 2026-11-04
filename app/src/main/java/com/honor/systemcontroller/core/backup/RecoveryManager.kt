package com.honor.systemcontroller.core.backup

import android.content.Context
import android.net.Uri
import android.content.pm.PackageManager
import com.honor.systemcontroller.core.packages.Commands
import com.honor.systemcontroller.data.AppDatabase
import com.honor.systemcontroller.data.PackageSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds exportable recovery material: JSON backup + ADB restore scripts for PC. */
class RecoveryManager(private val context: Context, private val db: AppDatabase) {

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun restoreLines(s: PackageSnapshot): List<String> {
        val lines = mutableListOf<String>()
        s.rootRemovedDir?.let { d ->
            val m = if (d.startsWith("/system/")) "/data/adb/modules/hsc_debloat$d" else "/data/adb/modules/hsc_debloat/system$d"
            lines += "su -c \"rm -rf '$m'\"   # root removal – REBOOT after this script, then run it again"
        }
        // Harmless if the package is already installed; brings back system apps removed with pm uninstall --user 0.
        if (s.isSystem) lines += Commands.reinstall(s.packageName)
        val wasDisabled = s.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            s.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (!wasDisabled) lines += Commands.enable(s.packageName)
        if (!s.wasSuspended) lines += Commands.unsuspend(s.packageName)
        lines += Commands.allowBg(s.packageName, s.runAnyInBackgroundMode, s.runInBackgroundMode, s.standbyBucket)
        s.disabledComponents.split('|').filter { it.isNotBlank() }.forEach { lines += Commands.enableComponent(s.packageName, it) }
        return lines
    }

    suspend fun shellScript(): String {
        val snaps = db.snapshots().all()
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# HONOR System Controller – ADB restore script")
            appendLine("# Generated ${stamp()} for ${snaps.size} modified package(s).")
            appendLine("# Usage from a computer:  adb push restore.sh /data/local/tmp/ && adb shell sh /data/local/tmp/restore.sh")
            appendLine("# or run each line prefixed with 'adb shell'.")
            snaps.forEach { s ->
                appendLine()
                appendLine("# ${s.label} (${s.packageName})")
                restoreLines(s).forEach { appendLine(it) }
            }
            appendLine()
            appendLine("echo 'Restore finished'")
        }
    }

    suspend fun windowsBatch(): String {
        val snaps = db.snapshots().all()
        return buildString {
            appendLine("@echo off")
            appendLine("REM HONOR System Controller - ADB restore (Windows). Generated ${stamp()}")
            appendLine("REM Requires platform-tools (adb.exe) in PATH and USB debugging authorised.")
            appendLine("adb wait-for-device")
            snaps.forEach { s ->
                appendLine("REM ${s.label} (${s.packageName})")
                restoreLines(s).forEach { appendLine("adb shell $it") }
            }
            appendLine("echo Restore finished")
            appendLine("pause")
        }
    }

    /** Emergency script that re-enables EVERYTHING this app could have touched, ignoring snapshots. */
    suspend fun emergencyEnableAll(): String {
        val pkgs = (db.snapshots().all().map { it.packageName } + db.blocklist().all().map { it.packageName } +
            db.logs().all().map { it.packageName }).filter { it.contains('.') }.distinct()
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# EMERGENCY: re-enable every package HONOR System Controller ever touched (${stamp()})")
            pkgs.forEach { p ->
                appendLine(Commands.reinstall(p)); appendLine(Commands.enable(p)); appendLine(Commands.unsuspend(p))
                Commands.allowBg(p).forEach { appendLine(it) }
            }
        }
    }

    suspend fun json(): String {
        val root = JSONObject()
        root.put("app", "HONOR System Controller")
        root.put("generated", stamp())
        root.put("snapshots", JSONArray().apply {
            db.snapshots().all().forEach { s ->
                put(JSONObject().apply {
                    put("package", s.packageName); put("label", s.label); put("system", s.isSystem)
                    put("enabledSetting", s.enabledSetting); put("suspended", s.wasSuspended); put("stopped", s.wasStopped)
                    put("runAnyInBackground", s.runAnyInBackgroundMode ?: JSONObject.NULL)
                    put("runInBackground", s.runInBackgroundMode ?: JSONObject.NULL)
                    put("standbyBucket", s.standbyBucket ?: JSONObject.NULL)
                    put("disabledComponents", s.disabledComponents); put("capturedAt", s.capturedAt)
                })
            }
        })
        root.put("blocklist", JSONArray().apply {
            db.blocklist().all().forEach { b ->
                put(JSONObject().apply {
                    put("package", b.packageName); put("label", b.label); put("disable", b.disablePackage)
                    put("suspend", b.suspendLaunch); put("restrictBackground", b.restrictBackground); put("forceStop", b.forceStop)
                })
            }
        })
        root.put("history", JSONArray().apply {
            db.logs().all().forEach { l ->
                put(JSONObject().apply {
                    put("time", l.timestamp); put("package", l.packageName); put("action", l.action); put("mode", l.mode)
                    put("command", l.command); put("success", l.success); put("verified", l.verified); put("message", l.message)
                    put("before", l.beforeState); put("after", l.afterState)
                })
            }
        })
        return root.toString(2)
    }

    fun writeTo(uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
    }.getOrDefault(false)
}
