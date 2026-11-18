# Migración a login con Google (Supabase Auth)

Reemplaza el login manual anterior (usuario/contraseña + TOTP contra una tabla `users` propia) por
"Continuar con Google", con el selector nativo de cuentas (Credential Manager) y Supabase Auth.
Checklist para dejarlo funcionando, en orden:

## 1. Google Cloud Console

1. Entra a tu proyecto en https://console.cloud.google.com/ (o crea uno nuevo).
2. **Pantalla de consentimiento OAuth** (APIs y servicios → Pantalla de consentimiento OAuth):
   configúrala en modo "Externo" si tu cuenta no es de Google Workspace, con el nombre de la app y
   tu correo de soporte.
3. **Credenciales → Crear credenciales → ID de cliente de OAuth**:
   - Tipo **"Aplicación web"**. Nombre libre (ej. "BD Control App - Supabase"). No hace falta poner
     URIs de redirección a mano todavía. Guarda el **Client ID** y el **Client Secret**: son para
     Supabase (paso 2).
   - Crea un **segundo** cliente, tipo **"Android"**. Paquete: `cl.avelyn.bd_control_app`. SHA-1:
     obtén el de debug con `./gradlew signingReport` (busca la variante `debug`) y agrega también
     el de tu keystore de release cuando lo tengas. Este cliente no se usa en el código: solo le
     dice a Google que confíe en tu app para el selector nativo de cuentas.

## 2. Supabase

1. En el dashboard del proyecto → **Authentication → Sign In / Providers → Google**.
2. Actívalo y pega el **Client ID** y **Client Secret** del cliente "Aplicación web" del paso 1.
3. Guarda. No hace falta tocar nada de `device_permissions` todavía.

## 3. Base de datos

Ejecuta `docs/supabase_google_auth.sql` en el SQL Editor de Supabase. Si ya tenías usuarios con
permisos cargados bajo la tabla vieja, el script incluye (comentado) cómo migrar la columna
`username` a `email` en vez de recrear la tabla desde cero — ahí mismo tendrás que reemplazar cada
username por el correo de Gmail real de esa persona.

## 4. `local.properties`

Agrega la línea (ver `local.properties.example`):

```
google.webClientId=TU_CLIENT_ID_WEB.apps.googleusercontent.com
```

Es el Client ID del cliente **"Aplicación web"** del paso 1 (el mismo que pegaste en Supabase), no
el del cliente "Android".

## 5. Dar acceso a cada persona

Ya no hace falta "dar de alta" cuentas (cualquier Gmail puede entrar). Solo hay que asignarle sus
permisos por dispositivo:

```
python3 tools/manage_permissions.py permisos correo@gmail.com rele-1:control sensor-1:ver
```

Sin ninguna fila en `device_permissions`, el correo puede iniciar sesión pero no ve ni controla
ningún dispositivo (se deniega por defecto).

## Notas

- La sesión sigue sin sobrevivir al cierre de la app (a propósito, como antes): cada vez que se
  abre la app hay que volver a tocar "Continuar con Google" y elegir la cuenta.
- El emulador de Android necesita una imagen de sistema **con Google Play Services** (las que dicen
  "Google Play" en el AVD Manager) para que aparezca el selector de cuentas; con una imagen
  "Google APIs" a secas no funciona.
- Quien pruebe la app necesita tener agregada una cuenta de Google en el teléfono/emulador.
