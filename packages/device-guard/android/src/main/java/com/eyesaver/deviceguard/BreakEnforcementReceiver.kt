package com.eyesaver.deviceguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BreakEnforcementReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != "com.eyesaver.childsession.EVENT") return
    when (intent.getStringExtra("eventType")) {
      "started", "resumed" -> {
        RestrictedAppMonitorService.stop(context)
        BreakOverlayService.dismiss(context)
      }
      "paused" -> {
        RestrictedAppMonitorService.stop(context)
        BreakOverlayService.dismiss(context)
      }
      "expired" -> {
        val session = context.getSharedPreferences("eye_saver_child_session", Context.MODE_PRIVATE)
        if (session.getBoolean("blockOnExpire", false) && Settings.canDrawOverlays(context)) {
          val guard = context.getSharedPreferences(RestrictedAppMonitorService.PREFS, Context.MODE_PRIVATE)
          val selectedPackages = guard.getStringSet(RestrictedAppMonitorService.KEY_PACKAGES, emptySet()).orEmpty()
          if (selectedPackages.isNotEmpty() && RestrictedAppMonitorService.hasUsageAccess(context)) {
            BreakOverlayService.dismiss(context)
            try { RestrictedAppMonitorService.startIfConfigured(context) } catch (_: Exception) { Unit }
          } else {
            RestrictedAppMonitorService.stop(context)
            try {
              context.startService(BreakOverlayService.showIntent(
                context,
                "Time for an eye and screen break",
                intent.getLongExtra("timestampMs", System.currentTimeMillis())
              ))
            } catch (_: Exception) {
              // The high-priority expiry notification remains as the safe fallback.
            }
          }
        }
      }
      "stopped" -> {
        RestrictedAppMonitorService.stop(context)
        BreakOverlayService.dismiss(context)
      }
    }
  }
}
