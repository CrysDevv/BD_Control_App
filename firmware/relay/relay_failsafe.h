/*
  relay_failsafe.h - Modo seguro del relé: qué hace el ventilador si la app deja de dar señales.

  C++ puro, SIN dependencias de Arduino (se prueba en un computador: ver test/).

  PROBLEMA. El sensor está conectado por cable al teléfono, no al relé: el relé NO ve la humedad. Si la app
  se cierra, el teléfono se apaga o el enlace falla, nadie decide por el ventilador y la bodega queda
  desprotegida. SOLUCIÓN. La app le envía al relé un "latido" (comando HB, cifrado igual que FAN) mientras su
  lazo de control está sano. Si el relé pasa [silenceMs] sin oír nada válido de la app, entra en MODO SEGURO:
    - DutyCycle (por defecto): ventila por ciclos, [onMs] encendido cada [periodMs], empezando encendido.
    - KeepLast: deja el ventilador como estaba.
    - AllOff: lo apaga.
  Apenas llega un comando válido de la app (HB o FAN) vuelve al modo normal; quien manda es la app.

  El tiempo es uint32 de millis(): las restas sin signo toleran que millis() dé la vuelta (cada 49,7 días).
  Al arrancar se cuenta como si hubiera habido contacto, así que tras un corte de luz sin app el relé también
  llega al modo seguro pasado [silenceMs].
*/
#ifndef BD_RELAY_FAILSAFE_H
#define BD_RELAY_FAILSAFE_H

#include <stdint.h>

namespace bd {

enum class FailSafeMode : uint8_t { DutyCycle = 0, KeepLast = 1, AllOff = 2 };

/** Qué debe hacer el ventilador según el modo seguro. */
enum class FanOrder : uint8_t { None, On, Off };

/** Cambio de estado del modo seguro desde la última consulta, para registrarlo y anunciarlo. */
enum class FailSafeEvent : uint8_t { None, Entered, Left };

struct FailSafeConfig {
  uint32_t silenceMs = 10UL * 60UL * 1000UL;   // 10 min sin oír a la app => modo seguro
  FailSafeMode mode = FailSafeMode::DutyCycle;
  uint32_t onMs = 5UL * 60UL * 1000UL;         // ventilar 5 min...
  uint32_t periodMs = 30UL * 60UL * 1000UL;    // ...cada 30 min (≈17 % del tiempo)
};

class FailSafe {
 public:
  explicit FailSafe(const FailSafeConfig& cfg = FailSafeConfig()) : cfg_(cfg) {
    if (cfg_.periodMs == 0) cfg_.periodMs = 1;
    if (cfg_.onMs > cfg_.periodMs) cfg_.onMs = cfg_.periodMs;
  }

  /** Llamar una vez al arrancar. */
  void begin(uint32_t nowMs) { lastContactMs_ = nowMs; }

  /** Llegó un comando VÁLIDO y autenticado de la app (HB o FAN). */
  void contact(uint32_t nowMs) { lastContactMs_ = nowMs; }

  /**
    Actualiza el estado. Devuelve qué hacer con el ventilador: None = no tocarlo (modo normal, o KeepLast);
    On/Off = ordenar ese estado (solo en modo seguro). Llamar seguido desde loop().
  */
  FanOrder update(uint32_t nowMs) {
    const bool shouldEngage = static_cast<uint32_t>(nowMs - lastContactMs_) >= cfg_.silenceMs;
    if (shouldEngage && !engaged_) {
      engaged_ = true;
      engagedSinceMs_ = nowMs;
      event_ = FailSafeEvent::Entered;
    } else if (!shouldEngage && engaged_) {
      engaged_ = false;
      event_ = FailSafeEvent::Left;
    }
    if (!engaged_) return FanOrder::None;
    switch (cfg_.mode) {
      case FailSafeMode::AllOff: return FanOrder::Off;
      case FailSafeMode::KeepLast: return FanOrder::None;
      case FailSafeMode::DutyCycle:
      default: {
        const uint32_t phase = static_cast<uint32_t>(nowMs - engagedSinceMs_) % cfg_.periodMs;
        return phase < cfg_.onMs ? FanOrder::On : FanOrder::Off;
      }
    }
  }

  bool engaged() const { return engaged_; }

  /** Devuelve el último cambio de estado una sola vez (para el log y para anunciarlo enseguida). */
  FailSafeEvent takeEvent() {
    const FailSafeEvent e = event_;
    event_ = FailSafeEvent::None;
    return e;
  }

  const FailSafeConfig& config() const { return cfg_; }

 private:
  FailSafeConfig cfg_;
  uint32_t lastContactMs_ = 0;
  uint32_t engagedSinceMs_ = 0;
  bool engaged_ = false;
  FailSafeEvent event_ = FailSafeEvent::None;
};

}  // namespace bd

#endif  // BD_RELAY_FAILSAFE_H
