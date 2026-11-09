/*
  bd_serial.h - Protocolo serial (USB) entre el ESP32 del sensor y la app Android, con buffer
  de lecturas para cuando la app no está escuchando.

  C++ puro, SIN dependencias de Arduino: la misma lógica se compila y se prueba en un computador
  (ver test/). El .ino solo le entrega los bytes que llegan por Serial, la hora (setNow) y un
  ByteSink por donde escribir.

  TRAMA (texto ASCII, una por línea):
      $<TIPO>,<campo>,<campo>...*<CRC16>\n
  CRC16 = CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) de los bytes entre '$' y '*', en 4 dígitos
  hexadecimales MAYÚSCULAS. Una trama con CRC inválido se descarta sin responder. Las líneas que no
  empiezan con '$' (p. ej. el log de arranque del ESP32) son ruido y se ignoran.

  IDENTIDAD DE UNA LECTURA: (id del sensor, bootId, n). bootId es un número aleatorio que el sensor
  elige al arrancar; n es un contador que sube de 1 en 1. Es única y estable: la app y Supabase la
  usan para no duplicar nunca una lectura aunque llegue dos veces.

  BUFFER: cada lectura se guarda en un buffer circular (kBufferCapacity lecturas) y se libera SOLO
  cuando la app confirma (CONF) que ya la guardó. Si la app no está escuchando, las lecturas se
  acumulan; cuando vuelve las recupera con SYNC. Si el buffer se llena se descarta la más antigua
  (y se cuenta en "dropped"). El buffer está en RAM: se pierde si la placa se reinicia o se queda sin
  energía (con el cable USB desenchufado, la placa se apaga: el buffer cubre "app cerrada o teléfono
  bloqueado con el cable conectado", no "cable desenchufado").

  ¿APP ESCUCHANDO? El sensor lo deduce de que haya recibido alguna trama válida de la app en los
  últimos kLinkTimeoutMs (la app envía CONF o PING todo el tiempo). Mientras sí, manda cada lectura
  en vivo ($TLM) además de guardarla; si no, solo la guarda.

  App -> ESP32 (el primer campo tras el tipo es un número de secuencia 1..65535, devuelto en la respuesta):
      $PING,<seq>*CRC                  -> $PONG,<seq>
      $INFO,<seq>*CRC                  -> $HELLO,<seq>,<id>,<version>,<intervaloMs>,<bootId>
      $GET,<seq>*CRC                   -> $ACK,<seq>  (y enseguida una lectura)
      $SET,<seq>,INTERVAL,<ms>*CRC     -> $ACK,<seq>  |  $ERR,<seq>,RANGE|FORMAT
      $SYNC,<seq>*CRC                  -> $SYNCSTART,<seq>,<pendientes>,<capacidad>,<descartadas>,<bootId>,<n más antigua>
                                          seguido de $BUF,... por cada lectura pendiente (de la más antigua a la más
                                          nueva) y de $SYNCEND,<seq>,<cantidad>
      $CONF,<seq>,<n>*CRC              -> (sin respuesta) "guardé todas las lecturas hasta n": el sensor las libera
      (cualquier otro tipo)            -> $ERR,<seq>,UNKNOWN

  ESP32 -> App, sin que la app lo pida:
      $HELLO,0,<id>,<version>,<intervaloMs>,<bootId>*CRC           al arrancar
      $TLM,<id>,<bootId>,<n>,<temperatura>,<humedad>*CRC           lectura en vivo
  Dentro de un SYNC:
      $BUF,<id>,<bootId>,<n>,<edadMs>,<temperatura>,<humedad>*CRC  edadMs = hace cuánto se midió (el sensor
                                                                   no tiene reloj: la app calcula la hora real)
*/
#ifndef BD_SERIAL_H
#define BD_SERIAL_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Capacidad del buffer, en lecturas (12 bytes cada una). Ajustable al compilar: -DBD_BUFFER_CAPACITY=...
#ifndef BD_BUFFER_CAPACITY
#define BD_BUFFER_CAPACITY 1800  // 21,6 KB de RAM: 1 h a 2 s, 5 h a 10 s, 30 h a 60 s
#endif

