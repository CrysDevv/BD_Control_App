/*
  bd_control_app - ESP32 del sensor (DHT11)

  TRANSPORTE PRINCIPAL: cable USB-C al teléfono (comunicación serial, 115200 baudios 8N1).
  El teléfono alimenta la placa por el mismo cable, así que el sensor no necesita fuente propia
  ni Wi-Fi. El protocolo (tramas con CRC-16) está en bd_serial.h; léelo ahí.

    ESP32 -> app:  $TLM,sensor-1,<bootId>,12,24.5,60.0*CRC   una lectura cada <intervalo> (por defecto 2 s)
                   $HELLO,0,sensor-1,2.0,2000,<bootId>*CRC   saludo al arrancar
    app -> ESP32:  $SET,5,INTERVAL,10000*CRC                 cambia cada cuánto mide (1 a 600 s); queda guardado
                   $GET / $PING / $INFO                      lectura inmediata / ¿sigues vivo? / preséntate
                   $SYNC / $CONF                             recuperar lecturas guardadas / confirmar que ya las guardó

  BUFFER: si la app no está escuchando (cerrada, teléfono bloqueado) pero el cable sigue conectado, el
  sensor guarda las lecturas en RAM (hasta BD_BUFFER_CAPACITY, por defecto 1800 = 1 h a 2 s, 30 h a 60 s)
  y la app las recupera al volver. Con el cable desenchufado la placa se APAGA y el buffer se pierde.

  IMPORTANTE: por el puerto serie solo deben salir tramas del protocolo. NO agregues Serial.print()
  de depuración en este sketch: la app los ignora (los trata como ruido), pero ensucian el enlace.

  OPCIONAL - además por Wi-Fi (UDP broadcast, como antes): en config.h agrega
    #define SENSOR_WIFI_TELEMETRY
    #define WIFI_SSID "BODEGA_NET"
    #define WIFI_PASS "..."
  Con Wi-Fi apagado (lo normal) la radio ni se inicia, lo que ahorra energía.

  Varios sensores: flashea cada uno con un DEVICE_ID distinto en config.h:  #define DEVICE_ID "sensor-bodega"
  (config.h es opcional; si no existe se usa "sensor-1".)

  ENERGÍA. Esta placa se alimenta de la batería del teléfono por el cable, así que cada mA del sensor es batería
  del teléfono. Medidas aplicadas: Wi-Fi y Bluetooth apagados (el Wi-Fi activo llega a 95-240 mA) y CPU a 80 MHz en
  lugar de los 240 MHz de fábrica (la hoja de datos del ESP32 da 20-31 mA a 80 MHz contra 30-68 mA a 240 MHz, con la
  radio apagada). Para otra frecuencia: #define BD_CPU_MHZ 160 en config.h (no bajar de 80: por debajo cambia el reloj de
  los periféricos y el UART exige reajustar el baudrate; si el DHT11 diera lecturas inválidas a 80 MHz, usa 160). Detalle y cifras: docs/energia.md.

  Librerías (Arduino IDE > Administrador de bibliotecas):
    - DHT sensor library (Adafruit)
    - Adafruit Unified Sensor
  Placa: "ESP32 Dev Module" (o la que uses). Preferences viene incluida con el soporte ESP32.
*/
#include <Arduino.h>
#include <DHT.h>
#include <Preferences.h>
#if __has_include(<esp_random.h>)
#include <esp_random.h>
#else
#include <esp_system.h>
#endif

#if __has_include("config.h")
#include "config.h"
#endif
#include "bd_serial.h"

#ifndef DEVICE_ID
#define DEVICE_ID "sensor-1"
#endif
#ifndef BD_CPU_MHZ
#define BD_CPU_MHZ 80  // 80 / 160 / 240. Menos MHz = menos corriente (ver docs/energia.md)
#endif
#define FW_VERSION "2.0"

#define DHTPIN 4
#define DHTTYPE DHT11
#define SERIAL_BAUD 115200

