# BD_Control_App 🌡️🌀

> Monitoreo de temperatura/humedad y control de ventilación de una bodega con **varios ESP32** por **Wi-Fi** y una app Android (Kotlin + Jetpack Compose).

---

## 📌 Descripción

Hay dos tipos de ESP32: un **sensor** (DHT11) conectado al teléfono por **cable USB-C (comunicación serial)** y uno o más **relés** (ventilador, con su propia fuente de poder) controlados por **Wi-Fi**. La app los **descubre sola**, muestra cada sensor en vivo, guarda su historial y permite encender/apagar cada ventilador de forma independiente. Los comandos viajan cifrados con **AES-256-GCM**. El acceso es con **verificación en 2 pasos** y cada usuario solo ve y controla los **dispositivos para los que tiene permiso**.

---

## 🏗️ Arquitectura

```text
                        cable USB-C (serial, 115200 8N1, tramas con CRC-16)
 ESP32 sensor (DHT11) ◄══════════════════════════════════════════════════╗
   alimentado por el teléfono                                            ║
                                                                   App Android ──► tarjeta por sensor
 ESP32 relé ──UDP anuncio (JSON)──────────┐                         (SerialLink +   interruptor por relé
   fuente propia (12 V / 5 V)             ├──────────────────────►   WifiIotBridge)  historial por sensor
 ESP32 relé ◄──TCP cifrado AES-GCM────────┘        Wi-Fi (BODEGA_NET)
```

- **Sensor ⇄ app (serial USB):** el teléfono hace de *USB host* (OTG) y alimenta la placa por el mismo cable. El sensor envía lecturas y la app le envía instrucciones (p. ej. cada cuánto medir). El Wi-Fi del sensor queda **apagado**, lo que ahorra energía.
- **Relé ⇄ app (Wi-Fi):** UDP para el estado (el relé se anuncia con su `id` y la app aprende su IP) y TCP con AES-256-GCM para los comandos. Los sockets se atan a la red Wi-Fi (`Network.bindSocket`), así funcionan aunque esa red no tenga internet.
- **Continuidad:** la app envía un **latido** cifrado al relé mientras su lazo de control esté sano; si el relé deja de oírlo entra en **modo seguro** y ventila por ciclos (ver abajo). La app puede gobernar el ventilador en **automático** por humedad (histéresis 60/70 %) y solo trabaja con la **app a la vista** (ahorra batería).
- **Reconexión automática:** cable USB (se reabre solo; detecta desenchufe y sensor colgado con `PING`), socket UDP (espera creciente y al cambiar de red), comandos (3 intentos) y dispositivos (vuelven solos a "en línea").

### Protocolo serial (sensor ⇄ app)

Líneas de texto `$TIPO,campo,...*CRC16\n`. El CRC-16/CCITT-FALSE cubre lo que hay entre `$` y `*`; una trama con CRC incorrecto se descarta y se cuenta. Cualquier línea que no empiece con `$` (el log de arranque del ESP32) se ignora. Especificación completa en `firmware/sensor/bd_serial.h`.

| Dirección | Trama | Respuesta / efecto |
| --- | --- | --- |
| Sensor ➔ App | `$TLM,sensor-1,<bootId>,12,24.5,60.0*CRC` | lectura en vivo; (`bootId`, `12`) la identifica de forma única |
| Sensor ➔ App | `$HELLO,0,sensor-1,2.0,2000,<bootId>*CRC` | saludo al arrancar (id, versión, intervalo, bootId) |
| App ➔ Sensor | `$SET,5,INTERVAL,10000*CRC` | `$ACK,5` o `$ERR,5,RANGE` (de 1 a 600 s; queda guardado en la memoria del ESP32) |
| App ➔ Sensor | `$PING,n` / `$INFO,n` / `$GET,n` | `$PONG,n` / `$HELLO,n,...` / `$ACK,n` + lectura inmediata |
| App ➔ Sensor | `$SYNC,n` | `$SYNCSTART,...`, una `$BUF,...` por cada lectura pendiente y `$SYNCEND,...` |
| App ➔ Sensor | `$CONF,n,k` | sin respuesta: "ya guardé hasta la lectura k"; el sensor libera su buffer |

### Mensajes por Wi-Fi (relés)

