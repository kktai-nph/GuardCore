package com.parentalcontrol.core.localization

import android.content.Context
import android.content.SharedPreferences

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val shortLabel: String,
    val flag: String
) {
    EN("en", "English", "EN", "🇬🇧"),
    KM("km", "ភាសាខ្មែរ", "KH", "🇰🇭");

    companion object {
        fun fromCode(code: String): AppLanguage {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: EN
        }
    }
}

object LanguageManager {
    private const val PREFS_NAME = "crt_lang_prefs"
    private const val KEY_SELECTED_LANG = "selected_lang"

    fun getSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_SELECTED_LANG, "km") ?: "km" // Default to Khmer or English
        return AppLanguage.fromCode(code)
    }

    fun setSavedLanguage(context: Context, lang: AppLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_LANG, lang.code).apply()
    }
}

data class AppStrings(
    // Common / General
    val appTitleParent: String,
    val appSubtitleParent: String,
    val appTitleChild: String,
    val appSubtitleChild: String,
    val cancel: String,
    val close: String,
    val saveAndClose: String,
    val enable: String,
    val delete: String,
    val version: String,
    val build: String,

    // Parent Dashboard
    val managedDevices: String,
    val pairNewChild: String,
    val noChildPaired: String,
    val deviceUnpaired: String,
    val instantControls: String,
    val lockDevice: String,
    val unlockDevice: String,
    val bonusTimeTitle: String,
    val bedtimeSchedulesTitle: String,
    val bedtimeSchedulesDesc: String,
    val appLimitsTitle: String,
    val appLimitsDesc: String,
    val autoUpdateTitle: String,
    val checkUpdate: String,
    val updateChild: String,
    val previewUpdatePopup: String,
    val checkingUpdateMsg: String,
    val appUpToDateMsg: String,
    val newUpdateFoundMsg: String,
    val downloadInstallNow: String,
    val remindLater: String,
    val whatsNew: String,

    // Parent Dialogs
    val chooseLockDuration: String,
    val lock15m: String,
    val lock30m: String,
    val lock1h: String,
    val lock2h: String,
    val lock4h: String,
    val lock8h: String,
    val telegramConfigTitle: String,
    val botTokenLabel: String,
    val chatIdLabel: String,
    val addScheduleTitle: String,
    val scheduleNameLabel: String,
    val startTimeLabel: String,
    val endTimeLabel: String,
    val sendPolicy: String,
    val addAppLimitTitle: String,
    val packageNameLabel: String,
    val appNameLabel: String,
    val dailyLimitLabel: String,
    val blockCompletely: String,
    val sendLimit: String,
    val pushChildUpdateTitle: String,
    val pushChildUpdateDesc: String,
    val childApkUrlLabel: String,
    val targetVersionCodeLabel: String,
    val pushCommand: String,

    // Child Dashboard
    val protectionActive: String,
    val devicePairing: String,
    val scanQrDesc: String,
    val showPairingQr: String,
    val scanPairingQrTitle: String,
    val protectionPermissions: String,
    val deviceAdmin: String,
    val deviceAdminDesc: String,
    val accessibility: String,
    val accessibilityDesc: String,
    val overlay: String,
    val overlayDesc: String,
    val batteryOptimization: String,
    val batteryOptimizationDesc: String,
    val relayConfig: String,
    val downloadingProgress: String,
    val updateFailed: String,
    val pairedSuccess: String
)