#ifdef SENSOR_WIFI_TELEMETRY
#include <WiFi.h>
#include <WiFiUdp.h>
#define UDP_PORT 4210
WiFiUDP udp;
#endif

/** Escribe en el puerto serie USB (el que ve el teléfono). */
class UsbSerialSink : public bd::ByteSink {
 public:
  void write(const char* data, size_t len) override {
    Serial.write(reinterpret_cast<const uint8_t*>(data), len);
  }
};

DHT dht(DHTPIN, DHTTYPE);
Preferences prefs;
UsbSerialSink sink;
bd::SensorCore* core = nullptr;  // se crea en setup() con el intervalo guardado
uint32_t lastReadMs = 0;

#ifdef SENSOR_WIFI_TELEMETRY
void sendWifi(float t, float h) {
  if (WiFi.status() != WL_CONNECTED) return;  // se reconecta solo (setAutoReconnect); no bloquea el enlace USB
  char buf[128];
  snprintf(buf, sizeof buf, "{\"id\":\"%s\",\"t\":%.1f,\"h\":%.1f}", DEVICE_ID, t, h);
  udp.beginPacket(WiFi.broadcastIP(), UDP_PORT);
  udp.print(buf);
  udp.endPacket();
}
#endif

void setup() {
  // ENERGÍA: bajar la frecuencia ANTES de abrir el puerto serie (así el divisor del UART se calcula con el reloj final)
  setCpuFrequencyMhz(BD_CPU_MHZ);
#ifndef SENSOR_WIFI_TELEMETRY
  btStop();  // sin Bluetooth; el Wi-Fi no se inicializa en ningún momento (no hay WiFi.begin) => radio apagada
#endif
  Serial.begin(SERIAL_BAUD);
  dht.begin();

  // El intervalo elegido desde la app sobrevive a reinicios y desconexiones del cable.
  prefs.begin("bdsensor", false);
  uint32_t interval = prefs.getUInt("interval", bd::kDefaultIntervalMs);
  if (interval < bd::kMinIntervalMs || interval > bd::kMaxIntervalMs) interval = bd::kDefaultIntervalMs;

  // bootId: número aleatorio distinto en cada arranque. Junto con el contador de lecturas identifica
  // cada medición de forma única, para que la app y Supabase nunca dupliquen una.
  static bd::SensorCore instance(sink, DEVICE_ID, FW_VERSION, interval, bd::kMinIntervalMs, esp_random());
  core = &instance;
  core->setNow(millis());

#ifdef SENSOR_WIFI_TELEMETRY
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);  // sin esperar: el sensor responde por USB aunque no haya Wi-Fi
#endif

  core->begin();  // saludo: la app se entera de quién es este sensor
  lastReadMs = millis();
}

void loop() {
  core->setNow(millis());

  // 1) Instrucciones que llegan de la app por el cable (se procesan unos pocos bytes por vuelta para no monopolizar el loop)
  for (int i = 0; i < 64 && Serial.available(); i++) core->onByte(static_cast<uint8_t>(Serial.read()));

  // 1b) Si la app pidió un SYNC, enviar unas pocas lecturas guardadas por vuelta (sin bloquear el loop)
  core->pump();

  // 2) Si la app cambió el intervalo, guardarlo (solo cuando cambia, para no desgastar la memoria flash)
  uint32_t newInterval;
  if (core->takeIntervalChanged(newInterval)) prefs.putUInt("interval", newInterval);

  // 3) Medir y enviar: por intervalo, o de inmediato si la app lo pidió ($GET)
  const bool requested = core->takeReadRequest();
  if (!requested && millis() - lastReadMs < core->intervalMs()) return;
  lastReadMs = millis();

  const float t = dht.readTemperature();
  const float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) return;  // lectura inválida (cable suelto): se omite, sin inventar datos

  core->recordMeasurement(t, h);  // la guarda en el buffer y, si la app escucha, la envía en vivo
#ifdef SENSOR_WIFI_TELEMETRY
  sendWifi(t, h);
#endif
}
