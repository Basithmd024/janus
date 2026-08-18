package com.janus.app.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class JanusAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JanusAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("JanusAccessibility", "Accessibility Service Connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        Log.d("JanusAccessibility", "Accessibility Service Interrupted")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("JanusAccessibility", "Accessibility Service Destroyed")
        instance = null
    }

    fun injectClick(x: Float, y: Float): Boolean {
        Log.d("JanusAccessibility", "Injecting click at ($x, $y)")
        val path = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L) // 50ms tap duration
        gestureBuilder.addStroke(stroke)
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean {
        Log.d("JanusAccessibility", "Injecting swipe from ($startX, $startY) to ($endX, $endY) over ${duration}ms")
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(100L))
        gestureBuilder.addStroke(stroke)
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performSystemAction(action: Int): Boolean {
        Log.d("JanusAccessibility", "Performing global action: $action")
        return performGlobalAction(action)
    }
}
