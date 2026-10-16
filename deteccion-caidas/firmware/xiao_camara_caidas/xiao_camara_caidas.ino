/*
 * Cámara para detección de caídas - Seeed Studio XIAO ESP32S3 Sense
 *
 * El XIAO captura video y lo transmite por WiFi. El análisis con el modelo
 * preentrenado de pose (YOLO11-pose) se hace en la computadora con
 * servidor/detector_caidas.py, que además avisa a esta placa cuando hay una caída
 * para que encienda el LED (y el zumbador, si lo conectas).
 *
 * Rutas:
 *   http://<ip>/            página con el video y el estado de la alarma
 *   http://<ip>/capture     una foto JPEG
 *   http://<ip>/estado      estado en JSON
 *   http://<ip>/alerta?estado=1|0   activa/desactiva la alarma local
 *   http://<ip>:81/stream   video MJPEG (lo usa el detector)
 *
 * Arduino IDE: placa "XIAO_ESP32S3" y en Herramientas -> PSRAM: "OPI PSRAM".
 * El botón BOOT de la placa apaga la alarma local.
 */

#include <Arduino.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include "esp_camera.h"
#include "esp_http_server.h"

#if __has_include("config.h")
#include "config.h"
#else
#include "config.example.h"
#warning "No existe config.h: copia config.example.h como config.h y pon tu WiFi"
#endif

// Pines de la cámara del XIAO ESP32S3 Sense (CAMERA_MODEL_XIAO_ESP32S3)
#define PWDN_GPIO_NUM  -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM  10
#define SIOD_GPIO_NUM  40
#define SIOC_GPIO_NUM  39
#define Y9_GPIO_NUM    48
#define Y8_GPIO_NUM    11
#define Y7_GPIO_NUM    12
#define Y6_GPIO_NUM    14
#define Y5_GPIO_NUM    16
#define Y4_GPIO_NUM    18
#define Y3_GPIO_NUM    17
#define Y2_GPIO_NUM    15
#define VSYNC_GPIO_NUM 38
#define HREF_GPIO_NUM  47
#define PCLK_GPIO_NUM  13

#define PIN_LED   21  // LED naranja de usuario, se enciende con LOW
#define PIN_BOTON 0   // botón BOOT

#define PART_BOUNDARY "123456789000000000000987654321"
static const char *STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char *STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static httpd_handle_t servidorWeb = NULL;
static httpd_handle_t servidorStream = NULL;

static volatile bool alertaActiva = false;
static uint32_t totalAlertas = 0;  // solo lo tocan los manejadores HTTP
static const char *nombreSensor = "desconocido";

static const char PAGINA_INICIO[] = R"HTML(<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>XIAO - Deteccion de caidas</title>
<style>
body{font-family:sans-serif;margin:0;padding:16px;background:#111;color:#eee}
img{width:100%;max-width:640px;border-radius:8px;background:#222}
#estado{font-size:1.4em;margin:12px 0;padding:8px 12px;border-radius:6px;background:#264d26}
#estado.alerta{background:#a11}
button{font-size:1em;padding:8px 16px}
</style></head><body>
<h2>XIAO ESP32S3 Sense - Detección de caídas</h2>
<div id="estado">Sin alerta</div>
<button onclick="fetch('/alerta?estado=0').then(actualizar)">Apagar alarma</button>
<p><img id="video" alt="video"></p>
<p><small>Si el detector en la PC está leyendo el video, aquí puede no verse:
la placa atiende un solo video a la vez.</small></p>
<script>
document.getElementById('video').src = location.protocol + '//' + location.hostname + ':81/stream';
function actualizar(){
  fetch('/estado').then(r => r.json()).then(e => {
    const d = document.getElementById('estado');
    d.textContent = e.alerta ? 'CAIDA DETECTADA' : 'Sin alerta';
    d.className = e.alerta ? 'alerta' : '';
  }).catch(() => {});
}
setInterval(actualizar, 1000); actualizar();
</script></body></html>)HTML";

// Solo cambia la bandera; loop() es quien maneja el LED y el zumbador.
static void ponerAlerta(bool activa) {
  if (activa && !alertaActiva) {
    totalAlertas++;
  }
  alertaActiva = activa;
  Serial.printf("Alarma %s\n", activa ? "ACTIVADA" : "apagada");
}

static void ponerSalidas(bool encendidas) {
  digitalWrite(PIN_LED, encendidas ? LOW : HIGH);
#if PIN_ZUMBADOR >= 0
  digitalWrite(PIN_ZUMBADOR, encendidas ? HIGH : LOW);
#endif
}

static esp_err_t manejadorInicio(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  return httpd_resp_send(req, PAGINA_INICIO, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t manejadorCaptura(httpd_req_t *req) {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Content-Disposition", "inline; filename=captura.jpg");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return res;
}

static esp_err_t manejadorEstado(httpd_req_t *req) {
  char json[256];
  snprintf(json, sizeof(json),
           "{\"alerta\":%s,\"alertas\":%lu,\"sensor\":\"%s\",\"rssi\":%d,"
           "\"psram\":%s,\"segundos_encendida\":%lu}",
           alertaActiva ? "true" : "false", (unsigned long)totalAlertas, nombreSensor,
           WiFi.RSSI(), psramFound() ? "true" : "false", (unsigned long)(millis() / 1000));
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  return httpd_resp_send(req, json, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t manejadorAlerta(httpd_req_t *req) {
  char consulta[32];
  char valor[4];
  if (httpd_req_get_url_query_str(req, consulta, sizeof(consulta)) == ESP_OK &&
      httpd_query_key_value(consulta, "estado", valor, sizeof(valor)) == ESP_OK) {
    ponerAlerta(valor[0] == '1');
  }
  return manejadorEstado(req);
}

static esp_err_t manejadorStream(httpd_req_t *req) {
  char cabecera[64];
  esp_err_t res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
  if (res != ESP_OK) {
    return res;
  }
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  Serial.println("Cliente de video conectado");

  while (true) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
      Serial.println("No se pudo capturar un cuadro");
      res = ESP_FAIL;
      break;
    }
    size_t largo = snprintf(cabecera, sizeof(cabecera), STREAM_PART, fb->len);
    res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));
    if (res == ESP_OK) {
      res = httpd_resp_send_chunk(req, cabecera, largo);
    }
    if (res == ESP_OK) {
      res = httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len);
    }
    esp_camera_fb_return(fb);
    if (res != ESP_OK) {
      break;
    }
  }
  Serial.println("Cliente de video desconectado");
  return res;
}

