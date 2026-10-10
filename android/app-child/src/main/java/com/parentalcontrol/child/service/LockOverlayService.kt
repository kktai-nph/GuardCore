package com.parentalcontrol.child.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup

class LockOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    companion object {
        const val EXTRA_LOCK_REASON = "extra_lock_reason"
        const val ACTION_SHOW_LOCK = "com.parentalcontrol.child.SHOW_LOCK"
        const val ACTION_HIDE_LOCK = "com.parentalcontrol.child.HIDE_LOCK"

        fun showLock(context: Context, reason: String) {
            val intent = Intent(context, LockOverlayService::class.java).apply {
                action = ACTION_SHOW_LOCK
                putExtra(EXTRA_LOCK_REASON, reason)
            }
            context.startService(intent)
        }

        fun hideLock(context: Context) {
            val intent = Intent(context, LockOverlayService::class.java).apply {
                action = ACTION_HIDE_LOCK
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_LOCK -> {
                val reason = intent.getStringExtra(EXTRA_LOCK_REASON) ?: "Device is locked by parental policy"
                showOverlay(reason)
            }
            ACTION_HIDE_LOCK -> {
                hideOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlay(reasonText: String) {
        if (overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Programmatically build sleek modern lock screen layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E60F172A")) // Dark Slate with 90% opacity
            setPadding(64, 64, 64, 64)

            // Lock Icon / Title
            val titleView = TextView(context).apply {
                text = "🔒 Device Restricted"
                textSize = 26f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(titleView)

            // Reason Subtitle
            val reasonView = TextView(context).apply {
                text = reasonText
                textSize = 16f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 48)
            }
            addView(reasonView)

            // Emergency SOS Call Button
            val sosButton = Button(context).apply {
                text = "Emergency Call (Dialer)"
                setBackgroundColor(Color.parseColor("#DC2626"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:911")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dialIntent)
                }
            }
            addView(sosButton)
        }

        overlayView = layout
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }
}
