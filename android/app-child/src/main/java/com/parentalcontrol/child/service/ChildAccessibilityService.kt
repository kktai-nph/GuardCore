package com.parentalcontrol.child.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.parentalcontrol.child.engine.ChildEngineHolder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ChildAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChildAccessibility"
        private val BLOCKED_SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )
    }

    private var currentForegroundPackage: String? = null
    private var lastCheckTimeMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return

            // Ignore system UI and our own package
            if (pkgName == packageName || pkgName == "com.android.systemui") {
                return
            }

            currentForegroundPackage = pkgName
            evaluateForegroundApp(pkgName)
        }
    }

    private fun evaluateForegroundApp(packageName: String) {
        val engine = ChildEngineHolder.getEngine(applicationContext) ?: return

        val nowEpoch = System.currentTimeMillis() / 1000
        val calendar = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val currentHhmm = timeFormat.format(calendar.time)
        val todayStr = dateFormat.format(calendar.time)

        // Day of week: 1=Mon..7=Sun
        var dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        if (dayOfWeek == 0) dayOfWeek = 7

        // 1. Prevent tampering with Settings / Uninstaller if restricted
        if (BLOCKED_SETTINGS_PACKAGES.contains(packageName)) {
            // Check if device is under parental enforcement
            val lockStatus = engine.evaluateDeviceLock(nowEpoch.toULong(), currentHhmm, dayOfWeek.toUByte())
            if (lockStatus.isLocked) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                showWarningToast("Settings are restricted by parental policy")
                return
            }
        }

        // 2. Evaluate App Access against Policy Engine
        val decision = engine.evaluateAppAccess(
            packageName,
            nowEpoch.toULong(),
            currentHhmm,
            dayOfWeek.toUByte(),
            todayStr
        )

        when (decision.decisionType) {
            "ALLOWED" -> {
                LockOverlayService.hideLock(applicationContext)
            }
            "BLOCKED", "QUOTA_EXCEEDED", "DEVICE_LOCKED" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                LockOverlayService.showLock(applicationContext, decision.detailMessage)
                showWarningToast(decision.detailMessage)
            }
        }
    }

    private fun showWarningToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }
}
