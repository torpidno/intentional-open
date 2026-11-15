package com.pause.app.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The four setup steps shown during onboarding. */
enum class PermissionType {
    ACCESSIBILITY,
    OVERLAY,
    BATTERY_OPTIMIZATION,
    NOTIFICATIONS,
}

/** A point-in-time snapshot of the permissions Pause needs. */
data class PermissionSnapshot(
    val accessibilityGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val notificationsGranted: Boolean = false,
) {
    val allGranted: Boolean
        get() = accessibilityGranted &&
            overlayGranted &&
            batteryOptimizationIgnored &&
            notificationsGranted

    val grantedCount: Int
        get() = listOf(
            accessibilityGranted,
            overlayGranted,
            batteryOptimizationIgnored,
            notificationsGranted,
        ).count { it }

    companion object {
        const val TOTAL_COUNT = 4
    }
}

/**
 * Reads live permission state and builds the exact system settings intents.
 *
 * Deliberately stateless: every screen calls [snapshot] again on resume, so the
 * cards update as soon as the user comes back from Settings.
 */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun snapshot(): PermissionSnapshot = PermissionSnapshot(
        accessibilityGranted = isAccessibilityServiceEnabled(),
        overlayGranted = Settings.canDrawOverlays(context),
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(),
        notificationsGranted = areNotificationsGranted(),
    )

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        // Checking the enabled service list (rather than the raw Secure setting)
        // also covers services enabled through OEM-specific flows.
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun areNotificationsGranted(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** Intent that jumps straight to the relevant system settings screen. */
    fun settingsIntent(type: PermissionType): Intent {
        val intent = when (type) {
            PermissionType.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            PermissionType.OVERLAY ->
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                )

            PermissionType.BATTERY_OPTIMIZATION ->
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                )

            PermissionType.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Fallback when an OEM does not implement the battery exemption dialog. */
    fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
