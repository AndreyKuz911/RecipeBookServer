# RecipeBookServer

Ktor backend для курсового проекта "Книга рецептов". Сервер предоставляет REST API для регистрации, JWT-аутентификации, профилей, рецептов, рейтингов, комментариев, избранного и ленты подписок.

## Стек

- Kotlin
- Ktor Server
- PostgreSQL / Neon
- Exposed ORM
- HikariCP
- JWT
- bcrypt
- kotlinx.serialization
- JUnit + H2 для тестов

## Возможности

- регистрация и вход по email/password;
- хранение паролей только в `bcrypt`-хэше;
- JWT access token;
- CRUD для рецептов;
- поиск, фильтрация и сортировка рецептов;
- лайки/дизлайки;
- комментарии с одним уровнем вложенности;
- избранное;
- подписки и персональная лента;
- seed-данные при старте.

## Настройка Neon PostgreSQL

1. Создайте проект в Neon.
2. Откройте `Connect`.
3. Отключите `Connection pooling`.
4. Скопируйте `direct connection string`.
5. Создайте файл `.env` рядом с `build.gradle.kts`.
6. Заполните переменные по образцу из `.env.example`.

Пример:

```env
DATABASE_URL=jdbc:postgresql://USER:PASSWORD@HOST/DB_NAME?sslmode=require
JWT_SECRET=super_secret_key
JWT_ISSUER=recipebook-server
JWT_AUDIENCE=recipebook-client
JWT_REALM=RecipeBook API
SEED_ON_START=true
```

## Запуск

```bash
./gradlew run
```

Сервер стартует на `http://localhost:8080`.

Проверка тестов:

```bash
./gradlew test
```

## Тестовые пользователи

Если `SEED_ON_START=true`, автоматически создаются:

- `alice@example.com` / `password123`
- `bob@example.com` / `password123`

## Основные endpoints

### Auth

- `POST /auth/register`
- `POST /auth/login`

### Users

- `GET /users/me`
- `PUT /users/me`
- `GET /users/{id}`
- `GET /users/{id}/recipes`
- `POST /users/{id}/follow`
- `DELETE /users/{id}/follow`

### Recipes

- `GET /recipes`
- `GET /recipes/{id}`
- `POST /recipes`
- `PUT /recipes/{id}`
- `DELETE /recipes/{id}`
- `GET /feed`

### Ratings

- `POST /recipes/{id}/rating`
- `DELETE /recipes/{id}/rating`

### Comments

- `GET /recipes/{id}/comments`
- `POST /recipes/{id}/comments`
- `DELETE /comments/{id}`

### Favorites

- `GET /favorites`
- `POST /recipes/{id}/favorite`
- `DELETE /recipes/{id}/favorite`

## Структура

```text
src/main/kotlin/com/recipebook/server/
  Application.kt
  config/
  database/
  features/
    auth/
    comments/
    common/
    recipes/
    users/
  plugins/
  routing/
  security/
```

## Что нужно подставить перед реальным запуском

- ваш `DATABASE_URL` из Neon;
- свой `JWT_SECRET`.
