/*
 * ESP32 - Control de Ventilación con Supabase + Portal Cautivo Wi-Fi
 * Proyecto: BD_Control_App  (versión 2: modo auto/manual)
 *
 * REQUIERE la librería "ArduinoJson" (v7) de Benoit Blanchon
 * (Arduino IDE -> Herramientas -> Administrar bibliotecas -> ArduinoJson)
 *
 * Arquitectura (cada columna tiene un solo dueño):
 *   APP escribe  : mode, fan_command, hum_on, hum_off
 *   ESP32 escribe: temp_c, hum_pct, fan_state, uptime_s
 *
 * Ciclo (cada CYCLE_INTERVAL_MS):
 *   1) Leer DHT11
 *   2) GET configuración (mode, fan_command, hum_on, hum_off)
 *   3) Decidir y accionar el relé
 *   4) PATCH de telemetría con el estado final del relé
 *
 * - Sin Wi-Fi guardado (o si falla): AP "ESP32_Config_Bodega" + portal en 192.168.4.1
 * - Mantén BOOT (GPIO 0) 3 s con la placa encendida para borrar el Wi-Fi guardado
 */

#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <ArduinoJson.h>
#include "DHT.h"

// ============================================================================
// CONFIGURACIÓN
// ============================================================================
#define SUPABASE_URL    "https://yxnfwmxvfnhyyefwwwsp.supabase.co"
#define SUPABASE_KEY    "sb_publishable_0KzCVYB2x68juHCht6apMQ_VVDVMI6N"
#define SUPABASE_TABLE  "bodega_status"
#define ROW_ID          1

#define AP_SSID         "ESP32_Config_Bodega"
#define RESET_PIN       0

#define PIN_DHT         4
#define DHT_TYPE        DHT11

#define PIN_RELAY       26
#define RELAY_ON_LEVEL  HIGH

// Valores por defecto (se usan hasta recibir la configuración de la app)
#define DEFAULT_HUM_ON   65.0
#define DEFAULT_HUM_OFF  55.0

#define CYCLE_INTERVAL_MS     5000   // no bajar de 2000 (límite del DHT11)
#define HTTP_TIMEOUT_MS       8000

// ============================================================================
// GLOBALES
// ============================================================================
DHT dht(PIN_DHT, DHT_TYPE);
Preferences prefs;
WebServer server(80);
DNSServer dnsServer;

bool fanState = false;         // estado real del relé
bool inConfigMode = false;
unsigned long lastCycleMs = 0;
unsigned long bootPressStart = 0;

float lastTemp = 0.0;
float lastHum = 0.0;
bool hasReading = false;       // hay al menos una lectura válida del DHT11

// Configuración recibida de la app (se conserva la última válida si falla la red)
bool  modeAuto   = true;       // true = automático, false = manual
bool  fanCommand = false;      // orden de la app (solo vale en manual)
float humOn      = DEFAULT_HUM_ON;
float humOff     = DEFAULT_HUM_OFF;

String savedSSID = "";
String savedPass = "";
String networkOptions = "";

// ============================================================================
// RELÉ Y DECISIÓN
// ============================================================================
void setRelay(bool turnOn) {
    fanState = turnOn;
    digitalWrite(PIN_RELAY, turnOn ? RELAY_ON_LEVEL : !RELAY_ON_LEVEL);
}

// Única función que decide el estado del relé
void applyControl() {
    bool desired = fanState;   // por defecto, no cambiar

    if (!modeAuto) {
        // MANUAL: manda la app, sin importar la humedad
        desired = fanCommand;
    } else if (hasReading) {
        // AUTO: histéresis por humedad
        if (lastHum >= humOn)       desired = true;
        else if (lastHum <= humOff) desired = false;
        // entre ambos umbrales se conserva el estado actual
    }

    if (desired != fanState) {
        setRelay(desired);
        Serial.printf("[RELE] %s (%s)\n",
                      desired ? "ENCENDIDO" : "APAGADO",
                      modeAuto ? "auto por humedad" : "manual por app");
    }
}

