# Архитектура

## Поток данных

```
Mi Band 8 Pro ─┐
               ├─BLE→ Gadgetbridge ──→ Health Connect ──→ [Android-приложение] ──HTTPS──→ Supabase Postgres
Mi Scale 2 ────┘      (Sync after         (на телефоне)     Changes API +                      │
                       device sync)                          WorkManager                        │ SQL (read-only)
                                                                                                ▼
                                                    Claude Desktop ←──HTTP MCP──← [Docker: health-mcp]
                                                     Claude Code       127.0.0.1:8933
```

1. **Gadgetbridge** по BLE читает данные с Mi Band 8 Pro (шаги, пульс, сон,
   SpO₂, HRV) и с весов Mi Body Composition Scale 2 (вес, состав тела). При
   включённой опции **Sync after device sync** пишет их в Health Connect
   сразу после каждой синхронизации браслета — без этой опции запись идёт
   только по кнопке, и вся цепочка отстаёт на ручной шаг.
2. **Health Connect** — локальное хранилище здоровья на телефоне,
   принадлежит Android, а не этому приложению.
3. **Android-приложение** (`android/`) читает Health Connect через
   `androidx.health.connect:connect-client` двумя способами:
   - инкрементально, через Changes API (`getChangesToken`/`getChanges`) — так
     работает обычный синк, в фоне и по кнопке «Синхронизировать сейчас»;
   - по диапазону дат (`readRecords`) — для бэкфилла и как автоматический
     откат, если токен истёк (Health Connect держит токен валидным примерно
     30 дней бездействия).
   Результат льётся в Supabase одним HTTP-запросом на партию через
   PostgREST-эндпоинт с `Prefer: resolution=merge-duplicates` — это и даёт
   идемпотентность: повторная отправка той же записи не создаёт дубль.
4. **Supabase Postgres** хранит всё в одной универсальной таблице
   `health_records` (см. [схему](#схема-данных) ниже) плюс журнал прогонов
   `sync_runs`.
5. **MCP-сервер** (`mcp/`) в Docker слушает `127.0.0.1:8933`, ходит в тот же
   Postgres отдельной read-only ролью и отдаёт данные Claude через
   инструменты вроде `daily_summary`, `weight_trend`, `sleep_sessions`.

## Модель доступа

Два независимых секрета, ни один не в репозитории:

| Кто | Ключ | Права |
| --- | --- | --- |
| Телефон | Supabase publishable key | `insert`, `update` на `health_records`/`sync_runs`; `select` отозван |
| MCP-сервер | Postgres-роль `health_reader` | только `select`, включая типизированные view |

Утёкший из APK publishable-ключ позволяет засорить таблицу, но не прочитать
данные о здоровье — это гарантирует RLS в `db/003_roles_rls.sql`, а не
секретность ключа. `run_sql` в MCP не парсит запрос на предмет опасных
операций: безопасность целиком на стороне грантов роли `health_reader`.

## Схема данных

Одна обобщённая таблица вместо таблицы на каждый из ~15 типов записей,
которые пишет Gadgetbridge:

```sql
health_records (
  record_type, hc_record_id,   -- составной PK, дедупликация по нему
  start_time, end_time, zone_offset,
  source_app, device,
  value jsonb,                 -- форма зависит от record_type
  deleted,                     -- soft delete: телефону не выдан DELETE
  local_date                   -- generated stored, локальный день телефона
)
```

Типизированные `view` (`db/002_views.sql`) дают удобный доступ поверх:
`v_weight`, `v_body_composition` (пивот нескольких записей весов с одного
измерения в одну строку), `v_steps_daily`, `v_sleep_sessions`,
`v_heart_rate` (разворачивает список сэмплов из `HeartRateRecord` в строки),
`v_resting_hr`, `v_daily_summary`.

Вторая таблица, `sync_runs`, — зеркало локальной истории синков с телефона.
Локальная копия (DataStore, кольцо на 500 записей) — источник истины для
экрана истории в приложении: прогон, упавший на сети, до Supabase не
доедет по определению, а увидеть его нужно. Серверная копия существует для
инструмента `sync_health` в MCP — «когда последний раз приезжали данные».

## Почему такой стек

- **Kotlin + Compose, без Android Studio** — сборка и установка целиком из
  CLI (`./gradlew`, `adb`), чтобы не тянуть тяжёлую IDE ради CLI-разработки.
- **Supabase вместо Google Sheets** — идемпотентный upsert одним заголовком
  PostgREST, и MCP получает настоящий SQL с агрегатами вместо построчного
  файла таблицы, который на годовом объёме сэмплов пульса (~500 тыс. строк)
  стал бы неудобным для чтения и медленным для листа.
- **WorkManager, а не foreground-сервис** — штатный способ фонового синка,
  переживает ребут; ручной запуск идёт той же цепочкой (`OneTimeWorkRequest`
  с `ExistingWorkPolicy.KEEP`), так что повторное нажатие «Синхронизировать»
  не плодит параллельных прогонов.
- **MCP на streamable-HTTP в Docker** — тот же паттерн личной MCP-сборки,
  что уже использовался в соседнем проекте `Yazio_integration`: один
  постоянно работающий контейнер на `127.0.0.1`, доступный и Claude Desktop,
  и Claude Code.
