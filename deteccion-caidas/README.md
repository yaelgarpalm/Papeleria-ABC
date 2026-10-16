# Detección de caídas con XIAO ESP32S3 Sense

El XIAO ESP32S3 Sense transmite el video por WiFi. En una computadora, un modelo
**preentrenado** de estimación de pose (**YOLO11-pose** de Ultralytics, entrenado con
COCO; no hace falta entrenar nada) encuentra a cada persona y sus 17 puntos del cuerpo.
Con eso se decide si hubo una caída. Cuando se confirma una caída:

- se guarda una foto en `servidor/capturas/`,
- el LED del XIAO parpadea (y suena el zumbador, si lo conectas),
- opcionalmente llega un aviso por **Telegram** o a un **webhook**.

```
 XIAO ESP32S3 Sense                         PC / laptop                
┌────────────────────┐   video MJPEG    ┌──────────────────────────────────┐
│ cámara OV2640/3660 │ ───────────────▶ │ YOLO11-pose (preentrenado)       │
│ :81/stream         │                  │   ↓ 17 puntos por persona        │
│                    │   /alerta=1/0    │ lógica de caídas                 │
│ LED / zumbador     │ ◀─────────────── │   ↓                              │
└────────────────────┘                  │ foto + Telegram/webhook          │
                                        └──────────────────────────────────┘
```

**¿Por qué el modelo no corre en el XIAO?** El ESP32-S3 puede correr detectores muy
pequeños, pero no un modelo de pose con la precisión y velocidad necesarias. Sin los
puntos del cuerpo solo queda adivinar por la forma de la caja (alta = de pie,
ancha = acostado), lo que da muchas falsas alarmas. Por eso el XIAO solo capta el video
y avisa, y la PC hace el análisis.

## Estructura

```
deteccion-caidas/
  firmware/xiao_camara_caidas/
    xiao_camara_caidas.ino   cámara + WiFi + video + alarma local
    config.example.h         plantilla para tu WiFi (cópiala como config.h)
  servidor/
    detector_caidas.py       programa principal (lee el video, corre YOLO, avisa)
    logica_caidas.py         reglas para decidir si es caída (sin dependencias)
    requirements.txt
    tests/                   pruebas de la lógica con esqueletos simulados
```

## 1. Firmware del XIAO

1. Conecta el módulo de cámara a la placa y **pon la antena** (sin ella el WiFi es muy
   débil y el video se corta).
2. En el Arduino IDE, en *Archivo → Preferencias → URLs adicionales del gestor de
   placas*, agrega:
   `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
3. En *Herramientas → Placa → Gestor de placas*, instala **esp32 de Espressif Systems,
   versión 3.x**.
4. Abre `firmware/xiao_camara_caidas/xiao_camara_caidas.ino`.
5. Copia `config.example.h` como `config.h` (en la misma carpeta) y escribe tu red WiFi
   (solo 2.4 GHz) y tu contraseña.
6. Selecciona:
   - *Herramientas → Placa*: **XIAO_ESP32S3**
   - *Herramientas → PSRAM*: **OPI PSRAM** (si no, la cámara arranca en baja resolución)
7. Sube el programa y abre el Monitor Serie a **115200**. Verás algo así:

```
Cámara lista (OV2640)
Conectando a MiRed....
Listo.
  Página:  http://192.168.1.50/  (o http://xiao-caidas.local/)
  Video:   http://192.168.1.50:81/stream
  En la PC: python detector_caidas.py --fuente http://192.168.1.50:81/stream
```

Si abres `http://192.168.1.50/` en el navegador verás el video y el estado de la alarma.
**Ojo:** el XIAO solo puede mandar video a un cliente a la vez. Cierra esa página antes
de arrancar el detector.

Rutas del XIAO:

| Ruta | Para qué |
|---|---|
| `http://IP/` | página con el video y el estado |
| `http://IP:81/stream` | video MJPEG (lo usa el detector) |
| `http://IP/capture` | una foto JPEG |
| `http://IP/estado` | estado en JSON (alerta, sensor, señal WiFi...) |
| `http://IP/alerta?estado=1` / `0` | activa / apaga la alarma local |

El botón **BOOT** de la placa también apaga la alarma.

### Zumbador (opcional)

Conecta un zumbador activo de 3.3 V entre un pin (por ejemplo **D0 = GPIO1**) y GND, y
en `config.h` pon `#define PIN_ZUMBADOR 1`.

## 2. Detector en la computadora

Necesitas Python 3.9 o más nuevo.

Windows (PowerShell):

```powershell
cd deteccion-caidas\servidor
py -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
python detector_caidas.py --fuente http://192.168.1.50:81/stream
```

Linux / macOS:

```bash
cd deteccion-caidas/servidor
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python detector_caidas.py --fuente http://192.168.1.50:81/stream
```

