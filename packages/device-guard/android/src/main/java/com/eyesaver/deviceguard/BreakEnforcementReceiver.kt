package com.eyesaver.deviceguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BreakEnforcementReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != "com.eyesaver.childsession.EVENT") return
    when (intent.getStringExtra("eventType")) {
      "started", "resumed" -> try { RestrictedAppMonitorService.startIfConfigured(context) } catch (_: Exception) { Unit }
      "paused" -> RestrictedAppMonitorService.stop(context)
      "expired" -> {
        RestrictedAppMonitorService.stop(context)
        val session = context.getSharedPreferences("eye_saver_child_session", Context.MODE_PRIVATE)
        if (session.getBoolean("blockOnExpire", false) && Settings.canDrawOverlays(context)) {
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
      "stopped" -> {
        RestrictedAppMonitorService.stop(context)
        BreakOverlayService.dismiss(context)
      }
    }
  }
}
