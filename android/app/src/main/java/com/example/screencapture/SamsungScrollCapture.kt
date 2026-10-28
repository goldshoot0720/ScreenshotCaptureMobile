package com.example.screencapture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Samsung scroll capture lives in the system screenshot toolbar.
 * There is no public API, and a normal app cannot inject the hardware keys.
 * Exported components are tried; anything else falls through to the key shortcut.
 */
internal object SamsungScrollCapture {
    private const val PACKAGE = "com.samsung.android.app.smartcapture"

    enum class SettingsTarget { SAMSUNG, SYSTEM }

    fun isSamsung(context: Context): Boolean {
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return true
        return packageInstalled(context, PACKAGE)
    }

    /** @return true only when an exported Samsung capture activity actually started. */
    fun tryLaunch(context: Context): Boolean {
        val activities = listOf(
            ComponentName(PACKAGE, "$PACKAGE.screenshot.ScreenshotActivity"),
            ComponentName(PACKAGE, "$PACKAGE.screenshot.ScreenCaptureActivity"),
            ComponentName(PACKAGE, "$PACKAGE.SmartCaptureActivity")
        )
        return activities.any { start(context, Intent().setComponent(it)) }
    }

    fun openSettings(context: Context): SettingsTarget {
        val activities = listOf(
            ComponentName("com.android.settings", "com.samsung.android.settings.screenshot.ScreenshotSettingsActivity"),
            ComponentName("com.android.settings", "com.samsung.android.settings.display.ScreenCaptureSettingsActivity"),
            ComponentName(PACKAGE, "$PACKAGE.setting.SmartCaptureSettingsActivity"),
            ComponentName(PACKAGE, "$PACKAGE.setting.ScreenCaptureSettingsActivity"),
            ComponentName(PACKAGE, "$PACKAGE.settings.SmartCaptureSettingsActivity")
        )
        if (activities.any { start(context, Intent().setComponent(it)) }) return SettingsTarget.SAMSUNG
        start(context, Intent(Settings.ACTION_SETTINGS))
        return SettingsTarget.SYSTEM
    }

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun packageInstalled(context: Context, name: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(name, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