object Localization {
    val EN = AppStrings(
        appTitleParent = "SafeShield Parent",
        appSubtitleParent = "Guard Core Sovereign Safety",
        appTitleChild = "SafeShield Child",
        appSubtitleChild = "Guard Core • Sovereign Protection",
        cancel = "Cancel",
        close = "Close",
        saveAndClose = "Save & Close",
        enable = "Enable",
        delete = "Delete",
        version = "Version",
        build = "Build",

        managedDevices = "Managed Devices",
        pairNewChild = "Pair New Child",
        noChildPaired = "No child device paired yet. Tap 'Pair New Child' to scan child QR.",
        deviceUnpaired = "Device un-paired",
        instantControls = "Instant Device Controls",
        lockDevice = "Lock Device",
        unlockDevice = "Unlock",
        bonusTimeTitle = "Quick Bonus Screen Time",
        bedtimeSchedulesTitle = "Bedtime & Study Schedules",
        bedtimeSchedulesDesc = "Enforced offline 100% on child device even if internet is disconnected.",
        appLimitsTitle = "App Daily Limits & Blocking",
        appLimitsDesc = "Set daily quotas for TikTok, YouTube, Games or block them completely.",
        autoUpdateTitle = "Guard Core • Auto-Update & OTA",
        checkUpdate = "Check Update",
        updateChild = "Update Child",
        previewUpdatePopup = "Preview Update Popup (Test)",
        checkingUpdateMsg = "Checking GitHub Releases & Telegram for updates...",
        appUpToDateMsg = "App is up to date!",
        newUpdateFoundMsg = "New update available: v%s",
        downloadInstallNow = "Download & Install Now",
        remindLater = "Remind Me Later",
        whatsNew = "What's New in this Version:",

        chooseLockDuration = "Choose Lock Duration",
        lock15m = "15 Minutes",
        lock30m = "30 Minutes",
        lock1h = "1 Hour",
        lock2h = "2 Hours",
        lock4h = "4 Hours",
        lock8h = "Until Morning (8 Hours)",
        telegramConfigTitle = "Telegram Transport Config",
        botTokenLabel = "Telegram Bot Token",
        chatIdLabel = "Telegram Chat ID / Group ID",
        addScheduleTitle = "Add Bedtime Schedule",
        scheduleNameLabel = "Schedule Name",
        startTimeLabel = "Start Time (24h e.g. 21:30)",
        endTimeLabel = "End Time (24h e.g. 06:00)",
        sendPolicy = "Send Policy",
        addAppLimitTitle = "Add App Limit / Block",
        packageNameLabel = "Package Name",
        appNameLabel = "App Name",
        dailyLimitLabel = "Daily Limit (Minutes)",
        blockCompletely = "Block Completely",
        sendLimit = "Send Limit",
        pushChildUpdateTitle = "Push Remote Update to Child",
        pushChildUpdateDesc = "The child phone will download and install the new APK (silent install if Device Owner is active).",
        childApkUrlLabel = "Child APK Download Direct URL",
        targetVersionCodeLabel = "Target Version Code",
        pushCommand = "Push Direct Command",

        protectionActive = "Protection Service Active",
        devicePairing = "Device Pairing",
        scanQrDesc = "Scan this QR code from Parent App to establish trusted cryptographic keys.",
        showPairingQr = "Show Pairing QR Code",
        scanPairingQrTitle = "Scan Pairing QR",
        protectionPermissions = "Protection Permissions",
        deviceAdmin = "Device Administrator",
        deviceAdminDesc = "Anti-uninstall & instant lock",
        accessibility = "Accessibility Monitoring",
        accessibilityDesc = "Enforce daily app quotas",
        overlay = "Display Over Other Apps",
        overlayDesc = "Fullscreen lock screen overlay",
        batteryOptimization = "Ignore Battery Optimizations",
        batteryOptimizationDesc = "Keep background listener alive",
        relayConfig = "Transport Relay Configuration",
        downloadingProgress = "Downloading: %d%%",
        updateFailed = "Download failed: %s",
        pairedSuccess = "✓ Paired with %s!"
    )

