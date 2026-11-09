# Evaluación y optimización del consumo de energía

Criterio: *evaluar el consumo de energía de los sensores IoT y del dispositivo móvil, optimizando la duración de la batería.*

> **Qué es medido y qué es calculado.** Las cifras de esta página son **calculadas** a partir de los rangos de la hoja de datos
> del ESP32 y de supuestos que se declaran abajo. **No son mediciones de tu hardware.** Al final hay un procedimiento para medir y
> reemplazar las estimaciones por valores reales. La app sí **mide en el teléfono** (tarjeta "Modo ahorro de energía").

## 1. Idea central: el sensor gasta la batería del teléfono

El ESP32 del sensor se alimenta por el cable USB-C **del teléfono**. Cada miliamperio que consume sale de la batería del teléfono
(a través del convertidor OTG, que no es 100 % eficiente). Por eso optimizar el firmware del sensor **es** optimizar la batería
del móvil.

## 2. Consumo del sensor

Valores de la hoja de datos del ESP32 (solo el chip; núcleo dual): modem-sleep (radio apagada, CPU encendida) **30–68 mA a 240 MHz**,
**27–44 mA a 160 MHz** y **20–31 mA a 80 MHz**; Wi-Fi recibiendo/escuchando **95–100 mA** y transmitiendo hasta **240 mA**;
light-sleep **0,8 mA**; deep-sleep **10 µA**.

Supuestos: la placa de desarrollo suma ~7 mA (regulador, conversor USB-serie, LED; ~6,5 mA medidos por terceros en una placa en reposo);
suministro de 5 V; el convertidor OTG del teléfono rinde 85 %; batería de 4000 mAh a 3,85 V (15,4 Wh).

| Configuración | Corriente por USB | Potencia | Energía por día | % de la batería por día |
| --- | --- | --- | --- | --- |
| A. Wi-Fi sin ahorro (radio siempre activa, escuchando), 240 MHz | 102–107 mA | 0,51–0,54 W | 14,4–15,1 Wh | **94–98 %** |
| B. Radio apagada, 240 MHz (de fábrica sin Wi-Fi) | 37–75 mA | 0,18–0,38 W | 5,2–10,6 Wh | **34–69 %** |
| **C. Radio apagada, 80 MHz (esta fase)** | **27–38 mA** | **0,14–0,19 W** | **3,8–5,4 Wh** | **25–35 %** |
| D. Light-sleep casi siempre (*no implementado*) | ~8 mA | ~0,04 W | ~1,1 Wh | ~7 % |

Lectura:
- **B → C ahorra ~42 %** y **A → C ~69 %** (con los puntos medios de los rangos). La fila A es el peor caso de una radio siempre activa;
  un STA con ahorro de módem de Wi-Fi promedia menos, así que el ahorro real frente al diseño anterior puede ser menor. Hay que medirlo.
- **Aun optimizado, el sensor cuesta 25–35 % de la batería por día.** Conclusión práctica: **el teléfono debe estar conectado a un
  cargador** mientras monitorea (idealmente con un adaptador/hub USB-C que deje pasar la carga y el sensor a la vez). La app avisa
  si la batería baja de 20 % sin cargar.
- La fila D (light-sleep) mostraría el mayor ahorro, pero no se implementó: despertar por UART pierde los primeros caracteres y
  no puedo validarlo sin hardware; el protocolo ya tolera tramas perdidas (CRC + reintentos), así que es un buen siguiente paso.

## 3. Optimizaciones aplicadas

**Firmware del sensor** (`firmware/sensor/sensor.ino`)
- Wi-Fi y Bluetooth apagados (`btStop()`, sin `WiFi.begin`).
- CPU a 80 MHz (`setCpuFrequencyMhz`), configurable con `BD_CPU_MHZ`.
- Intervalo de medición configurable desde la app (1–600 s), guardado en memoria no volátil.

