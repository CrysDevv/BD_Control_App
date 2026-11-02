```markdown
# BD_Control_App 🌡️🌀

> Sistema de monitoreo de humedad y control de ventilación en tiempo real mediante ESP32 (DHT11 + Relé) y Android (BLE).

---

## 📌 Descripción

**BD_Control_App** es un proyecto de IoT que integra una aplicación móvil para Android con un microcontrolador **ESP32** utilizando **Bluetooth Low Energy (BLE)**. Permite supervisar lecturas de humedad con un sensor **DHT11** y conmutar un **módulo relé** para accionar un ventilador de extracción, incorporando un motor de detección y reconexión automática ante pérdidas de señal.

---

## 📁 Estructura del Proyecto

El repositorio mantiene la siguiente organización modular[cite: 1]:

```text
BD_Control_App/
├── app/                  # Aplicación Android (UI, ViewModels, Cliente/Scanner BLE)[cite: 1]
├── firmware/             # Firmware C++/Arduino para ESP32 (Servidor GATT, GPIO, DHT11)[cite: 1]
├── gradle/               # Wrapper y configuraciones de Gradle[cite: 1]
├── .gitignore            # Archivos ignorados en el control de versiones[cite: 1]
├── build.gradle.kts      # Script de construcción principal (Kotlin DSL)[cite: 1]
├── gradle.properties     # Variables de compilación de Gradle[cite: 1]
├── gradlew               # Script de ejecución de Gradle para Linux/macOS[cite: 1]
└── gradlew.bat           # Script de ejecución de Gradle para Windows[cite: 1]

```

---

## 🔌 Protocolo de Comunicación BLE

La app y el ESP32 intercambian información mediante tramas de texto delimitadas por dos puntos (`:`):

| Código | Dirección | Función | Ejemplo de Payload |
| --- | --- | --- | --- |
| `01` | ESP32 ➔ App | Envío de lectura de humedad actual (%) | `01:65.5` |
| `02` | App ➔ ESP32 | Comando de activación/desactivación del relé | `02:ON` / `02:OFF` |
| `80` | ESP32 ➔ App | Confirmación de estado / Latido (*Keep-Alive*) | `80:OK` |

---

## 🛠️ Requisitos e Instalación

### 1. Hardware Requerido

* Microcontrolador **ESP32 DevKit v1**
* Sensor **DHT11** (Pin de datos ➔ `GPIO 4`)
* Módulo **Relé de 1 Canal** (Pin de control ➔ `GPIO 5`)
* Ventilador DC / Extractor de aire

### 2. Carga del Firmware (`/firmware`)



1. Abre el módulo en la carpeta `firmware/` usando **Arduino IDE** o **PlatformIO**.


2. Instala las dependencias `BLEDevice` y `DHT sensor library`.
3. Flashea el código en tu tarjeta ESP32.

### 3. Compilación de la Aplicación (`/app`)



1. Abre el proyecto en **Android Studio**.


2. Otorga los permisos de **Bluetooth** y **Ubicación** en el dispositivo de prueba.
3. Sincroniza Gradle y ejecuta `./gradlew assembleDebug` para generar el APK.



---

## 📄 Licencia

Este proyecto se distribuye bajo la Licencia MIT.


```

```