/*
  bd_control_app - ESP32 #2 (Relé)
  - Emite el punto de acceso Wi-Fi BODEGA_NET (SoftAP) para el sensor y los teléfonos.
  - Reenvía por UDP broadcast la telemetría que llega del sensor.
  - Atiende comandos TCP cifrados (AES-256-GCM) para encender o apagar el ventilador.
  - Anuncia su propio estado por UDP para que varios teléfonos lo vean sincronizado.

  Librería requerida (Arduino IDE > Administrador de bibliotecas): ArduinoJson (v7).
*/
#include <WiFi.h>
#include <WiFiUdp.h>
#include <ArduinoJson.h>
#include "mbedtls/gcm.h"
#include "config.h"

const int RELAY_PIN = 26;
const bool RELAY_ACTIVE_LOW = true;
const int UDP_PORT = 4210;
const int TCP_PORT = 5000;

WiFiServer server(TCP_PORT);
WiFiUDP udp;
bool fanOn = false;
uint32_t lastAnnounceMs = 0;

void setFan(bool on) {
  fanOn = on;
  digitalWrite(RELAY_PIN, (on ^ RELAY_ACTIVE_LOW) ? HIGH : LOW);
}

void announceFanState() {
  char buf[48];
  snprintf(buf, sizeof buf, "{\"src\":\"relay\",\"fan\":%d}", fanOn ? 1 : 0);
  udp.beginPacket(IPAddress(192, 168, 4, 255), UDP_PORT);
  udp.print(buf);
  udp.endPacket();
  lastAnnounceMs = millis();
}

/** Reenvía tal cual cualquier datagrama que no sea el propio anuncio del relé (es decir, del sensor). */
void forwardSensorReadings() {
  int size = udp.parsePacket();
  if (size <= 0) return;
  char buf[128];
  int n = udp.read(buf, sizeof buf - 1);
  buf[n] = 0;
  if (strstr(buf, "\"src\":\"relay\"") != nullptr) return; // evita reenviar su propio anuncio
  udp.beginPacket(IPAddress(192, 168, 4, 255), UDP_PORT);
  udp.print(buf);
  udp.endPacket();
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
  if (cmd != "FAN" || val < 0) return;

  setFan(val == 1);
  announceFanState();

  char reply[32];
  int n = snprintf(reply, sizeof reply, "{\"ok\":1,\"fan\":%d}", fanOn ? 1 : 0);
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

  WiFi.mode(WIFI_AP);
  WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1), IPAddress(255, 255, 255, 0));
  WiFi.softAP(WIFI_SSID, WIFI_PASS);
  Serial.print("SoftAP IP: ");
  Serial.println(WiFi.softAPIP());

  server.begin();
  udp.begin(UDP_PORT);

  Serial.println("Rele listo.");
}

void loop() {
  forwardSensorReadings();

  WiFiClient client = server.available();
  if (client) {
    handleClient(client);
    client.stop();
  }

  if (millis() - lastAnnounceMs > 5000) announceFanState(); // heartbeat del estado
}
