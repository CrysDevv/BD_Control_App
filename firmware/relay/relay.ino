/*
  bd_control_app - ESP32 de relé (puede haber varios, cada uno con su DEVICE_ID)
  - Modo punto de acceso (por defecto): emite la red Wi-Fi BODEGA_NET (SoftAP) para los
    sensores y los teléfonos. Solo UN relé puede hacerlo.
  - Modo router (#define NETWORK_MODE_STA en config.h): se une a tu router como cualquier
    otro dispositivo. Necesario si hay más de un relé. En ese modo conviene además definir
    FORWARD_SENSOR_UDP 0, porque los sensores ya llegan directo a los teléfonos.
  - Atiende comandos TCP cifrados (AES-256-GCM) para encender o apagar el ventilador.
    Cada comando trae el id del relé destinatario y este lo ignora si no es el suyo.
  - MODO SEGURO: la app le envía un latido (comando HB) mientras su lazo de control está sano. Si pasan
    FAILSAFE_SILENCE_MIN minutos sin oír nada válido de la app, el relé ventila por ciclos por sí solo
    (ver relay_failsafe.h). Así la bodega no queda desprotegida si la app o el enlace fallan.
  - Anuncia su propio estado por UDP, con su id, para que la app lo descubra y conozca su IP:
      {"src":"relay","id":"rele-1","fan":0}

  Configurable en config.h (todo opcional):
    #define DEVICE_ID "rele-bodega"     // único por relé; por defecto "rele-1"
    #define NETWORK_MODE_STA            // unirse al router en vez de crear BODEGA_NET
    #define FORWARD_SENSOR_UDP 0        // no reenviar la telemetría de los sensores
    #define FAILSAFE_SILENCE_MIN 10     // minutos sin app para entrar al modo seguro (0 = desactivado)
    #define FAILSAFE_MODE 0             // 0 = ventilar por ciclos, 1 = dejar como está, 2 = apagar
    #define FAILSAFE_ON_MIN 5           // modo 0: minutos encendido...
    #define FAILSAFE_PERIOD_MIN 30      // ...de cada tantos minutos

  Librería requerida (Arduino IDE > Administrador de bibliotecas): ArduinoJson (v7).
*/
#include <WiFi.h>
#include <WiFiUdp.h>
#include <ArduinoJson.h>
#include "mbedtls/gcm.h"
#include "config.h"
#include "relay_failsafe.h"

#ifndef DEVICE_ID
#define DEVICE_ID "rele-1"
#endif
#ifndef FORWARD_SENSOR_UDP
#define FORWARD_SENSOR_UDP 1
#endif
#ifndef FAILSAFE_SILENCE_MIN
#define FAILSAFE_SILENCE_MIN 10
#endif
#ifndef FAILSAFE_MODE
#define FAILSAFE_MODE 0
#endif
#ifndef FAILSAFE_ON_MIN
#define FAILSAFE_ON_MIN 5
#endif
#ifndef FAILSAFE_PERIOD_MIN
#define FAILSAFE_PERIOD_MIN 30
#endif

const int RELAY_PIN = 26;
const bool RELAY_ACTIVE_LOW = true;
const int UDP_PORT = 4210;
const int TCP_PORT = 5000;

WiFiServer server(TCP_PORT);
WiFiUDP udp;
bool fanOn = false;
uint32_t lastAnnounceMs = 0;

bd::FailSafeConfig makeFailSafeConfig() {
  bd::FailSafeConfig c;
  // 0 = desactivado: un silencio de ~24 días, que en la práctica nunca se cumple
  c.silenceMs = FAILSAFE_SILENCE_MIN == 0 ? 0x7FFFFFFFUL : static_cast<uint32_t>(FAILSAFE_SILENCE_MIN) * 60000UL;
  c.mode = static_cast<bd::FailSafeMode>(FAILSAFE_MODE);
  c.onMs = static_cast<uint32_t>(FAILSAFE_ON_MIN) * 60000UL;
  c.periodMs = static_cast<uint32_t>(FAILSAFE_PERIOD_MIN) * 60000UL;
  return c;
}
bd::FailSafe failSafe(makeFailSafeConfig());

