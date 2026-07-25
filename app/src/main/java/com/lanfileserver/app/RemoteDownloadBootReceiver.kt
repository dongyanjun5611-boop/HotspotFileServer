package com.lanfileserver.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RemoteDownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPreferences.remoteEnabled(context)) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteDownloadService::class.java)
                    .setAction(RemoteDownloadService.ACTION_START),
            )
        }
    }
}
