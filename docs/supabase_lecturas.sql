-- BD_Control_App: almacenamiento externo de las lecturas de los sensores.
-- Ejecutar UNA vez en el SQL Editor de Supabase. Es idempotente (se puede repetir sin romper nada).
--
-- La app guarda cada lectura primero en el teléfono (cola local) y la sube aquí cuando hay internet.
-- La clave única (device_id, sample_key) hace la subida IDEMPOTENTE: si una lectura llega dos veces
-- (por ejemplo, la subida llegó pero la app se cerró antes de enterarse), el servidor ignora la repetida.

CREATE TABLE IF NOT EXISTS sensor_readings (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id   text             NOT NULL CHECK (device_id ~ '^[A-Za-z0-9_.-]{1,32}$'),
  -- Identidad de la lectura: "<bootId>-<n>" (sensores por cable) o "w-<hora>" (Wi-Fi), o "m<id>" (migradas)
  sample_key  text             NOT NULL CHECK (char_length(sample_key) BETWEEN 1 AND 40),
  -- Hora real (ms desde 1970) en que se MIDIÓ, no en que se subió
  ts_ms       bigint           NOT NULL CHECK (ts_ms > 1577836800000),            -- posterior a 2020
  temp_c      double precision NOT NULL CHECK (temp_c BETWEEN -40 AND 125),
  hum_pct     double precision NOT NULL CHECK (hum_pct BETWEEN 0 AND 100),
  received_at timestamptz      NOT NULL DEFAULT now(),
  UNIQUE (device_id, sample_key)
);

CREATE INDEX IF NOT EXISTS sensor_readings_device_ts ON sensor_readings (device_id, ts_ms DESC);

-- La app solo ESCRIBE (con la llave anónima): RLS activado y únicamente INSERT. Así la llave anónima no
-- puede leer, modificar ni borrar lecturas. Para consultarlas usa el panel de Supabase.
-- "Insertar e ignorar duplicados" solo exige permiso de INSERT.
ALTER TABLE sensor_readings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "app sube lecturas" ON sensor_readings;
CREATE POLICY "app sube lecturas" ON sensor_readings FOR INSERT TO anon WITH CHECK (true);

GRANT INSERT ON sensor_readings TO anon;

-- Ejemplo de consulta: promedio por hora de un sensor
-- SELECT date_trunc('hour', to_timestamp(ts_ms / 1000.0)) AS hora, avg(temp_c), avg(hum_pct)
-- FROM sensor_readings WHERE device_id = 'sensor-1' GROUP BY 1 ORDER BY 1 DESC;
