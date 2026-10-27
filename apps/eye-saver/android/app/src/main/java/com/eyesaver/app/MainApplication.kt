package com.eyesaver.app

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.eyesaver.childsession.ChildSessionPackage
import com.eyesaver.deviceguard.DeviceGuardPackage
import com.eyesaver.facedistance.FaceDistancePackage
import com.eyesaver.faceverification.FaceVerificationPackage
import com.eyesaver.parentauth.ParentAuthPackage
import com.faceproximity.FaceProximityPackage

class MainApplication : Application(), ReactApplication {
  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList = PackageList(this).packages.apply {
        add(ChildSessionPackage())
        add(DeviceGuardPackage())
        add(FaceDistancePackage())
        add(FaceVerificationPackage())
        add(ParentAuthPackage())
        add(FaceProximityPackage())
      },
    )
  }

  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)
  }
}
