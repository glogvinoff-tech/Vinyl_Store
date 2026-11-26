# SPIN / Vinyl Store — КР №1–3

Дополнение: [нейросеть рекомендаций на Python — запуск и устройство](docs/kr3/RECOMMENDATIONS.md).

КР №3: Spring Boot backend, PostgreSQL/Flyway, React-фронтенд версии 2.1 по макету Figma, роли/JWT, вход и регистрация по коду на Gmail.

```bash
python3 scripts/init-env.py
docker compose --env-file .env -f compose.kr3.yml up -d --build --wait
```

Магазин: http://localhost:8080/ · Управление: http://localhost:8080/admin.html · Локальные письма: http://localhost:8025/

[Инструкция КР №3](docs/kr3/README.md) · [План](docs/kr3/PLAN.md) · [Матрица требований](docs/kr3/REQUIREMENTS.md) · [Сценарий защиты](docs/kr3/DEFENSE.md) · [Проверки](docs/kr3/VERIFICATION.md)

Ниже сохранена инструкция для консольного и настольного приложений КР №1–2.

---

# Vinyl Store Console

Консольная информационная система для учета заказов магазина виниловых пластинок.


- Maven-проект на Java 21.
- Подключение к PostgreSQL через JDBC.
- Слои `model`, `repository`, `service`, `db`.
- Интерфейс `CrudRepository`.
- CRUD для пользователей.
- CRUD для заказов.
- Поиск заказов по пластинке, исполнителю, общему тексту и ID.
- Фильтрация заказов по статусу и пользователю.
- Сортировка заказов по сумме и дате.
- Статистика больше чем по пяти показателям.
- Свои исключения для бизнес-ошибок и ненайденных сущностей.
- Экспорт заказов, пластинок и пользователей в `exports/orders.xlsx`.
- Docker Compose для запуска PostgreSQL.
- JavaFX-клиент для КР №2 с прямым JDBC-подключением к PostgreSQL.

```bash
docker compose up -d
```

В контейнере автоматически создается база `vinyl_store`, пользователь `postgres`, пароль `1234`, а SQL-скрипт `src/main/resources/database.sql` применяется при первом запуске контейнера.

## Запуск базы через Docker

```bash
docker compose up -d
```

Остановить базу:

```bash
docker compose down
```

Полностью удалить данные и создать базу заново:

```bash
docker compose down -v
docker compose up -d
```

## Запуск программы

```bash
mvn compile exec:java
```

Или запустите класс:

```text
ru.mirea.project.Main
```

## Запуск JavaFX-клиента

Перед запуском поднимите PostgreSQL:

```bash
docker compose up -d
```

Затем запустите настольное приложение:

```bash
mvn javafx:run
```

Главный класс JavaFX-приложения:

```text
ru.mirea.project.fx.VinylStoreFxApplication
```

В интерфейсе доступны обзор статистики, таблицы пользователей, заказов и каталога пластинок, CRUD-операции, поиск заказов, фильтры, сортировка и экспорт данных в Excel. При создании заказа пластинка выбирается из каталога, а сумма рассчитывается по цене и количеству. Телефоны пользователей проверяются и сохраняются в формате `+7XXXXXXXXXX`.
Ключевой интерфейс вынесен в FXML: `src/main/resources/ru/mirea/project/fx/main-view.fxml`.
Обработчики JavaFX находятся в `src/main/java/ru/mirea/project/fx/MainController.java`.

## Подключение

По умолчанию приложение подключается к:

```text
jdbc:postgresql://localhost:5432/vinyl_store
user: postgres
password: 1234
```

Эти параметры можно заменить переменными окружения:

```text
VINYL_DB_URL
VINYL_DB_USER
VINYL_DB_PASSWORD
```

Также параметры лежат в `src/main/resources/database.properties`:

```text
db.url=jdbc:postgresql://localhost:5432/vinyl_store
db.user=postgres
db.password=1234
```

Переменные окружения имеют приоритет над файлом настроек.

ER-описание находится в `docs/er-diagram.md`.