namespace bd {

constexpr size_t kBufferCapacity = BD_BUFFER_CAPACITY;
constexpr size_t kMaxLine = 120;           // largo máximo de una línea completa; más largas se descartan
constexpr uint32_t kDefaultIntervalMs = 2000;
constexpr uint32_t kMinIntervalMs = 1000;  // el DHT11 mide como máximo 1 vez por segundo
constexpr uint32_t kMaxIntervalMs = 600000;
constexpr uint32_t kLinkTimeoutMs = 6000;  // sin tramas válidas de la app por este tiempo = "app no escucha"
constexpr size_t kReplayPerPump = 4;       // tramas $BUF por llamada a pump(), para no bloquear el loop()

/** CRC-16/CCITT-FALSE. Valor de referencia: crc16("123456789") == 0x29B1. */
inline uint16_t crc16(const uint8_t* data, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= static_cast<uint16_t>(data[i]) << 8;
    for (int b = 0; b < 8; b++) crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021) : static_cast<uint16_t>(crc << 1);
  }
  return crc;
}

/** Destino de los bytes (en el ESP32 es Serial; en las pruebas, stdout o un buffer). */
class ByteSink {
 public:
  virtual void write(const char* data, size_t len) = 0;
  virtual ~ByteSink() {}
};

/** Arma `$<body>*CRC\n` y lo envía. Devuelve false (sin enviar nada) si no cabe en kMaxLine. */
inline bool sendFrame(ByteSink& out, const char* body) {
  const size_t n = strlen(body);
  if (n + 7 > kMaxLine) return false;  // '$' + cuerpo + '*' + 4 hex + '\n'
  char line[kMaxLine + 1];
  const uint16_t crc = crc16(reinterpret_cast<const uint8_t*>(body), n);
  const int w = snprintf(line, sizeof line, "$%s*%04X\n", body, static_cast<unsigned>(crc));
  if (w <= 0) return false;
  out.write(line, static_cast<size_t>(w));
  return true;
}

/** Una lectura guardada. temperatura y humedad en décimas (245 = 24,5) para ocupar poco y mostrarse igual en vivo y recuperada. */
struct Sample {
  uint32_t n;
  uint32_t tMs;  // millis() del sensor cuando se midió
  int16_t t10;
  int16_t h10;
};

/** Buffer circular de lecturas. Cuando se llena descarta la más antigua. */
template <size_t Capacity>
class SampleBuffer {
 public:
  size_t size() const { return count_; }
  uint32_t dropped() const { return dropped_; }
  bool full() const { return count_ == Capacity; }
  const Sample& at(size_t i) const { return data_[(head_ + i) % Capacity]; }

  void push(const Sample& s) {
    if (count_ == Capacity) {  // lleno: se pierde la más antigua
      head_ = (head_ + 1) % Capacity;
      count_--;
      dropped_++;
    }
    data_[(head_ + count_) % Capacity] = s;
    count_++;
  }

  /** Libera todas las lecturas con n <= [n]; devuelve cuántas. */
  size_t confirmUpTo(uint32_t n) {
    size_t popped = 0;
    while (count_ > 0 && data_[head_].n <= n) {
      head_ = (head_ + 1) % Capacity;
      count_--;
      popped++;
    }
    return popped;
  }

 private:
  Sample data_[Capacity];
  size_t head_ = 0;
  size_t count_ = 0;
  uint32_t dropped_ = 0;
};

/** Contadores de calidad del enlace, para diagnóstico. */
struct Stats {
  uint32_t framesOk = 0;
  uint32_t crcErrors = 0;
  uint32_t overflows = 0;
  uint32_t noiseLines = 0;
};

class SensorCore {
 public:
  SensorCore(ByteSink& out, const char* deviceId, const char* version, uint32_t intervalMs = kDefaultIntervalMs,
             uint32_t minIntervalMs = kMinIntervalMs, uint32_t bootId = 1, uint32_t linkTimeoutMs = kLinkTimeoutMs)
      : out_(out), id_(deviceId), version_(version), interval_(intervalMs), minInterval_(minIntervalMs),
        bootId_(bootId), linkTimeoutMs_(linkTimeoutMs) {}

  /** Saludo de arranque: la app lo usa para enterarse del id y del bootId del sensor. */
  void begin() { sendHello(0); }

  /** Hora actual (millis). Llamar en cada vuelta del loop(), antes de onByte()/recordMeasurement()/pump(). */
  void setNow(uint32_t nowMs) { nowMs_ = nowMs; }

  /** Alimenta con cada byte que llega por el puerto serie. No bloquea. */
  void onByte(uint8_t b) {
    if (b == '\r') return;
    if (b == '\n') {
      if (overflow_) { stats_.overflows++; }
      else if (len_ > 0) { handleLine(); }
      len_ = 0;
      overflow_ = false;
      return;
    }
    if (overflow_) return;
    if (len_ >= kMaxLine) { overflow_ = true; return; }
    line_[len_++] = static_cast<char>(b);
  }

