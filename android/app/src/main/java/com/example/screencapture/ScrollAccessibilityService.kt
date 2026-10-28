package com.example.screencapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/** Injects one downward swipe so a long screenshot can advance the page. It never reads window content. */
class ScrollAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun scrollDown(onDone: (Boolean) -> Unit) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * SCROLL_START
        val endY = metrics.heightPixels * SCROLL_END
        val move = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val hold = Path().apply {
            moveTo(x, endY)
            lineTo(x, endY - 1f)
        }
        val builder = GestureDescription.Builder()
        try {
            val travel = GestureDescription.StrokeDescription(move, 0, 420, true)
            val settle = travel.continueStroke(hold, 420, 160, false)
            builder.addStroke(travel)
            builder.addStroke(settle)
        } catch (failure: Exception) {
            builder.addStroke(GestureDescription.StrokeDescription(move, 0, 480))
        }
        val accepted = try {
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
            }, null)
        } catch (failure: Exception) {
            false
        }
        if (!accepted) onDone(false)
    }

    companion object {
        const val SCROLL_START = 0.74f
        const val SCROLL_END = 0.30f

        @Volatile
        var instance: ScrollAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val setting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val component = ComponentName(context, ScrollAccessibilityService::class.java)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            return setting.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        }
    }
}
