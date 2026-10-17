package com.example.deteccioncaidas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import java.util.Locale

/** Estado compartido entre el servicio y la pantalla. Se usa solo desde el hilo principal. */
internal object MonitorState {
    interface Listener {
        fun onReadings(acceleration: FloatArray, gyroscope: FloatArray)
        fun onStatusChanged()
        fun onEvaluation(evidence: FallEvidence)
    }

    var listener: Listener? = null
    var monitoring = false
    var status = "Listo para iniciar."
    var alertDeadlineMs: Long? = null // SystemClock.elapsedRealtime() en que se confirma la alerta
    var lastEvaluation: FallEvidence? = null
}

/**
 * Lee el acelerómetro y el giroscopio en un servicio en primer plano. Desde Android 9 una app
 * en segundo plano deja de recibir estos sensores, así que la medición vive aquí para seguir
 * funcionando con la pantalla apagada y el teléfono en el bolsillo.
 */
class FallMonitorService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val fallDetector = FallDetector(onFallDetected = ::startAlert, onEvaluation = ::publishEvaluation)
    private val confirmAlertOnTimeout = Runnable {
        finishAlert("Alerta de prueba confirmada. No se envió ningún aviso.")
    }

    private val latestAcceleration = FloatArray(3)
    private val latestGyroscope = FloatArray(3)
    private var lastReadingsPublishedAtNs = 0L
    private var lastAccelerationLogAtNs = 0L
    private var lastGyroscopeLogAtNs = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring("Medición detenida.")
            ACTION_CANCEL_ALERT -> finishAlert("Alerta cancelada; medición activa.")
            ACTION_CONFIRM_ALERT -> finishAlert("Caída confirmada (demo). No se envió ningún aviso.")
        }
        if (!MonitorState.monitoring) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (MonitorState.monitoring) stopMonitoring("La medición se detuvo.")
        super.onDestroy()
    }

    private fun startMonitoring() {
        // startForeground va primero (también si ya se está midiendo): Android cierra la app si
        // un servicio iniciado con startForegroundService() no lo llama a tiempo.
        try {
            val notification = monitoringNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(MONITORING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(MONITORING_NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            Log.e(SENSOR_LOG_TAG, "No se pudo iniciar el servicio en primer plano", e)
            publishStatus("No se pudo iniciar la medición en segundo plano: ${e.message}")
            return
        }
        if (MonitorState.monitoring) return

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        fallDetector.reset()
        val started = accelerometer != null && gyroscope != null &&
            sensorManager.registerListener(this, accelerometer, SAMPLING_PERIOD_US) &&
            sensorManager.registerListener(this, gyroscope, SAMPLING_PERIOD_US)
        if (!started) {
            sensorManager.unregisterListener(this)
            removeForegroundNotification()
            publishStatus("No se pudieron activar los dos sensores.")
            return
        }

        // Sin esto el procesador se duerme con la pantalla apagada y los sensores dejan de llegar.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeteccionCaidas:medicion")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_MONITORING_MS)
            }
        lastReadingsPublishedAtNs = 0L
        lastAccelerationLogAtNs = 0L
        lastGyroscopeLogAtNs = 0L
        MonitorState.monitoring = true
        publishStatus("Midiendo. Puedes apagar la pantalla y guardar el teléfono en el bolsillo del pantalón.")
    }

    private fun stopMonitoring(message: String) {
        sensorManager.unregisterListener(this)
        fallDetector.reset()
        handler.removeCallbacks(confirmAlertOnTimeout)
        MonitorState.alertDeadlineMs = null
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        MonitorState.monitoring = false
        removeForegroundNotification()
        publishStatus(message)
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!MonitorState.monitoring) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAcceleration[0] = x
                latestAcceleration[1] = y
                latestAcceleration[2] = z
                if (event.timestamp - lastAccelerationLogAtNs >= SENSOR_LOG_INTERVAL_NS) {
                    Log.d(SENSOR_LOG_TAG, "ACCEL x=${format(x)} y=${format(y)} z=${format(z)} m/s²")
                    lastAccelerationLogAtNs = event.timestamp
                }
                fallDetector.onAccelerometer(x, y, z, event.timestamp)
                if (event.timestamp - lastReadingsPublishedAtNs >= READINGS_INTERVAL_NS) {
                    MonitorState.listener?.onReadings(latestAcceleration, latestGyroscope)
                    lastReadingsPublishedAtNs = event.timestamp
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                latestGyroscope[0] = x
                latestGyroscope[1] = y
                latestGyroscope[2] = z
                if (event.timestamp - lastGyroscopeLogAtNs >= SENSOR_LOG_INTERVAL_NS) {
                    Log.d(SENSOR_LOG_TAG, "GYRO x=${format(x)} y=${format(y)} z=${format(z)} rad/s")
                    lastGyroscopeLogAtNs = event.timestamp
                }
                fallDetector.onGyroscope(x, y, z, event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publishEvaluation(evidence: FallEvidence) {
        Log.i(DETECTOR_LOG_TAG, evidence.summary().replace('\n', ' '))
        MonitorState.lastEvaluation = evidence
        MonitorState.listener?.onEvaluation(evidence)
    }

    private fun startAlert(evidence: FallEvidence) {
        // Mientras hay una alerta abierta no se acumulan otras.
        if (MonitorState.alertDeadlineMs != null) return
        MonitorState.alertDeadlineMs = SystemClock.elapsedRealtime() + ALERT_COUNTDOWN_MS
        handler.postDelayed(confirmAlertOnTimeout, ALERT_COUNTDOWN_MS)
        notificationManager.notify(ALERT_NOTIFICATION_ID, alertNotification(evidence))
        vibrate()
        publishStatus("Posible caída detectada. Confirma que estás bien.")
    }

    private fun finishAlert(message: String) {
        if (MonitorState.alertDeadlineMs == null) return
        handler.removeCallbacks(confirmAlertOnTimeout)
        MonitorState.alertDeadlineMs = null
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
        publishStatus(message)
    }

    private fun publishStatus(message: String) {
        MonitorState.status = message
        MonitorState.listener?.onStatusChanged()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(MONITORING_CHANNEL_ID, "Medición activa", NotificationManager.IMPORTANCE_LOW),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Posibles caídas", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = ALERT_VIBRATION
            },
        )
    }

    private fun monitoringNotification(): Notification =
        notificationBuilder(MONITORING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Detector de caídas activo")
            .setContentText("Midiendo acelerómetro y giroscopio.")
            .setContentIntent(activityIntent(REQUEST_OPEN, null))
            .setOngoing(true)
            .build()

    private fun alertNotification(evidence: FallEvidence): Notification {
        val showAlert = activityIntent(REQUEST_SHOW_ALERT, MainActivity.ALERT_SHOW)
        return notificationBuilder(ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("¿Estás bien?")
            .setContentText("Posible caída. La alerta de prueba se confirmará en 15 segundos.")
            .setStyle(Notification.BigTextStyle().bigText(evidence.summary()))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(showAlert)
            .setFullScreenIntent(showAlert, true)
            .addAction(alertAction("Estoy bien", REQUEST_CANCEL_ALERT, MainActivity.ALERT_CANCEL))
            .addAction(alertAction("Confirmar caída", REQUEST_CONFIRM_ALERT, MainActivity.ALERT_CONFIRM))
            .setAutoCancel(true)
            .apply {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) setPriority(Notification.PRIORITY_MAX)
            }
            .build()
    }

    private fun alertAction(title: String, requestCode: Int, response: String) =
        Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_dialog_alert),
            title,
            activityIntent(requestCode, response),
        ).build()

    private fun activityIntent(requestCode: Int, alertResponse: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (alertResponse != null) intent.putExtra(MainActivity.EXTRA_ALERT_RESPONSE, alertResponse)
        return PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun vibrate() {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(ALERT_VIBRATION, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ALERT_VIBRATION, -1)
        }
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    companion object {
        const val ACTION_START = "com.example.deteccioncaidas.INICIAR"
        const val ACTION_STOP = "com.example.deteccioncaidas.DETENER"
        const val ACTION_CANCEL_ALERT = "com.example.deteccioncaidas.CANCELAR_ALERTA"
        const val ACTION_CONFIRM_ALERT = "com.example.deteccioncaidas.CONFIRMAR_ALERTA"
        const val ALERT_COUNTDOWN_MS = 15_000L

        private const val SAMPLING_PERIOD_US = 10_000 // 100 Hz: a 50 Hz se pierden picos de impacto
        private const val READINGS_INTERVAL_NS = 100_000_000L
        private const val SENSOR_LOG_INTERVAL_NS = 500_000_000L
        private const val MAX_MONITORING_MS = 12 * 60 * 60 * 1_000L
        private const val SENSOR_LOG_TAG = "FallSensors"
        private const val DETECTOR_LOG_TAG = "FallDetector"
        private const val MONITORING_CHANNEL_ID = "medicion"
        private const val ALERT_CHANNEL_ID = "alertas"
        private const val MONITORING_NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val REQUEST_OPEN = 0
        private const val REQUEST_SHOW_ALERT = 1
        private const val REQUEST_CANCEL_ALERT = 2
        private const val REQUEST_CONFIRM_ALERT = 3
        private val ALERT_VIBRATION = longArrayOf(0, 600, 300, 600, 300, 600)
    }
}
