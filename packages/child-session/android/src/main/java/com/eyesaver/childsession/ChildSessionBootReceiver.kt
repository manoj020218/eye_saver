package com.eyesaver.childsession

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ChildSessionBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    if (!ChildSessionService.shouldRestore(context)) return
    try {
      val restore = Intent(context, ChildSessionService::class.java).setAction(ChildSessionService.ACTION_RESTORE)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(restore)
      else context.startService(restore)
    } catch (_: Exception) {
      // A later app launch also restores the persisted session.
    }
  }
}
