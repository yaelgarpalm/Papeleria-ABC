"""Pruebas de la lógica de caídas con esqueletos sintéticos (sin cámara ni modelo)."""

import math
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from logica_caidas import (  # noqa: E402
    DetectorCaidas, Estado, Parametros, Postura, calcular_medidas, clasificar_postura,
)

FPS = 10
ESTATURA = 300.0  # píxeles
PISO = 450.0

# Esqueleto de pie en coordenadas del cuerpo (x a la derecha, y hacia arriba, en estaturas),
# con origen entre los pies. Orden COCO-17.
DE_PIE = [
    (0.00, 0.93),                                # nariz
    (-0.02, 0.95), (0.02, 0.95),                 # ojos
    (-0.05, 0.93), (0.05, 0.93),                 # orejas
    (-0.12, 0.82), (0.12, 0.82),                 # hombros
    (-0.15, 0.62), (0.15, 0.62),                 # codos
    (-0.15, 0.45), (0.15, 0.45),                 # muñecas
    (-0.08, 0.52), (0.08, 0.52),                 # caderas
    (-0.08, 0.28), (0.08, 0.28),                 # rodillas
    (-0.08, 0.03), (0.08, 0.03),                 # tobillos
]
TRONCO_SUPERIOR = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
ALTURA_CADERA = 0.52


def _rotar(x, y, angulo, cx=0.0, cy=0.0):
    a = math.radians(angulo)
    x, y = x - cx, y - cy
    return cx + x * math.cos(a) + y * math.sin(a), cy - x * math.sin(a) + y * math.cos(a)


def persona(angulo_cuerpo=0.0, angulo_tronco=0.0, x_pies=320.0):
    """Devuelve (caja, puntos) de una persona.

    angulo_cuerpo: todo el cuerpo gira sobre los pies (0 = de pie, 90 = tendida en el piso).
    angulo_tronco: solo la parte de arriba gira sobre la cadera (agacharse).
    """
    puntos = []
    for i, (x, y) in enumerate(DE_PIE):
        if i in TRONCO_SUPERIOR:
            x, y = _rotar(x, y, angulo_tronco, 0.0, ALTURA_CADERA)
        x, y = _rotar(x, y, angulo_cuerpo)
        y = max(y, 0.0)  # el cuerpo no atraviesa el piso
        puntos.append([x_pies + x * ESTATURA, PISO - y * ESTATURA, 0.9])
    xs = [p[0] for p in puntos]
    ys = [p[1] for p in puntos]
    margen = 0.04 * ESTATURA
    caja = (min(xs) - margen, min(ys) - margen, max(xs) + margen, max(ys) + margen)
    return caja, puntos


class Escena:
    def __init__(self, parametros=None):
        self.detector = DetectorCaidas(parametros or Parametros())
        self.t = 0.0
        self.eventos = []

    def cuadro(self, *personas):
        self.eventos += self.detector.actualizar(list(personas), self.t)
        self.t += 1 / FPS

    def quieto(self, segundos, **pose):
        for _ in range(int(segundos * FPS)):
            self.cuadro(persona(**pose))

    def transicion(self, segundos, clave, desde, hasta, **resto):
        n = max(int(segundos * FPS), 1)
        for i in range(1, n + 1):
            self.cuadro(persona(**{clave: desde + (hasta - desde) * i / n}, **resto))

    def tipos(self):
        return [e.tipo for e in self.eventos]

    def unica(self):
        assert len(self.detector.personas) == 1
        return next(iter(self.detector.personas.values()))


def test_angulo_del_tronco():
    p = Parametros()
    de_pie = calcular_medidas(*persona(), t=0, p=p)
    tendida = calcular_medidas(*persona(angulo_cuerpo=90), t=0, p=p)
    assert de_pie.angulo_tronco == pytest.approx(0, abs=1)
    assert tendida.angulo_tronco == pytest.approx(90, abs=5)  # el piso aplana un hombro
    assert clasificar_postura(de_pie, p) == Postura.ERGUIDA
    assert clasificar_postura(tendida, p, ESTATURA) == Postura.TENDIDA


def test_de_pie_no_alerta():
    e = Escena()
    e.quieto(10)
    assert e.eventos == []
    assert e.unica().postura == Postura.ERGUIDA


def test_caida_confirmada_tras_quedar_en_el_suelo():
    e = Escena()
    e.quieto(2)
    e.transicion(0.6, "angulo_cuerpo", 0, 90)
    e.quieto(1.0, angulo_cuerpo=90)
    assert e.eventos == []  # aún no pasa el tiempo de confirmación
    assert e.unica().estado == Estado.POSIBLE_CAIDA
    e.quieto(2.0, angulo_cuerpo=90)
    assert e.tipos() == ["caida"]
    assert e.unica().estado == Estado.CAIDA
    assert e.eventos[0].t == pytest.approx(2.6 + Parametros.tiempo_confirmacion_s, abs=0.25)