| Dirección | Transporte | Contenido |
| --- | --- | --- |
| Relé ➔ App | UDP (cada 5 s y tras cada comando) | `{"src":"relay","id":"rele-1","fan":1,"fs":0}` (`fs` = modo seguro) |
| App ➔ Relé | TCP + AES-GCM | `{"cmd":"FAN","val":1,"id":"rele-1"}` → `{"ok":1,"fan":1,"fs":0}` |
| App ➔ Relé | TCP + AES-GCM | `{"cmd":"HB","id":"rele-1"}` (latido: "sigo aquí", no cambia nada) → misma respuesta |

Opcional: el sensor también puede emitir por Wi-Fi (`{"id":"sensor-1","t":24.5,"h":60.0}` por UDP) si se define `SENSOR_WIFI_TELEMETRY`. El campo `id` es opcional en firmware antiguo: sin él se usan `sensor` y `rele`.

---

---

## 💾 Almacenamiento temporal y sincronización (sin conexión)

La app no pierde lecturas cuando algo falla. Hay **dos buffers** y una subida automática:

```text
 sensor ──► [buffer circular en el ESP32] ──cable──► app ──► [base de datos del teléfono = cola] ──internet──► Supabase
              se libera con CONF                        guarda y recién entonces confirma             sube por lotes, sin duplicar
```

**1. Buffer en el microcontrolador.** Cada medición recibe una identidad única (`bootId` aleatorio por arranque + contador `n`) y se guarda en un buffer circular **hasta que la app confirma que la guardó** (`CONF`, acumulativo). Si la app no está escuchando (cerrada, teléfono bloqueado) las lecturas se acumulan; al volver, la app pide `SYNC` y el sensor las reenvía con su *edad* (el ESP32 no tiene reloj: la app calcula la hora real). El mismo mecanismo **recupera las tramas que el CRC descartó** en vivo: si el contador salta, la app pide `SYNC` otra vez. Al abrir el puerto la app ignora lo que llegue antes de la respuesta a su primer comando: son lecturas viejas retenidas en el cable y se recuperan con su edad real, no con la hora de ahora.

**2. Cola en el teléfono.** La tabla `sensor_log` (Room + SQLCipher) es la cola: cada fila lleva una clave única y un indicador `synced`. Una lectura solo se le confirma al sensor **después de guardarse**; si guardar falla, el sensor la conserva. Los datos sobreviven aunque se cierre la app. Las lecturas recuperadas se guardan todas; las en vivo se espacian (cada 10 s o ante un cambio brusco).

**3. Subida a Supabase.** `SyncManager` sube por lotes de 100, de la más antigua a la más nueva, **solo cuando hay internet real** (red validada por Android, no solo "conectado al Wi-Fi de los relés"). Si falla reintenta con espera creciente (2 s hasta 5 min). La subida es **idempotente**: la tabla `sensor_readings` tiene clave única `(device_id, sample_key)` y se usa "insertar e ignorar duplicados", así que reenviar una lectura nunca la duplica (por ejemplo si la app se cierra justo entre subir y marcar). La app solo tiene permiso de INSERT. Crea la tabla con `docs/supabase_lecturas.sql`.

### Evaluación de capacidad

Cuentas a partir del tamaño de los datos (no son mediciones en hardware).

| | Tamaño | Capacidad | Cobertura |
| --- | --- | --- | --- |
| **Buffer del ESP32** (RAM) | 12 B por lectura × 1800 = **21,6 KB** (~7 % de los ~320 KB de RAM) | 1800 lecturas | a 1 s: 30 min · a 2 s: **1 h** · a 10 s: 5 h · a 60 s: **30 h** · a 600 s: 12,5 días |
| **Cola del teléfono** | ~70–100 B por fila con índices (7–10 MB para el tope; estimado) | 100 000 lecturas sin subir | con 1 lectura/10 s por sensor (8 640 por día): **~11 días** con 1 sensor, ~5 con 2 |
| **Recuperación por cable** | ~60 B por trama a 115 200 baudios (~11,5 KB/s) | ~190 tramas/s | llenar el buffer completo (1800) se recupera en **~10 s** |
| **Subida a Supabase** | lotes de 100 filas (~10 KB de JSON) | 100 000 filas = 1 000 solicitudes | |

La capacidad del ESP32 se cambia al compilar (`-DBD_BUFFER_CAPACITY=...`). Si un buffer se llena se descarta lo **más antiguo**, y la app muestra cuántas lecturas se perdieron por desborde.

**Importante:** el sensor se alimenta por el cable USB. Con el cable **desenchufado la placa se apaga y su buffer (en RAM) se pierde**. El buffer cubre "app cerrada o teléfono bloqueado **con el cable conectado**". Para cubrir también un cable desenchufado, el sensor necesitaría alimentación propia (powerbank o batería) y otra vía para llegar a la app.

