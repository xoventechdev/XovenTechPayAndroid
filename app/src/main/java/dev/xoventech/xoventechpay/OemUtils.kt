package dev.xoventech.xoventechpay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * OEM-specific utility functions for detecting and handling custom Android
 * skins that have aggressive battery management.
 *
 * Currently supports:
 *   - Xiaomi MIUI / HyperOS
 *   - Infinix / Tecno / itel XOS (HiOS)
 *   - Samsung One UI
 *
 * All of these OEM skins have an ADDITIONAL battery management layer on top of
 * AOSP's stock battery optimization. Even with battery optimization disabled
 * via standard Android settings, these skins can still:
 *
 *   - Kill the app process during Doze
 *   - Freeze the app's background execution
 *   - Block BOOT_COMPLETED broadcasts
 *   - Throttle WorkManager/JobScheduler
 *
 * The ONLY reliable fix is to manually configure the OEM-specific settings
 * on each device. This class helps detect the OEM and guides the user.
 */
object OemUtils {

    // ─── OEM Detection ───────────────────────────────────────────────

    enum class OemType {
        XIAOMI,       // MIUI / HyperOS
        INFINIX_XOS,  // Infinix XOS / Tecno HiOS / itel
        SAMSUNG,      // One UI
        STOCK         // Pure AOSP (Pixel, Nokia, etc.)
    }

    /** Detect the current OEM skin. */
    fun detectOem(): OemType {
        if (isXiaomi()) return OemType.XIAOMI
        if (isInfinixOrTecno()) return OemType.INFINIX_XOS
        if (isSamsung()) return OemType.SAMSUNG
        return OemType.STOCK
    }

    /** Detect if the device is running MIUI/HyperOS. */
    private fun isXiaomi(): Boolean {
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
               Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
               Build.MANUFACTURER.equals("POCO", ignoreCase = true) ||
               try {
                   Build::class.java.getMethod("getMiuiVersionName").invoke(null) != null
               } catch (e: Exception) {
                   false
               }
    }

    /** Detect if device is Infinix, Tecno, or itel (all use XOS/HiOS). */
    private fun isInfinixOrTecno(): Boolean {
        return Build.MANUFACTURER.equals("Infinix", ignoreCase = true) ||
               Build.MANUFACTURER.equals("Tecno", ignoreCase = true) ||
               Build.MANUFACTURER.equals("Itel", ignoreCase = true) ||
               Build.BRAND.equals("Infinix", ignoreCase = true) ||
               Build.BRAND.equals("Tecno", ignoreCase = true) ||
               Build.BRAND.equals("Itel", ignoreCase = true)
    }

    /** Detect if device is Samsung. */
    private fun isSamsung(): Boolean {
        return Build.MANUFACTURER.equals("Samsung", ignoreCase = true)
    }

    /** Get human-readable OEM name. */
    fun getOemDisplayName(): String {
        return when (detectOem()) {
            OemType.XIAOMI -> "Xiaomi / MIUI / HyperOS"
            OemType.INFINIX_XOS -> "Infinix / XOS"
            OemType.SAMSUNG -> "Samsung / One UI"
            OemType.STOCK -> "Stock Android"
        }
    }

    /** Check if this device has OEM-specific battery restrictions. */
    fun hasOemBatteryRestrictions(): Boolean {
        return detectOem() != OemType.STOCK
    }

    // ─── Setup Steps ──────────────────────────────────────────────────

    /**
     * Get the required setup steps for the detected OEM.
     * Each step is a (title, description, isClickable) triple.
     */
    fun getRequiredSetupSteps(context: Context): List<OemSetupStep> {
        return when (detectOem()) {
            OemType.XIAOMI -> getXiaomiSteps(context)
            OemType.INFINIX_XOS -> getInfinixSteps(context)
            OemType.SAMSUNG -> getSamsungSteps(context)
            OemType.STOCK -> emptyList()
        }
    }

    data class OemSetupStep(
        val step: String,
        val title: String,
        val description: String,
        val isClickable: Boolean = false,
        val onClick: (() -> Unit)? = null
    )

    // ─── Xiaomi Steps ────────────────────────────────────────────────