void setFan(bool on) {
  fanOn = on;
  digitalWrite(RELAY_PIN, (on ^ RELAY_ACTIVE_LOW) ? HIGH : LOW);
}

/** Dirección de broadcast de la red en la que estamos (BODEGA_NET o el router). */
IPAddress broadcastAddress() {
#ifdef NETWORK_MODE_STA
  return WiFi.broadcastIP();
#else
  return IPAddress(192, 168, 4, 255);
#endif
}

void announceFanState() {
  char buf[96];
  snprintf(buf, sizeof buf, "{\"src\":\"relay\",\"id\":\"%s\",\"fan\":%d,\"fs\":%d}", DEVICE_ID, fanOn ? 1 : 0, failSafe.engaged() ? 1 : 0);
  udp.beginPacket(broadcastAddress(), UDP_PORT);
  udp.print(buf);
  udp.endPacket();
  lastAnnounceMs = millis();
}

/**
  Reenvía tal cual cualquier datagrama que no sea un anuncio de relé (es decir, de un sensor).
  Solo hace falta cuando este relé es el punto de acceso. Se descartan TODOS los anuncios de
  relé (no solo el propio) para no formar bucles si hay varios relés.
*/
void forwardSensorReadings() {
  int size = udp.parsePacket(); // siempre se consume el datagrama, aunque no se reenvíe, para no acumularlos
  if (size <= 0) return;
#if FORWARD_SENSOR_UDP
  char buf[128];
  int n = udp.read(buf, sizeof buf - 1);
  buf[n] = 0;
  if (strstr(buf, "\"src\":\"relay\"") != nullptr) return; // evita reenviar anuncios de relés
  udp.beginPacket(broadcastAddress(), UDP_PORT);
  udp.print(buf);
  udp.endPacket();
#endif
}

// ---------- AES-256-GCM (sin intercambio de nonce: IV nuevo por mensaje, va incluido en el frame) ----------
// Formato de frame: [len:2][IV:12][ciphertext:n][tag:16]
bool gcmDecrypt(const uint8_t* body, size_t len, uint8_t* out) {
  if (len < 28) return false;
  size_t ctLen = len - 28;
  mbedtls_gcm_context ctx;
  mbedtls_gcm_init(&ctx);
  mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, AES_KEY, 256);
  int r = mbedtls_gcm_auth_decrypt(&ctx, ctLen, body, 12, nullptr, 0, body + 12 + ctLen, 16, body + 12, out);
  mbedtls_gcm_free(&ctx);
  return r == 0;
}

size_t gcmEncrypt(const uint8_t* plain, size_t n, uint8_t* frame) {
  uint8_t* iv = frame + 2;
  uint8_t* ct = frame + 14;
  uint8_t tag[16];
  esp_fill_random(iv, 12);
  mbedtls_gcm_context ctx;
  mbedtls_gcm_init(&ctx);
  mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, AES_KEY, 256);
  mbedtls_gcm_crypt_and_tag(&ctx, MBEDTLS_GCM_ENCRYPT, n, iv, 12, nullptr, 0, plain, ct, 16, tag);
  mbedtls_gcm_free(&ctx);
  memcpy(ct + n, tag, 16);
  size_t bodyLen = 12 + n + 16;
  frame[0] = (uint8_t)(bodyLen >> 8);
  frame[1] = (uint8_t)(bodyLen & 0xFF);
  return 2 + bodyLen;
}

/**
  Modo seguro: si la app lleva mucho sin dar señales, ordena el ventilador según el modo configurado.
  Se llama desde loop() y también al recibir un comando (para salir del modo seguro al instante).
*/
void applyFailSafe() {
  const bd::FanOrder order = failSafe.update(millis());
  const bd::FailSafeEvent event = failSafe.takeEvent();
  if (event == bd::FailSafeEvent::Entered) Serial.println("[CONTINGENCIA] sin senales de la app: MODO SEGURO activado");
  if (event == bd::FailSafeEvent::Left) Serial.println("[CONTINGENCIA] la app volvio: modo normal");
  if (order != bd::FanOrder::None) {
    const bool want = (order == bd::FanOrder::On);
    if (want != fanOn) {
      setFan(want);
      announceFanState();
    }
  }
  if (event != bd::FailSafeEvent::None) announceFanState();  // que la app se entere del cambio enseguida
}

