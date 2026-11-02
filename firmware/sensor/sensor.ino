/*
  bd_control_app - ESP32 #1 (Sensor)
  Se conecta como cliente a la red BODEGA_NET (emitida por el ESP32 #2) y
  envía temperatura y humedad por UDP broadcast cada 2 segundos.

  Librerías requeridas (Arduino IDE > Administrador de bibliotecas):
    - DHT sensor library (Adafruit)
    - Adafruit Unified Sensor
*/
#include <WiFi.h>
#include <WiFiUdp.h>
#include <DHT.h>
#include "config.h"

#define DHTPIN 4
#define DHTTYPE DHT11
#define UDP_PORT 4210
#define READ_INTERVAL_MS 2000

DHT dht(DHTPIN, DHTTYPE);
WiFiUDP udp;
uint32_t lastMs = 0;

void setup() {
  Serial.begin(115200);
  delay(200);
  dht.begin();

  WiFi.mode(WIFI_STA);
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
}

void loop() {
  if (millis() - lastMs < READ_INTERVAL_MS) return;
  lastMs = millis();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Wi-Fi desconectado, reintentando...");
    WiFi.reconnect();
    return;
  }

  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) {
    Serial.println("[DHT11] lectura invalida, se omite este ciclo");
    return;
  }

  char buf[96];
  snprintf(buf, sizeof buf, "{\"t\":%.1f,\"h\":%.1f}", t, h);

  udp.beginPacket(IPAddress(192, 168, 4, 255), UDP_PORT);
  udp.print(buf);
  udp.endPacket();

  Serial.printf("Enviado: %s\n", buf);
}
