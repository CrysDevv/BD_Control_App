# 🏭 Control de Bodega IoT - Android & ESP32

Una aplicación móvil nativa para Android diseñada para el monitoreo y control en tiempo real de sensores de bodega, operados mediante un microcontrolador ESP32.

## 🚀 Características Principales
* **Monitoreo en Tiempo Real:** Visualización del estado de los sensores (temperatura, humedad, movimiento, proximidad, etc.) desplegados en la bodega.
* **Integración Hardware:** Comunicación bidireccional directa con el módulo ESP32.
* **Interfaz Moderna:** UI responsiva y fluida construida con Jetpack Compose.
* **Gestión de Alertas:** Notificaciones de estado sobre umbrales críticos de los sensores.

## 🛠️ Stack Tecnológico
* **Frontend:** Kotlin, Jetpack Compose, Material Design 3.
* **Hardware/IoT:** ESP32 (Microcontrolador).
* **Comunicación:** [Especificar si usas HTTP REST, MQTT, o Bluetooth/BLE].

## ⚙️ Configuración del Hardware (ESP32)
El código fuente del microcontrolador espera recibir y enviar tramas de datos estructuradas. Asegúrate de configurar la red o el broker correspondiente para que la app Android pueda establecer la conexión exitosamente.