static void iniciarServidores() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  config.ctrl_port = 32768;

  httpd_uri_t rutaInicio = {.uri = "/", .method = HTTP_GET, .handler = manejadorInicio, .user_ctx = NULL};
  httpd_uri_t rutaCaptura = {.uri = "/capture", .method = HTTP_GET, .handler = manejadorCaptura, .user_ctx = NULL};
  httpd_uri_t rutaEstado = {.uri = "/estado", .method = HTTP_GET, .handler = manejadorEstado, .user_ctx = NULL};
  httpd_uri_t rutaAlerta = {.uri = "/alerta", .method = HTTP_GET, .handler = manejadorAlerta, .user_ctx = NULL};
  httpd_uri_t rutaStream = {.uri = "/stream", .method = HTTP_GET, .handler = manejadorStream, .user_ctx = NULL};

  if (httpd_start(&servidorWeb, &config) == ESP_OK) {
    httpd_register_uri_handler(servidorWeb, &rutaInicio);
    httpd_register_uri_handler(servidorWeb, &rutaCaptura);
    httpd_register_uri_handler(servidorWeb, &rutaEstado);
    httpd_register_uri_handler(servidorWeb, &rutaAlerta);
  }

  // El video va en otro servidor (puerto 81) para que /alerta responda aunque
  // haya un cliente leyendo el stream.
  config.server_port = 81;
  config.ctrl_port = 32769;
  if (httpd_start(&servidorStream, &config) == ESP_OK) {
    httpd_register_uri_handler(servidorStream, &rutaStream);
  }
}

static bool iniciarCamara() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = RESOLUCION;
  config.jpeg_quality = CALIDAD_JPEG;

  if (psramFound()) {
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.fb_count = 2;
    config.grab_mode = CAMERA_GRAB_LATEST;  // siempre el cuadro más reciente
  } else {
    Serial.println("AVISO: no se detectó PSRAM. Activa 'OPI PSRAM' en Herramientas.");
    config.fb_location = CAMERA_FB_IN_DRAM;
    config.fb_count = 1;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
    config.frame_size = FRAMESIZE_QVGA;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Error al iniciar la cámara: 0x%x\n", err);
    return false;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s->id.PID == OV2640_PID) {
    nombreSensor = "OV2640";
  } else if (s->id.PID == OV3660_PID) {
    nombreSensor = "OV3660";
    // El OV3660 entrega la imagen volteada y algo saturada
    s->set_vflip(s, 1);
    s->set_brightness(s, 1);
    s->set_saturation(s, -2);
  } else if (s->id.PID == OV5640_PID) {
    nombreSensor = "OV5640";
    s->set_vflip(s, 1);
  }
  Serial.printf("Cámara lista (%s)\n", nombreSensor);
  return true;
}

static void conectarWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);  // sin ahorro de energía: más FPS y menos cortes
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.printf("Conectando a %s", WIFI_SSID);
  bool led = false;
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
    led = !led;
    digitalWrite(PIN_LED, led ? LOW : HIGH);
  }
  digitalWrite(PIN_LED, HIGH);
  Serial.println();
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BOTON, INPUT_PULLUP);
#if PIN_ZUMBADOR >= 0
  pinMode(PIN_ZUMBADOR, OUTPUT);
#endif
  ponerSalidas(false);

  if (!iniciarCamara()) {
    Serial.println("Revisa que el módulo de cámara esté bien conectado. Reiniciando en 5 s...");
    delay(5000);
    ESP.restart();
  }

  conectarWiFi();
  if (MDNS.begin(NOMBRE_MDNS)) {
    MDNS.addService("http", "tcp", 80);
  }
  iniciarServidores();

  String ip = WiFi.localIP().toString();
  Serial.println("Listo.");
  Serial.printf("  Página:  http://%s/  (o http://%s.local/)\n", ip.c_str(), NOMBRE_MDNS);
  Serial.printf("  Video:   http://%s:81/stream\n", ip.c_str());
  Serial.printf("  En la PC: python detector_caidas.py --fuente http://%s:81/stream\n", ip.c_str());
}

void loop() {
  static uint32_t ultimoParpadeo = 0;
  static uint32_t ultimaRevisionWiFi = 0;
  static bool salidasEncendidas = false;
  uint32_t ahora = millis();

  if (alertaActiva) {
    if (ahora - ultimoParpadeo >= 250) {
      ultimoParpadeo = ahora;
      salidasEncendidas = !salidasEncendidas;
      ponerSalidas(salidasEncendidas);
    }
  } else if (salidasEncendidas) {
    salidasEncendidas = false;
    ponerSalidas(false);
  }

  if (alertaActiva && digitalRead(PIN_BOTON) == LOW) {
    ponerAlerta(false);
  }

  if (ahora - ultimaRevisionWiFi >= 10000) {
    ultimaRevisionWiFi = ahora;
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("WiFi desconectado, reconectando...");
      WiFi.reconnect();
    }
  }

  delay(20);
}
