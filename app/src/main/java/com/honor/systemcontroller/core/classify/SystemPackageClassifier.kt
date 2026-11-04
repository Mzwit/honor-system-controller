package com.honor.systemcontroller.core.classify

enum class PackageCategory(val title: String) {
    ESSENTIAL_ANDROID("Essential Android components"),
    ESSENTIAL_HONOR("Essential HONOR / MagicOS components"),
    OPTIONAL_HONOR("Optional HONOR applications"),
    GOOGLE("Google applications and services"),
    CARRIER("Carrier applications"),
    OPTIONAL_SERVICE("Optional background services"),
    OTHER_SYSTEM("Other system applications"),
    USER("User-installed applications")
}

enum class Risk(val title: String) {
    /** Never disabled. No override exists. */
    CRITICAL("Critical – blocked"),
    /** Disabling may break features; requires typed confirmation. */
    CAUTION("Caution"),
    /** Positively identified as non-essential. */
    SAFE("Non-essential"),
    /** Unknown system package – treated as caution; override not available. */
    UNKNOWN("Unknown purpose")
}

/** Which sensitive subsystem a package touches, from known lists and name analysis. */
enum class Subsystem { TELEPHONY, NETWORKING, SECURITY, BIOMETRICS, BOOT, DISPLAY, STORAGE, UPDATES, INPUT, NONE }

data class Classification(
    val category: PackageCategory,
    val risk: Risk,
    val purpose: String,
    val subsystems: Set<Subsystem>,
    val consequences: String,
    /** True only when the package is positively classified non-essential (override allowed). */
    val knownNonEssential: Boolean
) {
    val canDisable get() = risk != Risk.CRITICAL
}

/**
 * Rule-based classifier. Known-package tables take priority; name analysis is used to detect
 * sensitive subsystems so that anything that *looks* like telephony, networking, security,
 * biometrics, boot, display, storage or update code is protected even if it is not in a table.
 *
 * HONOR package names were compiled from public MagicOS debloat lists; they are not official.
 * Always confirm on the device: the app shows the real package name and its installed path.
 */
object SystemPackageClassifier {

    private data class Known(val category: PackageCategory, val risk: Risk, val purpose: String)

