FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir uv

# Dependencies first, project code second -- this layer only rebuilds when
# pyproject.toml/uv.lock change, not on every source edit.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8933

CMD ["python", "-m", "health_mcp.server"]