---

## 🛟 Contingencia y continuidad operativa

El relé no ve la humedad (el sensor está en el teléfono), así que si la app o el enlace fallan nadie decide por el ventilador. Por eso:

1. **Latido y modo seguro.** Con la app sana envía un latido al relé cada 30 s. Si el relé pasa 10 min sin oír nada válido de la app, **ventila por ciclos él solo** (5 min cada 30 por defecto; ajustable en `config.h`). Al volver la app, sale del modo seguro.
2. **No ocultar fallas.** En automático, si **no hay ningún sensor con datos recientes**, la app deja de enviar latidos y órdenes a propósito: así el relé entra en modo seguro en vez de quedarse con un estado viejo.
3. **Avisos al operador** en la pantalla: relé en modo seguro, automático en pausa (con los minutos que faltan), sensor o relé caído, cable desconectado, permiso USB pendiente, lecturas perdidas por desborde, problemas de subida y batería baja.
4. **Automático por humedad.** Interruptor "Automático" en cada relé (enciende a ≥ 70 %, apaga a ≤ 60 %, con tiempos mínimos y el peor caso entre sensores). Tomar el control a mano lo desactiva.

Plan completo (matriz de 13 fallas con detección, respuesta, aviso y recuperación) y procedimiento de verificación con hardware: [`docs/plan_contingencia.md`](docs/plan_contingencia.md).

## 🔋 Energía

El sensor se alimenta de la batería del teléfono, así que optimizarlo es optimizar el móvil. Aplicado: radios del ESP32 apagadas y CPU a 80 MHz; la app solo trabaja a la vista, no retiene el `MulticastLock` y espacia las lecturas cuando la humedad está estable (modo ahorro: −97 % de tramas y −83 % de filas de 2 s a 60 s). La app **mide la batería** del teléfono y muestra el ritmo de descarga. Cuentas (calculadas desde la hoja de datos, no medidas), supuestos y procedimiento para medir con un medidor USB-C: [`docs/energia.md`](docs/energia.md).

> Conclusión práctica: aun optimizado, el sensor cuesta ~25–35 % de la batería por día. **Mantén el teléfono en un cargador.**

---

## 🔐 Seguridad: 2 pasos y permisos por dispositivo

**Inicio de sesión en dos pasos**
1. **Algo que sabes:** usuario y contraseña (PBKDF2-HMAC-SHA256, 120 000 iteraciones, salt por usuario).
2. **Algo que tienes:** código de 6 dígitos de una app de autenticación (TOTP, RFC 6238: Google Authenticator, Authy, Microsoft Authenticator…). Un código ya usado no se acepta de nuevo.

Una cuenta **sin secreto TOTP configurado no puede entrar**. Los dos pasos comparten el límite de 5 intentos fallidos y el bloqueo de 30 s.

**Permisos por dispositivo** (tabla `device_permissions`): para cada usuario y cada `DEVICE_ID`, `can_view` (consultar telemetría e historial) y `can_control` (enviar comandos). Reglas: *denegar por defecto* (un ESP32 sin permiso no aparece ni se controla), controlar exige poder ver, `*` aplica a todos y una fila específica lo sobrescribe. Se aplica con decoradores sobre el transporte (`AuthorizedIot.kt`), así que ningún ViewModel puede saltárselo; si los permisos no se pueden cargar, no se inicia sesión.

**Validación en el propio dispositivo:** cada comando cifrado lleva el `id` del relé destinatario y el relé lo ignora si no es el suyo. Los `id` que llegan por la red solo se aceptan con el formato `[A-Za-z0-9_.-]{1,32}`.

**Dar de alta usuarios** (nunca desde la app):
```bash
python3 tools/new_user.py alta USUARIO                    # contraseña + secreto TOTP (+ SQL listo para pegar)
python3 tools/new_user.py totp USUARIO                    # secreto TOTP para un usuario que ya existe
python3 tools/new_user.py permisos USUARIO rele-1:control sensor-1:ver
```
Antes, ejecuta una vez `docs/supabase_2fa_permisos.sql` en el SQL Editor de Supabase.

---

## 📁 Estructura

