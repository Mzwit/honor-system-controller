package com.honor.systemcontroller.core

import android.content.Context

/** Small SharedPreferences wrapper for user settings and automation rules. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("hsc_settings", Context.MODE_PRIVATE)

    var mode: String
        get() = p.getString("mode", "STANDARD") ?: "STANDARD"
        set(v) = p.edit().putString("mode", v).apply()

    /** "system", "light" or "dark" */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit().putString("theme", v).apply()

    var advancedOverride: Boolean
        get() = p.getBoolean("advanced_override", false)
        set(v) = p.edit().putBoolean("advanced_override", v).apply()

    // ---- Automation rules ----
    var ruleVerifyBlocklist: Boolean
        get() = p.getBoolean("rule_verify", true)
        set(v) = p.edit().putBoolean("rule_verify", v).apply()

    var ruleReapplyOnViolation: Boolean
        get() = p.getBoolean("rule_reapply", false)
        set(v) = p.edit().putBoolean("rule_reapply", v).apply()

    var ruleReapplyAfterBoot: Boolean
        get() = p.getBoolean("rule_boot", true)
        set(v) = p.edit().putBoolean("rule_boot", v).apply()

    var ruleNotifyActivity: Boolean
        get() = p.getBoolean("rule_notify_active", true)
        set(v) = p.edit().putBoolean("rule_notify_active", v).apply()

    var ruleHighUsageReport: Boolean
        get() = p.getBoolean("rule_high_usage", false)
        set(v) = p.edit().putBoolean("rule_high_usage", v).apply()

    /** Minutes between verification runs (WorkManager minimum is 15). */
    var verifyIntervalMin: Int
        get() = p.getInt("verify_interval", 60)
        set(v) = p.edit().putInt("verify_interval", v.coerceAtLeast(15)).apply()

    /** Foreground minutes in 24 h above which an app is flagged as high usage. */
    var highUsageMinutes: Int
        get() = p.getInt("high_usage_min", 120)
        set(v) = p.edit().putInt("high_usage_min", v).apply()

    var lastActivityCheck: Long
        get() = p.getLong("last_activity_check", 0)
        set(v) = p.edit().putLong("last_activity_check", v).apply()
}
