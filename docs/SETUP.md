# Установка с нуля

Инструкция воспроизводима на чистой машине: Windows с PowerShell,
Android-телефон с включённой отладкой по USB, аккаунт Supabase.

## 1. Тулчейн

Нужны JDK 21+ и Android SDK командной строки (Android Studio не нужна).

```bash
winget install --id EclipseAdoptium.Temurin.21.JDK -e
```

Скачать `commandlinetools-win-*.zip` со
[страницы Android SDK](https://developer.android.com/studio#command-tools),
распаковать так, чтобы получился путь `<sdk>\cmdline-tools\latest\bin\sdkmanager.bat`
(архив изначально распаковывается в подпапку `cmdline-tools`, её нужно
переименовать в `latest`). Затем:

```bash
sdkmanager --sdk_root=<sdk> "platform-tools" "platforms;android-36" "build-tools;36.0.0"
sdkmanager --sdk_root=<sdk> --licenses
```

Проверка: `java -version` → 21, `sdkmanager --sdk_root=<sdk> --list_installed`
показывает `platforms;android-36`.

## 2. Supabase

1. Создать проект на [supabase.com](https://supabase.com) (free tier).
2. В SQL Editor выполнить по очереди `db/001_schema.sql`, `db/002_views.sql`,
   `db/003_roles_rls.sql` — именно в этом порядке, `003` ссылается на
   таблицы и view из первых двух файлов.
3. В `003_roles_rls.sql` перед запуском заменить
   `REPLACE_WITH_STRONG_PASSWORD` на сгенерированный пароль для роли
   `health_reader`. Пароль нигде не коммитится — он понадобится только на
   шаге 5 (MCP), в `mcp/.env`.
4. Найти в **Project Settings → API**:
   - `Project URL` (вида `https://xxxx.supabase.co`),
   - `publishable key` (новый формат ключей, `sb_publishable_...`).
5. Взять хост пулера через кнопку **Connect** (вверху дашборда) → вкладка
   **Direct connection string** → режим **Session pooler** (не *Transaction*
   — нужен именно session, он не сбрасывает `SET` внутри сессии, которым
   MCP выставляет `statement_timeout`). Скопировать хост оттуда буквально —
   вида `aws-N-<region>.pooler.supabase.com`, где `N` — номер шарда,
   индивидуальный для проекта (не обязательно `0`). Неверный номер шарда
   даёт ошибку Supavisor `tenant/user ... not found`, которая выглядит как
   проблема с ролью или паролем, а на деле — просто не тот хост.
   Итоговая строка для `mcp/.env`:
   `postgresql://health_reader.<project-ref>:<пароль>@<хост из Connect>:5432/postgres`.

Проверка (замените плейсхолдеры):

```bash
curl -X POST 'https://<PROJECT_REF>.supabase.co/rest/v1/health_records?on_conflict=record_type,hc_record_id' \
  -H "apikey: <PUBLISHABLE_KEY>" \
  -H "Content-Type: application/json" \
  -H "Prefer: resolution=merge-duplicates,return=minimal" \
  -d '[{"record_type":"Test","hc_record_id":"probe-1","start_time":"2026-01-01T00:00:00Z","value":{}}]'
```

Ожидается `201`/`204`. Тот же ключ на `GET .../health_records` тоже отработает —
`anon` умышленно получает `select` (см. врезку в `db/003_roles_rls.sql`: без него
не работает upsert, которым пишет телефон). Удалить тестовую строку не обязательно —
`record_type = 'Test'` не пересекается ни с одним view, но если мешает —
`delete from health_records where record_type = 'Test';` в SQL Editor.

## 3. Gadgetbridge

**Settings → External Integrations → Health Connect**:
1. Включить синк, выдать разрешения на нужные типы данных при запросе.
2. Включить **Sync after device sync** — без этого Gadgetbridge пишет в
   Health Connect только по кнопке, и вся цепочка отстаёт на ручной шаг.

Health Connect на Android 14+ встроен в систему; на Android 13 и ниже нужно
поставить приложение «Health Connect» из Play Store отдельно.

## 4. Android-приложение

```bash
cd android
cp local.properties.example local.properties
```

В `local.properties` вписать `sdk.dir` (путь к Android SDK из шага 1),
`supabase.url` и `supabase.publishableKey` из шага 2. Значения попадают в
код через `BuildConfig`, сам файл — в `.gitignore`.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

При первом запуске:
1. Кнопка **«Grant permissions»** открывает системный экран Health Connect —
   выдать доступ на все запрошенные типы.
2. Разрешение **«Allow all the time»** для фонового чтения система спросит
   отдельно при первом фоновом запуске WorkManager; без него периодический
   синк будет читать пусто.
3. На Xiaomi/HyperOS и похожих прошивках агрессивный контроль батареи может
   не давать WorkManager просыпаться по расписанию. Снять ограничения:
   **Настройки → Приложения → Health Sync → Батарея → Без ограничений**.

Кнопка **«Sync now»** запускает синк немедленно, не дожидаясь фонового
расписания (раз в 6 часов) — полезно сразу после того, как Gadgetbridge
только что синхронизировал браслет.

## 5. MCP-сервер

```bash
cd mcp
cp .env.example .env
```

В `.env` вписать `DATABASE_URL` — session-pooler строку из шага 2 с именем
пользователя `health_reader.<project-ref>` и паролем роли `health_reader`.

```bash
docker compose up -d --build
```

Проверка:

```bash
curl -X POST http://127.0.0.1:8933/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Должен вернуться список инструментов (`list_metrics`, `daily_summary`,
`weight_trend`, `sleep_sessions`, `heart_rate`, `get_records`, `sync_health`,
`run_sql`).

Регистрация в Claude Code:

```bash
claude mcp add --transport http --scope user health http://127.0.0.1:8933/mcp
```

Для Claude Desktop — добавить как custom connector с тем же URL через
настройки приложения.

## Диагностика

- **Ручной синк не даёт новых данных.** Сначала проверьте, что данные вообще
  свежие в Gadgetbridge — приложение читает только то, что уже лежит в
  Health Connect, само с браслетом не общается.
- **Ошибка авторизации при апсерте с телефона.** Обычно неверный
  `publishableKey` в `local.properties` или не применён `003_roles_rls.sql`.
- **MCP не подключается к БД, ошибка `tenant/user ... not found`.** Это не про
  права роли — Supavisor не нашёл указанный pooler-хост для этого проекта.
  Проверьте: (1) в строке подключения формат `health_reader.<project-ref>`,
  а не просто `health_reader`; (2) хост скопирован буквально из **Connect →
  Direct connection string → Session pooler** в дашборде, а не собран вручную
  по шаблону — номер шарда (`aws-N-...`) индивидуален для проекта.
- **Фоновый синк не срабатывает.** `adb shell cmd jobscheduler run -f
  dev.krimsn.healthconnect <jobId>` форсирует ближайшую задачу WorkManager;
  `adb logcat -s HealthSync` покажет, что происходит.
