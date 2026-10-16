"""Lógica de detección de caídas a partir de los puntos clave de pose (formato COCO-17).

No depende del modelo ni de OpenCV: recibe cajas y puntos clave por cuadro y decide,
persona por persona, si hubo una caída. Así se puede probar sin cámara.

Una caída se confirma cuando se cumplen las tres cosas:
  1. el centro del tronco baja rápido (una buena parte de la estatura en poco tiempo),
  2. justo después la persona queda tendida (tronco horizontal y piernas no verticales),
  3. sigue tendida durante unos segundos (descarta agacharse o recoger algo del suelo).
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, Sequence

# Índices de los puntos clave COCO-17 que entrega YOLO-pose
NARIZ = 0
HOMBROS = (5, 6)
CADERAS = (11, 12)
RODILLAS = (13, 14)
TOBILLOS = (15, 16)

Caja = tuple[float, float, float, float]  # x1, y1, x2, y2 en píxeles
PuntosClave = Sequence[Sequence[float]]   # 17 filas de (x, y, confianza)


class Postura(Enum):
    ERGUIDA = "erguida"
    INTERMEDIA = "intermedia"  # inclinada, sentada, agachada...
    TENDIDA = "tendida"


class Estado(Enum):
    NORMAL = "normal"
    POSIBLE_CAIDA = "posible caida"
    CAIDA = "caida"


@dataclass
class Parametros:
    conf_punto: float = 0.4               # confianza mínima para usar un punto clave
    angulo_tendido: float = 60.0          # grados desde la vertical: tronco acostado
    angulo_erguido: float = 30.0          # grados desde la vertical: tronco derecho
    relacion_tendido: float = 1.3         # ancho/alto de la caja: cuerpo acostado
    relacion_erguido: float = 0.8
    piernas_verticales: float = 0.25      # caída vertical cadera->tobillo / largo del cuerpo
    ventana_descenso_s: float = 1.5       # tiempo en el que se mide la bajada rápida
    descenso_minimo: float = 0.3          # bajada mínima del tronco, en estaturas
    espera_tras_descenso_s: float = 2.5   # tiempo para quedar tendido después de bajar
    tiempo_confirmacion_s: float = 2.0    # tiempo tendido para confirmar la caída
    tiempo_recuperacion_s: float = 3.0    # tiempo erguido para dar por terminada la alerta
    # Si se define, también alerta cuando alguien que estaba de pie lleva este tiempo
    # tendido aunque no se haya visto la bajada (por ejemplo, si la cámara se trabó).
    tiempo_en_suelo_s: Optional[float] = None
    historial_s: float = 5.0
    tiempo_perdida_s: float = 3.0         # sin verse, se olvida a la persona...
    tiempo_perdida_caida_s: float = 15.0  # ...salvo si estaba caída (puede estar tapada)
    distancia_emparejar: float = 1.0      # distancia máxima entre centros, en tamaños de caja
    solape_duplicado: float = 0.8         # cajas que se tapan tanto son la misma persona


@dataclass
class Medidas:
    t: float
    caja: Caja
    angulo_tronco: Optional[float]  # 0 = de pie, 90 = horizontal, >90 = cabeza abajo
    centro_y: float                 # altura del centro del tronco en la imagen (píxeles)
    caida_piernas: Optional[float]  # distancia vertical cadera->tobillo (o rodilla), píxeles
    usa_tobillos: bool

    @property
    def ancho(self) -> float:
        return max(self.caja[2] - self.caja[0], 1.0)

    @property
    def alto(self) -> float:
        return max(self.caja[3] - self.caja[1], 1.0)

    @property
    def relacion(self) -> float:
        return self.ancho / self.alto

    @property
    def centro(self) -> tuple[float, float]:
        return ((self.caja[0] + self.caja[2]) / 2, (self.caja[1] + self.caja[3]) / 2)


@dataclass
class Evento:
    tipo: str          # "caida" o "recuperado"
    id_persona: int
    t: float
    motivo: str


def _promedio(puntos: PuntosClave, indices: Sequence[int], conf: float) -> Optional[tuple[float, float]]:
    validos = [puntos[i] for i in indices if len(puntos) > i and puntos[i][2] >= conf]
    if not validos:
        return None
    return (sum(p[0] for p in validos) / len(validos), sum(p[1] for p in validos) / len(validos))


def calcular_medidas(caja: Caja, puntos: Optional[PuntosClave], t: float, p: Parametros) -> Medidas:
    caja = tuple(float(v) for v in caja)
    alto = max(caja[3] - caja[1], 1.0)
    largo = max(caja[2] - caja[0], alto)
    angulo = None
    centro_y = (caja[1] + caja[3]) / 2
    caida_piernas = None
    usa_tobillos = False

    if puntos is not None and len(puntos) >= 17:
        hombros = _promedio(puntos, HOMBROS, p.conf_punto)
        caderas = _promedio(puntos, CADERAS, p.conf_punto)
        if hombros and caderas:
            dx = hombros[0] - caderas[0]
            dy = hombros[1] - caderas[1]
            # Si el tronco se ve muy corto (de frente y escorzado) el ángulo no es fiable
            if math.hypot(dx, dy) >= 0.1 * largo:
                angulo = math.degrees(math.atan2(abs(dx), -dy))
            centro_y = (hombros[1] + caderas[1]) / 2
        if caderas:
            tobillos = _promedio(puntos, TOBILLOS, p.conf_punto)
            if tobillos:
                caida_piernas = tobillos[1] - caderas[1]
                usa_tobillos = True
            else:
                rodillas = _promedio(puntos, RODILLAS, p.conf_punto)
                if rodillas:
                    caida_piernas = rodillas[1] - caderas[1]

    return Medidas(t, caja, angulo, centro_y, caida_piernas, usa_tobillos)


def clasificar_postura(m: Medidas, p: Parametros, estatura: Optional[float] = None) -> Postura:
    """Clasifica la postura de un cuadro. `estatura` es la altura de la persona de pie, si se conoce."""
    largo = max(m.ancho, m.alto, estatura or 0.0)
    piernas_verticales = False
    if m.caida_piernas is not None:
        # De la cadera a la rodilla hay más o menos la mitad que de la cadera al tobillo
        umbral = p.piernas_verticales * (1.0 if m.usa_tobillos else 0.5)
        piernas_verticales = m.caida_piernas >= umbral * largo

    if m.angulo_tronco is not None:
        if m.angulo_tronco >= p.angulo_tendido and not piernas_verticales:
            return Postura.TENDIDA
        if m.angulo_tronco <= p.angulo_erguido and m.relacion < p.relacion_tendido:
            return Postura.ERGUIDA
        return Postura.INTERMEDIA

    # Sin tronco visible: solo queda la forma de la caja
    if m.relacion >= p.relacion_tendido and not piernas_verticales:
        return Postura.TENDIDA
    if m.relacion <= p.relacion_erguido:
        return Postura.ERGUIDA
    return Postura.INTERMEDIA


@dataclass
class Persona:
    """Sigue a una persona a lo largo del tiempo y decide si se cayó."""

    id: int
    p: Parametros
    estado: Estado = Estado.NORMAL
    postura: Postura = Postura.INTERMEDIA
    ultima: Optional[Medidas] = None
    historial: deque = field(default_factory=deque)
    estuvo_erguida: bool = False
    t_descenso: Optional[float] = None
    descenso: float = 0.0
    t_tendida_desde: Optional[float] = None
    t_ultimo_tendido: Optional[float] = None
    t_erguida_desde: Optional[float] = None
    t_posible_desde: Optional[float] = None
    motivo: str = ""

    def estatura(self) -> Optional[float]:
        """Altura de la caja cuando estuvo de pie hace poco (referencia de tamaño)."""
        altos = [m.alto for m, postura in self.historial if postura == Postura.ERGUIDA]
        return max(altos) if altos else None

    def _medir_descenso(self, m: Medidas) -> float:
        estatura = self.estatura()
        if estatura is None:
            altos = [x.alto for x, _ in self.historial]
            estatura = max(altos + [m.alto])
        recientes = [x.centro_y for x, _ in self.historial if m.t - x.t <= self.p.ventana_descenso_s]
        if not recientes:
            return 0.0
        return (m.centro_y - min(recientes)) / estatura

    def actualizar(self, m: Medidas) -> Optional[Evento]:
        p = self.p
        while self.historial and m.t - self.historial[0][0].t > p.historial_s:
            self.historial.popleft()

        self.postura = clasificar_postura(m, p, self.estatura())
        self.descenso = self._medir_descenso(m)
        if self.descenso >= p.descenso_minimo:
            self.t_descenso = m.t
        self.historial.append((m, self.postura))
        self.ultima = m

        if self.postura == Postura.ERGUIDA:
            self.estuvo_erguida = True
            if self.t_erguida_desde is None:
                self.t_erguida_desde = m.t
            self.t_tendida_desde = None
        else:
            self.t_erguida_desde = None
        if self.postura == Postura.TENDIDA:
            if self.t_tendida_desde is None:
                self.t_tendida_desde = m.t
            self.t_ultimo_tendido = m.t
        # INTERMEDIA no reinicia el tiempo tendido: alguien en el suelo se mueve y a
        # ratos parece sentado o de lado.

        if self.estado == Estado.NORMAL:
            if self.postura == Postura.TENDIDA:
                bajo_rapido = self.t_descenso is not None and m.t - self.t_descenso <= p.espera_tras_descenso_s
                if bajo_rapido:
                    self.estado = Estado.POSIBLE_CAIDA
                    self.motivo = "bajada rapida y quedo tendida"
                    self.t_posible_desde = m.t
                elif (
                    p.tiempo_en_suelo_s is not None
                    and self.estuvo_erguida
                    and m.t - self.t_tendida_desde >= p.tiempo_en_suelo_s
                ):
                    return self._confirmar(m.t, "lleva tiempo tendida en el suelo")

        elif self.estado == Estado.POSIBLE_CAIDA:
            if self.postura == Postura.ERGUIDA or m.t - self.t_ultimo_tendido > p.espera_tras_descenso_s:
                # Se levantó o se quedó agachada: no fue una caída
                self.estado = Estado.NORMAL
            elif self.postura == Postura.TENDIDA and m.t - self.t_posible_desde >= p.tiempo_confirmacion_s:
                return self._confirmar(m.t, self.motivo)

        elif self.estado == Estado.CAIDA:
            if self.t_erguida_desde is not None and m.t - self.t_erguida_desde >= p.tiempo_recuperacion_s:
                self.estado = Estado.NORMAL
                self.t_descenso = None
                return Evento("recuperado", self.id, m.t, "se levanto")

        return None

    def _confirmar(self, t: float, motivo: str) -> Evento:
        self.estado = Estado.CAIDA
        self.motivo = motivo
        return Evento("caida", self.id, t, motivo)


class DetectorCaidas:
    """Empareja las detecciones de cada cuadro con las personas conocidas y las actualiza."""

    def __init__(self, parametros: Optional[Parametros] = None):
        self.p = parametros or Parametros()
        self.personas: dict[int, Persona] = {}
        self._siguiente_id = 1

    def hay_caida(self) -> bool:
        return any(per.estado == Estado.CAIDA for per in self.personas.values())

    def actualizar(self, detecciones: Sequence[tuple[Caja, Optional[PuntosClave]]], t: float) -> list[Evento]:
        medidas = [calcular_medidas(caja, puntos, t, self.p) for caja, puntos in detecciones]
        medidas = self._quitar_duplicadas(medidas)
        asignacion = self._emparejar(medidas, t)

        eventos = []
        for i, m in enumerate(medidas):
            persona = asignacion.get(i)
            if persona is None:
                persona = Persona(self._siguiente_id, self.p)
                self.personas[persona.id] = persona
                self._siguiente_id += 1
            evento = persona.actualizar(m)
            if evento:
                eventos.append(evento)

        for pid, per in list(self.personas.items()):
            limite = self.p.tiempo_perdida_caida_s if per.estado == Estado.CAIDA else self.p.tiempo_perdida_s
            if t - per.ultima.t > limite:
                del self.personas[pid]
        return eventos

    def _quitar_duplicadas(self, medidas: list[Medidas]) -> list[Medidas]:
        """A veces el modelo entrega dos cajas para la misma persona: se queda con la primera
        (la de mayor confianza, porque YOLO las devuelve ordenadas)."""
        unicas: list[Medidas] = []
        for m in medidas:
            if all(_solape(m.caja, u.caja) < self.p.solape_duplicado for u in unicas):
                unicas.append(m)
        return unicas

    def _emparejar(self, medidas: list[Medidas], t: float) -> dict[int, Persona]:
        """Emparejamiento voraz por cercanía de centros.

        Es tolerante a cambios bruscos de forma de la caja (al caer, la caja pasa de
        alta y angosta a baja y ancha), cosa que a los rastreadores genéricos les cuesta.
        A igual distancia gana la persona vista más recientemente, para que un rastro
        viejo no le quite la detección a quien se está siguiendo.
        """
        candidatos = []
        for i, m in enumerate(medidas):
            cx, cy = m.centro
            for per in self.personas.values():
                if per.ultima is None:
                    continue
                ux, uy = per.ultima.centro
                escala = max(per.ultima.ancho, per.ultima.alto, m.ancho, m.alto)
                distancia = math.hypot(cx - ux, cy - uy) / escala
                if distancia <= self.p.distancia_emparejar:
                    antiguedad = t - per.ultima.t
                    candidatos.append((distancia + 0.5 * antiguedad, i, per))
        candidatos.sort(key=lambda c: c[0])

        asignacion: dict[int, Persona] = {}
        usadas = set()
        for _, i, per in candidatos:
            if i in asignacion or per.id in usadas:
                continue
            asignacion[i] = per
            usadas.add(per.id)
        return asignacion


def _solape(a: Caja, b: Caja) -> float:
    """Área de la intersección dividida entre el área de la caja más chica."""
    ancho = min(a[2], b[2]) - max(a[0], b[0])
    alto = min(a[3], b[3]) - max(a[1], b[1])
    if ancho <= 0 or alto <= 0:
        return 0.0
    menor = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
    return ancho * alto / max(menor, 1.0)