void handleClient(WiFiClient& client) {
  client.setTimeout(3000);

  uint8_t hdr[2];
  if (client.readBytes(hdr, 2) != 2) return;
  size_t len = ((size_t)hdr[0] << 8) | hdr[1];
  if (len < 28 || len > 200) return;

  uint8_t body[200];
  if (client.readBytes(body, len) != len) return;

  uint8_t plain[200];
  if (!gcmDecrypt(body, len, plain)) {
    Serial.println("[SEGURIDAD] comando invalido (tag GCM o llave incorrecta)");
    return;
  }
  plain[len - 28] = 0;

  JsonDocument d;
  if (deserializeJson(d, (char*)plain) != DeserializationError::Ok) return;
  String cmd = d["cmd"] | "";
  int val = d["val"] | -1;
  const bool isFan = (cmd == "FAN" && val >= 0);
  const bool isHeartbeat = (cmd == "HB");   // latido: "sigo aquí", sin cambiar nada
  if (!isFan && !isHeartbeat) return;

  // Validación del destinatario: la app incluye el id del relé al que va dirigido el comando.
  // Si no es el de ESTE relé se ignora (sin responder, la app lo reporta como error). Así un comando
  // cifrado capturado o mal enrutado hacia otro relé no se ejecuta. Si no trae "id" (app antigua),
  // se acepta, por compatibilidad.
  const char* target = d["id"] | "";
  if (target[0] != '\0' && strcmp(target, DEVICE_ID) != 0) {
    Serial.print("[SEGURIDAD] comando dirigido a otro rele (");
    Serial.print(target);
    Serial.println("), ignorado");
    return;
  }

  // Comando válido y autenticado (llegó hasta aquí tras GCM + id): la app está viva.
  failSafe.contact(millis());
  applyFailSafe();                          // si estaba en modo seguro, sale ahora mismo
  if (isFan) setFan(val == 1);
  announceFanState();

  char reply[40];
  int n = snprintf(reply, sizeof reply, "{\"ok\":1,\"fan\":%d,\"fs\":%d}", fanOn ? 1 : 0, failSafe.engaged() ? 1 : 0);
  uint8_t frame[96];
  size_t frameLen = gcmEncrypt((uint8_t*)reply, n, frame);
  client.write(frame, frameLen);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  loadKeys();

  pinMode(RELAY_PIN, OUTPUT);
  setFan(false);
  failSafe.begin(millis());  // tras un corte de luz sin app también se llega al modo seguro

#ifdef NETWORK_MODE_STA
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Conectando a ");
  Serial.println(WIFI_SSID);
  while (WiFi.status() != WL_CONNECTED) {
    delay(300);
    Serial.print(".");
  }
  Serial.println();
  Serial.print("Conectado, IP: ");
  Serial.println(WiFi.localIP());
#else
  WiFi.mode(WIFI_AP);
  WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1), IPAddress(255, 255, 255, 0));
  // canal 1, SSID visible, hasta 8 clientes (el valor por defecto de la librería es solo 4)
  WiFi.softAP(WIFI_SSID, WIFI_PASS, 1, 0, 8);
  Serial.print("SoftAP IP: ");
  Serial.println(WiFi.softAPIP());
#endif

  server.begin();
  udp.begin(UDP_PORT);

  Serial.println("Rele listo.");
}

void loop() {
#ifdef NETWORK_MODE_STA
  // Si el router se cae o se reinicia, volver a unirse (rate-limited: no bloquea el servidor TCP).
  static uint32_t lastReconnectMs = 0;
  if (WiFi.status() != WL_CONNECTED && millis() - lastReconnectMs > 5000) {
    lastReconnectMs = millis();
    Serial.println("Wi-Fi desconectado, reintentando...");
    WiFi.reconnect();
  }
#endif

  applyFailSafe();

  forwardSensorReadings();

  WiFiClient client = server.available();
  if (client) {
    handleClient(client);
    client.stop();
  }

  if (millis() - lastAnnounceMs > 5000) announceFanState(); // heartbeat del estado
}