```text
app/src/main/java/cl/avelyn/bd_control_app/
├── model/network/   SerialProtocol + SerialLink + UsbSerialPortProvider (sensor por cable USB),
│                    WireProtocol (UDP + AES-GCM), WifiIotBridge (relés por Wi-Fi),
│                    AuthorizedIot (permisos), SupabaseIotBridge (simulador), contratos
├── model/automation, energy, alerts   automático + latidos, ahorro, batería y avisos (lógica pura)
├── model/           Room + SQLCipher (historial por sensor), repositorios
├── viewmodel/       DashboardViewModel (lista de dispositivos), HistoryViewModel, ...
├── ui/              Compose: dashboard, historial, login, simulador
└── di/              AppContainer: elige el transporte (wifi | supabase)
firmware/
├── sensor/sensor.ino      ESP32 + DHT11 (GPIO 4): serial USB (+ Wi-Fi opcional)
├── sensor/bd_serial.h     protocolo serial en C++ puro (se prueba en el computador)
├── sensor/test/           pruebas del protocolo y simulador del sensor (run_tests.sh)
├── relay/relay.ino        ESP32 + relé (GPIO 26): servidor TCP cifrado + anuncios UDP + modo seguro
├── relay/relay_failsafe.h modo seguro en C++ puro (se prueba en el computador)
└── relay/test/            pruebas del modo seguro (run_tests.sh)
```

---

## 🛠️ Puesta en marcha

### 1. Firmware (`firmware/`)

**Sensor (`sensor.ino`)** — no necesita Wi-Fi ni `config.h`. Instala *DHT sensor library* y *Adafruit Unified Sensor*, elige la placa (p. ej. *ESP32 Dev Module*) y súbelo. Opcional, en un `config.h` propio (está en `.gitignore`):

```cpp
#define DEVICE_ID "sensor-bodega"   // único por dispositivo; por defecto "sensor-1"
#define SENSOR_WIFI_TELEMETRY       // además por Wi-Fi (necesita WIFI_SSID y WIFI_PASS)
```

> Si ya tenías el sensor funcionando por Wi-Fi: tras volver a flashearlo **deja de usar Wi-Fi** salvo que agregues `SENSOR_WIFI_TELEMETRY`.

**Relé (`relay.ino`)** — necesita su `config.h` (está en `.gitignore`) con `WIFI_SSID`, `WIFI_PASS` y la llave AES (`AES_KEY_HEX` / `loadKeys()`). Opciones, todas opcionales:

```cpp
#define DEVICE_ID "rele-bodega"     // único por relé; por defecto "rele-1"
#define NETWORK_MODE_STA            // unirse al router en vez de crear BODEGA_NET
#define FORWARD_SENSOR_UDP 0        // no reenviar telemetría Wi-Fi de sensores
#define FAILSAFE_SILENCE_MIN 10     // minutos sin oír a la app para entrar al modo seguro (0 = desactivado)
#define FAILSAFE_MODE 0             // 0 = ventilar por ciclos, 1 = dejar como está, 2 = apagar
#define FAILSAFE_ON_MIN 5           // modo 0: minutos encendido...
#define FAILSAFE_PERIOD_MIN 30      // ...de cada tantos minutos
```

- **Un relé como punto de acceso** (por defecto): crea `BODEGA_NET` (hasta 8 clientes). **Solo un relé** puede hacerlo.
- **Todos al mismo router** (`NETWORK_MODE_STA`): necesario para más de un relé.

### 2. App (`app/`)

1. Copia `local.properties.example` → `local.properties` y completa `aes.key` (mismo valor que el relé; `openssl rand -hex 32`).
2. `iot.transport=wifi` (ESP32 reales) o `supabase` (simulador sin hardware). `iot.serial=true` activa el sensor por cable.
3. **Conecta el sensor al teléfono con un cable USB-C ⇄ USB-C.** La primera vez Android muestra un aviso de permiso USB (acéptalo, y marca "usar siempre" si quieres que no vuelva a preguntar). La pantalla principal muestra el estado del cable y la calidad del enlace.
4. Conecta el teléfono a la red Wi-Fi de los relés, abre la app e inicia sesión.

### Problemas típicos con el cable USB

- **"Conecta el cable…" aunque está enchufado:** algunas placas ESP32 baratas con USB-C **no traen las resistencias CC**, y con un cable C ⇄ C el teléfono no las reconoce ni las alimenta. Prueba con un adaptador USB-A hembra → USB-C (OTG) más el cable que traía la placa, o un cable USB-A a C.
- **Solo carga, no hay datos:** el cable es "solo carga". Usa uno con datos.
- **El sensor se reinicia al conectar:** normal; la placa se resetea una vez al abrir el puerto y se vuelve a presentar sola.
- **Consumo:** el teléfono alimenta la placa (~80 mA sin Wi-Fi). Si tu teléfono limita la corriente OTG, usa un hub USB-C con alimentación.
- **El enlace solo funciona con la app abierta en la pantalla principal** (no hay servicio en segundo plano todavía).

