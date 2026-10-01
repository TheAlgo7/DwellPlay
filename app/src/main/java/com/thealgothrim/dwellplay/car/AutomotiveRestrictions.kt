package com.thealgothrim.dwellplay.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android Automotive OS driving restrictions. The only value used is
 * isRequiresDistractionOptimization (Google's guidance). Touches android.car classes, so it's only
 * ever created when [CarScreens.isAutomotive] is true: phones don't have them.
 */
class AutomotiveRestrictions(private val context: Context) {
  private val _restricted = MutableStateFlow(false)
  val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

  private var car: Car? = null
  private var manager: CarUxRestrictionsManager? = null

  fun start() {
    if (car != null) return
    try {
      car = Car.createCar(context)
      manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
      val m = manager ?: return
      _restricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
      m.registerListener { r -> _restricted.value = r.isRequiresDistractionOptimization }
    } catch (e: Exception) {
      // No car service: assume restricted rather than show video we can't vouch for.
      Log.w(TAG, "Car service unavailable", e)
      _restricted.value = true
    }
  }

  fun stop() {
    runCatching { manager?.unregisterListener() }
    runCatching { car?.disconnect() }
    manager = null
    car = null
  }

  private companion object {
    const val TAG = "AutomotiveRestrictions"
  }
}
