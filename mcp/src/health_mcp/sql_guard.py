"""Input validation for the `run_sql` escape-hatch tool.

This is deliberately not the security boundary -- that's the `health_reader`
Postgres role having SELECT only (db/003_roles_rls.sql), which makes a stray
write statement fail at the database regardless of what gets past this check.
What's here is a usability guard: reject obviously-wrong input (an empty
query, something that isn't a read, multiple statements) with a clear message
instead of a confusing database error.
"""

from __future__ import annotations

_READ_ONLY_PREFIXES = ("select", "with")


class InvalidQueryError(ValueError):
    pass


def ensure_read_only(query: str) -> str:
    """Returns `query` unchanged if it looks like a single read statement,
    otherwise raises InvalidQueryError."""
    stripped = query.strip()
    if not stripped:
        raise InvalidQueryError("Query is empty")

    lowered = stripped.lower()
    if not lowered.startswith(_READ_ONLY_PREFIXES):
        raise InvalidQueryError("run_sql only accepts SELECT or WITH queries")

    # A single trailing semicolon is fine; anything after it (or a semicolon
    # in the middle) means more than one statement.
    body = stripped[:-1] if stripped.endswith(";") else stripped
    if ";" in body:
        raise InvalidQueryError("run_sql accepts a single statement")

    return stripped