### 3. Pruebas

`./gradlew testDebugUnitTest` — incluye `WireProtocolTest` (formato del frame AES-GCM byte a byte contra una implementación de referencia independiente), `TotpTest` (vectores oficiales del RFC 6238), `AccessPolicyTest` (reglas de permisos), `PasswordHasherTest` (incluye compatibilidad con `tools/new_user.py`) y `SerialProtocolTest` (el CRC y las tramas de Kotlin coinciden byte a byte con el firmware C++; ningún cambio de un bit produce una trama válida).

`SerialLinkFirmwareTest` ejecuta el enlace serial **real de la app contra el firmware real del sensor** compilado en el computador (`firmware/sensor/test/host_sim.cpp`, requiere `g++`; si no hay, se omite): lecturas, ruido de arranque, instrucciones, tramas dañadas, cable desenchufado y reintentos. `SyncManagerTest` y `SensorRepositoryTest` cubren la cola y la subida (sin internet, por lotes y en orden, reintentos, error de base de datos local, reenvío sin duplicar, tope de capacidad, limpieza que no borra lo no subido). Una prueba de punta a punta recorre **sensor → app cerrada un rato → teléfono sin internet → Supabase** y comprueba que cada lectura de ese periodo llega a la nube una sola vez. `AutomationControllerTest`, `AdaptiveSamplerTest`, `DischargeEstimatorTest`, `AlertsTest` y `EnergyMonitorTest` cubren el automático (histéresis, tiempos mínimos, **sin datos = sin latidos**), el ahorro, la estimación de descarga y los avisos. Las pruebas del firmware solas: `firmware/sensor/test/run_tests.sh` y `firmware/relay/test/run_tests.sh` (modo seguro, incluido el desbordamiento de `millis()`).

---

## ⚠️ Limitaciones conocidas

- **La verificación (contraseña, TOTP y permisos) se hace en la app.** Los datos se leen de Supabase con la llave anónima, que viaja dentro del APK. Quien la extraiga puede leer los hashes, los secretos TOTP y los permisos, y saltarse los controles de la app. Para una protección real el siguiente paso es pasar a **Supabase Auth** (que trae MFA/TOTP nativo) con **RLS** por usuario, o validar en el servidor con funciones `SECURITY DEFINER`.
- **La llave AES es compartida** por todos los relés y está en cada APK: los permisos por dispositivo se aplican en la app, no los impone el relé. Mejora posible: una llave distinta por relé entregada solo a los usuarios autorizados.
- La telemetría UDP por Wi-Fi va **en claro**; solo los comandos a los relés están cifrados y autenticados. El enlace serial USB tiene detección de errores (CRC-16) pero no cifrado: es un cable físico.
- El comando no lleva contador ni desafío: un comando capturado podría **reenviarse al mismo relé** (replay). Ya no sirve contra otro relé, pero sí contra el mismo.
- El panel de simulación (`login_simulator_link`) se abre sin iniciar sesión; es una herramienta de desarrollo y debería quitarse de las versiones finales.
- Un **latido grabado** podría reenviarse y mantener al relé fuera del modo seguro (no hay contador ni desafío); el modo seguro es una protección burda por ciclos, no un control por humedad.
- Los dispositivos solo aparecen tras su primer mensaje; no se recuerdan entre sesiones.
- Si el sensor está por cable, la app solo lo lee mientras esté abierta en la pantalla principal (no hay servicio en segundo plano): fuera de ahí, el buffer del sensor cubre el hueco.
- La subida a Supabase corre mientras el proceso de la app esté vivo; si queda algo pendiente y la app se cierra, se sube la próxima vez que se abra (el siguiente paso sería WorkManager).
- El buffer del ESP32 está en RAM: se pierde si la placa se reinicia o se apaga (ver arriba).
- Un lote que Supabase rechace de forma permanente (por ejemplo por una restricción) se reintentaría indefinidamente; las lecturas se validan antes de guardarse para evitarlo.
- Las lecturas recuperadas heredan la precisión del reloj del ESP32 (unos 0,01 %, ~1 s por cada 3 h) más la latencia del cable.

---

## 📄 Licencia

Este proyecto se distribuye bajo la Licencia MIT.
