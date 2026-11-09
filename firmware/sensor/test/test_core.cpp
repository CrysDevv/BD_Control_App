// Pruebas del núcleo del protocolo (bd_serial.h). Se compilan con -DBD_BUFFER_CAPACITY=8 para
// poder probar el desborde del buffer con pocas lecturas. Correr con run_tests.sh
#include "../bd_serial.h"

#include <cstdio>
#include <string>
#include <vector>

struct BufSink : bd::ByteSink {
  std::string out;
  void write(const char* d, size_t n) override { out.append(d, n); }
  std::vector<std::string> lines() const {
    std::vector<std::string> v; std::string cur;
    for (char c : out) { if (c == '\n') { v.push_back(cur); cur.clear(); } else cur += c; }
    return v;
  }
};

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { printf("  FAIL línea %d: %s\n", __LINE__, #cond); failures++; } } while (0)

static std::string frame(const std::string& body) { BufSink s; bd::sendFrame(s, body.c_str()); return s.out; }
static void feed(bd::SensorCore& c, const std::string& s) { for (char ch : s) c.onByte(static_cast<uint8_t>(ch)); }
static bool has(const std::string& all, const std::string& body) { return all.find(frame(body)) != std::string::npos; }
static int count(const std::string& all, const std::string& prefix) {
  int k = 0; for (size_t p = all.find(prefix); p != std::string::npos; p = all.find(prefix, p + 1)) k++; return k;
}
// bucle como el del .ino: avanzar la hora y bombear hasta terminar un SYNC
static void drain(bd::SensorCore& c) { for (int i = 0; i < 10000 && c.replaying(); i++) c.pump(); }

int main() {
  static_assert(bd::kBufferCapacity == 8, "estas pruebas se compilan con -DBD_BUFFER_CAPACITY=8");

  CHECK(bd::crc16(reinterpret_cast<const uint8_t*>("123456789"), 9) == 0x29B1);   // vector estándar

  { // saludo de arranque con bootId
    BufSink s; bd::SensorCore c(s, "sensor-1", "1.0", 2000, 1000, 777); c.begin();
    CHECK(s.out == frame("HELLO,0,sensor-1,1.0,2000,777"));
  }
  { // comandos básicos
    BufSink s; bd::SensorCore c(s, "sensor-9", "2.1", 3000, 1000, 5);
    feed(c, frame("PING,42"));  CHECK(s.out == frame("PONG,42")); s.out.clear();
    feed(c, frame("INFO,7"));   CHECK(s.out == frame("HELLO,7,sensor-9,2.1,3000,5")); s.out.clear();
    feed(c, frame("GET,3"));    CHECK(s.out == frame("ACK,3") && c.takeReadRequest() && !c.takeReadRequest()); s.out.clear();
    feed(c, frame("REBOOT,4")); CHECK(s.out == frame("ERR,4,UNKNOWN")); s.out.clear();
    feed(c, frame("PING,0"));   CHECK(s.out == frame("ERR,0,FORMAT")); s.out.clear();
    feed(c, frame("PING,x"));   CHECK(s.out == frame("ERR,0,FORMAT"));
  }
  { // SET INTERVAL
    BufSink s; bd::SensorCore c(s, "s", "v", 2000);
    feed(c, frame("SET,5,INTERVAL,10000"));
    CHECK(s.out == frame("ACK,5") && c.intervalMs() == 10000);
    uint32_t v = 0; CHECK(c.takeIntervalChanged(v) && v == 10000 && !c.takeIntervalChanged(v));
    s.out.clear();
    feed(c, frame("SET,6,INTERVAL,10"));       CHECK(s.out == frame("ERR,6,RANGE")); s.out.clear();
    feed(c, frame("SET,7,INTERVAL,9999999"));  CHECK(s.out == frame("ERR,7,RANGE")); s.out.clear();
    feed(c, frame("SET,8,INTERVAL,abc"));      CHECK(s.out == frame("ERR,8,FORMAT")); s.out.clear();
    feed(c, frame("SET,9,INTERVAL,-5"));       CHECK(s.out == frame("ERR,9,FORMAT"));
    CHECK(c.intervalMs() == 10000);
  }
  { // INTEGRIDAD: un solo bit alterado en cualquier posición => no se ejecuta ni se responde nada
    const std::string good = frame("SET,5,INTERVAL,10000");
    int accepted = 0;
    for (size_t i = 0; i + 1 < good.size(); i++) for (int bit = 0; bit < 8; bit++) {
      std::string bad = good; bad[i] = static_cast<char>(bad[i] ^ (1 << bit));
      if (bad[i] == '\n' || bad[i] == '\r') continue;
      BufSink s; bd::SensorCore c(s, "s", "v", 2000);
      feed(c, bad); feed(c, "\n");
      if (c.intervalMs() != 2000 || !s.out.empty()) accepted++;
    }
    CHECK(accepted == 0);
  }
  { // ruido, líneas largas, recuperación
    BufSink s; bd::SensorCore c(s, "s", "v", 2000);
    feed(c, "ets Jun  8 2016\r\nrst:0x1 (POWERON_RESET)\r\n");
    CHECK(s.out.empty() && c.stats().noiseLines == 2);
    feed(c, std::string(500, 'x') + "\n");
    CHECK(c.stats().overflows == 1 && s.out.empty());
    feed(c, frame("PING,9")); CHECK(s.out == frame("PONG,9"));
  }
  { // trama dañada no bloquea la siguiente
    BufSink s; bd::SensorCore c(s, "s", "v", 2000);
    std::string bad = frame("PING,1"); bad[3] = 'X';
    feed(c, bad); feed(c, frame("PING,2"));
    CHECK(s.out == frame("PONG,2") && c.stats().crcErrors == 1 && c.stats().framesOk == 1);
  }

  // ---------------- BUFFER ----------------
  { // formato de las lecturas: décimas, redondeo y signo
    BufSink s; bd::SensorCore c(s, "sensor-1", "v", 2000, 1000, 9, 6000);
    c.setNow(1000); feed(c, frame("PING,1")); s.out.clear();       // la app "escucha"
    c.recordMeasurement(24.56f, 60.04f);  CHECK(has(s.out, "TLM,sensor-1,9,1,24.6,60.0"));
    c.recordMeasurement(-0.5f, 100.0f);   CHECK(has(s.out, "TLM,sensor-1,9,2,-0.5,100.0"));
    c.recordMeasurement(-12.34f, 0.04f);  CHECK(has(s.out, "TLM,sensor-1,9,3,-12.3,0.0"));
  }
  { // sin app escuchando: se guardan y NO se envían; al escuchar, se envían en vivo
    BufSink s; bd::SensorCore c(s, "s", "v", 2000, 1000, 1, 6000);
    c.setNow(0);
    CHECK(!c.linkUp());
    c.recordMeasurement(20, 50); c.recordMeasurement(21, 51);
    CHECK(s.out.empty() && c.buffered() == 2);
    c.setNow(100); feed(c, frame("PING,1")); s.out.clear();
    CHECK(c.linkUp());
    c.recordMeasurement(22, 52);
    CHECK(has(s.out, "TLM,s,1,3,22.0,52.0") && c.buffered() == 3);
    c.setNow(100 + 6001);                                           // la app dejó de dar señales
    CHECK(!c.linkUp());
    s.out.clear(); c.recordMeasurement(23, 53);
    CHECK(s.out.empty() && c.buffered() == 4);
    c.setNow(100 + 6000 + 50); feed(c, frame("PING,2"));            // vuelve
    CHECK(c.linkUp());
  }
  { // SYNC: SYNCSTART + BUF en orden con su edad + SYNCEND
    BufSink s; bd::SensorCore c(s, "s", "v", 2000, 1000, 42, 6000);
    c.setNow(1000); c.recordMeasurement(20.0f, 50.0f);
    c.setNow(3000); c.recordMeasurement(21.0f, 51.0f);
    c.setNow(5000); c.recordMeasurement(22.0f, 52.0f);
    c.setNow(9000); feed(c, frame("SYNC,11"));
    CHECK(has(s.out, "SYNCSTART,11,3,8,0,42,1"));                    // 3 pendientes, capacidad 8, 0 descartadas, bootId 42, más antigua n=1
    drain(c);
    CHECK(has(s.out, "BUF,s,42,1,8000,20.0,50.0"));                  // edad = 9000-1000
    CHECK(has(s.out, "BUF,s,42,2,6000,21.0,51.0"));
    CHECK(has(s.out, "BUF,s,42,3,4000,22.0,52.0"));
    CHECK(has(s.out, "SYNCEND,11,3"));
    auto L = s.lines(); CHECK(L.size() == 5 && L.front().rfind("$SYNCSTART", 0) == 0 && L.back().rfind("$SYNCEND", 0) == 0);
    CHECK(c.buffered() == 3);                                        // SYNC NO libera: solo CONF libera
  }
  { // CONF libera hasta n y nada más; es acumulativo e idempotente
    BufSink s; bd::SensorCore c(s, "s", "v", 2000, 1000, 1, 6000);
    for (int i = 0; i < 5; i++) c.recordMeasurement(20, 50);
    feed(c, frame("CONF,1,3")); CHECK(c.buffered() == 2 && s.out.empty());   // sin respuesta
    feed(c, frame("CONF,2,3")); CHECK(c.buffered() == 2);                     // repetido: igual
    feed(c, frame("CONF,3,1")); CHECK(c.buffered() == 2);                     // un CONF atrasado no resucita nada
    feed(c, frame("CONF,4,99")); CHECK(c.buffered() == 0);
    feed(c, frame("SYNC,5"));
    CHECK(has(s.out, "SYNCSTART,5,0,8,0,1,6"));                                // vacío: la "más antigua" es la próxima (n=6)
    feed(c, frame("CONF,6,abc")); CHECK(has(s.out, "ERR,6,FORMAT"));
  }
  { // DESBORDE: se descarta la más antigua y se cuenta
    BufSink s; bd::SensorCore c(s, "s", "v", 2000, 1000, 1, 6000);
    for (int i = 0; i < 11; i++) c.recordMeasurement(20.0f + i, 50);          // capacidad 8: n=1..3 se pierden
    CHECK(c.buffered() == 8 && c.dropped() == 3);
    feed(c, frame("SYNC,1"));
    CHECK(has(s.out, "SYNCSTART,1,8,8,3,1,4"));                               // la más antigua que queda es n=4
    drain(c);
    CHECK(has(s.out, "BUF,s,1,4,0,23.0,50.0") && has(s.out, "BUF,s,1,11,0,30.0,50.0") && !has(s.out, "BUF,s,1,3,0,22.0,50.0"));
    CHECK(count(s.out, "$BUF,") == 8);
  }
  { // índices circulares: muchas vueltas al buffer sin perder el orden
    BufSink s; bd::SensorCore c(s, "s", "v", 2000, 1000, 1, 6000);
    for (int i = 1; i <= 100; i++) { c.recordMeasurement(static_cast<float>(i % 50), 50); if (i % 5 == 0) feed(c, frame("CONF,1," + std::to_string(i - 2))); }
    feed(c, frame("SYNC,2")); drain(c);
    CHECK(has(s.out, "SYNCSTART,2,2,8,0,1,99"));                              // quedan n=99 y 100
    CHECK(has(s.out, "BUF,s,1,99,0,49.0,50.0") && has(s.out, "BUF,s,1,100,0,0.0,50.0"));
  }
  { // el SYNC se retoma bien si llega CONF a mitad de la repetición
    BufSink s; bd::SensorCore c(s, "s", "v", 2000, 1000, 1, 6000);
    for (int i = 0; i < 8; i++) c.recordMeasurement(20, 50);
    feed(c, frame("SYNC,1")); c.pump();                                       // ya envió 4 (n=1..4)
    CHECK(count(s.out, "$BUF,") == 4);
    feed(c, frame("CONF,2,4"));                                               // la app confirma 1..4 mientras tanto
    drain(c);
    CHECK(count(s.out, "$BUF,") == 8 && has(s.out, "BUF,s,1,8,0,20.0,50.0") && has(s.out, "SYNCEND,1,8"));  // sigue con 5..8, sin repetir ni saltar
  }
  { // un SYNC nuevo reinicia la repetición; las lecturas en vivo se intercalan
    BufSink s; bd::SensorCore c(s, "s", "v", 2000, 1000, 1, 6000);
    c.setNow(0); feed(c, frame("PING,1"));
    for (int i = 0; i < 4; i++) c.recordMeasurement(20, 50);
    s.out.clear();
    feed(c, frame("SYNC,2")); c.pump();                                       // 4 BUF salen de una (kReplayPerPump=4)
    c.recordMeasurement(25, 50);                                              // llega una lectura nueva (TLM en vivo)
    feed(c, frame("SYNC,3"));                                                 // la app pide otro SYNC
    drain(c);
    CHECK(has(s.out, "TLM,s,1,5,25.0,50.0") && has(s.out, "SYNCEND,3,5") && !has(s.out, "SYNCEND,2"));
  }
  printf(failures == 0 ? "C++ core: TODO OK\n" : "C++ core: %d FALLO(S)\n", failures);
  return failures == 0 ? 0 : 1;
}
