-- BD_Control_App: verificación en 2 pasos + permisos por dispositivo.
-- Ejecutar UNA vez en el SQL Editor de Supabase. Es idempotente (se puede repetir sin romper nada).

-- 1) Segundo paso del login: secreto TOTP (Base32) por usuario.
--    NULL = la cuenta aún no tiene segundo paso y la app NO la deja entrar (ver AuthViewModel).
--    Usuarios existentes: python3 tools/new_user.py totp USUARIO   (genera el UPDATE de cada uno)
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret text;

-- 2) Permisos por dispositivo: qué puede consultar y controlar cada usuario sobre CADA ESP32.
--    device_id es el DEVICE_ID de firmware/*/config.h; el valor '*' aplica a todos (administrador) y una
--    fila específica la sobrescribe. Sin fila = sin acceso (denegar por defecto).
--    can_control solo vale si can_view también es true.
CREATE TABLE IF NOT EXISTS device_permissions (
  username    text    NOT NULL,
  device_id   text    NOT NULL CHECK (device_id = '*' OR device_id ~ '^[A-Za-z0-9_.-]{1,32}$'),
  can_view    boolean NOT NULL DEFAULT false,
  can_control boolean NOT NULL DEFAULT false,
  PRIMARY KEY (username, device_id)
);

-- 3) La app solo LEE esta tabla (con la llave anónima): se activa RLS y se permite únicamente SELECT.
--    Así la llave anónima no puede darse permisos a sí misma. Los permisos se modifican desde el
--    SQL Editor (o con python3 tools/new_user.py permisos ...).
ALTER TABLE device_permissions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "app lee permisos" ON device_permissions;
CREATE POLICY "app lee permisos" ON device_permissions FOR SELECT TO anon USING (true);

-- Ejemplo: Ana ve todo y controla un solo ventilador; Beto es administrador.
-- INSERT INTO device_permissions (username, device_id, can_view, can_control) VALUES
--   ('ana',  'sensor-1', true, false),
--   ('ana',  'rele-1',   true, true),
--   ('beto', '*',        true, true);

-- IMPORTANTE (modo simulador, iot.transport=supabase): sus dispositivos usan los ids 'sensor' y 'rele'.