**App Android**
- **Solo trabaja a la vista.** En segundo plano se cierran el puerto USB y el socket y se detienen los temporizadores. No se pierde
  nada: el sensor guarda lecturas en su buffer y el relé entra en modo seguro si pasa mucho rato (ver `plan_contingencia.md`).
- **Sin `MulticastLock` por defecto.** Retenerlo obliga al Wi-Fi a recibir todo el tráfico multicast y Android advierte de un consumo
  de batería notable; los anuncios del relé son *broadcast*, que no lo necesita (`iot.multicastLock=true` si algún teléfono no ve al relé).
- **Menos despertares del CPU:** lectura del puerto serie cada 500 ms en vez de 200 ms (de 5 a 2 por segundo; los datos que llegan se leen al instante).
- **Muestreo adaptativo (modo ahorro).** Con la humedad quieta y lejos de los umbrales el sensor pasa a medir cada 60 s; si cambia o se
  acerca a un umbral vuelve a 10 s. Reduce el trabajo de todo el flujo (por sensor y por día):

  | Intervalo | Lecturas | Tramas por el cable (TLM + CONF) | Filas guardadas |
  | --- | --- | --- | --- |
  | 2 s | 43 200 | ~86 400 | 8 640 |
  | 10 s | 8 640 | ~17 280 | 8 640 |
  | 30 s | 2 880 | ~5 760 | 2 880 |
  | 60 s | 1 440 | ~2 880 | 1 440 |

  De 2 s a 60 s: **−97 % de tramas** y **−83 % de filas** (y de lo que hay que subir). Además el buffer del sensor dura más (1800 lecturas = 1 h a 2 s, 30 h a 60 s).
  Si el usuario elige un intervalo a mano, el ahorro no lo pisa durante 10 min.
- **Subidas por lotes de 100** y solo con internet validado (no se despierta la radio móvil por cada lectura).
- Las lecturas se espacian en la base de datos (una cada 10 s como máximo en vivo).

## 4. Medición en el teléfono (dentro de la app)

La tarjeta **"Modo ahorro de energía"** muestra el nivel de batería, si carga, el **ritmo de descarga (%/hora)** y las horas que alcanzarían.
`EnergyMonitor` toma una muestra por minuto (solo a la vista) y `DischargeEstimator` ajusta una recta de mínimos cuadrados al último tramo
sin cargar (el nivel solo cambia de a 1 %, así que los extremos solos serían muy ruidosos); necesita ≥ 10 min y ≥ 3 puntos. La corriente
instantánea (`BATTERY_PROPERTY_CURRENT_NOW`) se muestra como **dato indicativo**: cada fabricante la informa distinto.

## 5. Procedimiento para medir de verdad (recomendado para el informe)

Material: medidor de potencia USB-C en línea (tipo UM25C / FNIRSI o similar), el teléfono y la placa.

1. Conectar el medidor entre el teléfono y la placa. Anotar la **corriente media a 5 V** durante 10 min en cada configuración:
   - firmware anterior con Wi-Fi (`#define SENSOR_WIFI_TELEMETRY`, `BD_CPU_MHZ 240`),
   - radio apagada a 240 MHz (`BD_CPU_MHZ 240`),
   - radio apagada a 80 MHz (por defecto).
2. Calcular Wh/día = V × I × 24 ÷ rendimiento OTG, y comparar con la tabla de la sección 2.
3. En el teléfono, **sin cargador**, con la pantalla fija, dejar la app a la vista 1 h con el sensor conectado y anotar el %/hora de la tarjeta;
   repetirlo con el modo ahorro activado y desactivado.
4. Si el DHT11 da lecturas inválidas a 80 MHz, usar `BD_CPU_MHZ 160` y volver a medir.

Plantilla de resultados:

| Prueba | Corriente USB (mA) | %/hora del teléfono | Observaciones |
| --- | --- | --- | --- |
| Wi-Fi activo, 240 MHz | | | |
| Radio apagada, 240 MHz | | | |
| Radio apagada, 80 MHz | | | |
| 80 MHz + modo ahorro | | | |
