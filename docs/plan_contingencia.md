# Plan de contingencia y continuidad operativa

Criterio: *plan de contingencia y continuidad operativa en caso de fallas en la comunicación entre la aplicación móvil y los sensores.*

## 1. Objetivo y principio de diseño

El ventilador existe para proteger la bodega de la humedad. El **sensor** va por cable al teléfono y el **relé** (con su propia fuente) se
comanda por Wi-Fi, así que **el relé no ve la humedad: depende de la app**. Si la app, el teléfono o el enlace fallan, nadie decide por el
ventilador. Principios:

1. **Detectar** rápido cada falla y **decirlo** (avisos en la pantalla).
2. **No perder datos:** buffer en el sensor y cola persistente en el teléfono.
3. **Degradar a un estado seguro:** si la app deja de dar señales, el relé ventila por su cuenta (modo seguro).
4. **No ocultar una falla:** si el lazo de control no puede decidir, **no** debe enviar latidos (un latido falso ocultaría el problema).

## 2. Mecanismo central: latido y modo seguro del relé

- La app envía al relé un **latido** (`{"cmd":"HB"}`, cifrado AES-256-GCM como cualquier comando) cada 30 s **mientras su lazo de control esté sano**.
  Cualquier comando válido (`FAN` o `HB`) cuenta como contacto.
- Si el relé pasa `FAILSAFE_SILENCE_MIN` minutos (10 por defecto) sin contacto, entra en **modo seguro**:
  - `FAILSAFE_MODE 0` (por defecto): ventila por ciclos, **5 min encendido cada 30 min** (~17 % del tiempo), empezando encendido;
  - `1`: deja el ventilador como estaba; `2`: lo apaga.
- Al llegar un comando válido vuelve al modo normal y la app se entera (el relé anuncia `"fs":1/0`).
- Tras un **corte de luz del relé** sin app también llega al modo seguro pasado ese tiempo (el arranque cuenta como contacto).
- **Los parámetros son un punto de partida:** ajusta ciclo y minutos a la humedad real de tu bodega (`config.h` del relé).

El lazo de control (`AutomationController`) aplica esas reglas: con el automático **sin ningún sensor con datos recientes (< 5 min) no
envía órdenes ni latidos**, de modo que el relé entra solo en modo seguro. En manual el usuario manda y los latidos siguen.

## 3. Matriz de fallas

| # | Falla | Cómo se detecta | Respuesta automática | Qué ve el operador | Qué hacer | Recuperación |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **Sensor sin datos** con la app viva (DHT11 suelto, firmware colgado) | Sin tramas > 6 s (sensor "sin conexión"); `PING` a los 4 s de silencio; sin respuesta a los 12 s el enlace se da por muerto y se reabre el puerto | Automático en pausa → **sin latidos** → relé a modo seguro a los 10 min | Aviso crítico "Automático en pausa… en ~N min modo seguro"; sensor "sin conexión" | Revisar el cable/sensor | Al volver los datos el lazo retoma y el primer latido saca al relé del modo seguro |
| 2 | **Cable USB desenchufado** | El puerto lanza error / el dispositivo desaparece | Reconexión automática con espera creciente; el sensor por cable pasa a "sin conexión" al instante | Aviso "Cable del sensor desconectado" | Volver a enchufar. Ojo: la placa **se apaga** y pierde su buffer (se alimenta del cable) | Reconecta sola; el sensor tiene otro `bootId`; pedido de `SYNC` |
| 3 | **App cerrada / en segundo plano / teléfono bloqueado** (cable conectado) | La app deja de escuchar; el sensor no recibe tramas válidas > 6 s | El sensor **guarda lecturas** en su buffer; el relé, sin latidos, entra en modo seguro a los 10 min | — (la app no está a la vista) | Abrir la app | `SYNC` recupera todas las lecturas con su hora real; el relé sale del modo seguro |
| 4 | **Teléfono apagado o sin batería** | — | La placa del sensor se apaga con él; el relé entra en modo seguro a los 10 min | Aviso previo de batería < 20 % / < 10 % | **Mantener el teléfono en cargador** | Al encender, abrir la app |
| 5 | **Relé inalcanzable** (Wi-Fi caído, sin energía) | Sin anuncios UDP > 12 s; comando TCP falla tras 3 intentos | Reintentos; el relé (si tiene energía) entra en modo seguro | Aviso "El relé no responde" | Revisar energía y Wi-Fi del relé | El relé se vuelve a anunciar y la app lo recupera sola |
| 6 | **Comando perdido o dañado** | Respuesta ausente / CRC o autenticación inválidos | 3 reintentos con espera; los datos dañados se descartan | Error en la tarjeta del dispositivo | — | Automática |
| 7 | **Tramas dañadas por ruido** | CRC-16 inválido; salto del contador `n` | Se descartan y se **piden de nuevo** (`SYNC`): no se pierde ninguna lectura | Contador "dañadas / perdidas / recuperadas" | Si crece mucho: cambiar el cable | Automática |
| 8 | **Buffer del sensor lleno** (app ausente mucho tiempo) | `SYNCSTART` informa lecturas descartadas | Se descarta lo más antiguo; el resto se recupera | Aviso "El sensor perdió N lecturas" | Aumentar `BD_BUFFER_CAPACITY` o espaciar el intervalo | — |
| 9 | **Sin internet** | `InternetMonitor` (red validada) | Las lecturas se acumulan en el teléfono; subida por lotes y reintentos al volver | Tarjeta "Sincronización" y aviso si hay > 500 pendientes | — | Sube sola, sin duplicar |
| 10 | **Falla de la base de datos local** | La escritura lanza excepción | La lectura **no se confirma** al sensor (la conserva); la escucha sigue | Registro en el log | Liberar espacio | El sensor la reenvía |
| 11 | **Sensor reiniciado** | Cambia el `bootId` | Se crea un nuevo seguimiento de lecturas y se pide `SYNC` | — | — | Automática |
| 12 | **Permiso USB pendiente** | Estado `PermissionRequired` | Reintenta cada 1,5 s | Aviso "Falta aceptar el permiso USB" | Aceptar el diálogo | Conecta sola |
| 13 | **Llave AES distinta / comando no autorizado** | El relé no puede descifrar | El relé ignora el comando | Error de comando | Revisar `aes.key` | — |

