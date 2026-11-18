#!/usr/bin/env python3
"""
Permisos por dispositivo de BD_Control_App: genera el SQL que se pega en el SQL Editor de Supabase.
Solo usa la biblioteca estándar de Python 3.

Las cuentas ya no se dan de alta a mano (reemplaza a tools/new_user.py, eliminado): cualquiera con
una cuenta de Gmail puede entrar con el botón "Continuar con Google" (ver AuthRepository.kt). Este
script solo administra qué dispositivos puede ver/controlar cada correo, en la tabla
`device_permissions` (ver docs/supabase_google_auth.sql).

Uso:
  python3 tools/manage_permissions.py permisos correo@gmail.com rele-1:control sensor-1:ver '*:control'
        # "ver" = solo consultar, "control" = consultar y controlar, "*" = todos los dispositivos
"""
import argparse
import re
import sys


def sql_text(value: str) -> str:
    """Literal SQL de texto con las comillas simples escapadas."""
    return "'" + value.replace("'", "''") + "'"


def cmd_permisos(args) -> None:
    rows = []
    for item in args.permisos:
        m = re.fullmatch(r"([A-Za-z0-9_.-]{1,32}|\*):(ver|control)", item)
        if not m:
            sys.exit(f"Permiso inválido '{item}'. Formato: DISPOSITIVO:ver o DISPOSITIVO:control (DISPOSITIVO puede ser *).")
        device, level = m.groups()
        rows.append(f"  ({sql_text(args.correo)}, {sql_text(device)}, true, {'true' if level == 'control' else 'false'})")
    print("\n== Pega esto en el SQL Editor de Supabase ==")
    print("INSERT INTO device_permissions (email, device_id, can_view, can_control) VALUES")
    print(",\n".join(rows))
    print("ON CONFLICT (email, device_id) DO UPDATE SET can_view = EXCLUDED.can_view, can_control = EXCLUDED.can_control;")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="comando", required=True)
    p = sub.add_parser("permisos", help="permisos por dispositivo para un correo de Gmail")
    p.add_argument("correo")
    p.add_argument("permisos", nargs="+")
    p.set_defaults(fn=cmd_permisos)
    args = parser.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
