/*
 * ESP32 - Control de Ventilación y Sensores por Bluetooth y Wi-Fi (Modo Dual)
 * Proyecto: BD_Control_App
 *
 * Características:
 * - Comunicación Bluetooth Serial (SPP) con nombre "ESP32_BD_Control"
 * - Conexión Wi-Fi a SSID: "Redmi_Note_13_Pro_5G" (Password: "1234554321")
 * - Emisión de telemetría por UDP Broadcast (puerto 4210) y por Bluetooth Serial
 * - Lectura de Sensor DHT11 (GPIO 4)
 * - Control de Relé de Ventilador (GPIO 26)
 * - Umbral de Humedad local: Encendido automático a >= 65% y Apagado a <= 55%
 * - Modo Seguro (Failsafe): Si se pierde conexión por más de 10 min,
 *   cicla automáticamente el ventilador para mantener la bodega ventilada.
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include "BluetoothSerial.h"
#include "DHT.h"

// ============================================================================
// CONFIGURACIÓN DE RED Y PINES
// ============================================================================
#define DEVICE_ID           "esp32"
#define BT_NAME             "ESP32_BD_Control"

#define WIFI_SSID           "Redmi_Note_13_Pro_5G"
#define WIFI_PASS           "1234554321"
#define UDP_PORT            4210

#define PIN_DHT             4        // Pin de datos del sensor DHT11
#define DHT_TYPE            DHT11    // Usando DHT11

#define PIN_RELAY           26       // Pin de control del relé del ventilador
#define RELAY_ON_LEVEL      HIGH     // HIGH si el relé activa con nivel alto, LOW si activa con nivel bajo

// ============================================================================
// UMBRAL DE HUMEDAD (CONTROL AUTOMÁTICO LOCAL EN ESP32)
// ============================================================================
#define HUMIDITY_CONTROL_ENABLED true   // Encendido/Apagado automático local por umbral
#define HUMIDITY_THRESHOLD_ON    65.0   // Encender ventilador si humedad >= 65.0%
#define HUMIDITY_THRESHOLD_OFF   55.0   // Apagar ventilador si humedad <= 55.0%

#define TELEMETRY_INTERVAL_MS 3000   // Enviar datos cada 3 segundos
#define FAILSAFE_TIMEOUT_MS   600000 // 10 minutos sin latido activa Modo Seguro

#define FAILSAFE_ON_MS        300000 // Modo seguro: 5 min ENCENDIDO
#define FAILSAFE_OFF_MS       600000 // Modo seguro: 10 min APAGADO

// ============================================================================
// OBJETOS Y VARIABLES GLOBALES
// ============================================================================
BluetoothSerial SerialBT;
DHT dht(PIN_DHT, DHT_TYPE);
WiFiUDP udp;

bool fanState = false;
bool failsafeMode = false;

unsigned long lastTelemetryMs = 0;
unsigned long lastHeartbeatMs = 0;
unsigned long failsafeCycleMs = 0;

// ============================================================================
// FUNCIONES AUXILIARES DE RELÉ
// ============================================================================
void setRelay(bool turnOn) {
    fanState = turnOn;
    digitalWrite(PIN_RELAY, turnOn ? RELAY_ON_LEVEL : !RELAY_ON_LEVEL);
}

// ============================================================================
// CONTROL AUTOMÁTICO POR UMBRAL DE HUMEDAD
// ============================================================================
void checkHumidityThreshold(float hum) {
    if (!HUMIDITY_CONTROL_ENABLED || failsafeMode || isnan(hum)) return;

    if (hum >= HUMIDITY_THRESHOLD_ON && !fanState) {
        setRelay(true);
        Serial.println("[AUTO ESP32] Humedad >= 65%: Ventilador ENCENDIDO");
    } else if (hum <= HUMIDITY_THRESHOLD_OFF && fanState) {
        setRelay(false);
        Serial.println("[AUTO ESP32] Humedad <= 55%: Ventilador APAGADO");
    }
}

// ============================================================================
// PROCESAMIENTO DE COMANDOS RECIBIDOS
// ============================================================================
void processIncomingLine(String line) {
    line.trim();
    if (line.length() == 0) return;

    // Reiniciar temporizador de latido al recibir cualquier mensaje válido
    lastHeartbeatMs = millis();
    if (failsafeMode) {
        failsafeMode = false; // Salir de modo seguro al recuperar comunicación
    }

    // Comandos JSON desde Bluetooth o Wi-Fi
    if (line.indexOf("set_fan") >= 0 || line.indexOf("\"cmd\":\"FAN\"") >= 0 || line.indexOf("FAN") >= 0) {
        if (line.indexOf("\"on\":true") >= 0 || line.indexOf("\"on\": true") >= 0 || line.indexOf("\"val\":1") >= 0 || line.indexOf("SET_FAN:1") >= 0 || line.indexOf("FAN:1") >= 0) {
            setRelay(true);
        } else if (line.indexOf("\"on\":false") >= 0 || line.indexOf("\"on\": false") >= 0 || line.indexOf("\"val\":0") >= 0 || line.indexOf("SET_FAN:0") >= 0 || line.indexOf("FAN:0") >= 0) {
            setRelay(false);
        }
    }
}

// ============================================================================
// LÓGICA MODO SEGURO (FAILSAFE)
// ============================================================================
void checkFailsafe() {
    unsigned long now = millis();

    // Si pasa más del tiempo límite sin recibir comandos/latidos de la app
    if (now - lastHeartbeatMs > FAILSAFE_TIMEOUT_MS) {
        failsafeMode = true;
    }

    if (failsafeMode) {
        // En modo seguro ciclar ventilador: 5 min ON / 10 min OFF
        unsigned long elapsedInCycle = (now - failsafeCycleMs) % (FAILSAFE_ON_MS + FAILSAFE_OFF_MS);
        if (elapsedInCycle < FAILSAFE_ON_MS) {
            setRelay(true);
        } else {
            setRelay(false);
        }
    }
}

// ============================================================================
// SETUP
// ============================================================================
void setup() {
    Serial.begin(115200);

    // Configuración de pin de relé
    pinMode(PIN_RELAY, OUTPUT);
    setRelay(false);

    // Inicializar sensor DHT11
    dht.begin();

    // Inicializar Bluetooth Serial
    if (!SerialBT.begin(BT_NAME)) {
        Serial.println("Error al iniciar Bluetooth Serial");
    } else {
        Serial.print("Bluetooth Serial listo. Nombre: ");
        Serial.println(BT_NAME);
    }

    // Inicializar Wi-Fi STA para conectarse a Redmi_Note_13_Pro_5G
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    Serial.print("Conectando a Wi-Fi: ");
    Serial.println(WIFI_SSID);

    udp.begin(UDP_PORT);

    lastHeartbeatMs = millis();
    failsafeCycleMs = millis();
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================
void loop() {
    unsigned long now = millis();

    // 1. Leer comandos desde Bluetooth Serial
    while (SerialBT.available()) {
        String line = SerialBT.readStringUntil('\n');
        processIncomingLine(line);
    }

    // 2. Leer comandos UDP por Wi-Fi
    int packetSize = udp.parsePacket();
    if (packetSize > 0) {
        char packetBuffer[256];
        int len = udp.read(packetBuffer, 255);
        if (len > 0) packetBuffer[len] = 0;
        processIncomingLine(String(packetBuffer));
    }

    // 3. Verificar estado de Modo Seguro (Failsafe)
    checkFailsafe();

    // 4. Enviar telemetría periódica por Bluetooth y por Wi-Fi UDP
    if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
        lastTelemetryMs = now;

        float temp = dht.readTemperature();
        float hum = dht.readHumidity();

        if (isnan(temp)) temp = 0.0;
        if (isnan(hum)) hum = 0.0;

        // Evaluar umbral de humedad local en el ESP32
        checkHumidityThreshold(hum);

        // --- Telemetría por Bluetooth (Formato SPP JSON) ---
        String btJson = "{\"id\":\"" + String(DEVICE_ID) + "\","
                      + "\"temp\":" + String(temp, 1) + ","
                      + "\"hum\":" + String(hum, 1) + ","
                      + "\"fan\":" + (fanState ? "true" : "false") + ","
                      + "\"failsafe\":" + (failsafeMode ? "true" : "false") + "}\n";

        SerialBT.print(btJson);
        Serial.print("Enviado BT: ");
        Serial.print(btJson);

        // --- Telemetría por Wi-Fi (Formato UDP Broadcast) ---
        if (WiFi.status() == WL_CONNECTED) {
            String wifiSensorJson = "{\"id\":\"" + String(DEVICE_ID) + "\",\"t\":" + String(temp, 1) + ",\"h\":" + String(hum, 1) + "}";
            String wifiRelayJson = "{\"src\":\"relay\",\"id\":\"" + String(DEVICE_ID) + "\",\"fan\":" + (fanState ? "1" : "0") + ",\"fs\":" + (failsafeMode ? "1" : "0") + "}";

            udp.beginPacket(IPAddress(255, 255, 255, 255), UDP_PORT);
            udp.print(wifiSensorJson);
            udp.endPacket();

            udp.beginPacket(IPAddress(255, 255, 255, 255), UDP_PORT);
            udp.print(wifiRelayJson);
            udp.endPacket();
        }
    }

    delay(20);
}