    val KM = AppStrings(
        appTitleParent = "SafeShield អាណាព្យាបាល",
        appSubtitleParent = "ប្រព័ន្ធការពារសុវត្ថិភាព Guard Core",
        appTitleChild = "SafeShield កូន",
        appSubtitleChild = "Guard Core • ប្រព័ន្ធការពារសុវត្ថិភាព",
        cancel = "បោះបង់",
        close = "បិទ",
        saveAndClose = "រក្សាទុក & បិទ",
        enable = "បើកសិទ្ធិ",
        delete = "លុប",
        version = "ជំនាន់",
        build = "Build",

        managedDevices = "ឧបករណ៍កូនគ្រប់គ្រង",
        pairNewChild = "ភ្ជាប់ទូរសព្ទ័កូនថ្មី",
        noChildPaired = "មិនទាន់បានភ្ជាប់ទូរសព្ទ័កូននៅឡើយទេ។ ចុច 'ភ្ជាប់ទូរសព្ទ័កូនថ្មី' ដើម្បីស្កេន QR កូដ។",
        deviceUnpaired = "បានផ្តាច់ការភ្ជាប់ឧបករណ៍",
        instantControls = "បញ្ជាឧបករណ៍ភ្លាមៗ",
        lockDevice = "ចាក់សោទូរសព្ទ័",
        unlockDevice = "ដោះសោ",
        bonusTimeTitle = "បន្ថែមម៉ោងប្រើប្រាស់រហ័ស",
        bedtimeSchedulesTitle = "កាលវិភាគគេង & រៀនសូត្រ",
        bedtimeSchedulesDesc = "អនុវត្តដោយស្វ័យប្រវត្តិ ១០០% នៅលើទូរសព្ទ័កូន ទោះបីដាច់អ៊ីនធឺណិតក៏ដោយ។",
        appLimitsTitle = "កំណត់ម៉ោង និងបិទ App ប្រចាំថ្ងៃ",
        appLimitsDesc = "កំណត់ម៉ោងសម្រាប់ TikTok, YouTube, ហ្គេម ឬបិទការប្រើប្រាស់ទាំងស្រុង។",
        autoUpdateTitle = "Guard Core • ប្រព័ន្ធ Update ស្វ័យប្រវត្តិ",
        checkUpdate = "ពិនិត្យ Update",
        updateChild = "Update កូន",
        previewUpdatePopup = "មើលទម្រង់ Update Pop-up (តេស្ត)",
        checkingUpdateMsg = "កំពុងស្វែងរក Update លើ GitHub & Telegram...",
        appUpToDateMsg = "កម្មវិធីស្ថិតនៅជំនាន់ចុងក្រោយបង្អស់!",
        newUpdateFoundMsg = "មានជំនាន់ថ្មី៖ v%s",
        downloadInstallNow = "ទាញយក និងដំឡើងភ្លាមៗ",
        remindLater = "រំលឹកពេលក្រោយ",
        whatsNew = "អ្វីដែលថ្មីក្នុងជំនាន់នេះ៖",

        chooseLockDuration = "ជ្រើសរើសរយៈពេលចាក់សោ",
        lock15m = "១៥ នាទី",
        lock30m = "៣០ នាទី",
        lock1h = "១ ម៉ោង",
        lock2h = "២ ម៉ោង",
        lock4h = "៤ ម៉ោង",
        lock8h = "ដល់ព្រឹកស្អែក (៨ ម៉ោង)",
        telegramConfigTitle = "ការកំណត់ Telegram Relay",
        botTokenLabel = "Telegram Bot Token",
        chatIdLabel = "Telegram Chat ID / Group ID",
        addScheduleTitle = "បង្កើតកាលវិភាគម៉ោងគេង",
        scheduleNameLabel = "ឈ្មោះកាលវិភាគ",
        startTimeLabel = "ម៉ោងចាប់ផ្តើម (ឧ. 21:30)",
        endTimeLabel = "ម៉ោងបញ្ចប់ (ឧ. 06:00)",
        sendPolicy = "បញ្ជូនទៅទូរសព្ទ័កូន",
        addAppLimitTitle = "កំណត់ម៉ោង App / បិទកម្មវិធី",
        packageNameLabel = "ឈ្មោះ Package កម្មវិធី",
        appNameLabel = "ឈ្មោះ App",
        dailyLimitLabel = "កំណត់ម៉ោងប្រើប្រចាំថ្ងៃ (នាទី)",
        blockCompletely = "បិទការប្រើប្រាស់ទាំងស្រុង",
        sendLimit = "បញ្ជូនការកំណត់",
        pushChildUpdateTitle = "បញ្ជូន Update ពីចម្ងាយទៅកូន",
        pushChildUpdateDesc = "ទូរសព្ទ័កូននឹងទាញយក និងដំឡើង APK ថ្មីដោយស្វ័យប្រវត្តិ (Silent Install ប្រសិនបើបើក Device Owner)។",
        childApkUrlLabel = "Link ទាញយក Child APK ផ្ទាល់",
        targetVersionCodeLabel = "លេខ Version កូដគោលដៅ",
        pushCommand = "បញ្ជូន Command ភ្លាមៗ",

        protectionActive = "សេវាការពារកំពុងដំណើរការ",
        devicePairing = "ការភ្ជាប់ឧបករណ៍",
        scanQrDesc = "ស្កេន QR កូដនេះពី Parent App ដើម្បីបង្កើតសោរសុវត្ថិភាពសម្ងាត់។",
        showPairingQr = "បង្ហាញ QR កូដសម្រាប់ភ្ជាប់",
        scanPairingQrTitle = "ស្កេន QR ភ្ជាប់ឧបករណ៍",
        protectionPermissions = "សិទ្ធិអនុញ្ញាតសម្រាប់ការពារ",
        deviceAdmin = "អ្នកគ្រប់គ្រងឧបករណ៍ (Device Admin)",
        deviceAdminDesc = "ការពារការលុប App & ចាក់សោរហ័ស",
        accessibility = "ការតាមដាន App (Accessibility)",
        accessibilityDesc = "អនុវត្តការកំណត់ម៉ោងប្រើប្រាស់ App",
        overlay = "បង្ហាញពីលើ App ផ្សេងទៀត (Overlay)",
        overlayDesc = "ផ្ទាំងចាក់សោពេញអេក្រង់",
        batteryOptimization = "មិនបិទ App សន្សំថ្ម (Battery)",
        batteryOptimizationDesc = "ដំណើរការសេវាការពារជាប់ជាប្រចាំ",
        relayConfig = "ការកំណត់ Telegram Relay",
        downloadingProgress = "កំពុងទាញយក៖ %d%%",
        updateFailed = "ទាញយកមិនបានសម្រេច៖ %s",
        pairedSuccess = "✓ បានភ្ជាប់ជាមួយ %s!"
    )

    fun getStrings(lang: AppLanguage): AppStrings {
        return when (lang) {
            AppLanguage.EN -> EN
            AppLanguage.KM -> KM
        }
    }
}