## 4. Verificación

**Automatizada** (corre sin hardware; ver `README.md`):
- `AutomationControllerTest`: histéresis, tiempos mínimos, peor caso entre sensores, datos viejos, **sin datos = sin latidos**, manual con latidos.
- `test/test_failsafe.cpp` (C++ real del relé): entrada exacta al modo seguro, ciclo 5/30, salida al recibir contacto, KeepLast/AllOff, arranque tras corte de luz y **desbordamiento de `millis()`** a los 49,7 días.
- Pruebas de integración del `DashboardViewModel`: sin sensores deja de enviar latidos y avisa; al volver los datos retoma y el relé sale del modo seguro; en segundo plano deja de escuchar y de enviar latidos.
- `SerialLinkFirmwareTest` contra el firmware real: cable desenchufado, sensor colgado, tramas dañadas (sin pérdida), app cerrada con recuperación, buffer lleno.
- `SyncManagerTest`: sin internet, reintentos, error de base de datos local, reenvío sin duplicar.

**Pendiente con hardware real** (no pude ejecutarlo): probar el modo seguro del relé físico y el comportamiento de la app con un teléfono y ESP32 reales.
Procedimiento sugerido:
1. En `config.h` del relé: `#define FAILSAFE_SILENCE_MIN 1`, `#define FAILSAFE_ON_MIN 1`, `#define FAILSAFE_PERIOD_MIN 3`.
2. Con la app a la vista y el relé en manual, cerrar la app. Esperar 1 min: el relé debe encender el ventilador 1 min y apagarlo 2 min, en ciclos.
3. Abrir la app: debe mostrar el relé "en modo seguro" y salir de él al primer latido (≤ 30 s).
4. Activar el automático, desenchufar el sensor: debe aparecer "Automático en pausa" y, pasado el silencio, el modo seguro.
5. Volver a los valores normales.

## 5. Límites conocidos

- **El teléfono es un punto único de falla** para la decisión; el modo seguro es una protección burda (ciclos fijos), no un control por humedad.
  Una mejora sería un sensor de humedad conectado al propio relé para un control autónomo real.
- Un **latido grabado** podría reenviarse (no hay contador ni desafío) y mantener al relé fuera del modo seguro; mitigaría un desafío-respuesta con contador.
- Sin notificaciones del sistema ni servicio en segundo plano: los avisos se ven con la app abierta.
- El buffer del sensor (RAM) no sobrevive a un corte de energía de la placa.
