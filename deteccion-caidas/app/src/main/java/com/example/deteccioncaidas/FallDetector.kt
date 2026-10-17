package com.example.deteccioncaidas

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/** Lo que midió el detector al revisar un posible evento. Sirve para calibrar los umbrales. */
internal data class FallEvidence(
    val isFall: Boolean,
    val reason: String,
    val freeFallMs: Long,
    val weightlessMs: Long,
    val minAcceleration: Float,
    val peakAcceleration: Float,
    val peakGyroscope: Float,
    val orientationChangeDeg: Float,
) {
    fun summary(): String {
        val orientation = if (orientationChangeDeg.isNaN()) "?" else "%.0f°".format(Locale.US, orientationChangeDeg)
        return "${if (isFall) "CAÍDA" else "No es caída"}: $reason\n" +
            "Caída libre ${freeFallMs} ms (ingravidez ${weightlessMs} ms) · mín ${"%.1f".format(Locale.US, minAcceleration)} m/s²\n" +
            "Impacto ${"%.1f".format(Locale.US, peakAcceleration)} m/s² · giro ${"%.1f".format(Locale.US, peakGyroscope)} rad/s · postura $orientation"
    }
}

/**
 * Detector heurístico para un prototipo con el teléfono en el bolsillo del pantalón o en la cintura.
 * Sus umbrales requieren calibración y no representan un sistema validado para emergencias.
 *
 * Busca esta secuencia:
 *  1. Caída libre: la aceleración total baja de ~0.6 g durante al menos 80 ms.
 *  2. Impacto o giro brusco durante el segundo siguiente
 *     (o un impacto muy fuerte, por si no se registró la caída libre).
 *  3. Cambio de postura: la gravedad apunta en otra dirección respecto al teléfono (de pie → acostado).
 *  4. Quietud: dos segundos casi sin movimiento.
 *
 * La revisión se hace hacia atrás: al cumplirse la quietud se buscan los pasos 1-3 en los segundos
 * previos. Así el resultado no depende de cuánto tarda la persona en quedarse quieta ni de rebotes.
 */
