#!/usr/bin/env python3
"""Render db/*.sql templates into db/generated/, substituting {{VAR}}
placeholders with values from db/.env or the environment.

db/003_roles_rls.sql carries a {{HEALTH_READER_PASSWORD}} placeholder
instead of a literal password so the committed SQL never holds a secret.
This resolves that placeholder (environment variable wins over db/.env)
and writes a ready-to-run copy of every db/*.sql file into db/generated/,
in the same numeric order -- apply those files in the Supabase SQL Editor,
not the source templates in db/.

db/generated/ is gitignored; nothing it writes gets committed. Stdlib only,
runs the same way under any Python 3 on Windows, Linux, or macOS.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

REQUIRED_VARS = ["HEALTH_READER_PASSWORD"]


def parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


def resolve_vars(db_dir: Path) -> dict[str, str]:
    file_values = parse_env_file(db_dir / ".env")
    resolved: dict[str, str] = {}
    missing: list[str] = []
    for name in REQUIRED_VARS:
        value = os.environ.get(name) or file_values.get(name)
        if value:
            resolved[name] = value
        else:
            missing.append(name)
    if missing:
        sys.exit(
            f"Missing required variable(s): {', '.join(missing)}. "
            "Set them in db/.env (copy db/.env.example) or as environment variables."
        )
    return resolved


def validate(resolved: dict[str, str]) -> None:
    password = resolved["HEALTH_READER_PASSWORD"]
    if len(password) < 16:
        sys.exit(
            "HEALTH_READER_PASSWORD is shorter than 16 characters -- "
            "generate a stronger one, e.g.: openssl rand -base64 24"
        )
    if "'" in password:
        sys.exit(
            "HEALTH_READER_PASSWORD contains a single quote, which would break "
            "out of the SQL string literal in the rendered CREATE ROLE statement. "
            "Regenerate without one."
        )


def main() -> None:
    db_dir = Path(__file__).resolve().parent
    out_dir = db_dir / "generated"
    resolved = resolve_vars(db_dir)
    validate(resolved)

    out_dir.mkdir(exist_ok=True)

    for sql_file in sorted(db_dir.glob("*.sql")):
        content = sql_file.read_text(encoding="utf-8")
        for name, value in resolved.items():
            content = content.replace("{{" + name + "}}", value)
        out_path = out_dir / sql_file.name
        out_path.write_text(content, encoding="utf-8")
        print(f"Wrote {sql_file.name} -> db/generated/{sql_file.name}")

    print(
        "\nApply the files in db/generated/ via the Supabase SQL Editor, in "
        "filename order. That directory is gitignored -- nothing there gets committed."
    )


if __name__ == "__main__":
    main()
