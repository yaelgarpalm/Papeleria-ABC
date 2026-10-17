package com.example.deteccioncaidas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var accelerationText: TextView
    private lateinit var gyroscopeText: TextView
    private lateinit var evaluationText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var sensorsAvailable = false
    private var fallDialog: AlertDialog? = null
    private var fallCountdown: CountDownTimer? = null
    private val stateListener = object : MonitorState.Listener {
        override fun onReadings(acceleration: FloatArray, gyroscope: FloatArray) = showReadings(acceleration, gyroscope)
        override fun onStatusChanged() = showStatus()
        override fun onEvaluation(evidence: FallEvidence) = showEvaluation(evidence)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val missing = buildList {
            if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) add("acelerómetro")
            if (sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null) add("giroscopio")
        }
        sensorsAvailable = missing.isEmpty()
        if (!sensorsAvailable) MonitorState.status = "Este teléfono no tiene ${missing.joinToString(" y ")} disponible."

        buildScreen()
        if (savedInstanceState == null) handleAlertResponse(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAlertResponse(intent)
    }

    override fun onStart() {
        super.onStart()
        MonitorState.listener = stateListener
        showStatus()
        MonitorState.lastEvaluation?.let(::showEvaluation)
    }

    override fun onStop() {
        // La medición sigue en FallMonitorService; aquí solo se deja de actualizar la pantalla.
        if (MonitorState.listener === stateListener) MonitorState.listener = null
        dismissFallDialog()
        super.onStop()
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
            text = "Prototipo con acelerómetro y giroscopio. La medición sigue con la pantalla apagada; lleva el teléfono en el bolsillo del pantalón."
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

        evaluationText = TextView(this).apply {
            text = "Última evaluación: todavía ninguna. Se revisa cada vez que el teléfono se queda quieto 2 segundos."
            textSize = 14f
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(evaluationText, matchWidthWrapHeight())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, dp(12))
        }
        startButton = Button(this).apply {
            text = "Iniciar"
            setOnClickListener { requestNotificationsThenStart() }
        }
        stopButton = Button(this).apply {
            text = "Detener"
            isEnabled = false
            setOnClickListener { sendToService(FallMonitorService.ACTION_STOP) }
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

    /** Sin el permiso de notificaciones, la alerta no se ve con la pantalla apagada. */
    private fun requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
        } else {
            startMonitoring()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATIONS_REQUEST) startMonitoring()
    }

    private fun startMonitoring() {
        startButton.isEnabled = false
        val intent = Intent(this, FallMonitorService::class.java).setAction(FallMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun sendToService(action: String) {
        startService(Intent(this, FallMonitorService::class.java).setAction(action))
    }

    /** Respuesta elegida desde la notificación de alerta. */
    private fun handleAlertResponse(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_ALERT_RESPONSE)) {
            ALERT_CANCEL -> sendToService(FallMonitorService.ACTION_CANCEL_ALERT)
            ALERT_CONFIRM -> sendToService(FallMonitorService.ACTION_CONFIRM_ALERT)
            ALERT_SHOW -> showOverLockScreen(true)
        }
        intent?.removeExtra(EXTRA_ALERT_RESPONSE)
    }

    private fun showReadings(acceleration: FloatArray, gyroscope: FloatArray) {
        accelerationText.text = "Acelerómetro (m/s²)\n${axes(acceleration)}\nMagnitud: ${format(magnitude(acceleration))}"
        gyroscopeText.text = "Giroscopio (rad/s)\n${axes(gyroscope)}\nMagnitud: ${format(magnitude(gyroscope))}"
    }

    private fun showStatus() {
        val monitoring = MonitorState.monitoring
        statusText.text = MonitorState.status
        startButton.isEnabled = sensorsAvailable && !monitoring
        stopButton.isEnabled = monitoring
        val deadline = MonitorState.alertDeadlineMs
        if (deadline != null) showFallConfirmation(deadline) else dismissFallDialog()
    }

    private fun showEvaluation(evidence: FallEvidence) {
        evaluationText.text = "Última evaluación\n${evidence.summary()}"
    }

    private fun showFallConfirmation(deadlineMs: Long) {
        if (fallDialog != null || isFinishing) return
        val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0) return

        val dialog = AlertDialog.Builder(this)
            .setTitle("¿Estás bien?")
            .setMessage(countdownMessage(remainingMs))
            .setPositiveButton("Estoy bien") { _, _ -> sendToService(FallMonitorService.ACTION_CANCEL_ALERT) }
            .setNegativeButton("Confirmar caída") { _, _ -> sendToService(FallMonitorService.ACTION_CONFIRM_ALERT) }
            .setCancelable(false)
            .create()
        fallDialog = dialog
        dialog.show()

        // El servicio decide cuándo vence la alerta; aquí solo se muestra el tiempo restante.
        fallCountdown = object : CountDownTimer(remainingMs, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                dialog.setMessage(countdownMessage(millisUntilFinished))
            }

            override fun onFinish() = Unit
        }.start()
    }

    private fun dismissFallDialog() {
        fallCountdown?.cancel()
        fallCountdown = null
        fallDialog?.dismiss()
        fallDialog = null
        showOverLockScreen(false)
    }

    private fun showOverLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    private fun countdownMessage(remainingMs: Long) =
        "Posible caída. La alerta de prueba se confirmará en ${(remainingMs + 999) / 1_000L} segundos."

    private fun axes(values: FloatArray) =
        "X: ${format(values[0])}  Y: ${format(values[1])}  Z: ${format(values[2])}"

    private fun magnitude(values: FloatArray) =
        sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])

    private fun format(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_ALERT_RESPONSE = "respuesta_alerta"
        const val ALERT_SHOW = "mostrar"
        const val ALERT_CANCEL = "cancelar"
        const val ALERT_CONFIRM = "confirmar"
        private const val NOTIFICATIONS_REQUEST = 1
    }
}