def test_caida_con_pocos_fps():
    global FPS
    anterior, FPS = FPS, 4
    try:
        e = Escena()
        e.quieto(2)
        e.transicion(0.5, "angulo_cuerpo", 0, 90)
        e.quieto(3, angulo_cuerpo=90)
        assert e.tipos() == ["caida"]
    finally:
        FPS = anterior


def test_se_levanta_y_se_apaga_la_alerta():
    e = Escena()
    e.quieto(2)
    e.transicion(0.6, "angulo_cuerpo", 0, 90)
    e.quieto(3, angulo_cuerpo=90)
    e.transicion(2.0, "angulo_cuerpo", 90, 0)
    e.quieto(Parametros.tiempo_recuperacion_s + 0.5)
    assert e.tipos() == ["caida", "recuperado"]
    assert e.unica().estado == Estado.NORMAL


def test_tropieza_y_se_levanta_enseguida_no_alerta():
    e = Escena()
    e.quieto(2)
    e.transicion(0.5, "angulo_cuerpo", 0, 90)
    e.quieto(0.5, angulo_cuerpo=90)
    e.transicion(0.8, "angulo_cuerpo", 90, 0)
    e.quieto(5)
    assert e.eventos == []


def test_agacharse_a_recoger_algo_no_alerta():
    e = Escena()
    e.quieto(2)
    e.transicion(0.5, "angulo_tronco", 0, 90)
    e.quieto(4, angulo_tronco=90)  # se queda agachada un rato
    e.transicion(0.5, "angulo_tronco", 90, 0)
    e.quieto(2)
    assert e.eventos == []


def test_acostarse_despacio_no_alerta():
    e = Escena()
    e.quieto(2)
    e.transicion(8, "angulo_cuerpo", 0, 90)
    e.quieto(10, angulo_cuerpo=90)
    assert e.eventos == []


def test_tiempo_en_el_suelo_opcional():
    e = Escena(Parametros(tiempo_en_suelo_s=5))
    e.quieto(2)
    e.transicion(8, "angulo_cuerpo", 0, 90)
    e.quieto(6, angulo_cuerpo=90)
    assert e.tipos() == ["caida"]
    assert "tiempo" in e.eventos[0].motivo


def test_sin_puntos_clave_usa_la_forma_de_la_caja():
    e = Escena()
    for _ in range(20):
        e.cuadro(((300, 150, 400, 450), None))  # caja alta: de pie
    for y1, y2, x1, x2 in [(250, 450, 280, 450), (330, 450, 220, 520), (370, 450, 180, 500)]:
        e.cuadro(((x1, y1, x2, y2), None))
    for _ in range(30):
        e.cuadro(((170, 370, 480, 450), None))  # caja ancha y baja: tendida
    assert e.tipos() == ["caida"]


def test_mismo_id_durante_la_caida_y_dos_personas():
    e = Escena()
    for _ in range(20):
        e.cuadro(persona(x_pies=150), persona(x_pies=520))
    ids = set(e.detector.personas)
    assert len(ids) == 2
    for i in range(1, 7):
        e.cuadro(persona(angulo_cuerpo=15 * i, x_pies=150), persona(x_pies=520))
    for _ in range(30):
        e.cuadro(persona(angulo_cuerpo=90, x_pies=150), persona(x_pies=520))
    assert set(e.detector.personas) == ids
    assert e.tipos() == ["caida"]
    caidas = [p for p in e.detector.personas.values() if p.estado == Estado.CAIDA]
    assert len(caidas) == 1
    assert caidas[0].ultima.centro[0] < 300


def test_persona_que_sale_de_la_imagen_se_olvida():
    e = Escena()
    e.quieto(1)
    for _ in range(int((Parametros.tiempo_perdida_s + 1) * FPS)):
        e.cuadro()
    assert e.detector.personas == {}


def test_persona_caida_tapada_no_se_olvida_enseguida():
    e = Escena()
    e.quieto(2)
    e.transicion(0.6, "angulo_cuerpo", 0, 90)
    e.quieto(3, angulo_cuerpo=90)
    for _ in range(int((Parametros.tiempo_perdida_s + 1) * FPS)):
        e.cuadro()
    assert e.detector.hay_caida()


def test_caja_duplicada_no_crea_otra_persona():
    e = Escena()
    e.quieto(1)
    caja, puntos = persona()
    x1, y1, x2, y2 = caja
    e.cuadro((caja, puntos), ((x1 + 5, y1 + 10, x2 - 5, y2 - 20), puntos))
    e.quieto(1)
    assert len(e.detector.personas) == 1


def test_rastro_viejo_no_roba_la_deteccion():
    e = Escena()
    e.quieto(2)
    original = e.unica().id
    # Aparece y desaparece alguien justo al lado (por ejemplo, un reflejo)
    e.cuadro(persona(), persona(x_pies=380))
    e.quieto(0.5)
    e.transicion(0.6, "angulo_cuerpo", 0, 90)
    e.quieto(3, angulo_cuerpo=90)
    assert e.tipos() == ["caida"]
    assert e.eventos[0].id_persona == original
