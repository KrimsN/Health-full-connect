"""MCP server exposing Supabase-synced Health Connect data to Claude.

Read-only by construction: the `health_reader` Postgres role behind
DATABASE_URL has SELECT only (see db/003_roles_rls.sql in the repo root), so
nothing this server does can touch health_records or sync_runs -- that's a
database grant, not a parsing trick in this code.
"""

from __future__ import annotations

import os
from typing import Any

from mcp.server.mcpserver import MCPServer

from health_mcp import db
from health_mcp.sql_guard import ensure_read_only

mcp = MCPServer("health")


@mcp.tool()
def list_metrics() -> list[dict[str, Any]]:
    """List every Health Connect record type synced so far, with its date range
    and row count."""
    return db.run_query(
        """
        select record_type,
               count(*) as record_count,
               min(start_time) as earliest,
               max(start_time) as latest
        from health_records
        where not deleted
        group by record_type
        order by record_type
        """,
    )


@mcp.tool()
def daily_summary(start_date: str, end_date: str) -> list[dict[str, Any]]:
    """Daily rollup: steps, total sleep minutes, average heart rate, resting
    heart rate, and weight. start_date/end_date are ISO dates (YYYY-MM-DD),
    inclusive."""
    return db.run_query(
        """
        select * from v_daily_summary
        where local_date between %(start)s and %(end)s
        order by local_date desc
        """,
        {"start": start_date, "end": end_date},
    )


@mcp.tool()
def weight_trend(start_date: str, end_date: str) -> list[dict[str, Any]]:
    """Body composition readings from the smart scale: weight, body fat %,
    lean body mass, bone mass, and basal metabolic rate."""
    return db.run_query(
        """
        select * from v_body_composition
        where local_date between %(start)s and %(end)s
        order by start_time desc
        """,
        {"start": start_date, "end": end_date},
    )


@mcp.tool()
def sleep_sessions(start_date: str, end_date: str) -> list[dict[str, Any]]:
    """Sleep sessions with total duration and stage breakdown (awake/light/
    deep/rem)."""
    return db.run_query(
        """
        select * from v_sleep_sessions
        where local_date between %(start)s and %(end)s
        order by start_time desc
        """,
        {"start": start_date, "end": end_date},
    )


@mcp.tool()
def heart_rate(start_date: str, end_date: str, bucket: str = "hour") -> list[dict[str, Any]]:
    """Heart rate samples aggregated into buckets, with average/min/max bpm
    per bucket. bucket must be 'hour' or 'day'."""
    if bucket not in ("hour", "day"):
        raise ValueError("bucket must be 'hour' or 'day'")
    return db.run_query(
        """
        select date_trunc(%(bucket)s, sample_time) as bucket_start,
               avg(bpm)::numeric(5, 1) as avg_bpm,
               min(bpm) as min_bpm,
               max(bpm) as max_bpm,
               count(*) as sample_count
        from v_heart_rate
        where local_date between %(start)s and %(end)s
        group by bucket_start
        order by bucket_start desc
        """,
        {"bucket": bucket, "start": start_date, "end": end_date},
    )


@mcp.tool()
def get_records(
    record_type: str,
    start_date: str,
    end_date: str,
    limit: int = 200,
) -> list[dict[str, Any]]:
    """Raw Health Connect records of one type in a date range (capped at 1000
    rows). Use list_metrics() first to see which record_type values exist."""
    capped_limit = min(limit, 1000)
    return db.run_query(
        """
        select hc_record_id, start_time, end_time, source_app, device, value
        from health_records
        where record_type = %(record_type)s
          and not deleted
          and local_date between %(start)s and %(end)s
        order by start_time desc
        limit %(limit)s
        """,
        {
            "record_type": record_type,
            "start": start_date,
            "end": end_date,
            "limit": capped_limit,
        },
    )


@mcp.tool()
def sync_health(days: int = 14) -> list[dict[str, Any]]:
    """Recent sync run history from the Android client: when data last
    arrived, run outcomes, and any errors -- use this to answer "when did my
    health data last sync" or to spot coverage gaps."""
    return db.run_query(
        """
        select run_id, started_at, finished_at, trigger, outcome,
               records_read, records_sent, per_type, error_message, duration_ms
        from sync_runs
        where started_at >= now() - (%(days)s || ' days')::interval
        order by started_at desc
        """,
        {"days": days},
    )


@mcp.tool()
def run_sql(query: str) -> list[dict[str, Any]]:
    """Run a read-only SQL query against the health database for analysis the
    other tools don't cover. Only SELECT/WITH, single statement -- safety
    comes from the health_reader role having no write grants, not from this
    check (see health_mcp/sql_guard.py)."""
    validated = ensure_read_only(query)
    return db.run_query(validated)


def main() -> None:
    host = os.environ.get("MCP_HOST", "0.0.0.0")
    port = int(os.environ.get("MCP_PORT", "8933"))
    mcp.run(transport="streamable-http", host=host, port=port)


if __name__ == "__main__":
    main()