    private fun getXiaomiSteps(context: Context): List<OemSetupStep> {
        return listOf(
            OemSetupStep(
                step = "1",
                title = "Enable Autostart",
                description = "Settings > Apps > Autostart > Enable your app",
                isClickable = true,
                onClick = { openXiaomiAutostart(context) }
            ),
            OemSetupStep(
                step = "2",
                title = "MIUI Battery: No Restrictions",
                description = "Settings > Apps > Your App > Battery Saver > No restrictions",
                isClickable = true,
                onClick = { openXiaomiBatterySettings(context) }
            ),
            OemSetupStep(
                step = "3",
                title = "Lock App in Recents",
                description = "Open the app > go to recents > long-press the card > " +
                    "tap the lock icon"
            ),
            OemSetupStep(
                step = "4",
                title = "Disable MIUI Optimization",
                description = "Settings > Developer Options > MIUI Optimization > OFF. " +
                    "Most effective option but affects all apps.",
                isClickable = true,
                onClick = { openDeveloperOptions(context) }
            )
        )
    }

    // ─── Infinix XOS Steps ───────────────────────────────────────────

    private fun getInfinixSteps(context: Context): List<OemSetupStep> {
        return listOf(
            OemSetupStep(
                step = "1",
                title = "Disable Battery Optimization",
                description = "Settings > Apps > XovenTech Pay > Battery > " +
                    "Select 'No restrictions' (NOT 'Smart' or 'Restricted')",
                isClickable = true,
                onClick = { openAppSettings(context) }
            ),
            OemSetupStep(
                step = "2",
                title = "Enable Autostart",
                description = "Settings > Apps > XovenTech Pay > 'Start in background' or " +
                    "'Auto-start' > Enable it. Some XOS versions: " +
                    "Phone Master > App Management > Autostart.",
                isClickable = true,
                onClick = { openInfinixPhoneMaster(context) }
            ),
            OemSetupStep(
                step = "3",
                title = "Lock App in Recents",
                description = "Open the app > go to recents > long-press the " +
                    "app card > tap the lock icon (or 'protect' icon)"
            ),
            OemSetupStep(
                step = "4",
                title = "Whitelist in Phone Master / XOS Manager",
                description = "Open 'Phone Master' app > App Management > " +
                    "Find XovenTech Pay > Enable 'No restriction'. " +
                    "Alternatively: Settings > XOS AI > Smart Power > " +
                    "Exclude XovenTech Pay from smart power management.",
                isClickable = true,
                onClick = { openInfinixPhoneMaster(context) }
            ),
            OemSetupStep(
                step = "5",
                title = "Disable Smart Power Saving",
                description = "Settings > Battery > Smart Power > OFF. " +
                    "Or: Settings > Battery > Power Saving Mode > OFF. " +
                    "This prevents XOS from killing apps in the background."
            )
        )
    }

    // ─── Samsung Steps ────────────────────────────────────────────────

    private fun getSamsungSteps(context: Context): List<OemSetupStep> {
        return listOf(
            OemSetupStep(
                step = "1",
                title = "Disable Sleeping Apps",
                description = "Settings > Device Care > Battery > Background " +
                    "usage limits > Sleeping apps > Remove XovenTech Pay",
                isClickable = true,
                onClick = { openAppSettings(context) }
            ),
            OemSetupStep(
                step = "2",
                title = "Disable Deep Sleeping",
                description = "Settings > Device Care > Battery > Background " +
                    "usage limits > Deep sleeping apps > Remove XovenTech Pay",
                isClickable = true,
                onClick = { openAppSettings(context) }
            ),
            OemSetupStep(
                step = "3",
                title = "Disable Battery Optimization",
                description = "Settings > Apps > XovenTech Pay > Battery > 'Unrestricted'"
            )
        )
    }

    // ─── Intent Launchers ─────────────────────────────────────────────

    /** Open the standard Android app settings page. */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open app settings: ${e.message}")
        }
    }

    /** Open Xiaomi Autostart settings. */
    private fun openXiaomiAutostart(context: Context) {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "MIUI autostart intent failed: ${e.message}")
        }
        openAppSettings(context)
    }

    /** Open Xiaomi Battery Saver settings. */
    private fun openXiaomiBatterySettings(context: Context) {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
                addCategory("android.intent.category.DEFAULT")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "MIUI battery settings intent failed: ${e.message}")
        }
        openAppSettings(context)
    }

    /** Open Infinix Phone Master app. */
    private fun openInfinixPhoneMaster(context: Context) {
        // Try multiple known package names for Phone Master / HiOS
        val packages = listOf(
            "com.transsion.phonemaster",
            "com.clean.master",
            "com.iqoo.secure"
        )
        for (pkg in packages) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                continue
            }
        }
        // Fallback to app settings
        openAppSettings(context)
    }

    /** Open Developer Options. */
    fun openDeveloperOptions(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open developer options: ${e.message}")
        }
    }

    private const val TAG = "OemUtils"
}
