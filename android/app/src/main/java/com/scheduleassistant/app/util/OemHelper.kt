package com.scheduleassistant.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * OEM Brand classification for handling aggressive background task killers
 * (ColorOS / Realme UI / MIUI / HyperOS / FuntouchOS / One UI).
 */
enum class OemBrand(val displayName: String, val oemSkin: String) {
    OPPO("OPPO", "ColorOS"),
    REALME("Realme", "Realme UI"),
    XIAOMI("Xiaomi", "MIUI / HyperOS"),
    VIVO("Vivo", "FuntouchOS / OriginOS"),
    ONEPLUS("OnePlus", "OxygenOS"),
    SAMSUNG("Samsung", "One UI"),
    OTHER("Android", "AOSP / Stock")
}

/**
 * Helper to navigate OEM-specific background restriction settings,
 * Autostart managers, and Android 13+ Restricted Settings bypass.
 */
object OemHelper {

    private const val TAG = "OemHelper"

    val currentBrand: OemBrand by lazy {
        resolveBrand(Build.MANUFACTURER, Build.BRAND)
    }

    fun resolveBrand(manufacturer: String?, brand: String?): OemBrand {
        val m = manufacturer?.lowercase() ?: ""
        val b = brand?.lowercase() ?: ""
        return when {
            m.contains("oppo") || b.contains("oppo") -> OemBrand.OPPO
            m.contains("realme") || b.contains("realme") -> OemBrand.REALME
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> OemBrand.XIAOMI
            m.contains("vivo") || m.contains("iqoo") -> OemBrand.VIVO
            m.contains("oneplus") -> OemBrand.ONEPLUS
            m.contains("samsung") -> OemBrand.SAMSUNG
            else -> OemBrand.OTHER
        }
    }

    val isAggressiveOem: Boolean
        get() = currentBrand != OemBrand.OTHER

    /**
     * OEMs that maintain proprietary Autostart / Startup managers
     * outside standard Android battery optimization (OPPO, Realme, Xiaomi, Vivo, OnePlus).
     * Samsung uses standard Android battery optimization (Unrestricted).
     */
    val hasDedicatedAutostart: Boolean
        get() = currentBrand == OemBrand.OPPO ||
                currentBrand == OemBrand.REALME ||
                currentBrand == OemBrand.XIAOMI ||
                currentBrand == OemBrand.VIVO ||
                currentBrand == OemBrand.ONEPLUS

    /**
     * Attempts to open the OEM's Autostart / Startup Manager.
     * Returns true if a specific OEM intent was launched, false if it fell back to App Details.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val intents = getAutostartIntents(context)
        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch intent: ${intent.component?.className}", e)
            }
        }
        // Fallback: Open standard App Details
        openAppDetailsSettings(context)
        return false
    }

    /**
     * Opens system App Info page (Settings -> Apps -> Schedule Assistant).
     * Essential for unblocking "Restricted Settings" on Android 13+ sideloaded APKs.
     */
    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details", e)
        }
    }

    /**
     * Returns step-by-step instructions for the user's specific phone model
     * to prevent background killing and enable notification access.
     */
    fun getOemGuidance(isRestrictedSettingsNeeded: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU): List<String> {
        val steps = mutableListOf<String>()

        if (isRestrictedSettingsNeeded) {
            steps.add("Android 13+ Security: If Notification Access is greyed out with 'Restricted setting', open App Info -> tap 3 dots (⋮) top right -> 'Allow restricted settings' -> verify fingerprint/PIN.")
        }

        when (currentBrand) {
            OemBrand.OPPO, OemBrand.REALME -> {
                steps.add("Auto-Launch: Settings -> Apps -> App management -> Schedule Assistant -> Battery usage -> Enable 'Allow background activity' and 'Allow auto-launch'.")
                steps.add("Deep Sleep: Settings -> Battery -> More battery settings -> App battery management -> Schedule Assistant -> Set to 'Don't optimize'.")
            }
            OemBrand.XIAOMI -> {
                steps.add("Autostart: Settings -> Apps -> Manage apps -> Schedule Assistant -> Enable 'Autostart'.")
                steps.add("Battery Saver: Set Battery saver to 'No restrictions'.")
            }
            OemBrand.VIVO -> {
                steps.add("Autostart: iManager -> App manager -> Autostart -> Enable Schedule Assistant.")
                steps.add("High Power: Settings -> Battery -> High background power consumption -> Allow Schedule Assistant.")
            }
            OemBrand.SAMSUNG -> {
                steps.add("Never Sleep: Settings -> Battery -> Background usage limits -> 'Never auto sleeping apps' -> Add Schedule Assistant.")
                steps.add("Battery Unrestricted: Settings -> Apps -> Schedule Assistant -> Battery -> Set to 'Unrestricted'.")
            }
            OemBrand.ONEPLUS -> {
                steps.add("Auto-Launch: Settings -> Apps -> Auto-launch -> Allow Schedule Assistant.")
                steps.add("Battery: Settings -> Battery -> Battery usage -> Schedule Assistant -> Enable 'Allow background activity'.")
            }
            OemBrand.OTHER -> {
                steps.add("Battery: Settings -> Apps -> Schedule Assistant -> Battery -> Set to 'Unrestricted'.")
            }
        }
        return steps
    }

    private fun getAutostartIntents(context: Context): List<Intent> {
        val list = mutableListOf<Intent>()

        when (currentBrand) {
            OemBrand.OPPO, OemBrand.REALME -> {
                list.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                list.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                list.add(Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))
                list.add(Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")))
                list.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")))
            }
            OemBrand.XIAOMI -> {
                list.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                list.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")))
            }
            OemBrand.VIVO -> {
                list.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
                list.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
                list.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity")))
            }
            OemBrand.ONEPLUS -> {
                list.add(Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")))
            }
            OemBrand.SAMSUNG -> {
                list.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")))
                list.add(Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")))
            }
            else -> {}
        }

        // Generic fallback to standard battery optimization settings
        list.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return list
    }
}
