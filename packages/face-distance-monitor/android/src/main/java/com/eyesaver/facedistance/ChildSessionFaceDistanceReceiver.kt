package com.eyesaver.facedistance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ChildSessionFaceDistanceReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != "com.eyesaver.childsession.EVENT") return
    if (intent.getStringExtra("eventType") in setOf("paused", "expired", "stopped")) {
      try {
        context.startService(Intent(context, FaceDistanceService::class.java).setAction(FaceDistanceService.ACTION_STOP))
      } catch (_: Exception) { Unit }
    }
  }
}
