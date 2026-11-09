// Simulador del ESP32 del sensor para el computador: usa EXACTAMENTE el mismo bd_serial.h que el firmware
// y habla por stdin/stdout en vez de por el puerto USB. Sirve para probar la app contra el protocolo real.
//
//   g++ -std=c++11 -O1 -o host_sim host_sim.cpp
//   ./host_sim --id sensor-1 --interval 1000
//
// Opciones: --id X | --interval MS | --min-interval MS (solo pruebas) | --boot-id N | --link-timeout MS
// (cuánto sin tramas de la app para dejar de enviar en vivo y solo guardar) | --noise (imprime el log de
// arranque del ESP32 antes del saludo) | --corrupt-every N (daña 1 de cada N tramas $TLM) | --corrupt-buf
// (con lo anterior, también daña 1 de cada N tramas $BUF) | --hang-after N (se "cuelga" tras N lecturas).
// Si se cierra la entrada estándar el programa termina (equivale a desenchufar el cable).
#include "../bd_serial.h"

#include <poll.h>
#include <unistd.h>

#include <chrono>
#include <cstdlib>
#include <cstring>

namespace {
long argValue(int argc, char** argv, const char* name, long fallback) {
  for (int i = 1; i + 1 < argc; i++) if (strcmp(argv[i], name) == 0) return atol(argv[i + 1]);
  return fallback;
}
bool hasFlag(int argc, char** argv, const char* name) {
  for (int i = 1; i < argc; i++) if (strcmp(argv[i], name) == 0) return true;
  return false;
}
const char* argString(int argc, char** argv, const char* name, const char* fallback) {
  for (int i = 1; i + 1 < argc; i++) if (strcmp(argv[i], name) == 0) return argv[i + 1];
  return fallback;
}

struct StdoutSink : bd::ByteSink {
  long corruptEvery = 0;
  bool corruptBuf = false;
  long count = 0;
  void write(const char* data, size_t len) override {
    char copy[256];
    if (len >= sizeof copy) return;
    memcpy(copy, data, len);
    const bool isTlm = len > 8 && strncmp(copy, "$TLM", 4) == 0;
    const bool isBuf = corruptBuf && len > 8 && strncmp(copy, "$BUF", 4) == 0;
    if (corruptEvery > 0 && (isTlm || isBuf) && (++count % corruptEvery) == 0) copy[6] ^= 0x01;  // 1 bit
    fwrite(copy, 1, len, stdout);
    fflush(stdout);
  }
};

uint32_t nowMs() {
  return static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
}
}  // namespace

int main(int argc, char** argv) {
  const char* id = argString(argc, argv, "--id", "sensor-1");
  const uint32_t interval = static_cast<uint32_t>(argValue(argc, argv, "--interval", 1000));
  const uint32_t minInterval = static_cast<uint32_t>(argValue(argc, argv, "--min-interval", bd::kMinIntervalMs));
  const uint32_t bootId = static_cast<uint32_t>(argValue(argc, argv, "--boot-id", 1234567));
  const uint32_t linkTimeout = static_cast<uint32_t>(argValue(argc, argv, "--link-timeout", bd::kLinkTimeoutMs));
  const long hangAfter = argValue(argc, argv, "--hang-after", 0);

  StdoutSink sink;
  sink.corruptEvery = argValue(argc, argv, "--corrupt-every", 0);
  sink.corruptBuf = hasFlag(argc, argv, "--corrupt-buf");
  static bd::SensorCore core(sink, id, "host-sim", interval, minInterval, bootId, linkTimeout);

  if (hasFlag(argc, argv, "--noise")) {
    fputs("ets Jun  8 2016 00:22:57\r\n\r\nrst:0x1 (POWERON_RESET),boot:0x13 (SPI_FAST_FLASH_BOOT)\r\nconfigsip: 0, SPIWP:0xee\r\n", stdout);
    fflush(stdout);
  }
  core.setNow(nowMs());
  core.begin();

  uint32_t lastSent = nowMs();
  long sent = 0;
  int n = 0;
  for (;;) {
    core.setNow(nowMs());
    pollfd pfd = {STDIN_FILENO, POLLIN, 0};
    if (poll(&pfd, 1, 5) > 0) {
      if (pfd.revents & POLLIN) {
        unsigned char buf[64];
        const ssize_t r = read(STDIN_FILENO, buf, sizeof buf);
        if (r <= 0) return 0;                       // la app cerró el "cable"
        core.setNow(nowMs());
        for (ssize_t i = 0; i < r; i++) core.onByte(buf[i]);
      } else if (pfd.revents & (POLLHUP | POLLERR)) {
        return 0;
      }
    }
    core.setNow(nowMs());
    core.pump();
    uint32_t newInterval;
    if (core.takeIntervalChanged(newInterval)) { /* en el ESP32 aquí se guarda en NVS */ }
    const bool immediate = core.takeReadRequest();
    if (immediate || nowMs() - lastSent >= core.intervalMs()) {
      lastSent = nowMs();
      // lecturas deterministas: 20.0..20.9 °C y 50..59 %
      core.recordMeasurement(20.0f + static_cast<float>(n % 10) / 10.0f, 50.0f + static_cast<float>(n % 10));
      n++;
      if (hangAfter > 0 && ++sent >= hangAfter) { for (;;) pause(); }   // simula un ESP32 colgado
    }
  }
}
