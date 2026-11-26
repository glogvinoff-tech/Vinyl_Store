# Матрица требований КР №3

Актуальная сверка от 9 ноября: [AUDIT.md](AUDIT.md). Все разделы представлены; найденные дефекты CSV/аудита исправлены и покрыты регрессионными тестами. Краткий список требований, реализации и шагов защиты — в [SUBMISSION_CHECKLIST.md](SUBMISSION_CHECKLIST.md).

Пути ниже относительно `backend/src/main/java/ru/mirea/vinylstore/`, SQL — `backend/src/main/resources/db/migration/`.

| Критерий | Реализация | Демонстрация |
|---|---|---|
| Предметная область КР №1 | Заказы магазина винила, справочник пластинок; V0 и V5 сохраняют и переносят исходные таблицы | SQL `users`, `vinyl`, `orders`, `purchase_order`, `vinyl_record` |
| Слои backend | controller, service, repository, domain, dto, security, filter, error, config; HTTP утилиты в web | Структура пакетов |
| PostgreSQL / Flyway / validate | V0–V5, индексы, FK, CHECK, `ddl-auto=validate` | Логи старта, `flyway_schema_history` |
| Docker | `compose.kr3.yml`, `backend/Dockerfile`, healthcheck HTTP backend и PostgreSQL, named volume, bind localhost | `docker compose ... ps` |
| Вход по паролю / BCrypt | AuthService, PasswordPolicy, LoginAttemptService | Панель: вход, смена временного пароля |
| 3 уровня доступа | VIEWER, MANAGER, ADMIN; PreAuthorize и проверка владельца для VIEWER | 403 на редактирование для VIEWER |
| Access JWT / refresh | JwtService, RefreshSession, ротация, отзыв, logout, me | Swagger / интеграционный тест |
| Смена и сброс пароля | AuthController, UserAdminService, PasswordChangeRequiredFilter | Обязательная смена при первом входе |
| Защита перебора | 5 неудачных паролей → блокировка, лимиты OTP | LoginAttemptService / EmailAuthService |
| Gmail OTP | EmailChallenge, Gmail API с OAuth2, `/auth/email/request`, `/auth/email/verify`, V2 | Код приходит в настоящий Gmail после OAuth-настройки |
| Основная сущность | StoreOrder: UUID, externalId, albumTitle, artist, customerName, email, phone, orderedOn, record, status, quantity, totalPrice, paid, tags, priorityLevel, timestamps, version, archived | Карточка заказа |
| Вычисляемый показатель | PriorityCalculator: оплаченный ≥10000 → CRITICAL; оплаченный → HIGH; неоплаченный ≥5000 → MEDIUM; прочие LOW; завершённый/отменённый NOT_APPLICABLE | Unit-тест и карточка |
| CRUD / version / архив | StoreOrderController/Service, `@Version`, ошибки 409, восстановление | Создать, изменить в двух вкладках, архивировать, восстановить |
| Окончательное удаление | ADMIN + флаг + подтверждающий заголовок | Кнопка в архивном заказе ADMIN |
| Поиск и фильтры | SmartFilterParser → StoreOrderFilter → Criteria API Specification | Свободный текст, пластинка, статус, оплата, приоритет, тег, архив, сумма с > >= = <= < |
| Сортировка / страницы | PageableFactory whitelist, size≤100, PageResponse | Селект сортировки и стрелки страниц |
| Справочник | RecordController/Service: список, создание, изменение, active; запись ADMIN | Раздел «Пластинки» |
| Заметки | OrderNote, автор, время, pinned, version; изменение автором или ADMIN | Добавить, закрепить, изменить, удалить |
| Массовые операции | Набор UUID≤500, архив, восстановление, статус, добавить/удалить тег | Выделить строки, «Массовая операция» |
| CSV | CsvStoreOrderService: preview/apply, CREATE_ONLY/UPSERT_BY_EXTERNAL_ID, row issues, фильтрованный экспорт | `examples/orders.csv` |
| Аналитика | Total / active / archived / paidCount / averageTotalPrice / byRecord / byStatus / byPriority | Раздел «Аналитика» |
| Пользователи | AdminUserController/Service: страницы, создание, изменение email/имени/ролей/enabled, сброс временного пароля | Раздел «Пользователи» |
| Аудит | AuditEvent/Service, actor/event/entity/id/time/summary, поиск, сортировка, страницы, только ADMIN | Раздел «Аудит» |
| Correlation ID | CorrelationIdFilter, RequestMetadata, X-Correlation-ID, в ошибках и аудите | Network / Swagger |
| Валидация / DTO | Bean Validation, контроллеры возвращают DTO, GlobalExceptionHandler / ApiError | 400/401/403/404/409, без stack trace |
| Swagger / health | OpenApiConfig, Actuator | ADMIN /swagger-ui.html, публичный /actuator/health |
| Тесты | ContextSmokeTest, PriorityCalculatorTest, SmartFilterParserTest, PasswordPolicyTest, AuthAndOrdersIntegrationTest | `mvn -s backend/maven-settings.xml -f backend/pom.xml test` |
| Веб-клиент | React 19 / JavaScript / CSS, интерфейс VINYL_STORE-version_2.1 по четырём фреймам Figma, API, регистрация, корзина, кабинет, служебная панель | `/` и `/admin.html` |

## Обязательные REST-маршруты

| Метод | Путь |
|---|---|
| POST | /api/auth/login, /refresh, /logout, /change-password, /change-initial-password |
| GET | /api/auth/me |
| POST | /api/auth/email/request, /api/auth/email/verify |
| GET / POST | /api/orders |
| GET / PUT / DELETE | /api/orders/{id} |
| POST | /api/orders/{id}/restore |
| DELETE | /api/orders/{id}/permanent |
| POST | /api/orders/bulk, /api/orders/import/preview, /api/orders/import/apply |
| GET | /api/orders/export |
| GET / POST | /api/orders/{id}/notes |
| PUT / DELETE | /api/orders/{id}/notes/{noteId} |
| GET / POST | /api/records |
| PUT | /api/records/{id} |
| PATCH | /api/records/{id}/active?active=...&version=... |
| GET | /api/analytics/summary |
| GET / POST | /api/admin/users |
| PUT | /api/admin/users/{id} |
| POST | /api/admin/users/{id}/reset-password |
| GET | /api/admin/audit |
| GET | /api/catalog, /api/catalog/{id}, /api/shop/orders |
| POST | /api/shop/checkout |

В первой строке `/refresh` и остальные сокращённые пути имеют общий префикс `/api/auth`. Точные схемы DTO доступны в OpenAPI.
