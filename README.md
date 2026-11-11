# Secret Santa Telegram Bot

A Java Telegram bot project for a private, single-group Secret Santa exchange.
Feature requirements and current implementation status are tracked in the
[spec](docs/SPEC-secret-santa-telegram-bot.md) and
[task list](tasks/todo.md).

## Requirements

- JDK 27
- Maven 3.9 or later

## Configuration

Provide these values through the process environment or a trusted secret manager:

- `TELEGRAM_BOT_TOKEN` — token issued by BotFather
- `TELEGRAM_BOT_USERNAME` — bot username
- `SECRET_SANTA_SETUP_CODE` — one-time organizer claim code
- `SECRET_SANTA_DATA_KEY` — stable Base64-encoded 256-bit key for pending draws

The data key must remain the same while a draw is pending. Store it securely
outside the repository and keep a backup; losing it makes an undelivered draw
unrecoverable. Do not commit credentials, keys, or environment files.

Telegram bot chats are not end-to-end encrypted between participants and the bot.
Telegram and the machine or service running the bot can access messages processed
by the bot.

## Commands

Run these commands from the directory containing `pom.xml`:

```powershell
mvn test
mvn clean package
mvn exec:java
```

Set `JAVA_HOME` to a JDK 27 installation and ensure its `bin` directory is on
`PATH` before running Maven.

## Organizer setup

In a private chat with the bot, the organizer sends `/claim <setup code>` once.
The bot stores only the organizer's Telegram user ID in
`<user.home>/.secretsanta-telegram-bot/organizer-id`; later claims are rejected. The
organizer then sends `/open` and receives a new invite code privately. Only the
organizer can use `/list` or `/run`, and those commands must also be sent in a
private chat.

The participant roster and active invite code are held in memory. If the bot
restarts before the draw, registration is closed and the old invite code is
discarded; the organizer must send `/open` again and share the new code.

## Project layout

- `src/main/java/com/secretsanta/` — bot and application source
- `src/test/java/com/secretsanta/` — JUnit Jupiter tests
- `docs/` — confirmed intent and feature specification
- `tasks/` — implementation plan and acceptance checklist
