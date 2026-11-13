package com.bikeguard.app.tracker

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/** Accelerometer watcher with two jobs: spot a crash-sized impact, or spot a parked bike being handled. */
class SensorGuard(ctx: Context, private val listener: Listener) : SensorEventListener {
    interface Listener {
        fun onImpact(peak: Float)
        fun onMotion()
    }

    enum class Mode { OFF, MOTION, CRASH }

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var mode = Mode.OFF
    private var gravity = 9.81f
    private var burst = 0
    private var burstStart = 0L

    fun updateMode(m: Mode) {
        if (m == mode) return
        sm.unregisterListener(this)
        mode = m
        burst = 0
        val a = accel ?: return
        when (m) {
            Mode.CRASH -> sm.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME)    // ~50 Hz: impacts are short
            Mode.MOTION -> sm.registerListener(this, a, SensorManager.SENSOR_DELAY_NORMAL) // ~5 Hz: cheap while parked
            Mode.OFF -> Unit
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        gravity = 0.95f * gravity + 0.05f * mag
        when (mode) {
            Mode.CRASH -> if (mag >= IMPACT_MS2) listener.onImpact(mag)
            Mode.MOTION -> {
                if (abs(mag - gravity) > 1.5f) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - burstStart > 3_000) { burst = 0; burstStart = now }
                    if (++burst >= 4) { burst = 0; listener.onMotion() }   // sustained shaking, not one bump
                }
            }
            Mode.OFF -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object { const val IMPACT_MS2 = 35f } // about 3.6 g
}
