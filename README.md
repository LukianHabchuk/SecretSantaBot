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
- `SECRET_SANTA_SETUP_CODE` — one-time organizer claim code, at least 32
  characters and randomly generated
- `SECRET_SANTA_DATA_KEY` — stable Base64-encoded 256-bit key for pending draws

The data key must remain the same while a draw is pending. Store it securely
outside the repository and keep a backup; losing it makes an undelivered draw
unrecoverable. The bot stores pending assignments and delivery progress encrypted
with AES-256-GCM in `<user.home>/.secretsanta-telegram-bot/pending-draw.enc`.
Do not commit credentials, keys, or environment files.

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

## Manual offline simulator

To try the real bot command and pairing logic without Telegram or credentials,
build the test image from the directory containing `Dockerfile`:

```powershell
docker build --target build --tag secret-santa-bot:offline-tests .
docker run --rm -it --entrypoint java secret-santa-bot:offline-tests -cp "/workspace/target/classes:/workspace/target/dependency/*" com.secretsanta.OfflineBotSimulator
```

In the simulator, each positive user ID represents a separate private chat.
Type `help` for examples. A basic exchange is:

1. As user `100`, send `/claim demo-setup`, then `/open`.
2. Copy the invite code printed in the local console.
3. Use different IDs with `/join <invite-code> <name>` for each participant.
4. As user `100`, send `/pair`; use `click <number>` to choose each pair.
5. Send `/run` and inspect the simulated private assignment messages printed
   locally.

The simulator uses a fake message sender, creates a fresh encryption key and
temporary data directory for each run, and removes that directory on exit. It
does not call Telegram or use `TELEGRAM_BOT_TOKEN`. This is a local behavior
test, not a check of Telegram delivery or connectivity.

## Container pilot with a test bot

The Docker image builds and tests the application with Java 27, then runs it as
an unprivileged user. It exposes no inbound ports; the bot connects to Telegram
using outbound networking.

Copy the example environment file and replace every placeholder with credentials
for a **separate test bot**:

```powershell
Copy-Item .env.example .env
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
[Convert]::ToBase64String($bytes)
$rng.Dispose()
```

Paste the generated Base64 value into `SECRET_SANTA_DATA_KEY` in `.env`. Generate
a separate random value for `SECRET_SANTA_SETUP_CODE` (it must be at least 32
characters), and set the test bot token and username there. Keep `.env` local;
it is ignored by Git. Never send tokens or encryption keys in chat or commit
them. For a hosted deployment, inject secrets through the platform's secret
manager rather than storing them in a repository or image.

Start the test bot and inspect its logs:

```powershell
docker compose up --build -d
docker compose logs -f bot
```

In a private chat, exercise `/claim`, `/open`, participant `/join`, organizer
`/pair`, and `/run` using test accounts before any real exchange. The bot stores
the organizer ID and encrypted pending draw in the persistent `bot-data`
volume. Back up that volume and the matching `SECRET_SANTA_DATA_KEY` separately;
the key must remain stable while a draw is pending. Registration and the roster
are in memory, so a restart during registration requires opening a new exchange.

Stop or remove the container without deleting persisted state:

```powershell
docker compose stop
docker compose down
```

Do **not** use `docker compose down --volumes` for normal shutdown: it deletes
the organizer ID and pending-draw data. Compose restarts the process after exit,
but this app has no health endpoint or external error monitoring; watch the logs
and configure platform-specific monitoring before relying on a hosted instance.

## Organizer setup

In a private chat with the bot, the organizer sends `/claim <setup code>` once.
The bot stores only the organizer's Telegram user ID in
`<user.home>/.secretsanta-telegram-bot/organizer-id`; later claims are rejected.
The organizer then sends `/open` and receives a new invite code privately. Only
the organizer can use `/list` or `/run`, and those commands must also be sent
in a private chat.

The participant roster and active invite code are held in memory. If the bot
restarts before the draw, registration is closed and the old invite code is
discarded; the organizer must send `/open` again and share the new code.

Participants join privately with `/join <invite code> <display name>`. The
organizer sends `/pair`, selects one participant, then selects their partner
using the inline buttons. Re-pairing either person removes their previous pair;
any former partners are left unpaired and the organizer is told which pairs
changed.

The organizer starts the draw with `/run`. The bot saves the complete encrypted
draw before sending any assignments. If any private delivery fails, the
organizer is told to retry with `/run`; the bot retries only undelivered messages
using the same assignments. A pending draw survives restarts, blocks `/open`,
and is deleted only after all assignments are delivered. If Telegram accepted a
message just before the process stopped, retrying may send that same assignment
again. Run only one bot process against a data directory at a time.

## Project layout

- `src/main/java/com/secretsanta/` — bot and application source
- `src/test/java/com/secretsanta/` — JUnit Jupiter tests
- `docs/` — confirmed intent and feature specification
- `tasks/` — implementation plan and acceptance checklist
