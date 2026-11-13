# Secret Santa Telegram Bot

A Java Telegram bot project for a private, single-group Secret Santa exchange.
Feature requirements and current implementation status are tracked in the
[spec](docs/SPEC-secret-santa-telegram-bot.md) and
[task list](tasks/todo.md).

## Run your own bot with Docker

You need Docker with Compose, a Telegram account, and a machine with outbound
internet access. Docker builds the application; you do not need to install Java
or Maven on the host. Clone this repository or download it from GitHub, then
open a terminal in the repository directory containing `compose.yaml`.

1. Create a bot with [BotFather](https://t.me/BotFather) using `/newbot`.
   Save the bot token and username privately. Use a separate test bot for your
   first walkthrough.
2. Copy `.env.example` to `.env`. Do not overwrite an existing `.env`: it may
   contain the key needed to read your existing data. For example:

   ```powershell
   Copy-Item .env.example .env
   ```

   On Linux or macOS, use `cp .env.example .env` instead.
3. Generate two **different** random values: one data key and one organizer
   setup code. On PowerShell:

   ```powershell
   $bytes = New-Object byte[] 32
   $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   try {
       $rng.GetBytes($bytes)
       [Convert]::ToBase64String($bytes) # data key
       $rng.GetBytes($bytes)
       [Convert]::ToBase64String($bytes) # organizer setup code
   } finally {
       $rng.Dispose()
   }
   ```

   On Linux or macOS with OpenSSL, run `openssl rand -base64 32` twice. Treat
   both outputs as secrets. Never send the bot token or data key in a chat;
   send the setup code only to your own bot's private chat when claiming
   organizer access.
4. Edit `.env` locally and replace all four placeholders:

   - `TELEGRAM_BOT_TOKEN`: the token from BotFather.
   - `TELEGRAM_BOT_USERNAME`: your bot's username.
   - `SECRET_SANTA_SETUP_CODE`: the second generated value, used for the
     one-time organizer claim; it must contain at least 32 characters.
   - `SECRET_SANTA_DATA_KEY`: the first generated value, a Base64-encoded
     32-byte key. Keep this value stable and back it up securely.

   Do not commit `.env`, credentials, or keys. For a hosted deployment, use
   your platform's secret manager instead of putting secrets in an image or
   repository.

   For a separate test installation, use a separate checkout, bot, and data
   key. Add `COMPOSE_PROJECT_NAME=secretsanta-test` to its `.env` so its
   volume is isolated from a live installation. Give the live installation a
   different project name, for example `secretsanta-live`. Keep each name
   stable: changing it makes Compose use a different volume.
5. Build and start the bot:

   ```powershell
   docker compose up --build -d
   docker compose ps
   docker compose logs -f bot
   ```

   The build runs the application's tests. Press `Ctrl+C` to stop viewing logs
   without stopping the bot. In Telegram, open your bot's private chat, send
   `/start`, choose a language, select **I'm the organizer** or **Я
   організатор**, and enter the setup code. The organizer can then open
   registration and share the invite code.

Before using a real exchange, complete the [live test-bot walkthrough](#live-test-bot-walkthrough)
with disposable accounts and data.

## Data, uptime, and backups

The bot runs as an unprivileged user and connects to Telegram using outbound
long polling; it exposes no inbound ports. **If the container stops, the bot
stops responding.** To keep it available, run it on a machine or VPS that stays
on, with Docker running and internet access. Configure Docker to start when the
host boots; a sleeping or shut-down machine cannot run the bot. Compose uses
`restart: unless-stopped`: it can restart a failed process, but a deliberately
stopped container must be started again. A running container does not prove
Telegram delivery is working; monitor logs and use external monitoring
appropriate for your host.

The Compose `bot-data` named volume holds the organizer ID, encrypted exchange
and draw data, and encrypted language preferences. Stopping or removing the
container normally does not delete this volume:

```powershell
docker compose stop       # Stop; keep containers and data
docker compose up -d      # Start again, including after a normal down
docker compose down        # Remove containers and network; keep data
```

Do **not** use `docker compose down --volumes` for normal shutdown: it deletes
the volume and its data. Stop the bot before backing up the volume; see
[Docker's volume backup and restore guide](https://docs.docker.com/engine/storage/volumes/#back-up-restore-or-migrate-data-volumes).
Use `docker volume ls` to find the volume whose name ends in `_bot-data`,
prefixed by the Compose project name. Preserve file ownership and permissions.
Back up `SECRET_SANTA_DATA_KEY` separately and securely; without
the matching key, encrypted data and preferences cannot be recovered. Restore
the volume and its matching key together, and run only one bot process against
that data directory. Test your backup and restore procedure with disposable data.

To deploy an updated checkout, back up first and rebuild with
`docker compose up --build -d`. Test an update with a separate bot before
relying on it for a live exchange.

## Configuration

Provide these values through the process environment or a trusted secret manager:

- `TELEGRAM_BOT_TOKEN` — token issued by BotFather
- `TELEGRAM_BOT_USERNAME` — bot username
- `SECRET_SANTA_SETUP_CODE` — one-time organizer claim code, at least 32
  characters and randomly generated
- `SECRET_SANTA_DATA_KEY` — stable Base64-encoded 256-bit key for exchange state,
  pending draws, and language preferences

The data key must remain the same while encrypted exchange data or language
preferences are retained. Store it securely outside the repository and keep
a backup; losing it makes those files unreadable. The bot encrypts exchange
state, pending draws, and language preferences with AES-256-GCM under
`<user.home>/.secretsanta-telegram-bot/`. Do not commit credentials, keys, or
environment files.

Telegram bot chats are not end-to-end encrypted between participants and the bot.
Telegram and the machine or service running the bot can access messages processed
by the bot.

## Local development

For development without Docker, install JDK 27 and Maven 3.9 or later. Set
`JAVA_HOME` to the JDK and add its `bin` directory to `PATH`. Provide the
configuration above as environment variables, then run these commands from the
directory containing `pom.xml`:

```powershell
mvn test
mvn clean package
mvn exec:java
```

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

1. As user `100`, send `/start`, choose **Українська**, press **Я організатор**,
   enter the local demo setup code shown by `help`, and press **Відкрити реєстрацію**.
2. Copy the invite code from the local output. For each participant, send
   `/start`, choose **Українська**, press **Приєднатися за кодом**, enter the
   invite code, and confirm or edit the name. Alternatively, choose **English**
   to try the English flow.
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

## Live test-bot walkthrough

In private chats, verify the full flow with test accounts before using a real
exchange:

1. Claim organizer access, open registration, and share the invite code.
2. Join several test accounts, edit a participant name, and exercise a pair
   request and recipient acceptance or decline. Each successful join sends the
   organizer a private notification with the participant's chosen name.
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

Join notifications are best-effort: if Telegram delivery fails, registration
still succeeds, the failure is logged, and the organizer can check the roster.
New action logs identify attempts using fixed labels without names, IDs, codes,
or button tokens. Draw activity is logged only when every assignment has been
delivered; assignment delivery errors are still logged for troubleshooting.
An attempt log does not mean the action succeeded.

The app has no health endpoint or built-in external error monitoring.

## Organizer setup

Each user chooses English or Ukrainian on their first private `/start`. Use the
**Language / Мова** button or `/language` to change it. The choice survives
restarts and exchange cleanup; `/forgetlanguage` deletes that user's saved
preference. The next `/start` asks again.

In a private chat with the bot, the organizer sends `/start`, selects **Я
організатор**, and enters the one-time setup code. The bot stores only the
organizer's Telegram user ID in
`<user.home>/.secretsanta-telegram-bot/organizer-id`; later claims are rejected.
Once claimed, new `/start` menus no longer offer organizer setup to other users.
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
Telegram's command menu lists participant commands in private chats and
organizer commands in the organizer's private chat. Not every inline button
has a slash-command equivalent. Before organizer setup, `/claim` is also listed.
If updating the menu fails, the bot logs a
warning and retries on `/start`; the normal buttons remain available.
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
