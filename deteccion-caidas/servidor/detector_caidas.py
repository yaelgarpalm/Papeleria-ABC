"""Detector de caídas para la cámara del XIAO ESP32S3 Sense.

Lee el video del XIAO, estima la pose de cada persona con YOLO11-pose (modelo
preentrenado en COCO, se descarga solo la primera vez) y aplica la lógica de
logica_caidas.py. Cuando confirma una caída:
  - guarda una foto en la carpeta "capturas",
  - activa la alarma del XIAO (LED parpadeando / zumbador),
  - opcionalmente avisa por Telegram o a un webhook.

Ejemplos:
    python detector_caidas.py --fuente http://192.168.1.50:81/stream
    python detector_caidas.py --fuente http://xiao-caidas.local:81/stream
    python detector_caidas.py --fuente video_prueba.mp4      # probar con un video
    python detector_caidas.py --fuente 0                     # webcam de la PC
"""

from __future__ import annotations

import argparse
import logging
import os
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse

import cv2
import numpy as np
import requests

from logica_caidas import DetectorCaidas, Estado, Evento, Parametros

log = logging.getLogger("caidas")

COLORES = {  # BGR
    Estado.NORMAL: (60, 200, 60),
    Estado.POSIBLE_CAIDA: (0, 165, 255),
    Estado.CAIDA: (0, 0, 255),
}
ESQUELETO = [
    (5, 6), (5, 7), (7, 9), (6, 8), (8, 10), (5, 11), (6, 12), (11, 12),
    (11, 13), (13, 15), (12, 14), (14, 16), (0, 5), (0, 6),
]


class LectorMJPEG:
    """Lee el stream MJPEG del XIAO en un hilo y guarda solo el cuadro más reciente.

    Así el análisis nunca se atrasa respecto a la realidad aunque el modelo sea más
    lento que la cámara. Si se corta la conexión, reintenta.
    """

    def __init__(self, url: str):
        self.url = url
        self._cuadro: Optional[np.ndarray] = None
        self._t = 0.0
        self._nuevo = threading.Event()
        self._lock = threading.Lock()
        self._activo = True
        threading.Thread(target=self._bucle, daemon=True).start()

    def _bucle(self):
        while self._activo:
            try:
                with requests.get(self.url, stream=True, timeout=(5, 10)) as r:
                    r.raise_for_status()
                    log.info("Conectado a %s", self.url)
                    buffer = b""
                    for trozo in r.iter_content(chunk_size=8192):
                        if not self._activo:
                            return
                        buffer += trozo
                        inicio = buffer.find(b"\xff\xd8")
                        fin = buffer.find(b"\xff\xd9", inicio + 2) if inicio >= 0 else -1
                        if inicio >= 0 and fin >= 0:
                            jpg = buffer[inicio:fin + 2]
                            buffer = buffer[fin + 2:]
                            cuadro = cv2.imdecode(np.frombuffer(jpg, np.uint8), cv2.IMREAD_COLOR)
                            if cuadro is not None:
                                with self._lock:
                                    self._cuadro, self._t = cuadro, time.time()
                                self._nuevo.set()
                        elif len(buffer) > 4_000_000:
                            buffer = b""  # basura: algo no va bien con el stream
            except requests.RequestException as e:
                log.warning("Sin video (%s). Reintentando en 3 s...", e)
                time.sleep(3)

    def leer(self, espera: float = 5.0) -> tuple[Optional[np.ndarray], float]:
        if not self._nuevo.wait(espera):
            return None, time.time()
        with self._lock:
            self._nuevo.clear()
            return self._cuadro, self._t

    def cerrar(self):
        self._activo = False


class LectorOpenCV:
    """Video de archivo o webcam. En archivos usa el tiempo del video, no el del reloj."""

    def __init__(self, fuente: str):
        self.es_archivo = not fuente.isdigit()
        self.cap = cv2.VideoCapture(fuente if self.es_archivo else int(fuente))
        if not self.cap.isOpened():
            raise SystemExit(f"No se pudo abrir la fuente de video: {fuente}")
        self.terminado = False

    def leer(self, espera: float = 5.0) -> tuple[Optional[np.ndarray], float]:
        ok, cuadro = self.cap.read()
        if not ok:
            self.terminado = self.es_archivo
            if not self.es_archivo:
                time.sleep(0.5)
            return None, time.time()
        t = self.cap.get(cv2.CAP_PROP_POS_MSEC) / 1000 if self.es_archivo else time.time()
        return cuadro, t

    def cerrar(self):
        self.cap.release()


