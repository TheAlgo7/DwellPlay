package com.thealgothrim.dwellplay.car

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.car.app.connection.CarConnection
import androidx.car.app.features.CarFeatures
import com.thealgothrim.dwellplay.core.policy.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which screen an activity is on. Android Auto runs a parked app on a display of its own (not the
 * phone's), while Android Automotive OS is a car all the way through.
 */
object CarScreens {
  private val _connection = MutableStateFlow<Int?>(null)

  /** Debug builds (the adb "car" hook): treat a second display as Android Auto's, to test car layouts. */
  @Volatile var pretendProjecting = false
  /** Android Auto's connection: not connected, projecting (Android Auto), or native (Android Automotive OS). Null until known. */
  val connection: StateFlow<Int?> = _connection.asStateFlow()

  /** Starts listening for Android Auto connecting and disconnecting. Main thread. */
  fun watch(context: Context) {
    runCatching { CarConnection(context.applicationContext).type.observeForever { _connection.value = it } }
        .onFailure { Log.w(TAG, "CarConnection unavailable", it) }
  }

  /** Android Auto is connected right now (not merely unknown yet). The phone then hands the picture to the car. */
  val projecting: Boolean
    get() = pretendProjecting || _connection.value == CarConnection.CONNECTION_TYPE_PROJECTION

  fun isAutomotive(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

  fun detect(activity: Activity): Screen {
    if (isAutomotive(activity)) return Screen.AUTOMOTIVE
    val displayId =
        if (Build.VERSION.SDK_INT >= 30) activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.displayId
    // Google's check: projecting AND on a display other than the phone's. While the connection
    // state is still loading, another display is treated as the car: the safe side.
    val projecting = pretendProjecting || _connection.value.let { it == null || it == CarConnection.CONNECTION_TYPE_PROJECTION }
    return if (displayId != Display.DEFAULT_DISPLAY && projecting) Screen.ANDROID_AUTO else Screen.PHONE
  }

  /**
   * Whether own media may carry on as sound while the car moves. Android Automotive OS says so per
   * car; through Android Auto the phone plays the sound like any music app.
   */
  fun vehicleAllowsBackgroundAudio(context: Context, screen: Screen): Boolean =
      when (screen) {
        Screen.AUTOMOTIVE ->
            runCatching { CarFeatures.isFeatureEnabled(context, CarFeatures.FEATURE_BACKGROUND_AUDIO_WHILE_DRIVING) }.getOrDefault(false)
        Screen.ANDROID_AUTO,
        Screen.PHONE -> true
      }

  private const val TAG = "CarScreens"
}