// ============================================================================
// PORTAL CAUTIVO
// ============================================================================
void buildNetworkOptions(int n) {
    networkOptions = "";
    if (n <= 0) {
        networkOptions = "<option value=''>-- Ninguna red encontrada --</option>";
        return;
    }
    for (int i = 0; i < n; ++i) {
        String s = WiFi.SSID(i);
        if (s.length() == 0) continue;
        Serial.printf("  %d: %s (%d dBm)\n", i + 1, s.c_str(), WiFi.RSSI(i));
        networkOptions += "<option value=\"" + s + "\">" + s + " (" + String(WiFi.RSSI(i)) + " dBm)</option>";
    }
}

void scanAndCacheNetworks() {
    Serial.println("[Scan] Escaneando redes...");
    WiFi.mode(WIFI_STA);
    WiFi.disconnect(true, true);
    delay(200);
    int n = WiFi.scanNetworks();
    Serial.printf("[Scan] Redes encontradas: %d\n", n);
    buildNetworkOptions(n);
    WiFi.scanDelete();
}

void handleRoot() {
    String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>";
    html += "<meta name='viewport' content='width=device-width, initial-scale=1'>";
    html += "<title>Configurar Wi-Fi</title><style>";
    html += "body{font-family:Arial,sans-serif;margin:20px;background:#f4f4f9;color:#333}";
    html += ".card{background:#fff;padding:20px;border-radius:10px;box-shadow:0 4px 6px rgba(0,0,0,.1);max-width:400px;margin:auto}";
    html += "h2{color:#007bff;margin-top:0}";
    html += "select,input{width:100%;padding:10px;margin:8px 0;border:1px solid #ccc;border-radius:5px;box-sizing:border-box}";
    html += "input[type=submit]{background:#28a745;color:#fff;font-weight:bold;border:none}";
    html += "</style></head><body><div class='card'><h2>Conectar ESP32 a Wi-Fi</h2>";
    html += "<form action='/save' method='POST'>";
    html += "<label>Redes detectadas (solo 2.4 GHz):</label><select name='ssid'>" + networkOptions + "</select>";
    html += "<label>O escribe el nombre manualmente:</label>";
    html += "<input type='text' name='ssid_manual' placeholder='Opcional'>";
    html += "<label>Contraseña Wi-Fi:</label>";
    html += "<input type='password' name='pass' placeholder='Ingresa la clave'>";
    html += "<input type='submit' value='Guardar y Conectar'></form>";
    html += "<p><a href='/rescan'>Volver a escanear</a></p></div></body></html>";
    server.send(200, "text/html", html);
}

void handleRescan() {
    int n = WiFi.scanNetworks();
    buildNetworkOptions(n);
    WiFi.scanDelete();
    server.sendHeader("Location", "/");
    server.send(302, "text/plain", "");
}

void handleSave() {
    String manualSSID = server.arg("ssid_manual");
    manualSSID.trim();
    String selectedSSID = manualSSID.length() > 0 ? manualSSID : server.arg("ssid");
    String selectedPass = server.arg("pass");

    if (selectedSSID.length() == 0) {
        server.send(400, "text/plain", "Error: selecciona o escribe una red Wi-Fi.");
        return;
    }

    prefs.begin("wificfg", false);
    prefs.putString("ssid", selectedSSID);
    prefs.putString("pass", selectedPass);
    prefs.end();
    Serial.println("[Portal] Guardado -> SSID: " + selectedSSID);

    String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>";
    html += "<meta name='viewport' content='width=device-width, initial-scale=1'></head>";
    html += "<body style='font-family:Arial;text-align:center;padding:40px;'>";
    html += "<h2>Credenciales guardadas</h2><p>Reiniciando para conectar a: <b>" + selectedSSID + "</b></p></body></html>";
    server.send(200, "text/html", html);
    delay(2000);
    ESP.restart();
}

