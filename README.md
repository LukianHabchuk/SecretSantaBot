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
- `SECRET_SANTA_DATA_KEY` — stable Base64-encoded 256-bit key for exchange state
  and pending draws

The data key must remain the same while an exchange or draw is active. Store it
securely outside the repository and keep a backup; losing it makes persisted
exchange data unrecoverable. The bot encrypts exchange state and pending draws
with AES-256-GCM under `<user.home>/.secretsanta-telegram-bot/`. Do not commit
credentials, keys, or environment files.

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
Type `help` for examples. Use `as <user-id> <message>` to send messages and
`click <button-number>` to press a button on that user's latest keyboard. Use
`click <keyboard-number> <button-number>` to press a button on an earlier
keyboard; the simulator labels each keyboard in its output.

To walk through an exchange:

1. As user `100`, send `/start`, press **Я організатор**, enter the local demo
   setup code shown by `help`, and press **Відкрити реєстрацію**.
2. Copy the invite code from the local output. For each participant, send
   `/start`, press **Приєднатися за кодом**, enter the invite code, and confirm
   or edit the name.
3. During registration, a participant can press **Запросити пару** and select
   another participant. The recipient accepts or declines from their own
   simulated chat. An organizer can also manage pairs from **Змінити пари**.
4. The organizer presses **Провести жеребкування**. Assignment messages are
   printed locally by the simulator. To exercise a retry, run `fail <user-id>`
   before the draw, then `recover <user-id>` and press the retry button.
5. Each participant can confirm receipt from their assignment keyboard. After
   all confirmations, the organizer can explicitly start a replacement draw or
   clean up the exchange; cleanup preserves organizer access.

The simulator uses a fake message sender, creates a fresh encryption key and
temporary data directory for each run, and removes that directory on exit. It
does not call Telegram or use `TELEGRAM_BOT_TOKEN`. This is a local behavior
test, not a check of Telegram delivery or connectivity. Simulated assignments
are intentionally visible in the local terminal; do not use real participant
data in a simulator session.

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

In private chats, verify the full flow with test accounts before using a real
exchange:

1. Claim organizer access, open registration, and share the invite code.
2. Join several test accounts, edit a participant name, and exercise a pair
   request and recipient acceptance or decline.
3. Check that the organizer's participant list shows pair/request status but
   never assignment recipients. Close registration and run the draw; verify
   each assignment is delivered privately.
4. Confirm receipt from each assignment message, verify status, and test the
   explicitly confirmed replacement draw. Confirm that old receipt controls
   no longer apply, then confirm the new generation and test confirmed cleanup.
5. Restart the container during a test exchange and verify the persisted state
   resumes. Use only disposable test data.

This repository's automated and offline tests do not connect to Telegram. A
separate live test-bot walkthrough has not been run because no separate test bot
credentials were provided. Complete the steps above with a dedicated test bot
before relying on live Telegram delivery. Never paste credentials, keys, or
private assignment details into source control, chat, or shared logs.

The bot stores the organizer ID and encrypted exchange state in the persistent
`bot-data` volume. Back up that volume and the matching
`SECRET_SANTA_DATA_KEY` separately; the key must remain stable to read any
backup and while an exchange or draw is active. Losing the key makes encrypted
exchange data unrecoverable. Do not delete the volume as part of normal
shutdown. The organizer can reset registration only after confirming and before
starting a draw.

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

In a private chat with the bot, the organizer sends `/start`, selects **Я
організатор**, and enters the one-time setup code. The bot stores only the
organizer's Telegram user ID in
`<user.home>/.secretsanta-telegram-bot/organizer-id`; later claims are rejected.
The organizer then selects **Відкрити реєстрацію** and receives the invite code
privately. The organizer is automatically included in the participant list.
Participants select **Приєднатися за кодом**, enter the invite code, and confirm
or edit their Telegram first name before joining.

The encrypted exchange snapshot in
`<user.home>/.secretsanta-telegram-bot/exchange-state.enc` preserves an open
registration, roster, pair requests, draw assignments, delivery progress, and
receipt confirmations across restarts. Use `/start` and the inline buttons for
the organizer and participant flows. The organizer can select **Скинути
обмін** and must confirm; this option is unavailable after registration closes
for a draw.

The legacy `/join <invite code> <display name>` command remains available.
During registration, participants can use **Запросити пару** to privately ask
another unpaired participant to pair. The recipient can accept or decline, and
the requester can cancel; an unresolved request blocks registration closure and
the draw. The **Мій запит** button shows the current request or confirmed pair.
The organizer can review the roster and pending requests with **Учасники**, or
use **Змінити пари** (also available through `/pair`) to create or replace
pairs. Re-pairing either person removes their previous pair, leaves former
partners unpaired, and privately notifies everyone affected. The organizer's
roster view shows pair/request status only, never assignment recipients.

The bot saves the complete encrypted draw before sending any assignments. If a
private delivery fails, the organizer can use **Повторити доставку**; the bot
retries only undelivered messages using the same assignments. The encrypted
draw and roster survive restarts and remain stored after delivery. Each
participant can confirm receipt from their private assignment message. The
organizer's **Статус обміну** button shows delivery and confirmation progress
without revealing recipients. After every participant confirms, the organizer
can choose **Очистити обмін** and must explicitly confirm deletion; this
removes exchange data but preserves organizer identity.

After the current draw is fully delivered, the organizer can instead choose
**Провести нове жеребкування** and explicitly confirm. The bot persists a new
draw generation before privately notifying everyone of their replacement
recipient. Previous receipt buttons then become stale, and incomplete
replacement delivery resumes with the same assignments. If Telegram accepted a
message just before the process stopped, retrying may send that same assignment
again.

On startup, legacy encrypted `pending-draw.enc` data is migrated into the
current exchange snapshot only when it can be decrypted with the configured
data key and reconciled with the current roster and exchange state. The bot
verifies the migrated snapshot before deleting the legacy file. A wrong key,
invalid legacy data, or conflicting persisted exchange state prevents
migration; preserve the data directory and key and resolve the conflict before
restarting. Migration does not recover data if its key has been lost. Run only
one bot process against a data directory at a time.

## Project layout

- `src/main/java/com/secretsanta/` — bot and application source
- `src/test/java/com/secretsanta/` — JUnit Jupiter tests
- `docs/` — confirmed intent and feature specification
- `tasks/` — implementation plan and acceptance checklist
