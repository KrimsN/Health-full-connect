"""Database access for the Supabase-backed health data store.

Uses the dedicated `health_reader` Postgres role (db/003_roles_rls.sql in the
repo root), which has SELECT only, reached through Supabase's Supavisor
connection pooler -- not the PostgREST REST API the Android client uses.
"""

from __future__ import annotations

import os
from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from typing import Any

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

_DATABASE_URL_ENV = "DATABASE_URL"
_STATEMENT_TIMEOUT_MS = 15_000

_pool: ConnectionPool | None = None


def _database_url() -> str:
    url = os.environ.get(_DATABASE_URL_ENV)
    if not url:
        raise RuntimeError(
            f"{_DATABASE_URL_ENV} is not set -- copy mcp/.env.example to mcp/.env "
            "and fill in the health_reader connection string.",
        )
    return url


def _get_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        _pool = ConnectionPool(
            conninfo=_database_url(),
            min_size=1,
            max_size=5,
            kwargs={
                "row_factory": dict_row,
                "options": f"-c statement_timeout={_STATEMENT_TIMEOUT_MS}",
            },
        )
    return _pool


@contextmanager
def _get_cursor() -> Iterator[Any]:
    with _get_pool().connection() as conn, conn.cursor() as cur:
        yield cur


def run_query(
    sql: str,
    params: Sequence[Any] | Mapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """Executes a single read-only query and returns all rows as dicts.

    Read-only isn't enforced here -- it's enforced by health_reader having no
    write grants on any table (see db/003_roles_rls.sql). A stray write
    statement fails at the database, not in this function.
    """
    with _get_cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()
