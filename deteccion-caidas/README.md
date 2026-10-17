# Detector de caídas para Android

Prototipo en Kotlin que lee el acelerómetro y el giroscopio mientras la pantalla de la app está abierta.

## Abrir y ejecutar

1. Abre esta carpeta en Android Studio y espera a que termine la sincronización de Gradle.
2. Conecta un teléfono Android, habilita la depuración USB y pulsa **Run**.
3. En la app, pulsa **Iniciar**. Los valores se muestran en m/s² para el acelerómetro y rad/s para el giroscopio.

El prototipo necesita que el teléfono incluya ambos sensores. Se recomienda llevarlo en el bolsillo del pantalón para que se mueva junto con la persona. El detector usa la magnitud de los tres ejes, así que la orientación del teléfono influye menos; la ubicación todavía puede cambiar las lecturas y requiere validación. La app también muestra X, Y y Z por separado y escribe muestras cada medio segundo en Logcat con la etiqueta `FallSensors`.

## Cómo decide que podría haber una caída

`FallDetector.kt` acepta dos patrones: aceleración muy baja seguida de un impacto, o un impacto fuerte aunque no se haya registrado la etapa de baja aceleración. Después requiere movimiento angular (o un impacto extremadamente fuerte) y dos segundos de poca actividad. Los valores iniciales están en las constantes de esa clase para poder ajustarlos con lecturas reales.

La app pide confirmar o cancelar la alerta durante 15 segundos. Al terminar, solo muestra un mensaje en pantalla: todavía no llama ni envía mensajes. La medición se detiene al cerrar o dejar la app en segundo plano.

Esto es una demostración, no un sistema validado de seguridad. No lo uses para depender de una alerta de emergencia ni provoques caídas para probarlo. Primero registra actividades normales y movimientos controlados del teléfono, y ajusta los umbrales para el dispositivo y la ubicación elegida.
