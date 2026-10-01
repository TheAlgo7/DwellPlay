package com.thealgothrim.dwellplay.car

import android.content.Context

/**
 * Words for "where local videos live". Through Android Auto the app runs on the phone; on Android
 * Automotive OS it runs in the car, so "this phone" would be wrong there.
 */
object Device {
  @Volatile private var car = false

  fun init(context: Context) {
    car = CarScreens.isAutomotive(context)
  }

  val isCar: Boolean
    get() = car

  /** "this phone" or "this car", mid-sentence. */
  val here: String
    get() = if (car) "this car" else "this phone"

  /** "This phone" or "This car", as a label. */
  val label: String
    get() = if (car) "This car" else "This phone"
}
