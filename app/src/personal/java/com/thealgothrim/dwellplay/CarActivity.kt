package com.thealgothrim.dwellplay

/**
 * The same screen, started by Android Auto on the car display. It is a separate class only so it
 * gets its own task: with one shared task, opening DwellPlay on the phone while it is open on the car
 * stacked the new screen onto the car's task, on the car display (seen on the head unit 2026-10-03),
 * so the phone could never act as the remote.
 */
class CarActivity : MainActivity()