    private val known: Map<String, Known> = buildMap {
        fun crit(cat: PackageCategory, purpose: String, vararg pk: String) = pk.forEach { put(it, Known(cat, Risk.CRITICAL, purpose)) }
        fun caution(cat: PackageCategory, purpose: String, vararg pk: String) = pk.forEach { put(it, Known(cat, Risk.CAUTION, purpose)) }
        fun safe(cat: PackageCategory, purpose: String, vararg pk: String) = pk.forEach { put(it, Known(cat, Risk.SAFE, purpose)) }
        val A = PackageCategory.ESSENTIAL_ANDROID
        crit(A, "Android framework (system_server resources)", "android")
        crit(A, "System UI – status bar, navigation, lock screen", "com.android.systemui")
        crit(A, "Settings app", "com.android.settings")
        crit(A, "Phone / telephony services", "com.android.phone", "com.android.server.telecom", "com.android.providers.telephony", "com.android.ims", "com.android.ims.rcsservice", "com.android.mms.service", "com.android.stk", "com.android.carrierconfig", "com.android.cellbroadcastreceiver", "com.android.cellbroadcastservice")
        crit(A, "Package installation & permissions", "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller", "com.google.android.permissioncontroller")
        crit(A, "Core content providers", "com.android.providers.settings", "com.android.providers.media", "com.android.providers.media.module", "com.google.android.providers.media.module", "com.android.providers.contacts", "com.android.providers.downloads", "com.android.providers.calendar", "com.android.externalstorage", "com.android.documentsui", "com.google.android.documentsui", "com.android.mtp")
        crit(A, "Networking stack", "com.android.networkstack", "com.google.android.networkstack", "com.android.networkstack.tethering", "com.google.android.networkstack.tethering", "com.android.wifi.resources", "com.android.server.wifi", "com.android.bluetooth", "com.android.nfc", "com.android.vpndialogs", "com.android.captiveportallogin", "com.google.android.captiveportallogin")
        crit(A, "Security & keys", "com.android.keychain", "com.android.se", "com.android.shell", "com.android.location.fused", "com.android.inputdevices", "com.android.certinstaller")
        crit(A, "Default launcher / setup", "com.android.provision", "com.android.launcher3")
        caution(A, "Android WebView (many apps render web content with it)", "com.google.android.webview", "com.android.webview")
        caution(A, "Default keyboard", "com.android.inputmethod.latin", "com.google.android.inputmethod.latin")
        safe(A, "Print spooler – only needed for printing", "com.android.printspooler", "com.android.bips")
        safe(A, "Live wallpaper picker", "com.android.wallpaper.livepicker")
        safe(A, "Basic Daydreams screensaver", "com.android.dreams.basic")
        safe(A, "Emergency info demo / BasicDreams", "com.android.dreams.phototable")

        val H = PackageCategory.ESSENTIAL_HONOR
        crit(H, "HONOR launcher (home screen)", "com.hihonor.android.launcher")
        crit(H, "Phone Manager / system manager (battery, startup management, permissions)", "com.hihonor.systemmanager")
        crit(H, "System update (OTA)", "com.hihonor.ouc")
        crit(H, "HONOR system services", "com.hihonor.systemserver", "com.hihonor.securityserver", "com.hihonor.android.hwouc")
        crit(H, "HONOR biometrics", "com.hihonor.facerecognition", "com.hihonor.biometric")
        caution(H, "HONOR ID / account (Cloud, Find Device and theme purchases depend on it)", "com.hihonor.id")
        caution(H, "HONOR Find My Device", "com.hihonor.findmydevice")
        caution(H, "HONOR Themes (current theme may reset)", "com.hihonor.android.thememanager")
        caution(H, "HONOR Camera", "com.hihonor.camera")
        caution(H, "HONOR Gallery", "com.hihonor.photos")

        val O = PackageCategory.OPTIONAL_HONOR
        safe(O, "HONOR App Market", "com.hihonor.appmarket")
        safe(O, "HONOR Game Center", "com.hihonor.gamecenter")
        safe(O, "HONOR Health", "com.hihonor.health")
        safe(O, "HONOR Browser", "com.hihonor.browser")
        safe(O, "HONOR Music", "com.hihonor.cloudmusic")
        safe(O, "HONOR Video", "com.hihonor.hnvideo", "com.hihonor.himovie")
        safe(O, "My HONOR / member services", "com.hihonor.phoneservice")
        safe(O, "HONOR Wallet", "com.hihonor.wallet")
        safe(O, "HONOR Notes", "com.hihonor.notepad")
        safe(O, "HONOR Tips", "com.hihonor.tips")
        safe(O, "YOYO assistant", "com.hihonor.yoyo")
        safe(O, "HONOR Search / suggestions", "com.hihonor.search")
        safe(O, "HONOR Compass", "com.hihonor.compass")
        safe(O, "HONOR Mirror", "com.hihonor.mirror")
        safe(O, "HONOR Email", "com.hihonor.email")

        val G = PackageCategory.GOOGLE
        crit(G, "Google Services Framework", "com.google.android.gsf")
        caution(G, "Google Play services (notifications, location and sign-in for most apps)", "com.google.android.gms")
        caution(G, "Google Play Store (updates)", "com.android.vending")
        caution(G, "Google Messages (may be your SMS app)", "com.google.android.apps.messaging")
        caution(G, "Google Phone / Dialer", "com.google.android.dialer")
        caution(G, "Google Contacts", "com.google.android.contacts")
        safe(G, "YouTube", "com.google.android.youtube")
        safe(G, "YouTube Music", "com.google.android.apps.youtube.music")
        safe(G, "Gmail", "com.google.android.gm")
        safe(G, "Google Maps", "com.google.android.apps.maps")
        safe(G, "Google Photos", "com.google.android.apps.photos")
        safe(G, "Google Drive", "com.google.android.apps.docs")
        safe(G, "Google Meet", "com.google.android.apps.tachyon")
        safe(G, "Google TV / Play Movies", "com.google.android.videos")
        safe(G, "Google app / Discover", "com.google.android.googlequicksearchbox")
        safe(G, "Google Assistant shell", "com.google.android.apps.googleassistant")
        safe(G, "Google Calendar", "com.google.android.calendar")
        safe(G, "Google Files", "com.google.android.apps.nbu.files")
        safe(G, "Google Wellbeing", "com.google.android.apps.wellbeing")
        safe(G, "Google Feedback", "com.google.android.feedback")
        safe(G, "Android Auto", "com.google.android.projection.gearhead")
        safe(G, "Google Partner Setup", "com.google.android.partnersetup")

        val S = PackageCategory.OPTIONAL_SERVICE
        safe(S, "Facebook preinstall services", "com.facebook.services", "com.facebook.system", "com.facebook.appmanager")
        safe(S, "Netflix partner activation", "com.netflix.partner.activation")
        safe(S, "Booking.com preinstall", "com.booking")
        safe(S, "Qualcomm logging / diagnostics", "com.qualcomm.qti.qms.service.telemetry")
    }

    private val sensitivePatterns: List<Pair<Regex, Subsystem>> = listOf(
        Regex("telephony|telecom|\\.phone\\b|\\.ims|radio|\\.mms|\\.sms|\\.stk|simtoolkit|carrierconfig|cellbroadcast|qti\\.(dpm|ims|telephony|uim)|uimremote|\\.ril") to Subsystem.TELEPHONY,
        Regex("wifi|networkstack|tethering|connectivity|\\.dns|\\.vpn|bluetooth|\\.nfc|captiveportal|location") to Subsystem.NETWORKING,
        Regex("security|keychain|keystore|keyguard|\\.se\\b|trust|permission|deviceadmin|antitheft|frp") to Subsystem.SECURITY,
        Regex("biometric|fingerprint|face(recog|unlock)|\\.face\\b") to Subsystem.BIOMETRICS,
        Regex("launcher|setupwizard|provision|bootanim|systemserver|\\.android$|^android$|framework") to Subsystem.BOOT,
        Regex("systemui|display|\\.gpu|graphics|surfaceflinger|wallpaperbackup") to Subsystem.DISPLAY,
        Regex("storage|\\.media\\b|providers\\.media|documentsui|\\.mtp|backup|\\.providers\\.") to Subsystem.STORAGE,
        Regex("\\.ouc|update|fota|\\.ota") to Subsystem.UPDATES,
        Regex("inputmethod|keyboard|\\.ime\\b") to Subsystem.INPUT
    )

