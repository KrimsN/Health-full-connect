import pytest

from health_mcp.sql_guard import InvalidQueryError, ensure_read_only


def test_accepts_select():
    query = "select * from v_daily_summary"
    assert ensure_read_only(query) == query


def test_accepts_with_cte():
    query = "with recent as (select 1) select * from recent"
    assert ensure_read_only(query) == query


def test_accepts_trailing_semicolon():
    assert ensure_read_only("select 1;") == "select 1;"


def test_is_case_insensitive():
    assert ensure_read_only("SELECT 1") == "SELECT 1"


@pytest.mark.parametrize(
    "query",
    [
        "",
        "   ",
        "insert into health_records values (1)",
        "update health_records set deleted = true",
        "delete from health_records",
        "drop table health_records",
    ],
)
def test_rejects_non_read_statements(query):
    with pytest.raises(InvalidQueryError):
        ensure_read_only(query)


def test_rejects_multiple_statements():
    with pytest.raises(InvalidQueryError):
        ensure_read_only("select 1; drop table health_records")


def test_rejects_multiple_statements_without_trailing_semicolon():
    with pytest.raises(InvalidQueryError):
        ensure_read_only("select 1; select 2")
