package com.example.deteccioncaidas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Secuencias sintéticas a 100 Hz con el teléfono en el bolsillo delantero del pantalón:
 * de pie la gravedad cae sobre el eje Y del teléfono; acostado (muslo horizontal), sobre el eje Z.
 */
class FallDetectorTest {
    private val falls = mutableListOf<FallEvidence>()
    private val evaluations = mutableListOf<FallEvidence>()
    private val detector = FallDetector(onFallDetected = { falls += it }, onEvaluation = { evaluations += it })
    private val random = Random(7)
    private var t = 1_000_000_000L

    /** Alimenta [ms] milisegundos. [tilt] es el ángulo del muslo (0° de pie, 90° acostado). */
    private fun phase(
        ms: Int,
        magnitudeG: (Float) -> Float,
        tilt: (Float) -> Float,
        gyro: (Float) -> Float = { 0.05f },
        noise: Float = 0.1f,
    ) {
        val steps = ms / 10
        repeat(steps) { i ->
            val p = i.toFloat() / steps
            val a = magnitudeG(p) * G
            val angle = Math.toRadians(tilt(p).toDouble())
            detector.onGyroscope(gyro(p), 0f, 0f, t)
            detector.onAccelerometer(
                noise * random.nextGaussian().toFloat(),
                (a * cos(angle)).toFloat() + noise * random.nextGaussian().toFloat(),
                (a * sin(angle)).toFloat() + noise * random.nextGaussian().toFloat(),
                t,
            )
            t += 10_000_000L
        }
    }

    private fun stand(ms: Int) = phase(ms, { 1f }, { 5f })
    private fun lieStill(ms: Int) = phase(ms, { 1f }, { 88f })

    private fun walk(ms: Int) = phase(
        ms,
        magnitudeG = { p -> 1f + 0.35f * sin(2 * PI * 1.8 * p * ms / 1000).toFloat() },
        tilt = { p -> 10f + 20f * sin(2 * PI * 0.9 * p * ms / 1000).toFloat() },
        gyro = { p -> 3f * abs(sin(2 * PI * 0.9 * p * ms / 1000)).toFloat() },
        noise = 0.3f,
    )

    /** Tropiezo, caída libre parcial, golpe amortiguado por colchoneta y rebote. */
    private fun fallOntoMat() {
        phase(300, { 1.15f }, { p -> 5f + 10f * p }, { 1.5f })
        phase(250, { 0.35f }, { p -> 15f + 55f * p }, { 4f })
        phase(80, { 1.9f }, { 80f }, { 3f })
        phase(700, { p -> 1.25f - 0.25f * p }, { 88f }, { p -> 0.9f * (1 - p) + 0.05f }, noise = 0.4f)
    }

    @Test
    fun fallOntoMat_isDetectedOnce() {
        stand(3000)
        fallOntoMat()
        lieStill(10_000)

        assertEquals(1, falls.size)
        val evidence = falls.single()
        assertTrue(evidence.freeFallMs >= 80)
        assertTrue(evidence.orientationChangeDeg > 60f)
    }

    @Test
    fun fallOntoThickMat_isDetectedByRotation() {
        stand(3000)
        phase(300, { 1.1f }, { p -> 5f + 10f * p }, { 1.5f })
        phase(300, { 0.45f }, { p -> 15f + 60f * p }, { 4f })
        // El golpe apenas llega a 1.5 g: lo que delata la caída es el giro.
        phase(150, { 1.5f }, { 85f }, { 2.5f })
        phase(600, { 1.1f }, { 88f }, { 0.5f }, noise = 0.3f)
        lieStill(4000)

        assertEquals(1, falls.size)
        assertTrue(falls.single().peakAcceleration < 16f)
    }

    @Test
    fun hardFallWithoutFreeFall_isDetected() {
        stand(3000)
        phase(400, { 0.8f }, { p -> 5f + 70f * p }, { 3f })
        phase(60, { 2.9f }, { 85f }, { 2f })
        phase(500, { 1.1f }, { 88f }, { 0.6f }, noise = 0.4f)
        lieStill(4000)

        assertEquals(1, falls.size)
        assertEquals(0L, falls.single().freeFallMs)
    }

    @Test
    fun sittingDownAfterWalking_isNotAFall() {
        walk(5000)
        // Sentarse: el muslo gira a horizontal sin caída libre ni golpe fuerte.
        phase(1200, { p -> if (p < 0.6f) 0.8f else 1.4f }, { p -> 20f + 70f * p }, { 1.8f })
        lieStill(5000)

        assertTrue(falls.isEmpty())
        assertTrue(evaluations.isNotEmpty())
    }

    @Test
    fun walkingThenStandingStill_isNotAFall() {
        walk(8000)
        stand(5000)

        assertTrue(falls.isEmpty())
    }

    @Test
    fun droppedPhone_isNotAFall() {
        phase(3000, { 1f }, { 40f }, { 0.3f })
        phase(400, { 0.02f }, { p -> 40f + 50f * p }, { 6f })
        phase(40, { 6f }, { 90f }, { 4f })
        phase(4000, { 1f }, { 90f }, noise = 0.02f)

        assertTrue(falls.isEmpty())
        assertTrue(evaluations.last().reason.contains("ingravidez"))
    }

    @Test
    fun jump_isNotAFall() {
        stand(3000)
        phase(150, { 1.8f }, { 5f }, { 1f })
        phase(350, { 0.05f }, { 5f }, { 0.5f })
        phase(80, { 3.5f }, { 5f }, { 1f })
        stand(4000)

        assertTrue(falls.isEmpty())
    }

    @Test
    fun lyingDownSlowly_isNotAFall() {
        stand(3000)
        phase(2500, { 1f }, { p -> 5f + 83f * p }, { 0.9f })
        lieStill(5000)

        assertTrue(falls.isEmpty())
    }

    @Test
    fun fallThenStandingUpQuickly_isNotAFall() {
        stand(3000)
        fallOntoMat()
        lieStill(500)
        phase(1200, { 1.1f }, { p -> 88f - 83f * p }, { 2f }, noise = 0.4f)
        stand(5000)

        assertTrue(falls.isEmpty())
        assertFalse(evaluations.any { it.isFall })
    }

    @Test
    fun gapInData_clearsHistory() {
        stand(3000)
        fallOntoMat()
        t += 2_000_000_000L // el sensor se detuvo dos segundos
        lieStill(5000)

        assertTrue(falls.isEmpty())
    }

    private companion object {
        const val G = 9.81f
    }
}
