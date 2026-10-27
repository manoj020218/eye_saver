package com.eyesaver.deviceguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap

class DeviceGuardModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  companion object { const val NAME = "EyeSaverDeviceGuard" }
  private val policy by lazy { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
  private val admin by lazy { ComponentName(context, EyeSaverAdminReceiver::class.java) }
  private val selectiveGuardPrefs by lazy { context.getSharedPreferences(RestrictedAppMonitorService.PREFS, Context.MODE_PRIVATE) }

  override fun getName(): String = NAME

  @ReactMethod
  fun getCapabilities(promise: Promise) {
    val owner = policy.isDeviceOwnerApp(context.packageName)
    val result = Arguments.createMap()
    result.putBoolean("isAdminActive", policy.isAdminActive(admin))
    result.putBoolean("isDeviceOwner", owner)
    result.putBoolean("usageAccessGranted", hasUsageAccess())
    result.putBoolean("canSuspendPackages", owner && Build.VERSION.SDK_INT >= 24)
    result.putBoolean("isLockTaskPermitted", policy.isLockTaskPermitted(context.packageName))
    result.putBoolean("canDrawOverlays", Settings.canDrawOverlays(context))
    result.putBoolean("canWriteSystemGrayscale", false)
    promise.resolve(result)
  }

  @ReactMethod
  fun requestAdmin(promise: Promise) {
    val activity = context.currentActivity ?: return promise.reject("E_NO_ACTIVITY", "A visible Activity is required")
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
      .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
      .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow the parent to lock the device when child time expires")
    activity.startActivity(intent)
    promise.resolve(true)
  }

  @ReactMethod
  fun requestUsageAccess(promise: Promise) {
    val activity = context.currentActivity ?: return promise.reject("E_NO_ACTIVITY", "A visible Activity is required")
    activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")))
    promise.resolve(true)
  }

  @ReactMethod
  fun requestOverlayPermission(promise: Promise) {
    if (Settings.canDrawOverlays(context)) { promise.resolve(true); return }
    val activity = context.currentActivity ?: return promise.reject("E_NO_ACTIVITY", "A visible Activity is required")
    activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    promise.resolve(true)
  }

  @ReactMethod
  fun lockDevice(promise: Promise) {
    if (!policy.isAdminActive(admin)) return promise.reject("E_ADMIN_REQUIRED", "Device Admin must be enabled first")
    try { policy.lockNow(); promise.resolve(true) }
    catch (error: SecurityException) { promise.reject("E_LOCK_DENIED", "Android denied device locking", error) }
  }

  @ReactMethod
  fun setPackagesSuspended(packages: ReadableArray, suspended: Boolean, promise: Promise) {
    if (Build.VERSION.SDK_INT < 24 || !policy.isDeviceOwnerApp(context.packageName)) {
      promise.reject("E_DEVICE_OWNER_REQUIRED", "Package suspension requires Device Owner provisioning")
      return
    }
    val names = Array(packages.size()) { index -> packages.getString(index) ?: "" }.filter { it.isNotBlank() }.toTypedArray()
    try {
      val failures = policy.setPackagesSuspended(admin, names, suspended)
      promise.resolve(Arguments.fromList(failures.toList()))
    } catch (error: Exception) {
      promise.reject("E_SUSPEND_FAILED", "Could not change package suspension", error)
    }
  }

  @ReactMethod
  fun startLockTask(promise: Promise) {
    val activity = context.currentActivity ?: return promise.reject("E_NO_ACTIVITY", "A visible Activity is required")
    if (!policy.isLockTaskPermitted(context.packageName)) {
      promise.reject("E_LOCK_TASK_NOT_ALLOWED", "The app must be allowlisted by a Device Owner")
      return
    }
    activity.startLockTask()
    promise.resolve(true)
  }

  @ReactMethod
  fun stopLockTask(promise: Promise) {
    val activity = context.currentActivity ?: return promise.reject("E_NO_ACTIVITY", "A visible Activity is required")
    try { activity.stopLockTask(); promise.resolve(true) }
    catch (error: Exception) { promise.reject("E_LOCK_TASK_STOP", "Could not stop lock task mode", error) }
  }

  @ReactMethod
  fun showBreakScreen(options: ReadableMap?, promise: Promise) {
    val message = if (options != null && options.hasKey("message") && !options.isNull("message")) options.getString("message") else null
    val timestamp = if (options != null && options.hasKey("triggeredAtMs") && !options.isNull("triggeredAtMs")) options.getDouble("triggeredAtMs").toLong() else System.currentTimeMillis()
    if (Settings.canDrawOverlays(context)) {
      context.startService(BreakOverlayService.showIntent(context, message, timestamp))
    } else {
      val activity = context.currentActivity ?: return promise.reject("E_OVERLAY_REQUIRED", "Display-over-other-apps permission is required for background enforcement")
      activity.startActivity(Intent(activity, BreakActivity::class.java).putExtra("message", message).putExtra("triggeredAtMs", timestamp))
    }
    promise.resolve(true)
  }

  @ReactMethod
  fun dismissBreakScreen(promise: Promise) {
    BreakOverlayService.dismiss(context)
    context.sendBroadcast(Intent(BreakActivity.ACTION_DISMISS).setPackage(context.packageName))
    promise.resolve(true)
  }

  @ReactMethod
  @Suppress("DEPRECATION")
  fun getLaunchableApps(promise: Promise) {
    try {
      val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      val seen = linkedMapOf<String, String>()
      context.packageManager.queryIntentActivities(launcherIntent, 0).forEach { info ->
        val targetPackage = info.activityInfo?.packageName ?: return@forEach
        if (targetPackage != context.packageName) {
          seen[targetPackage] = info.loadLabel(context.packageManager)?.toString() ?: targetPackage
        }
      }
      val result = Arguments.createArray()
      seen.entries.sortedBy { it.value.lowercase() }.forEach { (targetPackage, label) ->
        result.pushMap(Arguments.createMap().apply {
          putString("packageName", targetPackage)
          putString("label", label)
        })
      }
      promise.resolve(result)
    } catch (error: Exception) {
      promise.reject("E_APP_LIST", "Could not list launchable apps", error)
    }
  }

  @ReactMethod
  fun getRestrictedPackages(promise: Promise) {
    promise.resolve(Arguments.fromList(
      selectiveGuardPrefs.getStringSet(RestrictedAppMonitorService.KEY_PACKAGES, emptySet()).orEmpty().sorted()
    ))
  }

  @ReactMethod
  fun setRestrictedPackages(packages: ReadableArray, promise: Promise) {
    val selected = (0 until packages.size())
      .mapNotNull { packages.getString(it) }
      .filter { it.isNotBlank() && it != context.packageName }
      .toSet()
    val saved = selectiveGuardPrefs.edit().putStringSet(RestrictedAppMonitorService.KEY_PACKAGES, selected).commit()
    if (!saved) { promise.reject("E_RESTRICTION_SAVE", "Android did not save the selected apps"); return }
    if (selected.isEmpty()) RestrictedAppMonitorService.stop(context)
    promise.resolve(true)
  }

  @ReactMethod
  fun startSelectiveGuard(intervalMs: Double, promise: Promise) {
    if (!Settings.canDrawOverlays(context)) {
      promise.reject("E_OVERLAY_REQUIRED", "Display-over-other-apps permission is required")
      return
    }
    if (!hasUsageAccess()) {
      promise.reject("E_USAGE_ACCESS_REQUIRED", "Usage access is required to detect the selected app")
      return
    }
    if (selectiveGuardPrefs.getStringSet(RestrictedAppMonitorService.KEY_PACKAGES, emptySet()).isNullOrEmpty()) {
      promise.resolve(false)
      return
    }
    try {
      val intent = Intent(context, RestrictedAppMonitorService::class.java)
        .setAction(RestrictedAppMonitorService.ACTION_START)
        .putExtra("intervalMs", intervalMs.toLong().coerceIn(750L, 10_000L))
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
      else context.startService(intent)
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("E_SELECTIVE_GUARD_START", "Could not start selected-app protection", error)
    }
  }

  @ReactMethod
  fun stopSelectiveGuard(promise: Promise) {
    RestrictedAppMonitorService.stop(context)
    promise.resolve(true)
  }

  private fun hasUsageAccess(): Boolean {
    return RestrictedAppMonitorService.hasUsageAccess(context)
  }
}
