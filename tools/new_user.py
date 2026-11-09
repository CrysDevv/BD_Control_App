#!/usr/bin/env python3
"""
Alta de usuarios para BD_Control_App: genera el SQL que se pega en el SQL Editor de Supabase.
Solo usa la biblioteca estándar de Python 3. La app nunca crea cuentas por su cuenta.

Uso:
  python3 tools/new_user.py alta   USUARIO                      # usuario nuevo: contraseña + secreto TOTP
  python3 tools/new_user.py totp   USUARIO                      # solo el secreto TOTP de un usuario que ya existe
  python3 tools/new_user.py permisos USUARIO rele-1:control sensor-1:ver '*:control'
        # permisos por dispositivo: "ver" = solo consultar, "control" = consultar y controlar, "*" = todos

El hash usa los mismos parámetros que PasswordHasher.kt (PBKDF2-HMAC-SHA256, 120000 iteraciones,
256 bits, salt de 16 bytes, todo en Base64 estándar sin saltos de línea).
"""
import argparse
import base64
import getpass
import hashlib
import re
import secrets
import sys
from urllib.parse import quote

ITERATIONS = 120_000
KEY_BYTES = 32
SALT_BYTES = 16
TOTP_SECRET_BYTES = 20  # 160 bits, el tamaño estándar para TOTP con SHA-1
ISSUER = "BD Control"


def sql_text(value: str) -> str:
    """Literal SQL de texto con las comillas simples escapadas."""
    return "'" + value.replace("'", "''") + "'"


def new_totp_secret() -> str:
    return base64.b32encode(secrets.token_bytes(TOTP_SECRET_BYTES)).decode().rstrip("=")


def otpauth_uri(username: str, secret: str) -> str:
    label = quote(f"{ISSUER}:{username}")
    return f"otpauth://totp/{label}?secret={secret}&issuer={quote(ISSUER)}&algorithm=SHA1&digits=6&period=30"


def show_enrolment(username: str, secret: str) -> None:
    uri = otpauth_uri(username, secret)
    print("\n== Segundo paso (TOTP) ==")
    print(f"Secreto (Base32): {secret}")
    print("Agrégalo en Google Authenticator / Authy / Microsoft Authenticator con 'Ingresar clave de configuración'")
    print("(tipo: basado en tiempo), o escaneando el QR de este enlace:")
    print(f"  {uri}")
    try:  # QR en la terminal, solo si el usuario tiene instalado el paquete opcional `qrcode`
        import qrcode  # type: ignore
        qr = qrcode.QRCode(border=1)
        qr.add_data(uri)
        qr.print_ascii(invert=True)
    except ImportError:
        print("(para ver un QR aquí: pip install qrcode)")
    print("\nEntrega el secreto SOLO a su dueño y no lo guardes en ningún otro lado.")


def cmd_alta(args) -> None:
    password = getpass.getpass("Contraseña: ")
    if password != getpass.getpass("Repite la contraseña: "):
        sys.exit("Las contraseñas no coinciden.")
    if len(password) < 8:
        sys.exit("Usa al menos 8 caracteres.")
    salt = secrets.token_bytes(SALT_BYTES)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, ITERATIONS, KEY_BYTES)
    secret = new_totp_secret()
    print("\n== Pega esto en el SQL Editor de Supabase ==")
    print("INSERT INTO users (username, password_hash, salt, totp_secret) VALUES (")
    print(f"  {sql_text(args.usuario)},")
    print(f"  {sql_text(base64.b64encode(digest).decode())},")
    print(f"  {sql_text(base64.b64encode(salt).decode())},")
    print(f"  {sql_text(secret)}")
    print(");")
    show_enrolment(args.usuario, secret)


def cmd_totp(args) -> None:
    secret = new_totp_secret()
    print("\n== Pega esto en el SQL Editor de Supabase ==")
    print(f"UPDATE users SET totp_secret = {sql_text(secret)} WHERE username = {sql_text(args.usuario)};")
    show_enrolment(args.usuario, secret)


def cmd_permisos(args) -> None:
    rows = []
    for item in args.permisos:
        m = re.fullmatch(r"([A-Za-z0-9_.-]{1,32}|\*):(ver|control)", item)
        if not m:
            sys.exit(f"Permiso inválido '{item}'. Formato: DISPOSITIVO:ver o DISPOSITIVO:control (DISPOSITIVO puede ser *).")
        device, level = m.groups()
        rows.append(f"  ({sql_text(args.usuario)}, {sql_text(device)}, true, {'true' if level == 'control' else 'false'})")
    print("\n== Pega esto en el SQL Editor de Supabase ==")
    print("INSERT INTO device_permissions (username, device_id, can_view, can_control) VALUES")
    print(",\n".join(rows))
    print("ON CONFLICT (username, device_id) DO UPDATE SET can_view = EXCLUDED.can_view, can_control = EXCLUDED.can_control;")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="comando", required=True)
    a = sub.add_parser("alta", help="usuario nuevo"); a.add_argument("usuario"); a.set_defaults(fn=cmd_alta)
    t = sub.add_parser("totp", help="secreto TOTP para un usuario existente"); t.add_argument("usuario"); t.set_defaults(fn=cmd_totp)
    p = sub.add_parser("permisos", help="permisos por dispositivo")
    p.add_argument("usuario"); p.add_argument("permisos", nargs="+"); p.set_defaults(fn=cmd_permisos)
    args = parser.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
