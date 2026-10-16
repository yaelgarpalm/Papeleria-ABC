// Copia este archivo como "config.h" (en esta misma carpeta) y pon tus datos.
// config.h está en .gitignore para que tu contraseña de WiFi no se suba al repositorio.
#pragma once

// Red WiFi (solo 2.4 GHz: el ESP32-S3 no se conecta a redes de 5 GHz)
#define WIFI_SSID     "TU_RED_WIFI"
#define WIFI_PASSWORD "TU_CONTRASENA"

// Nombre en la red: podrás abrir http://xiao-caidas.local
#define NOMBRE_MDNS "xiao-caidas"

// Resolución del video. VGA (640x480) es un buen equilibrio entre FPS y precisión.
// Otras opciones: FRAMESIZE_QVGA (320x240), FRAMESIZE_SVGA (800x600), FRAMESIZE_HD (1280x720)
#define RESOLUCION FRAMESIZE_VGA

// Calidad JPEG: 10 = mejor imagen, 20 = archivos más pequeños (más FPS)
#define CALIDAD_JPEG 12

// Zumbador opcional para la alarma local. Pon el GPIO donde lo conectes
// (por ejemplo 1 para el pin D0 del XIAO) o -1 si no usas zumbador.
#define PIN_ZUMBADOR -1
