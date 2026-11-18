-- BD_Control_App: login con Google (Supabase Auth) + permisos por dispositivo.
-- Reemplaza a docs/supabase_2fa_permisos.sql (usuario/contraseña + TOTP manual, ya eliminado).
-- Ejecutar en el SQL Editor de Supabase. Es idempotente (se puede repetir sin romper nada).
--
-- Antes de esto, fuera de la base de datos (ver docs/migracion_google_auth.md):
--   1) Crear en Google Cloud Console un cliente OAuth tipo "Web application" y pegar su
--      Client ID/Secret en Supabase > Authentication > Sign In / Providers > Google.
--   2) Crear además un cliente OAuth tipo "Android" con el paquete cl.avelyn.bd_control_app y el
--      SHA-1 de la firma de la app (debug y release); Google lo usa para confiar en el selector
--      nativo de cuentas, aunque su Client ID no se use en el código.

-- 1) Permisos por dispositivo, ahora enlazados al correo de Google de cada usuario (antes `username`).
--    device_id es el DEVICE_ID de firmware/*/config.h; '*' aplica a todos (administrador) y una
--    fila específica lo sobrescribe. Sin fila = sin acceso (denegar por defecto).
--    can_control solo vale si can_view también es true.
CREATE TABLE IF NOT EXISTS device_permissions (
  email       text    NOT NULL,
  device_id   text    NOT NULL CHECK (device_id = '*' OR device_id ~ '^[A-Za-z0-9_.-]{1,32}$'),
  can_view    boolean NOT NULL DEFAULT false,
  can_control boolean NOT NULL DEFAULT false,
  PRIMARY KEY (email, device_id)
);

-- Si ya tenías la tabla vieja (con la columna `username`) y quieres conservar los permisos en vez
-- de recrearlos, usa esto en lugar del CREATE TABLE de arriba, y luego actualiza cada fila con el
-- correo real de Gmail de la cuenta (el username viejo no sirve para hacer login nunca más):
--   ALTER TABLE device_permissions RENAME COLUMN username TO email;
--   UPDATE device_permissions SET email = 'correo-real@gmail.com' WHERE email = 'nombre-usuario-viejo';

-- 2) La app solo LEE esta tabla (con la llave anónima): se activa RLS y se permite únicamente SELECT.
--    Los permisos se modifican desde el SQL Editor (o con python3 tools/manage_permissions.py).
ALTER TABLE device_permissions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "app lee permisos" ON device_permissions;
CREATE POLICY "app lee permisos" ON device_permissions FOR SELECT TO anon USING (true);

-- Ejemplo: Ana ve todo y controla un solo ventilador; Beto es administrador.
-- INSERT INTO device_permissions (email, device_id, can_view, can_control) VALUES
--   ('ana@gmail.com',  'sensor-1', true, false),
--   ('ana@gmail.com',  'rele-1',   true, true),
--   ('beto@gmail.com', '*',        true, true);

-- 3) Ya no se necesita la tabla de contraseñas/TOTP: bórrala cuando confirmes que ya no la usas.
-- DROP TABLE IF EXISTS users;

-- IMPORTANTE (modo simulador, iot.transport=supabase): sus dispositivos usan los ids 'sensor' y 'rele'.