  /**
    Registra una medición: la guarda en el buffer y, si la app está escuchando, la envía en vivo.
    Devuelve el n que se le asignó.
  */
  uint32_t recordMeasurement(float tempC, float humPct) {
    Sample s;
    s.n = ++lastN_;
    s.tMs = nowMs_;
    s.t10 = toTenths(tempC);
    s.h10 = toTenths(humPct);
    if (buf_.full() && replayOffset_ > 0) replayOffset_--;  // la más antigua se descarta: el cursor de repetición se corre
    buf_.push(s);
    if (linkUp()) sendSample("TLM", s, false);
    return s.n;
  }

  /** ¿Llegó alguna trama válida de la app hace poco? */
  bool linkUp() const { return hasRx_ && static_cast<uint32_t>(nowMs_ - lastRxMs_) <= linkTimeoutMs_; }

  /** Envía unas pocas tramas de un SYNC en curso. Llamar en cada vuelta del loop(). */
  void pump() {
    if (!replaying_) return;
    for (size_t k = 0; k < kReplayPerPump; k++) {
      if (replayOffset_ >= buf_.size()) {
        char body[40];
        snprintf(body, sizeof body, "SYNCEND,%u,%u", replaySeq_, replayCount_);
        sendFrame(out_, body);
        replaying_ = false;
        return;
      }
      sendSample("BUF", buf_.at(replayOffset_++), true);
      replayCount_++;
    }
  }

  uint32_t intervalMs() const { return interval_; }
  uint32_t bootId() const { return bootId_; }
  size_t buffered() const { return buf_.size(); }
  uint32_t dropped() const { return buf_.dropped(); }
  bool replaying() const { return replaying_; }

  /** true una sola vez después de que la app cambió el intervalo; el .ino lo guarda en memoria no volátil. */
  bool takeIntervalChanged(uint32_t& value) {
    if (!intervalChanged_) return false;
    intervalChanged_ = false;
    value = interval_;
    return true;
  }

  /** true una sola vez después de que la app pidió una lectura inmediata ($GET). */
  bool takeReadRequest() {
    const bool r = readRequested_;
    readRequested_ = false;
    return r;
  }

  const Stats& stats() const { return stats_; }

 private:
  static int16_t toTenths(float v) {
    float x = v * 10.0f;
    x += (x >= 0) ? 0.5f : -0.5f;
    if (x > 32767.0f) x = 32767.0f;
    if (x < -32768.0f) x = -32768.0f;
    return static_cast<int16_t>(x);
  }

  static void fmtTenths(int16_t v10, char* out, size_t size) {
    const int a = v10 < 0 ? -static_cast<int>(v10) : static_cast<int>(v10);
    snprintf(out, size, "%s%d.%d", v10 < 0 ? "-" : "", a / 10, a % 10);
  }

  void sendSample(const char* type, const Sample& s, bool withAge) {
    char t[12], h[12], body[kMaxLine];
    fmtTenths(s.t10, t, sizeof t);
    fmtTenths(s.h10, h, sizeof h);
    if (withAge) {
      snprintf(body, sizeof body, "%s,%s,%lu,%lu,%lu,%s,%s", type, id_, static_cast<unsigned long>(bootId_),
               static_cast<unsigned long>(s.n), static_cast<unsigned long>(static_cast<uint32_t>(nowMs_ - s.tMs)), t, h);
    } else {
      snprintf(body, sizeof body, "%s,%s,%lu,%lu,%s,%s", type, id_, static_cast<unsigned long>(bootId_),
               static_cast<unsigned long>(s.n), t, h);
    }
    sendFrame(out_, body);
  }

  void sendHello(unsigned seq) {
    char body[kMaxLine];
    snprintf(body, sizeof body, "HELLO,%u,%s,%s,%lu,%lu", seq, id_, version_, static_cast<unsigned long>(interval_),
             static_cast<unsigned long>(bootId_));
    sendFrame(out_, body);
  }

  void reply(const char* type, unsigned seq, const char* extra = nullptr) {
    char body[48];
    if (extra) snprintf(body, sizeof body, "%s,%u,%s", type, seq, extra);
    else snprintf(body, sizeof body, "%s,%u", type, seq);
    sendFrame(out_, body);
  }

  static bool parseUint(const char* s, unsigned long& out, unsigned long maxValue) {
    if (!s || !*s) return false;
    unsigned long v = 0;
    for (; *s; s++) {
      if (*s < '0' || *s > '9') return false;
      v = v * 10 + static_cast<unsigned long>(*s - '0');
      if (v > maxValue) return false;
    }
    out = v;
    return true;
  }