void startCaptivePortal() {
    inConfigMode = true;

    WiFi.setAutoReconnect(false);
    WiFi.persistent(false);
    WiFi.disconnect(true, true);
    delay(200);

    scanAndCacheNetworks();

    WiFi.mode(WIFI_AP);
    delay(200);
    bool apStarted = WiFi.softAP(AP_SSID, NULL, 1, 0, 4);
    delay(500);
    WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1), IPAddress(255, 255, 255, 0));

    dnsServer.start(53, "*", WiFi.softAPIP());

    server.on("/", handleRoot);
    server.on("/rescan", handleRescan);
    server.on("/save", HTTP_POST, handleSave);
    server.onNotFound(handleRoot);
    server.begin();

    Serial.println("==================================================");
    Serial.printf("AP iniciado: %s\n", apStarted ? "SI" : "FALLO");
    Serial.println("Busca en tu telefono la red: " AP_SSID);
    Serial.print("IP del portal: ");
    Serial.println(WiFi.softAPIP());
    Serial.println("==================================================");
}

// ============================================================================
// SUPABASE
// ============================================================================
String tableEndpoint() {
    return String(SUPABASE_URL) + "/rest/v1/" + SUPABASE_TABLE + "?id=eq." + String(ROW_ID);
}

void addAuthHeaders(HTTPClient &http) {
    http.addHeader("apikey", SUPABASE_KEY);
    http.addHeader("Authorization", String("Bearer ") + SUPABASE_KEY);
}

// GET: trae la configuración que escribe la app. Devuelve true si se actualizó.
bool fetchConfig() {
    if (WiFi.status() != WL_CONNECTED) return false;

    WiFiClientSecure client;
    client.setInsecure();

    HTTPClient http;
    http.setTimeout(HTTP_TIMEOUT_MS);
    String url = tableEndpoint() + "&select=mode,fan_command,hum_on,hum_off";

    if (!http.begin(client, url)) {
        Serial.println("[Config] No se pudo iniciar la conexion");
        return false;
    }
    addAuthHeaders(http);

    int code = http.GET();
    if (code != 200) {
        if (code > 0) Serial.printf("[Config] Error HTTP %d\n", code);
        else Serial.printf("[Config] Error de conexion: %s\n", http.errorToString(code).c_str());
        http.end();
        return false;
    }

    String body = http.getString();
    http.end();

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, body);
    if (err || !doc.is<JsonArray>() || doc.as<JsonArray>().size() == 0) {
        Serial.println("[Config] Respuesta invalida o fila no encontrada");
        return false;
    }

    JsonObject row = doc[0];

    const char* m = row["mode"] | "auto";
    modeAuto = (strcmp(m, "manual") != 0);   // cualquier valor distinto de "manual" => auto
    fanCommand = row["fan_command"] | false;

    float on  = row["hum_on"]  | (float)DEFAULT_HUM_ON;
    float off = row["hum_off"] | (float)DEFAULT_HUM_OFF;
    if (on > off) {            // validación: el umbral de encendido debe ser mayor
        humOn = on;
        humOff = off;
    } else {
        Serial.println("[Config] Umbrales invalidos, se usan los ultimos validos");
    }

    Serial.printf("[Config] modo=%s | orden=%s | ON>=%.1f | OFF<=%.1f\n",
                  modeAuto ? "AUTO" : "MANUAL",
                  fanCommand ? "ON" : "OFF", humOn, humOff);
    return true;
}

