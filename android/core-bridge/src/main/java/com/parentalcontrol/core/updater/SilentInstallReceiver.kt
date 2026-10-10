package com.parentalcontrol.core.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class SilentInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_COMMIT = "com.parentalcontrol.ACTION_INSTALL_COMMIT"
        private const val TAG = "SilentInstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Silent install requested user action, launching confirmation")
                val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "✓ Silent update installed successfully!")
            }
            else -> {
                Log.e(TAG, "Silent update failed. Status: $status, Message: $message")
            }
        }
    }
}