internal class FallDetector(
    private val onFallDetected: (FallEvidence) -> Unit,
    private val onEvaluation: (FallEvidence) -> Unit = {},
) {
    private class AccelerationSample(val t: Long, val x: Float, val y: Float, val z: Float, val magnitude: Float)
    private class GyroscopeSample(val t: Long, val magnitude: Float)
    private class LowRun(val start: Long, val end: Long) {
        val durationNs get() = end - start
    }
    private class Candidate(
        val start: Long,
        val impactAt: Long,
        val impact: Float,
        val rotation: Float,
        val freeFallNs: Long,
    )

    private val accelerations = ArrayDeque<AccelerationSample>()
    private val gyroscopes = ArrayDeque<GyroscopeSample>()
    private var latestGyroscope = 0f
    private var latestGyroscopeAtNs = 0L
    private var stillnessStartedAtNs = 0L
    private var movingSinceNs = 0L
    private var stillnessEvaluated = false

    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNs: Long) {
        val previous = accelerations.lastOrNull()
        if (previous != null) {
            if (timestampNs == previous.t) return
            // Un hueco en los datos (sensor detenido o reloj que retrocede) invalida el historial.
            if (timestampNs < previous.t || timestampNs - previous.t > MAX_GAP_NS) reset()
        }
        val magnitude = sqrt(x * x + y * y + z * z)
        accelerations.addLast(AccelerationSample(timestampNs, x, y, z, magnitude))
        while (timestampNs - accelerations.first().t > HISTORY_NS) accelerations.removeFirst()

        val gyroIsRecent = timestampNs - latestGyroscopeAtNs <= GYRO_FRESHNESS_NS
        val isStill = abs(magnitude - EARTH_GRAVITY_MPS2) < STILL_ACCELERATION_TOLERANCE_MPS2 &&
            gyroIsRecent && latestGyroscope < STILL_GYROSCOPE_RAD_PER_SEC

        if (isStill) {
            movingSinceNs = 0L
            if (stillnessStartedAtNs == 0L) stillnessStartedAtNs = timestampNs
        } else {
            if (movingSinceNs == 0L) movingSinceNs = timestampNs
            // Un movimiento breve (respirar, un temblor) no interrumpe la quietud.
            if (timestampNs - movingSinceNs >= MOVEMENT_BREAKS_STILLNESS_NS) {
                stillnessStartedAtNs = 0L
                stillnessEvaluated = false
            }
        }

        if (stillnessStartedAtNs != 0L && !stillnessEvaluated && movingSinceNs == 0L &&
            timestampNs - stillnessStartedAtNs >= STILLNESS_REQUIRED_NS
        ) {
            stillnessEvaluated = true
            val evidence = evaluate(stillnessStartedAtNs, timestampNs)
            onEvaluation(evidence)
            if (evidence.isFall) onFallDetected(evidence)
        }
    }

    fun onGyroscope(x: Float, y: Float, z: Float, timestampNs: Long) {
        latestGyroscope = sqrt(x * x + y * y + z * z)
        latestGyroscopeAtNs = timestampNs
        gyroscopes.addLast(GyroscopeSample(timestampNs, latestGyroscope))
        while (timestampNs - gyroscopes.first().t > HISTORY_NS) gyroscopes.removeFirst()
    }

    fun reset() {
        accelerations.clear()
        gyroscopes.clear()
        latestGyroscope = 0f
        latestGyroscopeAtNs = 0L
        stillnessStartedAtNs = 0L
        movingSinceNs = 0L
        stillnessEvaluated = false
    }

    private fun evaluate(stillStart: Long, now: Long): FallEvidence {
        val windowStart = stillStart - LOOKBACK_NS
        val freeFalls = lowRuns(windowStart, stillStart, FREE_FALL_MPS2)
        val windowSamples = accelerations.filter { it.t in windowStart..stillStart }
        val minAcceleration = windowSamples.minOfOrNull { it.magnitude } ?: EARTH_GRAVITY_MPS2
        val stillPosture = meanVector(stillStart, now)

        val candidate = findCandidate(freeFalls, windowStart, stillStart)
        if (candidate == null) {
            val prePosture = meanVector(windowStart - PRE_POSTURE_NS, windowStart)
            return FallEvidence(
                isFall = false,
                reason = "sin caída libre seguida de impacto o giro, ni impacto fuerte",
                freeFallMs = (freeFalls.maxOfOrNull { it.durationNs } ?: 0L) / NS_PER_MS,
                weightlessMs = longestRun(windowStart, stillStart, WEIGHTLESS_MPS2) / NS_PER_MS,
                minAcceleration = minAcceleration,
                peakAcceleration = windowSamples.maxOfOrNull { it.magnitude } ?: 0f,
                peakGyroscope = peakGyroscope(windowStart, stillStart),
                orientationChangeDeg = angleDeg(prePosture, stillPosture),
            )
        }

        // La postura "antes" se mide justo antes de que empezara la caída.
        val prePosture = meanVector(
            candidate.start - PRE_POSTURE_GAP_NS - PRE_POSTURE_NS,
            candidate.start - PRE_POSTURE_GAP_NS,
        )
        val orientationChange = angleDeg(prePosture, stillPosture)
        val weightlessNs = longestRun(candidate.start - PRE_POSTURE_GAP_NS, candidate.impactAt, WEIGHTLESS_MPS2)

        val rejection = when {
            weightlessNs >= MAX_WEIGHTLESS_NS -> "ingravidez demasiado larga: parece el teléfono soltado o un salto"
            orientationChange.isNaN() -> "no hay datos de la postura previa"
            orientationChange < MIN_ORIENTATION_CHANGE_DEG -> "la postura no cambió lo suficiente (de pie → acostado)"
            else -> null
        }
        val reason = rejection ?: if (candidate.freeFallNs > 0) {
            "caída libre, impacto o giro, cambio de postura y quietud"
        } else {
            "impacto fuerte, cambio de postura y quietud"
        }
        return FallEvidence(
            isFall = rejection == null,
            reason = reason,
            freeFallMs = candidate.freeFallNs / NS_PER_MS,
            weightlessMs = weightlessNs / NS_PER_MS,
            minAcceleration = minAcceleration,
            peakAcceleration = candidate.impact,
            peakGyroscope = candidate.rotation,
            orientationChangeDeg = orientationChange,
        )
    }

    /** El evento más reciente que parece una caída: caída libre + impacto/giro, o un impacto muy fuerte. */
    private fun findCandidate(freeFalls: List<LowRun>, windowStart: Long, windowEnd: Long): Candidate? {
        for (run in freeFalls.asReversed()) {
            if (run.durationNs < MIN_FREE_FALL_NS) continue
            val impact = peakAcceleration(run.start, run.end + AFTER_FREE_FALL_NS) ?: continue
            val rotation = peakGyroscope(run.start - PRE_POSTURE_GAP_NS, run.end + AFTER_FREE_FALL_NS)
            if (impact.magnitude >= IMPACT_AFTER_FREE_FALL_MPS2 || rotation >= FALL_ROTATION_RAD_PER_SEC) {
                return Candidate(run.start, impact.t, impact.magnitude, rotation, run.durationNs)
            }
        }
        val impact = peakAcceleration(windowStart, windowEnd) ?: return null
        if (impact.magnitude < IMPACT_WITHOUT_FREE_FALL_MPS2) return null
        return Candidate(
            start = impact.t - IMPACT_ONLY_FALL_NS,
            impactAt = impact.t,
            impact = impact.magnitude,
            rotation = peakGyroscope(impact.t - IMPACT_ONLY_FALL_NS, impact.t + AFTER_FREE_FALL_NS),
            freeFallNs = 0L,
        )
    }

    /** Rachas en las que la aceleración total estuvo por debajo de [threshold]. */
    private fun lowRuns(from: Long, to: Long, threshold: Float): List<LowRun> {
        val runs = mutableListOf<LowRun>()
        var runStart = 0L
        var lastT = 0L
        for (sample in accelerations) {
            if (sample.t < from) continue
            if (sample.t > to) break
            lastT = sample.t
            if (sample.magnitude < threshold) {
                if (runStart == 0L) runStart = sample.t
            } else if (runStart != 0L) {
                runs += LowRun(runStart, sample.t)
                runStart = 0L
            }
        }
        if (runStart != 0L) runs += LowRun(runStart, lastT)
        return runs
    }

    private fun longestRun(from: Long, to: Long, threshold: Float): Long =
        lowRuns(from, to, threshold).maxOfOrNull { it.durationNs } ?: 0L

    private fun peakAcceleration(from: Long, to: Long): AccelerationSample? =
        accelerations.filter { it.t in from..to }.maxByOrNull { it.magnitude }

    private fun peakGyroscope(from: Long, to: Long): Float =
        gyroscopes.filter { it.t in from..to }.maxOfOrNull { it.magnitude } ?: 0f

    /** Promedio de la aceleración en un intervalo: con poco movimiento, es la dirección de la gravedad. */
    private fun meanVector(from: Long, to: Long): FloatArray? {
        var x = 0f
        var y = 0f
        var z = 0f
        var count = 0
        for (sample in accelerations) {
            if (sample.t < from) continue
            if (sample.t > to) break
            x += sample.x
            y += sample.y
            z += sample.z
            count++
        }
        return if (count < MIN_POSTURE_SAMPLES) null else floatArrayOf(x / count, y / count, z / count)
    }

    private fun angleDeg(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null) return Float.NaN
        val normA = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val normB = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (normA < 1f || normB < 1f) return Float.NaN
        val cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (normA * normB)
        return Math.toDegrees(acos(cos.coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    private companion object {
        // Valores iniciales para experimentar; hay que ajustarlos al teléfono y a su ubicación.
        // Cada evaluación queda en Logcat (etiqueta FallDetector) con los valores medidos.
        const val EARTH_GRAVITY_MPS2 = 9.81f
        const val FREE_FALL_MPS2 = 6.0f // ~0.6 g
        const val WEIGHTLESS_MPS2 = 3.0f // ~0.3 g: casi ingravidez
        const val IMPACT_AFTER_FREE_FALL_MPS2 = 16f // ~1.6 g (las colchonetas amortiguan mucho)
        const val IMPACT_WITHOUT_FREE_FALL_MPS2 = 24f // ~2.5 g
        const val FALL_ROTATION_RAD_PER_SEC = 2.5f
        const val MIN_ORIENTATION_CHANGE_DEG = 45f
        const val STILL_ACCELERATION_TOLERANCE_MPS2 = 2.5f
        const val STILL_GYROSCOPE_RAD_PER_SEC = 0.5f

        const val NS_PER_MS = 1_000_000L
        const val MIN_FREE_FALL_NS = 80_000_000L
        const val MAX_WEIGHTLESS_NS = 150_000_000L
        const val AFTER_FREE_FALL_NS = 1_000_000_000L
        const val IMPACT_ONLY_FALL_NS = 500_000_000L
        const val STILLNESS_REQUIRED_NS = 2_000_000_000L
        const val MOVEMENT_BREAKS_STILLNESS_NS = 200_000_000L
        const val LOOKBACK_NS = 4_000_000_000L
        const val PRE_POSTURE_NS = 1_500_000_000L
        const val PRE_POSTURE_GAP_NS = 200_000_000L
        const val HISTORY_NS = 12_000_000_000L
        const val MAX_GAP_NS = 500_000_000L
        const val GYRO_FRESHNESS_NS = 500_000_000L
        const val MIN_POSTURE_SAMPLES = 10
    }
}