    private val CRITICAL_SUBSYSTEMS = setOf(Subsystem.TELEPHONY, Subsystem.BOOT, Subsystem.DISPLAY, Subsystem.BIOMETRICS)

    private val carrierPattern = Regex("carrier|operator|vodacom|\\bmtn\\b|\\.mtn|orange|vodafone|airtel|tmobile|verizon|att\\.|sprint|claro|telkom|eswatini")

    fun detectSubsystems(pkg: String): Set<Subsystem> {
        val lower = pkg.lowercase()
        return sensitivePatterns.filter { it.first.containsMatchIn(lower) }.map { it.second }.toSet()
    }

    fun classify(pkg: String, isSystem: Boolean): Classification {
        val subs = detectSubsystems(pkg)
        val k = known[pkg]
        if (k != null) {
            return Classification(k.category, k.risk, k.purpose, subs, consequencesFor(k.risk, subs, k.purpose), k.risk == Risk.SAFE)
        }
        if (!isSystem) {
            return Classification(PackageCategory.USER, Risk.SAFE, "User-installed application", subs,
                "Disabling hides the app and stops it from running. Its data is kept; enable it again to restore.", true)
        }
        val category = when {
            pkg.startsWith("com.android.") || pkg.startsWith("android.") -> PackageCategory.ESSENTIAL_ANDROID
            pkg.startsWith("com.hihonor.") || pkg.startsWith("com.honor.") || pkg.startsWith("com.hihonorcloud.") -> PackageCategory.OTHER_SYSTEM
            pkg.startsWith("com.google.") -> PackageCategory.GOOGLE
            carrierPattern.containsMatchIn(pkg.lowercase()) -> PackageCategory.CARRIER
            pkg.startsWith("com.qualcomm.") || pkg.startsWith("com.qti.") || pkg.startsWith("vendor.") -> PackageCategory.ESSENTIAL_ANDROID
            else -> PackageCategory.OTHER_SYSTEM
        }
        val risk = when {
            subs.any { it in CRITICAL_SUBSYSTEMS } -> Risk.CRITICAL
            category == PackageCategory.ESSENTIAL_ANDROID -> Risk.CRITICAL
            subs.isNotEmpty() -> Risk.CAUTION
            else -> Risk.UNKNOWN
        }
        val purpose = when (category) {
            PackageCategory.CARRIER -> "Probably a carrier/operator application"
            PackageCategory.GOOGLE -> "Google component (purpose not in local database)"
            else -> "Purpose not in local database"
        }
        return Classification(category, risk, purpose, subs, consequencesFor(risk, subs, purpose), false)
    }

    private fun consequencesFor(risk: Risk, subs: Set<Subsystem>, purpose: String): String {
        val parts = mutableListOf<String>()
        if (Subsystem.TELEPHONY in subs) parts += "calls, SMS or mobile data may stop working"
        if (Subsystem.NETWORKING in subs) parts += "Wi-Fi, Bluetooth, hotspot, VPN or location may break"
        if (Subsystem.SECURITY in subs) parts += "lock screen, credentials or permission prompts may fail"
        if (Subsystem.BIOMETRICS in subs) parts += "fingerprint / face unlock may stop working"
        if (Subsystem.BOOT in subs) parts += "the phone may fail to finish booting (boot loop)"
        if (Subsystem.DISPLAY in subs) parts += "the screen UI may crash or go black"
        if (Subsystem.STORAGE in subs) parts += "file access, downloads or media scanning may fail"
        if (Subsystem.UPDATES in subs) parts += "you may stop receiving security updates"
        if (Subsystem.INPUT in subs) parts += "you may lose your keyboard – keep another one installed first"
        return when (risk) {
            Risk.CRITICAL -> "Protected: " + (if (parts.isEmpty()) "this is a core system component." else parts.joinToString("; ") + ".") + " This app will not disable it."
            Risk.CAUTION -> "Possible effects: " + (if (parts.isEmpty()) "features that depend on \"$purpose\" may stop working" else parts.joinToString("; ")) + ". Fully reversible with Enable/Restore."
            Risk.UNKNOWN -> "Unknown system package. Effects cannot be predicted. Search the package name before disabling. Reversible with Enable/Restore."
            Risk.SAFE -> "Considered non-essential. The app is hidden and cannot run; data is kept and it can be re-enabled any time."
        }
    }
}
