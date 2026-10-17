package com.example.deteccioncaidas

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var statusText: TextView
    private lateinit var accelerationText: TextView
    private lateinit var gyroscopeText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val fallDetector = FallDetector { runOnUiThread { showFallConfirmation() } }
    private var isMonitoring = false
    private var fallDialog: AlertDialog? = null
    private var fallCountdown: CountDownTimer? = null
    private var fallAlertVisible = false
    private var lastAccelerationLogAtNs = 0L
    private var lastGyroscopeLogAtNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        buildScreen()
        updateSensorAvailability()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }

        content.addView(TextView(this).apply {
            text = "Detector de caídas"
            textSize = 26f
            setTextColor(Color.rgb(25, 40, 60))
        }, matchWidthWrapHeight())

        content.addView(TextView(this).apply {
            text = "Prototipo con acelerómetro y giroscopio. Mantén esta pantalla abierta durante la medición. Se recomienda llevar el teléfono contigo."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(20))
        }, matchWidthWrapHeight())

        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(30, 90, 60))
            setPadding(0, 0, 0, dp(16))
        }
        content.addView(statusText, matchWidthWrapHeight())

        accelerationText = valueText("Acelerómetro (m/s²): esperando datos")
        gyroscopeText = valueText("Giroscopio (rad/s): esperando datos")
        content.addView(accelerationText, matchWidthWrapHeight())
        content.addView(gyroscopeText, matchWidthWrapHeight())
        content.addView(TextView(this).apply {
            text = "X, Y y Z son los valores de cada eje. El giroscopio mide velocidad de giro, no el ángulo acumulado."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(4))
        }, matchWidthWrapHeight())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, dp(12))
        }
        startButton = Button(this).apply {
            text = "Iniciar"
            setOnClickListener { startMonitoring() }
        }
        stopButton = Button(this).apply {
            text = "Detener"
            isEnabled = false
            setOnClickListener { stopMonitoring() }
        }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(buttons, matchWidthWrapHeight())

        content.addView(TextView(this).apply {
            text = "La cuenta regresiva es solo una demostración: esta app todavía no llama ni envía mensajes. Los umbrales son iniciales y pueden producir falsas alarmas o no detectar algunas caídas."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, 0)
        }, matchWidthWrapHeight())

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun valueText(initialText: String) = TextView(this).apply {
        text = initialText
        textSize = 17f
        setTextColor(Color.BLACK)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun updateSensorAvailability() {
        val missing = buildList {
            if (accelerometer == null) add("acelerómetro")
            if (gyroscope == null) add("giroscopio")
        }
        if (missing.isNotEmpty()) {
            statusText.text = "Este teléfono no tiene ${missing.joinToString(" y ")} disponible."
            startButton.isEnabled = false
        } else {
            statusText.text = "Listo para iniciar."
        }
    }

    private fun startMonitoring() {
        val accel = accelerometer ?: return
        val gyro = gyroscope ?: return
        fallDetector.reset()

        val accelStarted = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        val gyroStarted = sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        if (!accelStarted || !gyroStarted) {
            sensorManager.unregisterListener(this)
            statusText.text = "No se pudieron activar los dos sensores."
            return
        }

        isMonitoring = true
        lastAccelerationLogAtNs = 0L
        lastGyroscopeLogAtNs = 0L
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "Midiendo. Se recomienda llevarlo en el bolsillo del pantalón."
    }

    private fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        isMonitoring = false
        fallDetector.reset()
        startButton.isEnabled = accelerometer != null && gyroscope != null
        stopButton.isEnabled = false
        fallCountdown?.cancel()
        fallCountdown = null
        fallDialog?.dismiss()
        fallDialog = null
        fallAlertVisible = false
        statusText.text = "Medición detenida."
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isMonitoring) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2],
                )
                accelerationText.text =
                    "Acelerómetro (m/s²)\nX: ${format(event.values[0])}  Y: ${format(event.values[1])}  Z: ${format(event.values[2])}\nMagnitud: ${format(magnitude)}"
                if (event.timestamp - lastAccelerationLogAtNs >= SENSOR_LOG_INTERVAL_NS) {
                    Log.d(
                        SENSOR_LOG_TAG,
                        "ACCEL x=${format(event.values[0])} y=${format(event.values[1])} z=${format(event.values[2])} magnitude=${format(magnitude)} m/s²",
                    )
                    lastAccelerationLogAtNs = event.timestamp
                }
                if (!fallAlertVisible) {
                    fallDetector.onAccelerometer(
                        event.values[0], event.values[1], event.values[2], event.timestamp,
                    )
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2],
                )
                gyroscopeText.text =
                    "Giroscopio (rad/s)\nX: ${format(event.values[0])}  Y: ${format(event.values[1])}  Z: ${format(event.values[2])}\nMagnitud: ${format(magnitude)}"
                if (event.timestamp - lastGyroscopeLogAtNs >= SENSOR_LOG_INTERVAL_NS) {
                    Log.d(
                        SENSOR_LOG_TAG,
                        "GYRO x=${format(event.values[0])} y=${format(event.values[1])} z=${format(event.values[2])} magnitude=${format(magnitude)} rad/s",
                    )
                    lastGyroscopeLogAtNs = event.timestamp
                }
                if (!fallAlertVisible) {
                    fallDetector.onGyroscope(
                        event.values[0], event.values[1], event.values[2], event.timestamp,
                    )
                }
            }
        }
    }

    private fun showFallConfirmation() {
        if (fallAlertVisible || !isMonitoring || isFinishing) return
        fallAlertVisible = true
        statusText.text = "Posible caída detectada. Confirma que estás bien."

        val dialog = AlertDialog.Builder(this)
            .setTitle("¿Estás bien?")
            .setMessage("Posible caída. La alerta de prueba se confirmará en 15 segundos.")
            .setPositiveButton("Estoy bien") { _, _ ->
                finishFallAlert("Alerta cancelada; medición activa.")
            }
            .setNegativeButton("Confirmar caída") { _, _ ->
                finishFallAlert("Caída confirmada (demo). No se envió ningún aviso.")
            }
            .setOnCancelListener { finishFallAlert("Diálogo cerrado; medición activa.") }
            .create()
        fallDialog = dialog
        dialog.show()

        fallCountdown = object : CountDownTimer(ALERT_COUNTDOWN_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                dialog.setMessage(
                    "Posible caída. La alerta de prueba se confirmará en ${millisUntilFinished / 1_000L} segundos.",
                )
            }

            override fun onFinish() {
                dialog.dismiss()
                fallAlertVisible = false
                fallDialog = null
                fallCountdown = null
                if (isMonitoring) {
                    statusText.text = "Alerta de prueba confirmada. No se envió ningún aviso."
                }
            }
        }.start()
    }

    private fun finishFallAlert(message: String) {
        fallCountdown?.cancel()
        fallCountdown = null
        fallDialog = null
        fallAlertVisible = false
        if (isMonitoring) statusText.text = message
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onPause() {
        super.onPause()
        if (isMonitoring) stopMonitoring()
    }

    override fun onDestroy() {
        fallCountdown?.cancel()
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    private fun format(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val ALERT_COUNTDOWN_MS = 15_000L
        const val SENSOR_LOG_INTERVAL_NS = 500_000_000L
        const val SENSOR_LOG_TAG = "FallSensors"
    }
}
