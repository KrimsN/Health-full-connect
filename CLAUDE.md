# CLAUDE.md

Личный проект: сквозная синхронизация данных о здоровье. Публичный на GitHub, для
портфолио, в Play Market не публикуется.

## Поток данных

```
Mi Band 8 Pro / Mi Scale 2 → Gadgetbridge → Health Connect (на телефоне)
    → android/ (Changes API + WorkManager) → Supabase Postgres
    → mcp/ (Docker, streamable-HTTP) → Claude Desktop / Claude Code
```

## Структура

- `db/` — SQL-схема Supabase: `001_schema.sql` (таблицы), `002_views.sql` (типизированные
  представления для чтения), `003_roles_rls.sql` (RLS и роль `health_reader`). Файлы в
  репозитории — шаблоны: `003_roles_rls.sql` содержит плейсхолдер
  `{{HEALTH_READER_PASSWORD}}` вместо реального пароля. `db/generate-migrations.py`
  (без внешних зависимостей, работает и на Windows, и на Linux/macOS) подставляет значение
  из `db/.env` (пример — `db/.env.example`) или переменных окружения
  и кладёт готовые файлы в гитигнорнутый `db/generated/`. Применяются вручную через
  SQL Editor в панели Supabase — раннера, который сам подключается к базе и исполняет
  файлы, в проекте нет.
- `android/` — Kotlin 2.4.10 + Jetpack Compose, один модуль `app`. minSdk 28, compileSdk/
  targetSdk 36, AGP 9.3.0, Gradle 9.5. Собирается CLI (`./gradlew`), Android Studio не
  используется. Версии androidx (`core-ktx`, `lifecycle-*`) намеренно не самые свежие —
  актуальные на момент написания требуют compileSdk 37/AGP 9.1+, что конфликтует с
  зафиксированным здесь compileSdk 36; при апгрейде compileSdk можно поднять и их.
- `mcp/` — Python (`uv`), MCP-сервер на `mcp` SDK, транспорт streamable-HTTP, слушает
  `127.0.0.1:8933` в Docker.
- `docs/` — `ARCHITECTURE.md` (устройство системы), `SETUP.md` (воспроизводимая установка
  с нуля: тулчейн, Supabase, Gadgetbridge, сборка, регистрация MCP).

## Модель доступа

Два независимых секрета, ни один не в репозитории:

- Телефон использует **publishable**-ключ Supabase. RLS даёт роли `anon` `select`,
  `insert`, `update` на `health_records` и `sync_runs`. `select` — вынужденная уступка:
  PostgREST-upsert (`Prefer: resolution=merge-duplicates`) компилируется в
  `INSERT ... ON CONFLICT DO UPDATE`, а этой команде Postgres требует `SELECT` и сверяет
  обновляемые строки с RLS-политикой `SELECT` (это поведение самого Postgres, закрывает
  CVE-2017-15099, не особенность PostgREST) — write-only роль для upsert невозможна в
  принципе. Утёкший из APK ключ может читать данные о здоровье, не только писать; это
  принятый компромисс для личного сайдлоад-приложения, а не недосмотр.
- MCP использует отдельную Postgres-роль `health_reader` (только `select`,
  `statement_timeout`). Строка подключения — в `mcp/.env`, не в репозитории.

Ключ телефона живёт в `android/local.properties` (gitignored) и попадает в код через
`buildConfigField`. Пример — `android/local.properties.example`.

## Схема данных

Одна универсальная таблица `health_records` (`record_type`, `hc_record_id`, время,
`value jsonb`) вместо таблицы на каждый из ~15 типов записей Gadgetbridge — типизированные
`view` в `002_views.sql` дают удобный доступ поверх. Дедупликация — по PK
`(record_type, hc_record_id)` и PostgREST-заголовку `Prefer: resolution=merge-duplicates`,
источник `hc_record_id` — стабильный `metadata.id` из Health Connect Changes API.
Удаления не физические — `deleted = true` в апсерте (телефону не выдан DELETE).

`sync_runs` — журнал прогонов синхронизации, зеркало локальной истории на телефоне.
Локальная копия (DataStore, кольцо на 500 записей) — источник истины для UI: прогон,
упавший на сети, до сервера не доедет по определению, но должен быть виден пользователю.

## Инструменты MCP

`list_metrics`, `daily_summary`, `weight_trend`, `sleep_sessions`, `heart_rate`,
`get_records`, `sync_health`, `run_sql` (read-only escape hatch — безопасен за счёт прав
роли `health_reader`, не за счёт парсинга запроса).

## Конвенции

- Комментарии и docstring'и в коде — на английском.
- Коммиты — Conventional Commits на английском (`feat:`, `fix:`, `chore:`, `docs:`).
- SQL-файлы в `db/` пронумерованы и применяются по порядку, не переименовываются задним
  числом — история применения важнее косметики.
