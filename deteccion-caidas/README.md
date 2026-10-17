# Detector de caídas para Android

Prototipo en Kotlin que lee el acelerómetro y el giroscopio del teléfono para detectar caídas. La medición sigue funcionando con la pantalla apagada y el teléfono en el bolsillo.

## Abrir y ejecutar

1. Abre esta carpeta en Android Studio y espera a que termine la sincronización de Gradle.
2. Conecta un teléfono Android, habilita la depuración USB y pulsa **Run**.
3. En la app pulsa **Iniciar** y acepta el permiso de notificaciones (Android 13+). Sin ese permiso la alerta no aparece con la pantalla apagada.
4. Guarda el teléfono en el **bolsillo del pantalón** (o en la cintura). Puedes bloquear la pantalla.

El prototipo necesita que el teléfono tenga ambos sensores. Los valores se muestran en m/s² para el acelerómetro y rad/s para el giroscopio.

## Qué cambió y por qué no detectaba

1. **La medición se detenía al apagar la pantalla.** `MainActivity` registraba los sensores y los quitaba en `onPause()`. Desde Android 9, además, una app en segundo plano deja de recibir el acelerómetro y el giroscopio. Al guardar el teléfono en el bolsillo la app ya no medía nada. Ahora la medición vive en `FallMonitorService`, un servicio en primer plano con notificación fija y *wake lock* parcial.
2. **La secuencia se perdía con facilidad.** El detector anterior exigía un impacto mayor que 18 m/s² después de la caída libre. Sobre colchonetas el golpe suele quedarse por debajo, así que el candidato se descartaba. Si la caída libre duraba menos de 80 ms y el golpe estaba entre 18 y 30 m/s², también se descartaba, aunque fuera un impacto fuerte.
3. **Faltaba comprobar la postura.** Bastaba "golpe + quietud", así que soltar el teléfono sobre la mesa, saltar o caerse y levantarse enseguida también disparaban la alerta.
4. **Se leía a ~50 Hz** (`SENSOR_DELAY_GAME`), y un impacto dura entre 20 y 50 ms. Ahora se piden 100 Hz.

## Cómo decide que hubo una caída

`FallDetector.kt` busca esta secuencia:

| Paso | Qué mide | Umbral inicial |
|---|---|---|
| 1. Caída libre | la aceleración total baja de ~0.6 g | < 6 m/s² durante ≥ 80 ms |
| 2. Impacto o giro | en el segundo siguiente a la caída libre | ≥ 16 m/s² **o** giro ≥ 2.5 rad/s |
| 2b. (alternativa) Impacto fuerte | si no se registró la caída libre | ≥ 24 m/s² |
| 3. Cambio de postura | ángulo entre la gravedad antes de caer y durante la quietud (de pie → acostado) | ≥ 45° |
| 4. Quietud | casi sin movimiento | 2 s con \|a − 9.81\| < 2.5 m/s² y giro < 0.5 rad/s |

Se descarta si hubo **ingravidez** (< 3 m/s²) durante ≥ 150 ms. Eso pasa al soltar el teléfono o al saltar, no en la caída de una persona.

La revisión se hace **hacia atrás**: cada vez que el teléfono cumple 2 s de quietud se buscan los pasos 1–3 en los 4 s anteriores. Así no importa cuánto rebote o se mueva la persona antes de quedarse quieta.

Al detectar una caída, el servicio vibra, muestra una notificación (de pantalla completa si el sistema lo permite) y abre una cuenta regresiva de 15 segundos con **Estoy bien** / **Confirmar caída**. Todavía no llama ni envía mensajes.

## Calibrar con tus propias lecturas

Cada evaluación queda en la pantalla ("Última evaluación") y en Logcat con la etiqueta `FallDetector`, por ejemplo:

```
CAÍDA: caída libre, impacto o giro, cambio de postura y quietud Caída libre 250 ms (ingravidez 0 ms) · mín 3.3 m/s² Impacto 18.8 m/s² · giro 4.0 rad/s · postura 83°
```

Las muestras crudas siguen en Logcat con la etiqueta `FallSensors`, cada medio segundo. Si una caída de prueba no se detecta, el mensaje dice qué paso faltó. Ajusta la constante correspondiente en el `companion object` de `FallDetector`.

## Pruebas automáticas

`app/src/test/.../FallDetectorTest.kt` simula a 100 Hz los casos siguientes:

- caída sobre colchoneta
- caída sobre colchoneta gruesa (golpe de solo 1.5 g)
- caída fuerte sin caída libre registrada
- sentarse después de caminar
- caminar y quedarse de pie
- soltar el teléfono
- un salto
- acostarse despacio
- caerse y levantarse enseguida

Ejecútalas con `./gradlew test` o desde Android Studio (clic derecho → *Run tests*). Si cambias umbrales, vuelve a correrlas.

## Limitaciones

- Con el teléfono en el muslo, **sentarse de golpe** puede parecer una caída (caída libre + golpe + muslo horizontal). La cuenta regresiva permite cancelar.
- Una caída que termina **de rodillas** no cambia la postura del muslo y puede no detectarse.
- Si el teléfono está fijo en un soporte (como en la versión con cámara), sus sensores miden el teléfono, no a la persona. En ese caso la caída se detecta con los puntos del esqueleto; el acelerómetro solo sirve para saber dónde está la vertical y para descartar cuadros cuando el teléfono se mueve.

Esto es una demostración, no un sistema validado de seguridad. No dependas de esta alerta en una emergencia. Para probarlo, empieza registrando actividades normales (caminar, sentarte, acostarte). Si simulas caídas, hazlo sobre colchonetas, de forma controlada y con alguien presente.
