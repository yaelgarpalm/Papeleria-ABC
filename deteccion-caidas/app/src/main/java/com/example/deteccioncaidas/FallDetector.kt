package com.example.deteccioncaidas

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detector heurístico para un prototipo. Sus umbrales requieren calibración
 * y no representan un sistema validado para emergencias.
 */
internal class FallDetector(
    private val onFallDetected: () -> Unit,
) {
    private enum class Phase { WAITING, FREE_FALL, WAITING_FOR_STILLNESS }

    private var phase = Phase.WAITING
    private var freeFallStartedAtNs = 0L
    private var impactAtNs = 0L
    private var stillnessStartedAtNs = 0L
    private var peakGyroscope = 0f
    private var peakAcceleration = 0f
    private var latestGyroscope = 0f
    private var latestGyroscopeAtNs = 0L

    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNs: Long) {
        val acceleration = sqrt(x * x + y * y + z * z)

        when (phase) {
            Phase.WAITING -> {
                if (acceleration < FREE_FALL_MPS2) {
                    phase = Phase.FREE_FALL
                    freeFallStartedAtNs = timestampNs
                    peakGyroscope = recentGyroscopeOrZero(timestampNs)
                } else if (acceleration > IMPACT_WITHOUT_FREE_FALL_MPS2) {
                    beginPostImpactCheck(timestampNs, acceleration)
                }
            }

            Phase.FREE_FALL -> {
                val freeFallDuration = timestampNs - freeFallStartedAtNs
                if (acceleration > IMPACT_AFTER_FREE_FALL_MPS2) {
                    if (freeFallDuration >= MIN_FREE_FALL_NS) {
                        beginPostImpactCheck(timestampNs, acceleration)
                    } else if (acceleration > VERY_STRONG_IMPACT_MPS2) {
                        // Un pulso muy corto puede saltarse muestras de caída libre.
                        beginPostImpactCheck(timestampNs, acceleration)
                    } else {
                        resetCandidate()
                    }
                } else if (freeFallDuration > MAX_FREE_FALL_NS) {
                    resetCandidate()
                }
            }

            Phase.WAITING_FOR_STILLNESS -> {
                if (timestampNs - impactAtNs > MAX_POST_IMPACT_NS) {
                    resetCandidate()
                    return
                }
                peakAcceleration = max(peakAcceleration, acceleration)

                val gyroIsRecent = timestampNs - latestGyroscopeAtNs <= GYRO_FRESHNESS_NS
                val isStill =
                    kotlin.math.abs(acceleration - EARTH_GRAVITY_MPS2) < STILL_ACCELERATION_TOLERANCE_MPS2 &&
                        gyroIsRecent && latestGyroscope < STILL_GYROSCOPE_RAD_PER_SEC

                if (isStill) {
                    if (stillnessStartedAtNs == 0L) stillnessStartedAtNs = timestampNs
                    val stillFor = timestampNs - stillnessStartedAtNs
                    val hasRotationEvidence = peakGyroscope >= MIN_GYROSCOPE_PEAK_RAD_PER_SEC
                    val hasVeryStrongImpact = peakAcceleration >= VERY_STRONG_IMPACT_MPS2
                    if (stillFor >= STILLNESS_REQUIRED_NS && (hasRotationEvidence || hasVeryStrongImpact)) {
                        resetCandidate()
                        onFallDetected()
                    }
                } else {
                    stillnessStartedAtNs = 0L
                }
            }
        }
    }

    fun onGyroscope(x: Float, y: Float, z: Float, timestampNs: Long) {
        latestGyroscope = sqrt(x * x + y * y + z * z)
        latestGyroscopeAtNs = timestampNs
        if (phase != Phase.WAITING) {
            peakGyroscope = max(peakGyroscope, latestGyroscope)
        }
    }

    fun reset() {
        resetCandidate()
        latestGyroscope = 0f
        latestGyroscopeAtNs = 0L
    }

    private fun beginPostImpactCheck(timestampNs: Long, acceleration: Float) {
        phase = Phase.WAITING_FOR_STILLNESS
        impactAtNs = timestampNs
        stillnessStartedAtNs = 0L
        peakAcceleration = acceleration
        peakGyroscope = max(peakGyroscope, recentGyroscopeOrZero(timestampNs))
    }

    private fun recentGyroscopeOrZero(timestampNs: Long): Float =
        if (timestampNs - latestGyroscopeAtNs <= GYRO_FRESHNESS_NS) latestGyroscope else 0f

    private fun resetCandidate() {
        phase = Phase.WAITING
        freeFallStartedAtNs = 0L
        impactAtNs = 0L
        stillnessStartedAtNs = 0L
        peakGyroscope = 0f
        peakAcceleration = 0f
    }

    private companion object {
        // Valores iniciales para experimentar; hay que ajustarlos al teléfono y a su ubicación.
        const val EARTH_GRAVITY_MPS2 = 9.81f
        const val FREE_FALL_MPS2 = 4.5f
        const val IMPACT_AFTER_FREE_FALL_MPS2 = 18f
        const val IMPACT_WITHOUT_FREE_FALL_MPS2 = 21f
        const val VERY_STRONG_IMPACT_MPS2 = 30f
        const val MIN_FREE_FALL_NS = 80_000_000L
        const val MAX_FREE_FALL_NS = 1_500_000_000L
        const val STILL_ACCELERATION_TOLERANCE_MPS2 = 2.5f
        const val STILL_GYROSCOPE_RAD_PER_SEC = 0.5f
        const val MIN_GYROSCOPE_PEAK_RAD_PER_SEC = 1.0f
        const val STILLNESS_REQUIRED_NS = 2_000_000_000L
        const val MAX_POST_IMPACT_NS = 10_000_000_000L
        const val GYRO_FRESHNESS_NS = 500_000_000L
    }
}