  void startSync(unsigned seq) {
    replaying_ = true;
    replayOffset_ = 0;
    replayCount_ = 0;
    replaySeq_ = seq;
    const unsigned long oldest = buf_.size() > 0 ? buf_.at(0).n : lastN_ + 1;
    char body[kMaxLine];
    snprintf(body, sizeof body, "SYNCSTART,%u,%u,%u,%lu,%lu,%lu", seq, static_cast<unsigned>(buf_.size()),
             static_cast<unsigned>(kBufferCapacity), static_cast<unsigned long>(buf_.dropped()),
             static_cast<unsigned long>(bootId_), oldest);
    sendFrame(out_, body);
  }

  void handleLine() {
    line_[len_] = '\0';
    if (line_[0] != '$') { stats_.noiseLines++; return; }          // log de arranque u otro texto
    // La línea debe terminar en "*XXXX"
    if (len_ < 7 || line_[len_ - 5] != '*') { stats_.crcErrors++; return; }
    unsigned crcRx = 0;
    for (size_t i = len_ - 4; i < len_; i++) {
      const char c = line_[i];
      unsigned d;
      if (c >= '0' && c <= '9') d = static_cast<unsigned>(c - '0');
      else if (c >= 'A' && c <= 'F') d = static_cast<unsigned>(c - 'A' + 10);
      else { stats_.crcErrors++; return; }   // solo MAYÚSCULAS: así ningún cambio de un bit pasa inadvertido
      crcRx = (crcRx << 4) | d;
    }
    const size_t bodyLen = len_ - 6;                                 // sin '$' ni '*XXXX'
    if (crc16(reinterpret_cast<const uint8_t*>(line_ + 1), bodyLen) != crcRx) { stats_.crcErrors++; return; }
    stats_.framesOk++;
    hasRx_ = true;                                                   // la app está escuchando
    lastRxMs_ = nowMs_;

    line_[len_ - 5] = '\0';                                          // el cuerpo termina donde estaba '*'
    char* f[6] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    int n = 0;
    char* p = line_ + 1;
    f[n++] = p;
    for (; *p && n < 6; p++) if (*p == ',') { *p = '\0'; f[n++] = p + 1; }

    unsigned long seq = 0;
    if (n < 2 || !parseUint(f[1], seq, 65535) || seq == 0) { reply("ERR", 0, "FORMAT"); return; }
    const unsigned s = static_cast<unsigned>(seq);

    if (strcmp(f[0], "PING") == 0 && n == 2) { reply("PONG", s); }
    else if (strcmp(f[0], "INFO") == 0 && n == 2) { sendHello(s); }
    else if (strcmp(f[0], "GET") == 0 && n == 2) { readRequested_ = true; reply("ACK", s); }
    else if (strcmp(f[0], "SYNC") == 0 && n == 2) { startSync(s); }
    else if (strcmp(f[0], "CONF") == 0 && n == 3) {
      unsigned long upTo = 0;
      if (!parseUint(f[2], upTo, 0xFFFFFFFFUL)) { reply("ERR", s, "FORMAT"); return; }
      const size_t popped = buf_.confirmUpTo(static_cast<uint32_t>(upTo));
      replayOffset_ = replayOffset_ > popped ? replayOffset_ - popped : 0;  // el cursor sigue apuntando a la misma lectura
    }
    else if (strcmp(f[0], "SET") == 0 && n == 4 && strcmp(f[2], "INTERVAL") == 0) {
      unsigned long ms = 0;
      if (!parseUint(f[3], ms, 0xFFFFFFFFUL)) { reply("ERR", s, "FORMAT"); return; }
      if (ms < minInterval_ || ms > kMaxIntervalMs) { reply("ERR", s, "RANGE"); return; }
      interval_ = static_cast<uint32_t>(ms);
      intervalChanged_ = true;
      reply("ACK", s);
    } else { reply("ERR", s, "UNKNOWN"); }
  }

  ByteSink& out_;
  const char* id_;
  const char* version_;
  uint32_t interval_;
  uint32_t minInterval_;
  uint32_t bootId_;
  uint32_t linkTimeoutMs_;
  uint32_t nowMs_ = 0;
  uint32_t lastRxMs_ = 0;
  bool hasRx_ = false;
  uint32_t lastN_ = 0;
  SampleBuffer<kBufferCapacity> buf_;
  bool replaying_ = false;
  size_t replayOffset_ = 0;
  uint32_t replayCount_ = 0;
  unsigned replaySeq_ = 0;
  char line_[kMaxLine + 1];
  size_t len_ = 0;
  bool overflow_ = false;
  bool intervalChanged_ = false;
  bool readRequested_ = false;
  Stats stats_;
};

}  // namespace bd

#endif  // BD_SERIAL_H