class Alertas:
    """Envía los avisos en hilos aparte para no frenar el análisis."""

    def __init__(self, args):
        self.carpeta = Path(args.capturas)
        self.carpeta.mkdir(parents=True, exist_ok=True)
        self.esp32 = args.esp32
        self.telegram_token = args.telegram_token
        self.telegram_chat = args.telegram_chat
        self.webhook = args.webhook

    def _en_hilo(self, funcion, *argumentos):
        def envolver():
            try:
                funcion(*argumentos)
            except Exception as e:  # un aviso fallido no debe tumbar el detector
                log.warning("No se pudo enviar un aviso (%s): %s", funcion.__name__, e)
        threading.Thread(target=envolver, daemon=True).start()

    def caida(self, evento: Evento, cuadro: np.ndarray):
        marca = datetime.now().strftime("%Y%m%d_%H%M%S")
        ruta = self.carpeta / f"caida_{marca}_persona{evento.id_persona}.jpg"
        cv2.imwrite(str(ruta), cuadro)
        texto = f"CAIDA DETECTADA ({datetime.now():%d/%m/%Y %H:%M:%S}): {evento.motivo}"
        log.warning("%s. Foto: %s", texto, ruta)

        if self.esp32:
            self._en_hilo(self._esp32, 1)
        if self.telegram_token and self.telegram_chat:
            ok, jpg = cv2.imencode(".jpg", cuadro)
            self._en_hilo(self._telegram, texto, jpg.tobytes() if ok else None)
        if self.webhook:
            self._en_hilo(self._webhook, evento, str(ruta))

    def fin_de_caidas(self, evento: Evento):
        log.info("La persona %d se levanto. Alarma apagada.", evento.id_persona)
        if self.esp32:
            self._en_hilo(self._esp32, 0)
        if self.webhook:
            self._en_hilo(self._webhook, evento, None)

    def _esp32(self, estado: int):
        requests.get(f"{self.esp32}/alerta", params={"estado": estado}, timeout=5).raise_for_status()

    def _telegram(self, texto: str, jpg: Optional[bytes]):
        base = f"https://api.telegram.org/bot{self.telegram_token}"
        if jpg:
            r = requests.post(f"{base}/sendPhoto", data={"chat_id": self.telegram_chat, "caption": texto},
                              files={"photo": ("caida.jpg", jpg, "image/jpeg")}, timeout=15)
        else:
            r = requests.post(f"{base}/sendMessage", data={"chat_id": self.telegram_chat, "text": texto},
                              timeout=15)
        r.raise_for_status()

    def _webhook(self, evento: Evento, foto: Optional[str]):
        requests.post(self.webhook, json={
            "evento": evento.tipo, "persona": evento.id_persona, "motivo": evento.motivo,
            "fecha": datetime.now().isoformat(timespec="seconds"), "foto": foto,
        }, timeout=10).raise_for_status()


def extraer_personas(resultado) -> list:
    """Convierte la salida de Ultralytics en [(caja, puntos_clave 17x3), ...]."""
    if resultado.boxes is None or len(resultado.boxes) == 0:
        return []
    cajas = resultado.boxes.xyxy.cpu().numpy()
    if resultado.keypoints is None:
        return [(tuple(c), None) for c in cajas]
    xy = resultado.keypoints.xy.cpu().numpy()
    conf = resultado.keypoints.conf
    conf = conf.cpu().numpy() if conf is not None else np.ones(xy.shape[:2])
    puntos = np.concatenate([xy, conf[..., None]], axis=2)
    return [(tuple(c), k.tolist()) for c, k in zip(cajas, puntos)]