La primera vez se descarga solo el modelo `yolo11n-pose.pt` (unos 6 MB). Se abre una
ventana con el video: verde = normal, naranja = posible caída, rojo = caída. Sal con `q`.

Las fotos de las caídas quedan en `servidor/capturas/`.

### Opciones útiles

| Opción | Qué hace |
|---|---|
| `--modelo yolo11s-pose.pt` | modelo más preciso (más lento). También `yolo11m-pose.pt` |
| `--tamano 320` | más rápido en computadoras lentas, algo menos preciso |
| `--dispositivo cuda:0` | usar la tarjeta de video NVIDIA (`mps` en Mac M1/M2/M3) |
| `--confirmacion 3` | segundos en el suelo antes de avisar (por defecto 2) |
| `--en-suelo 20` | avisa también si alguien lleva 20 s tendido aunque no se vio caer (no lo uses si la cámara ve una cama o un sillón) |
| `--esp32 no` | no activar la alarma del XIAO |
| `--sin-ventana` | sin video en pantalla (servidor sin monitor) |
| `--salida-video anotado.mp4` | guarda el video con las anotaciones |
| `--fuente 0` | usar la webcam de la PC (para probar sin el XIAO) |
| `--fuente video.mp4` | analizar un video grabado |

### Avisos por Telegram

1. En Telegram habla con **@BotFather**, crea un bot con `/newbot` y copia el token.
2. Mándale cualquier mensaje a tu bot y abre
   `https://api.telegram.org/bot<TOKEN>/getUpdates`; ahí aparece tu `chat.id`.
3. Ejecuta:

```bash
python detector_caidas.py --fuente http://192.168.1.50:81/stream --telegram-token 123456:ABC... --telegram-chat 987654321
```

(o define las variables de entorno `TELEGRAM_TOKEN` y `TELEGRAM_CHAT_ID`). Llega la foto
con la hora de la caída.

### Webhook

Con `--webhook https://tu-servidor/caidas` se envía un `POST` JSON por cada evento:

```json
{"evento": "caida", "persona": 1, "motivo": "bajada rapida y quedo tendida",
 "fecha": "2026-10-16T18:24:58", "foto": "capturas/caida_20261016_182458_persona1.jpg"}
```

y `"evento": "recuperado"` cuando la persona se levanta.

## Cómo decide que es una caída

Para cada persona (con los puntos de hombros, caderas, rodillas y tobillos):

1. **Bajada rápida**: el centro del tronco baja al menos 30 % de su estatura en 1.5 s.
2. **Queda tendida**: el tronco queda a más de 60° de la vertical y las piernas ya no
   están verticales (así no cuenta agacharse a recoger algo).
3. **Sigue en el suelo** 2 s seguidos. Si se levanta antes, no se avisa.

La alarma se apaga cuando la persona vuelve a estar de pie 3 s. Si la persona deja de
verse estando en el suelo (por ejemplo, la tapa un mueble), la alarma **sigue**.

Si el modelo no ve los hombros o las caderas, se usa la forma de la caja (ancha y baja =
tendida). Todos los umbrales están en la clase `Parametros` de `logica_caidas.py`.

## Dónde poner la cámara

- A 1.5–2.2 m de altura, viendo la habitación **de lado o en diagonal**, con el cuerpo
  completo a la vista. Desde el techo mirando hacia abajo el modelo de pose funciona mal.
- Con buena luz. De noche hace falta luz (el sensor no ve en la oscuridad).
- Evita que la cama o el sillón queden en el centro de la imagen: acostarse rápido en
  ellos se parece a una caída.

## Pruebas

```bash
cd deteccion-caidas/servidor
pip install pytest
python -m pytest tests
```

Simulan con esqueletos sintéticos: caída (también a 4 FPS), tropezar y levantarse,
agacharse, acostarse despacio, recuperarse, dos personas a la vez, cajas duplicadas del
modelo y persona tapada después de caer.

## Problemas comunes

- **`Error al iniciar la cámara: 0x105`**: el cable plano de la cámara está mal puesto o
  flojo.
- **Imagen en baja resolución o reinicios**: falta seleccionar *PSRAM → OPI PSRAM*.
- **No conecta al WiFi**: el ESP32 solo usa redes de 2.4 GHz.
- **El detector dice "Sin video"**: revisa la IP, que la PC esté en la misma red y que
  no tengas abierta la página del XIAO en el navegador.
- **Va lento**: usa `--tamano 320`, o baja la resolución en `config.h`
  (`FRAMESIZE_QVGA`).

## Limitaciones

Es un proyecto de ayuda y aprendizaje, **no un dispositivo médico certificado**: puede
haber caídas que no detecte (por ejemplo, detrás de un mueble o fuera de cuadro) y falsas
alarmas (ejercicios en el piso, jugar con niños en el suelo). Pruébalo en tu espacio y
ajusta los umbrales antes de confiar en él.
