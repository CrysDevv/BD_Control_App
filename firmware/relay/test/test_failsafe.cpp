// Pruebas del modo seguro del relé (relay_failsafe.h). Correr con run_tests.sh
#include "../relay_failsafe.h"

#include <cstdio>

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { printf("  FAIL línea %d: %s\n", __LINE__, #cond); failures++; } } while (0)

using namespace bd;
static const uint32_t MIN = 60UL * 1000UL;

static FailSafeConfig cfg(FailSafeMode m = FailSafeMode::DutyCycle) {
  FailSafeConfig c; c.silenceMs = 10 * MIN; c.mode = m; c.onMs = 5 * MIN; c.periodMs = 30 * MIN; return c;
}

int main() {
  { // con contacto reciente no hace nada
    FailSafe fs(cfg()); fs.begin(0);
    CHECK(fs.update(0) == FanOrder::None && !fs.engaged());
    CHECK(fs.update(9 * MIN + 59000) == FanOrder::None && !fs.engaged());
    CHECK(fs.takeEvent() == FailSafeEvent::None);
  }
  { // al cumplirse exactamente el silencio entra al modo seguro, empezando por ventilar
    FailSafe fs(cfg()); fs.begin(0);
    CHECK(fs.update(10 * MIN) == FanOrder::On && fs.engaged());
    CHECK(fs.takeEvent() == FailSafeEvent::Entered && fs.takeEvent() == FailSafeEvent::None);   // el evento sale una sola vez
  }
  { // ciclo: 5 min encendido, 25 apagado, y se repite
    FailSafe fs(cfg()); fs.begin(0);
    const uint32_t t0 = 10 * MIN;
    CHECK(fs.update(t0) == FanOrder::On);
    CHECK(fs.update(t0 + 4 * MIN + 59000) == FanOrder::On);
    CHECK(fs.update(t0 + 5 * MIN) == FanOrder::Off);
    CHECK(fs.update(t0 + 29 * MIN + 59000) == FanOrder::Off);
    CHECK(fs.update(t0 + 30 * MIN) == FanOrder::On);                 // segundo ciclo
    CHECK(fs.update(t0 + 35 * MIN) == FanOrder::Off);
    CHECK(fs.update(t0 + 60 * MIN) == FanOrder::On);                 // tercero
  }
  { // un comando de la app lo saca del modo seguro y reinicia el conteo de silencio
    FailSafe fs(cfg()); fs.begin(0);
    fs.update(10 * MIN); fs.takeEvent();
    fs.contact(12 * MIN);
    CHECK(fs.update(12 * MIN) == FanOrder::None && !fs.engaged());
    CHECK(fs.takeEvent() == FailSafeEvent::Left);
    CHECK(fs.update(12 * MIN + 9 * MIN) == FanOrder::None);           // vuelve a contar desde el último contacto
    CHECK(fs.update(12 * MIN + 10 * MIN) == FanOrder::On && fs.engaged());
  }
  { // latidos periódicos mantienen el modo normal indefinidamente
    FailSafe fs(cfg()); fs.begin(0);
    for (uint32_t t = 0; t < 600 * MIN; t += 30000) { fs.contact(t); CHECK(fs.update(t) == FanOrder::None); }
    CHECK(!fs.engaged());
  }
  { // KeepLast no toca el ventilador; AllOff lo apaga
    FailSafe keep(cfg(FailSafeMode::KeepLast)); keep.begin(0);
    CHECK(keep.update(11 * MIN) == FanOrder::None && keep.engaged());
    FailSafe off(cfg(FailSafeMode::AllOff)); off.begin(0);
    CHECK(off.update(11 * MIN) == FanOrder::Off && off.engaged());
  }
  { // tras un corte de luz sin app: arranca "con contacto" y entra al modo seguro pasado el silencio
    FailSafe fs(cfg()); fs.begin(5000);
    CHECK(fs.update(5000 + 10 * MIN - 1) == FanOrder::None);
    CHECK(fs.update(5000 + 10 * MIN) == FanOrder::On);
  }
  { // millis() da la vuelta (cada 49,7 días): todo sigue bien alrededor del desbordamiento
    const uint32_t nearWrap = 0xFFFFFFFFu - 2 * MIN;
    FailSafe fs(cfg()); fs.begin(nearWrap);
    CHECK(fs.update(nearWrap + 9 * MIN) == FanOrder::None);          // ya cruzó el 0, 9 min de silencio
    CHECK(fs.update(nearWrap + 10 * MIN) == FanOrder::On && fs.engaged());
    CHECK(fs.update(nearWrap + 15 * MIN) == FanOrder::Off);
    fs.contact(nearWrap + 16 * MIN);
    CHECK(fs.update(nearWrap + 16 * MIN) == FanOrder::None && !fs.engaged());
  }
  { // configuraciones absurdas se corrigen en vez de dividir por cero
    FailSafeConfig c; c.periodMs = 0; c.onMs = 100; c.silenceMs = 1000;
    FailSafe fs(c); fs.begin(0);
    (void)fs.update(2000);                                           // no debe fallar
    CHECK(fs.config().periodMs >= 1 && fs.config().onMs <= fs.config().periodMs);
  }
  printf(failures == 0 ? "Modo seguro: TODO OK\n" : "Modo seguro: %d FALLO(S)\n", failures);
  return failures == 0 ? 0 : 1;
}
