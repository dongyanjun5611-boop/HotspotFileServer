package com.lanfileserver.app

import android.content.Context
import android.content.Intent

object FileChangeNotifier {
    const val ACTION_FILES_CHANGED = "com.lanfileserver.app.action.FILES_CHANGED"

    fun notify(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_FILES_CHANGED).setPackage(context.packageName),
        )
    }
}