// PATCH: sube solo la telemetría (nunca toca mode ni fan_command)
bool pushTelemetry() {
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("[Telemetria] Sin Wi-Fi, se omite el envio");
        return false;
    }

    WiFiClientSecure client;
    client.setInsecure();

    HTTPClient http;
    http.setTimeout(HTTP_TIMEOUT_MS);

    if (!http.begin(client, tableEndpoint())) {
        Serial.println("[Telemetria] No se pudo iniciar la conexion");
        return false;
    }
    addAuthHeaders(http);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("Prefer", "return=minimal");

    String payload = "{";
    if (hasReading) {
        payload += "\"temp_c\":" + String(lastTemp, 1) + ",";
        payload += "\"hum_pct\":" + String(lastHum, 1) + ",";
    }
    payload += "\"fan_state\":" + String(fanState ? "true" : "false") + ",";
    payload += "\"uptime_s\":" + String((unsigned long)(millis() / 1000));
    payload += "}";

    int code = http.sendRequest("PATCH", payload);
    bool ok = (code >= 200 && code < 300);

    if (ok) {
        Serial.printf("[Telemetria] OK (%d): %s\n", code, payload.c_str());
    } else if (code > 0) {
        Serial.printf("[Telemetria] Error HTTP %d: %s\n", code, http.getString().c_str());
    } else {
        Serial.printf("[Telemetria] Error de conexion: %s\n", http.errorToString(code).c_str());
    }

    http.end();
    return ok;
}

// ============================================================================
// SETUP
// ============================================================================
void setup() {
    Serial.begin(115200);
    delay(1000);

    pinMode(PIN_RELAY, OUTPUT);
    setRelay(false);
    pinMode(RESET_PIN, INPUT_PULLUP);

    dht.begin();

    Serial.println("\n--- BODEGA CONTROL v2 ---");
    Serial.println("Manten BOOT 3 s (con la placa ya encendida) para borrar el Wi-Fi.");

    prefs.begin("wificfg", true);
    savedSSID = prefs.getString("ssid", "");
    savedPass = prefs.getString("pass", "");
    prefs.end();

    if (savedSSID.length() > 0) {
        WiFi.mode(WIFI_STA);
        WiFi.begin(savedSSID.c_str(), savedPass.c_str());
        Serial.print("Conectando a: ");
        Serial.println(savedSSID);

        int attempts = 0;
        while (WiFi.status() != WL_CONNECTED && attempts < 20) {
            delay(500);
            Serial.print(".");
            attempts++;
        }

        if (WiFi.status() == WL_CONNECTED) {
            WiFi.setAutoReconnect(true);
            Serial.println("\n[OK] Conectado!");
            Serial.print("IP: ");
            Serial.println(WiFi.localIP());
            return;
        }
        Serial.printf("\n[ERROR] No conecto (status %d). Abriendo portal...\n", WiFi.status());
    } else {
        Serial.println("No hay Wi-Fi guardado.");
    }

    startCaptivePortal();
}

// ============================================================================
// LOOP
// ============================================================================
void loop() {
    // Borrar credenciales: BOOT presionado 3 s
    if (digitalRead(RESET_PIN) == LOW) {
        if (bootPressStart == 0) {
            bootPressStart = millis();
        } else if (millis() - bootPressStart >= 3000) {
            Serial.println("\n[RESET] Borrando credenciales y reiniciando...");
            prefs.begin("wificfg", false);
            prefs.clear();
            prefs.end();
            delay(500);
            ESP.restart();
        }
    } else {
        bootPressStart = 0;
    }

    if (inConfigMode) {
        dnsServer.processNextRequest();
        server.handleClient();
        return;
    }

    unsigned long now = millis();

    if (lastCycleMs == 0 || now - lastCycleMs >= CYCLE_INTERVAL_MS) {
        lastCycleMs = now;

        // 1) Lectura del sensor
        float temp = dht.readTemperature();
        float hum  = dht.readHumidity();

        if (isnan(temp) || isnan(hum)) {
            Serial.println("[DHT] Lectura invalida, se reutiliza la ultima valida (revisa cableado/pin 4)");
        } else {
            lastTemp = temp;
            lastHum = hum;
            hasReading = true;
            Serial.printf("[DHT] Temp: %.1f C | Hum: %.1f %% | Ventilador: %s\n",
                          temp, hum, fanState ? "ON" : "OFF");
        }

        // 2) Traer configuración de la app (si falla, se conserva la última)
        fetchConfig();

        // 3) Decidir y accionar el relé
        applyControl();

        // 4) Subir telemetría con el estado final del relé
        pushTelemetry();
    }

    delay(50);
}