def dibujar(cuadro: np.ndarray, detecciones: list, detector: DetectorCaidas, t: float, fps: float,
            conf_punto: float):
    for _, puntos in detecciones:
        if puntos is None:
            continue
        for a, b in ESQUELETO:
            if puntos[a][2] >= conf_punto and puntos[b][2] >= conf_punto:
                cv2.line(cuadro, (int(puntos[a][0]), int(puntos[a][1])),
                         (int(puntos[b][0]), int(puntos[b][1])), (255, 200, 0), 2)

    for persona in detector.personas.values():
        m = persona.ultima
        if m is None or m.t != t:  # no aparece en este cuadro
            continue
        x1, y1, x2, y2 = (int(v) for v in m.caja)
        color = COLORES[persona.estado]
        cv2.rectangle(cuadro, (x1, y1), (x2, y2), color, 2)
        angulo = f"{m.angulo_tronco:.0f}g" if m.angulo_tronco is not None else "-"
        texto = f"#{persona.id} {persona.postura.value} {angulo}"
        if persona.estado != Estado.NORMAL:
            texto += f" [{persona.estado.value.upper()}]"
        cv2.putText(cuadro, texto, (x1, max(y1 - 8, 15)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)

    if detector.hay_caida():
        alto, ancho = cuadro.shape[:2]
        cv2.rectangle(cuadro, (0, 0), (ancho, 40), (0, 0, 255), -1)
        cv2.putText(cuadro, "CAIDA DETECTADA", (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
    cv2.putText(cuadro, f"{fps:.1f} FPS", (10, cuadro.shape[0] - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                (255, 255, 255), 1)


def url_esp32_desde_fuente(fuente: str) -> Optional[str]:
    """http://192.168.1.50:81/stream -> http://192.168.1.50"""
    url = urlparse(fuente)
    if url.scheme in ("http", "https") and url.hostname:
        return f"{url.scheme}://{url.hostname}"
    return None


def leer_argumentos():
    a = argparse.ArgumentParser(description="Detector de caídas para el XIAO ESP32S3 Sense con YOLO11-pose")
    a.add_argument("--fuente", required=True,
                   help="URL del stream del XIAO (http://IP:81/stream), un archivo de video o el número de webcam")
    a.add_argument("--modelo", default="yolo11n-pose.pt",
                   help="Modelo de pose preentrenado de Ultralytics (yolo11n/s/m-pose.pt). Se descarga solo.")
    a.add_argument("--dispositivo", default=None, help="cpu, cuda:0 o mps (por defecto lo elige Ultralytics)")
    a.add_argument("--tamano", type=int, default=640, help="Tamaño de entrada del modelo (320 es más rápido)")
    a.add_argument("--confianza", type=float, default=0.4, help="Confianza mínima para aceptar una persona")
    a.add_argument("--esp32", default="auto",
                   help="URL del XIAO para activar su alarma (auto = la misma IP que --fuente; 'no' = desactivar)")
    a.add_argument("--capturas", default="capturas", help="Carpeta donde guardar las fotos de cada caída")
    a.add_argument("--telegram-token", default=os.getenv("TELEGRAM_TOKEN"), help="Token del bot (o TELEGRAM_TOKEN)")
    a.add_argument("--telegram-chat", default=os.getenv("TELEGRAM_CHAT_ID"), help="Chat ID (o TELEGRAM_CHAT_ID)")
    a.add_argument("--webhook", default=os.getenv("WEBHOOK_CAIDAS"), help="URL que recibe un POST JSON por evento")
    a.add_argument("--sin-ventana", action="store_true", help="No mostrar video (para servidores sin pantalla)")
    a.add_argument("--confirmacion", type=float, default=Parametros.tiempo_confirmacion_s,
                   help="Segundos que la persona debe seguir en el suelo para confirmar la caída")
    a.add_argument("--en-suelo", type=float, default=None,
                   help="Alerta también si alguien lleva estos segundos tendido aunque no se viera la caída. "
                        "No lo uses si la cámara ve una cama o un sillón.")
    a.add_argument("--salida-video", default=None, help="Guardar el video anotado en este archivo .mp4")
    args = a.parse_args()

    if args.esp32 == "auto":
        args.esp32 = url_esp32_desde_fuente(args.fuente)
    elif args.esp32.lower() == "no":
        args.esp32 = None
    else:
        args.esp32 = args.esp32.rstrip("/")
    return args


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    args = leer_argumentos()

    from ultralytics import YOLO  # import tardío: --help responde sin cargar torch
    modelo = YOLO(args.modelo)
    log.info("Modelo %s cargado", args.modelo)

    parametros = Parametros(tiempo_confirmacion_s=args.confirmacion, tiempo_en_suelo_s=args.en_suelo)
    detector = DetectorCaidas(parametros)
    alertas = Alertas(args)
    if args.fuente.startswith(("http://", "https://")):
        lector = LectorMJPEG(args.fuente)
    else:
        lector = LectorOpenCV(args.fuente)
    if args.esp32:
        log.info("Alarma del XIAO en %s/alerta", args.esp32)

    escritor = None
    fps = 0.0
    t_anterior = time.time()
    try:
        while True:
            cuadro, t = lector.leer()
            if cuadro is None:
                if getattr(lector, "terminado", False):
                    break
                log.info("Esperando video...")
                continue

            resultado = modelo.predict(cuadro, imgsz=args.tamano, conf=args.confianza, classes=[0],
                                       device=args.dispositivo, verbose=False)[0]
            detecciones = extraer_personas(resultado)

            for evento in detector.actualizar(detecciones, t):
                if evento.tipo == "caida":
                    anotado = cuadro.copy()
                    dibujar(anotado, detecciones, detector, t, fps, parametros.conf_punto)
                    alertas.caida(evento, anotado)
                elif not detector.hay_caida():
                    # Solo se apaga la alarma cuando se levantó; si la persona deja de
                    # verse estando en el suelo, la alarma sigue (puede estar tapada).
                    alertas.fin_de_caidas(evento)

            ahora = time.time()
            instantaneo = 1 / max(ahora - t_anterior, 1e-3)
            fps = 0.9 * fps + 0.1 * instantaneo if fps else instantaneo
            t_anterior = ahora
            dibujar(cuadro, detecciones, detector, t, fps, parametros.conf_punto)

            if args.salida_video:
                if escritor is None:
                    alto, ancho = cuadro.shape[:2]
                    escritor = cv2.VideoWriter(args.salida_video, cv2.VideoWriter_fourcc(*"mp4v"), 10, (ancho, alto))
                escritor.write(cuadro)
            if not args.sin_ventana:
                cv2.imshow("Deteccion de caidas (q para salir)", cuadro)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break
    except KeyboardInterrupt:
        pass
    finally:
        lector.cerrar()
        if escritor is not None:
            escritor.release()
        if not args.sin_ventana:
            cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
