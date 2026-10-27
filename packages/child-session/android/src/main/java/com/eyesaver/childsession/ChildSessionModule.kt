package com.eyesaver.childsession

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

@Suppress("DEPRECATION")
class ChildSessionModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  companion object { const val NAME = "EyeSaverChildSession" }

  private val eventReceiver = object : BroadcastReceiver() {
    override fun onReceive(receiverContext: Context?, intent: Intent?) {
      if (intent?.action != ChildSessionService.EVENT_ACTION || !context.hasActiveCatalystInstance()) return
      context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("childSessionEvent", ChildSessionService.stateMap(context, intent.getStringExtra("eventType")))
    }
  }
  private var receiverRegistered = false

  init { registerReceiver() }

  override fun getName(): String = NAME

  @ReactMethod
  fun start(options: ReadableMap?, promise: Promise) {
    val durationMs = options.longValue("durationMs", 20 * 60_000L).coerceIn(10_000L, 24 * 60 * 60_000L)
    val warningBeforeMs = options.longValue("warningBeforeMs", 2 * 60_000L).coerceIn(0L, durationMs)
    val blockOnExpire = options != null && options.hasKey("blockOnExpire") && !options.isNull("blockOnExpire") && options.getBoolean("blockOnExpire")
    val intent = command(ChildSessionService.ACTION_START)
      .putExtra("durationMs", durationMs)
      .putExtra("warningBeforeMs", warningBeforeMs)
      .putExtra("blockOnExpire", blockOnExpire)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
      else context.startService(intent)
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("E_SESSION_START", "Could not start the child session", error)
    }
  }

  @ReactMethod fun pause(promise: Promise) = send(ChildSessionService.ACTION_PAUSE, promise)
  @ReactMethod fun resume(promise: Promise) = send(ChildSessionService.ACTION_RESUME, promise)
  @ReactMethod fun stop(promise: Promise) = send(ChildSessionService.ACTION_STOP, promise)

  @ReactMethod
  fun getState(promise: Promise) {
    if (ChildSessionService.shouldRestore(context)) {
      try {
        val restore = command(ChildSessionService.ACTION_RESTORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(restore)
        else context.startService(restore)
      } catch (_: Exception) {
        // State remains readable; Android can retry the sticky service when allowed.
      }
    }
    promise.resolve(ChildSessionService.stateMap(context))
  }

  @ReactMethod
  fun getCapabilities(promise: Promise) {
    val result = Arguments.createMap()
    result.putBoolean("foregroundServiceSupported", true)
    result.putBoolean(
      "notificationsGranted",
      Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    )
    result.putString("timingModel", "screen-on-active-time")
    promise.resolve(result)
  }

  @ReactMethod fun addListener(eventName: String) = Unit
  @ReactMethod fun removeListeners(count: Double) = Unit

  override fun invalidate() {
    if (receiverRegistered) {
      try { context.unregisterReceiver(eventReceiver) } catch (_: IllegalArgumentException) {}
      receiverRegistered = false
    }
    super.invalidate()
  }

  private fun send(action: String, promise: Promise) {
    try { context.startService(command(action)); promise.resolve(true) }
    catch (error: Exception) { promise.reject("E_SESSION_COMMAND", "Could not send session command", error) }
  }

  private fun command(action: String) = Intent(context, ChildSessionService::class.java).setAction(action)

  private fun registerReceiver() {
    val filter = IntentFilter(ChildSessionService.EVENT_ACTION)
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else context.registerReceiver(eventReceiver, filter)
    receiverRegistered = true
  }

  private fun ReadableMap?.longValue(key: String, fallback: Long): Long =
    if (this != null && hasKey(key) && !isNull(key)) getDouble(key).toLong() else fallback
}
