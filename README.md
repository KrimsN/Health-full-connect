# Health Full Connect

[![Android](https://github.com/KrimsN/Health-full-connect/actions/workflows/android.yml/badge.svg)](https://github.com/KrimsN/Health-full-connect/actions/workflows/android.yml)
[![MCP Server](https://github.com/KrimsN/Health-full-connect/actions/workflows/mcp.yml/badge.svg)](https://github.com/KrimsN/Health-full-connect/actions/workflows/mcp.yml)

Личный проект: сквозная синхронизация данных о здоровье с фитнес-браслета и
умных весов в одно облачное хранилище, с доступом из Claude через MCP.

```
Mi Band 8 Pro ─┐
               ├─BLE→ Gadgetbridge ──→ Health Connect ──→ [Android-приложение] ──HTTPS──→ Supabase Postgres
Mi Scale 2 ────┘      (Sync after         (на телефоне)     Changes API +                      │
                       device sync)                          WorkManager                        │ SQL (read-only)
                                                                                                ▼
                                                    Claude Desktop ←──HTTP MCP──← [Docker: health-mcp]
                                                     Claude Code       127.0.0.1:8933
```

Gadgetbridge собирает данные с браслета Xiaomi Mi Band 8 Pro и весов Mi Body
Composition Scale 2 и синхронизирует их в Android Health Connect. Приложение
[`android/`](android/) читает эти данные через Changes API, инкрементально
и в фоне (WorkManager), и отправляет их в собственный проект Supabase.
MCP-сервер [`mcp/`](mcp/) в Docker даёт Claude Desktop и Claude Code доступ к
этим данным только на чтение — через выделенную роль Postgres без прав на
запись.

Проект личный: APK ставится сайдлоадом, в Play Market не публикуется.

## Компоненты

| Каталог | Что это |
| --- | --- |
| [`db/`](db/) | SQL-схема Supabase: таблицы, типизированные view, роли и RLS |
| [`android/`](android/) | Kotlin + Jetpack Compose, читает Health Connect и льёт данные в Supabase |
| [`mcp/`](mcp/) | Python MCP-сервер поверх Supabase Postgres, транспорт HTTP в Docker |
| [`docs/`](docs/) | [ARCHITECTURE.md](docs/ARCHITECTURE.md) — устройство системы; [SETUP.md](docs/SETUP.md) — установка с нуля |

## Быстрый старт

Подробности — в [docs/SETUP.md](docs/SETUP.md). Коротко:

1. Применить `db/*.sql` в SQL Editor свежего проекта Supabase.
2. Собрать и установить APK: `cd android && ./gradlew assembleDebug`, затем
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. В Gadgetbridge включить **Settings → External Integrations → Health
   Connect → Sync after device sync**.
4. Поднять MCP-сервер: `cd mcp && docker compose up -d --build`.
5. Зарегистрировать в Claude Code:
   ```bash
   claude mcp add --transport http --scope user health http://127.0.0.1:8933/mcp
   ```

## Лицензия

Личный проект для портфолио, без отдельной лицензии на использование третьими
лицами.
