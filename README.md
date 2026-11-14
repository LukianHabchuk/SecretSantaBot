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
   `/start`, choose **English**, select **I'm the organizer**, and enter the
   setup code. The organizer can then open registration and share the invite
   code.

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

## Manual deployment to AWS EC2

The GitHub Actions workflow builds and tests the Docker image for pull requests
and pushes to `main`. Pushes also publish images to GHCR. A manually started
workflow on `main` builds and publishes the selected commit, then deploys its
commit-tagged image to one EC2 instance over SSH. A deployment verifies that
the container is running; it does not verify Telegram connectivity or delivery.

This is a one-instance learning setup, not a high-availability service. AWS
eligibility, instance availability, credits, and pricing depend on your account
and can change. Review the [AWS Free Tier terms](https://aws.amazon.com/free/)
and your account's billing dashboard before creating resources. Stopping an
instance does not necessarily stop all storage or address charges. Do not upgrade
to a paid plan unless you accept its charges.

### One-time server setup

1. Create an AWS account and check its Free Plan eligibility. Launch an eligible
   **x86-64 Ubuntu** EC2 instance in an available region. The image produced by
   this workflow is x86-64; an ARM instance needs a different image build. Create
   an EC2 SSH key pair and keep the private key secure.
2. Configure the instance's security group for SSH access. Initially restrict
   port 22 to your own IP for setup. The GitHub-hosted runner also needs SSH
   access when deploying; its IP can change. If you temporarily broaden access,
   do so only for the deployment window and restrict it again afterward.
   Never enable password-based SSH or open an inbound bot port: the bot uses
   outbound long polling.
3. Connect to the instance as its SSH user (normally `ubuntu` for Ubuntu images).
   Install Docker Engine and the Compose plugin following
   [Docker's Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/).
   Enable Docker at boot. Make sure the SSH user can run `docker` without
   `sudo`; membership in the Docker group grants root-equivalent access.
   Reconnect after changing group membership, and verify:

   ```bash
   docker version
   docker compose version
   sudo systemctl enable --now docker
   ```

4. Create `~/secretsanta` on the instance and a private
   `~/secretsanta/.env` with the four values described in [Configuration](#configuration).
   For this disposable trial, generate fresh, different values for the setup code
   and data key, for example with `openssl rand -base64 32` run twice. Set
   `chmod 600 ~/secretsanta/.env`. Never commit the file or send its contents
   to chat. Stop any other bot using the same Telegram token before deployment.
5. The instance must be able to pull the GHCR package. If the package is private,
   authenticate **on the instance** with a GitHub personal access token that has
   `read:packages` and access to the package. Protect Docker's credentials file
   and follow [GitHub's container registry instructions](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
   A public package does not require this login. The package can only be pulled
   after a successful publish.
6. Verify the SSH host key before configuring GitHub Actions. On the EC2
   instance, run `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`. On your
   Windows computer, replace `EC2_HOST` with the exact DNS name or IP you will
   use for deployment and run:

   ```powershell
   ssh-keyscan -t ed25519 EC2_HOST | Set-Content -Encoding ascii .\ec2_known_hosts
   ssh-keygen -lf .\ec2_known_hosts
   ```

   Compare the fingerprint with the one displayed on the instance. Only after
   matching them, use the complete contents of `ec2_known_hosts` as the secret
   below. Do not trust an unverified key obtained from the network.
7. In GitHub repository **Settings → Secrets and variables → Actions**, add:

   | Secret | Value |
   | --- | --- |
   | `DEPLOY_HOST` | EC2 public DNS name or IPv4 address |
   | `DEPLOY_USER` | SSH username, normally `ubuntu` |
   | `DEPLOY_SSH_KEY` | Complete private key for that SSH user |
   | `DEPLOY_SSH_KNOWN_HOSTS` | Verified known-hosts line from step 6 |

   These grant access to the server. Store the bot's token and data key only in
   the instance's protected `.env`, not in workflow code or container images.

### Deploy, check, and stop

1. Merge the workflow to `main` and wait for its push build to publish an image.
   If necessary, configure GHCR package access on the instance as described above.
2. In GitHub Actions, select **Build, publish and deploy → Run workflow**, select
   `main`, and start the run. Only this manual trigger deploys; pull requests
   and ordinary pushes do not. Allow SSH access from the runner during the run.
3. Check that the deploy job succeeds, then check `docker ps` and
   `docker logs <container-id>` on the instance. Open a private chat with the bot and verify
   `/start` and the desired exchange flow. Turn off your PC and verify again.
   A running container alone does not establish that Telegram works. The named
   volume survives container replacement and the restart policy handles normal
   host reboots while Docker is enabled.
4. After the experiment, stop the instance in AWS. To avoid continued charges,
   review all EC2 resources, including EBS volumes and public IPs, and terminate
   or release the resources you no longer need. Termination and volume deletion
   may permanently delete bot data; back up anything you want to keep first.
   AWS account and Free Plan expiry can also end access to the instance.

To roll back, choose an earlier successfully published commit SHA, connect to the
instance, and run the following from `~/secretsanta`, replacing the image with
the lowercase GHCR owner/repository and the full 40-character SHA:

```bash
export SECRET_SANTA_IMAGE=ghcr.io/owner/repository:sha-FULL_COMMIT_SHA
docker compose -p secretsanta -f compose.yaml -f compose.deploy.yaml pull bot
docker compose -p secretsanta -f compose.yaml -f compose.deploy.yaml up -d --no-build
```

This uses the current Compose files and existing data volume; it does not roll
back data or guarantee compatibility with an older application. The original
image must still exist in GHCR. Verify the bot again after a rollback.

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

1. As user `100`, send `/start`, choose **English**, press **I'm the organizer**,
   enter the local demo setup code shown by `help`, and press **Open registration**.
2. Copy the invite code from the local output. For at least three participants
   besides the organizer, send `/start`, choose **English**, press **Join with a
   code**, enter the invite code, and confirm or edit the name. Before the draw,
   participants can choose **Edit my name**, and the organizer can choose
   **Edit participant names** to update any roster entry.
3. During registration, a participant can send `/start`, press **Request a
   partner**, and select another participant. The recipient accepts or declines
   from their own simulated chat. An organizer can also manage pairs from
   **Manage pairs**.
4. The organizer sends `/start` and presses **Run the draw**. Assignment
   messages are printed locally by the simulator. To exercise a retry, run
   `fail <user-id>` before the draw, then `recover <user-id>`, send `/start`
   as the organizer, and press **Retry delivery**.
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
   request and recipient acceptance or decline. After joining, verify that a
   participant can use **Edit my name** and the organizer can use **Edit
   participant names** to change the intended roster entry, including when two
   participants share a name. Each successful join sends the organizer a
   private notification with the participant's chosen name.
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
language button or `/language` to change it. The choice survives restarts and
exchange cleanup; `/forgetlanguage` deletes that user's saved preference. The
next `/start` asks again. The button labels below assume English is selected.

In a private chat with the bot, the organizer sends `/start`, selects **I'm the
organizer**, and enters the one-time setup code. The bot stores only the
organizer's Telegram user ID in
`<user.home>/.secretsanta-telegram-bot/organizer-id`; later claims are rejected.
Once claimed, new `/start` menus no longer offer organizer setup to other users.
The organizer then selects **Open registration** and receives the invite code
privately. The organizer is automatically included in the participant list.
Participants select **Join with a code**, enter the invite code, and confirm
or edit their Telegram first name before joining.

The encrypted exchange snapshot in
`<user.home>/.secretsanta-telegram-bot/exchange-state.enc` preserves an open
registration, roster, pair requests, draw assignments, delivery progress, and
receipt confirmations across restarts. Use `/start` and the inline buttons for
the organizer and participant flows. The organizer can select **Reset
exchange** and must confirm; this option is unavailable after registration closes
for a draw.

The legacy `/join <invite code> <display name>` command remains available.
Telegram's command menu lists participant commands in private chats and
organizer commands in the organizer's private chat. Not every inline button
has a slash-command equivalent. Before organizer setup, `/claim` is also listed.
If updating the menu fails, the bot logs a
warning and retries on `/start`; the normal buttons remain available.
Before the draw starts, registered participants can select **Edit my name** to
update their own display name. The organizer can select **Edit participant
names** and choose any registered participant. Name edits are unavailable after
the draw starts.
During registration, participants can use **Request a partner** to privately ask
another unpaired participant to pair. The recipient can accept or decline, and
the requester can cancel; an unresolved request blocks registration closure and
the draw. The **My pair request** button shows the current request or confirmed pair.
A pair only means neither participant will be assigned to the other in the draw.
The organizer can review the roster and pending requests with **Participants**, or
use **Manage pairs** (also available through `/pair`) to create or replace
pairs. Re-pairing either person removes their previous pair, leaves former
partners unpaired, and privately notifies everyone affected. The organizer's
roster view shows pair/request status only, never assignment recipients.

The bot saves the complete encrypted draw before sending any assignments. If a
private delivery fails, the organizer can use **Retry delivery**; the bot
retries only undelivered messages using the same assignments. The encrypted
draw and roster survive restarts and remain stored after delivery. Each
participant can confirm receipt from their private assignment message. The
organizer's **Exchange status** button shows delivery and confirmation progress
without revealing recipients. After every participant confirms, the organizer
can choose **Delete exchange** and must explicitly confirm deletion; this
removes exchange data but preserves organizer identity.

After the current draw is fully delivered, the organizer can instead choose
**Run a new draw** and explicitly confirm. The bot persists a new
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
