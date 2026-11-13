# Spec: Private Secret Santa Exchange

## Status
Proposed revision; awaiting human approval. This replaces the earlier
command-driven organizer-pairing flow. The current implementation does not yet
meet this specification.

## Objective
Build a button-driven Telegram bot for one friend group's Secret Santa exchange.
The organizer and participants use private chats with the bot. Friends enter an
exchange invitation code, register using their Telegram first name (with an
option to edit it), and can propose partner pairs that require mutual acceptance.
The organizer can review and override pairings, start or rerun a complete draw,
and eventually delete the exchange after all participants confirm.

The organizer is also a participant. The bot sends each participant only their
own assignment. It must not display or log the full giver-to-recipient mapping
to the organizer. This is an application-interface and logging guarantee, not a
cryptographic guarantee against someone who can inspect the running process or
its secrets. The organizer may also infer information from the participant
roster, pair exclusions, and their own assignment, especially in a small group.

## Scope and user stories
- As the organizer, I can open one exchange, join it automatically as a
  participant, and privately share its invitation code.
- As a friend, I can start the bot, enter the invite code, confirm or edit the
  Telegram first name used for the exchange, and join the active roster.
- As a participant, I can propose a partner and accept or decline a proposal
  addressed to me.
- As the organizer, I can inspect roster and pair/request status and directly
  create or replace a pair.
- As the organizer, I can start an assignment from a button. Every participant,
  including me, receives only their own assignment in the same private chat.
- As a participant, I can confirm receipt of the current assignment.
- As the organizer, I can request a complete replacement draw before cleanup,
  or delete the exchange after all participants confirm.
- An open exchange and its delivery state survive process restarts.

Email delivery, group-chat workflows, multiple concurrent exchanges, and
arbitrary non-partner exclusions are out of scope.

## Assumptions and decisions
- "Room" means one shared exchange roster, presented through separate private
  chats. The bot cannot identify a Telegram user who only opens its profile;
  Telegram identity becomes available after the user presses Start or sends a
  message.
- A participant may have at most one accepted partner and at most one open
  partner request, sent or received, at a time. Only unpaired participants
  without another open request can create or accept a request.
- The requester can cancel a pending request; its recipient can accept or
  decline it. An accepted participant-created pair cannot be changed by either
  participant; the organizer can change it directly.
- Assignment cannot start while any participant pair request is unresolved.
  Requests must first be accepted, declined, or canceled by the requester.
- The invitation remains valid until assignment starts. Starting assignment
  closes registration.
- The organizer can reset an exchange before assignment only, and must confirm
  the reset. Reset is unavailable while delivery is pending.
- A new full draw is permitted only after the previous draw was fully delivered
  and before exchange cleanup. It replaces the prior assignments for everyone;
  each recipient must be told that the new result supersedes their previous one.
- Confirmation applies only to the current draw. A replacement draw clears all
  prior confirmations. Stale buttons from an older draw cannot confirm the new
  one.
- Assignment data is persisted encrypted with AES-256-GCM using the stable
  `SECRET_SANTA_DATA_KEY`. The running application must decrypt it to deliver
  messages. The organizer trusts the application and its host.
- After cleanup, participant and assignment data for the exchange is deleted;
  the persistent organizer identity remains.

## Tech Stack
- Java 27 (non-LTS)
- Maven
- Existing Telegram Bots Java library, version 6.9.7.1
- Existing SLF4J Simple logger, version 2.0.9
- JUnit Jupiter, version 6.1.3
- Standard Maven source layout: `src/main/java` and `src/test/java`
- Java Cryptography Architecture (AES-256-GCM); no additional cryptography
  dependency is required
- Existing Docker multi-stage build and Docker Compose pilot configuration

## Commands
Run Maven commands from the directory containing `pom.xml`. The Maven commands
require JDK 27.

```powershell
mvn clean test
mvn clean package
```

The container build uses Java 27 and runs the test suite:

```powershell
docker build --target build --tag secret-santa-bot:offline-tests .
docker run --rm -it --entrypoint java secret-santa-bot:offline-tests -cp "/workspace/target/classes:/workspace/target/dependency/*" com.secretsanta.OfflineBotSimulator
```

The manual simulator uses fake messages and temporary data; it does not connect
to Telegram. A live test-bot pilot uses local `.env` configuration and Docker
Compose:

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose logs -f bot
docker compose stop
```

The live bot requires `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`,
`SECRET_SANTA_SETUP_CODE` (random, at least 32 characters), and
`SECRET_SANTA_DATA_KEY` (stable Base64 for exactly 32 bytes). Do not put real
values in source control or chat. Do not remove the Compose data volume during
normal shutdown.

## Project Structure
- `src/main/java/com/secretsanta/` — Telegram bot, exchange and pairing
  services, assignment generation/delivery, and persistence
- `src/test/java/com/secretsanta/` — JUnit Jupiter unit and integration tests
- `docs/intent/secret-santa-telegram-bot.md` — confirmed product intent
- `docs/SPEC-secret-santa-telegram-bot.md` — this specification
- `tasks/` — implementation plan and acceptance checklist
- Docker data volume mounted at
  `/home/bot/.secretsanta-telegram-bot` — organizer identity and durable
  exchange state

## Code Style
Follow existing Java conventions: descriptive PascalCase type names,
camelCase methods and fields, immutable records for simple values, and explicit
handling of Telegram and storage errors. Validate messages and callback data at
the update boundary; authorize every organizer action, including callbacks.

```java
record PairRequest(
        long requesterId,
        long requestedPartnerId,
        PairRequestStatus status) {}
```

Never log Telegram update bodies, participant assignments, or assignment
delivery payloads. Do not place credentials or keys in source files or docs.

## Functional Requirements
1. The first user who claims organizer access with the randomly generated,
   at-least-32-character `SECRET_SANTA_SETUP_CODE` becomes the sole organizer.
   Persist only the organizer's Telegram user ID for this claim; do not log the
   provided code.
2. The bot offers a button-driven private-chat flow. `/start` is the entry point
   and may provide navigation buttons. Text entry is limited to values that
   cannot be selected with a button, such as an invite code or edited display
   name.
3. The organizer can open one exchange. Opening automatically adds the
   organizer to its roster using the organizer's Telegram first name, which the
   organizer can confirm or edit. The bot sends the invite code only to the
   organizer's private chat.
4. A friend can enter the current invite code in a private chat, confirm or edit
   their Telegram first name, and join once. The bot uses Telegram's sender ID
   as the participant identity. It does not collect usernames, phone numbers, or
   other profile fields unless separately approved.
5. The invitation is reusable for the active exchange until assignment starts.
   Registration is then closed. A restart does not invalidate the invitation or
   discard the roster.
6. Persist the active exchange across restarts, including participant IDs and
   display names, invite/registration state, accepted pairs, pending pair
   requests, current assignment generation, delivery progress, and current-draw
   confirmations. Persist updates atomically. Do not start a second active
   exchange.
7. Participant pairing is private and consent-based. Only unpaired participants
   with no pending request can send a request. The recipient can accept or
   decline; the requester can cancel. Acceptance creates a reciprocal
   exclusion. Participants cannot change an accepted pair themselves.
8. The organizer can privately review the roster, pair status, and pending
   requests, and can directly pair or replace pairs. Organizer changes must
   preserve the one-partner-per-participant constraint and notify affected
   participants privately. Pairing controls never expose assignment recipients.
9. The organizer can reset the exchange before assignment. The bot must show a
   clear confirmation step. Confirming deletes the current invite, participants,
   pairs, and pending pairing requests, then allows a fresh exchange. Reset is
   rejected after assignment begins.
10. The organizer starts assignment through a button. The bot closes
    registration and generates a complete one-to-one draw in which nobody is
    assigned to themselves or their accepted partner. Assignment is blocked
    until all open pair requests are accepted, declined, or canceled. If no
    valid draw exists, no assignment is sent, and the exchange remains available
    for correction.
11. Before any assignment message is sent, persist the complete current draw
    encrypted with AES-256-GCM and a fresh random nonce. Persist per-recipient
    delivery progress. If persistence fails, send no assignments.
12. Send each participant, including the organizer, only their own recipient in
    their private chat. The organizer's UI and application logs must never show
    or contain the complete giver-to-recipient mapping. Organizer status may
    show delivery/confirmation counts and participant confirmation state, but
    not assignment recipients. The organizer receives only their own assignment.
13. A failed or interrupted delivery remains pending. Retry the same assignment
    rather than drawing a replacement. A crash after Telegram accepts a message
    but before delivery progress is persisted may cause that same assignment to
    be sent again. The organizer can see failures without seeing the mapping.
14. After the current draw is fully delivered, the organizer may trigger a
    complete replacement draw before cleanup. Require an explicit confirmation
    that prior assignments will be superseded. Generate and persist the full new
    draw before sending; notify every participant privately that the new result
    replaces the old one and deliver their new individual assignment. If any
    delivery fails, retry that same replacement draw. Never mix assignment
    generations in the current exchange state.
15. Each assignment message includes a private Received/OK button. Record
    confirmation against the current draw generation. Ignore stale confirmation
    callbacks from prior assignments. The organizer can see who has confirmed,
    not what they received.
16. The organizer can delete the completed exchange only after every current
    participant has confirmed receipt. Require a confirmation step before
    deletion. Delete participant, pairing, invitation, confirmation, and
    assignment data; retain the organizer identity. The next exchange can then
    be opened.
17. Persisted assignment data remains encrypted until deletion. The data key
    must remain stable and backed up while the exchange is retained; losing it
    makes pending or retained assignments unrecoverable. Telegram and the host
    can access messages the bot processes; bot chats are not end-to-end
    encrypted.

## Localization: English and Ukrainian

### Objective and scope
- Make the bot usable in English and Ukrainian without changing exchange,
  pairing, assignment, or authorization behavior.
- Each person chooses their own language on their first private `/start` and
  can change it later. The initial choice is presented in both languages.
- Localize all bot-facing messages, prompts, buttons, callback answers, command
  descriptions, and delivery and error messages. Preserve user-provided names,
  invite codes, command names, and callback data.
- Notifications and assignments sent to another person use the recipient's
  language, not the sender's. Existing messages already sent by Telegram are
  not rewritten.
- Support only English and Ukrainian. Translation of arbitrary participant
  content and localization of application logs are out of scope.

### Persistence and compatibility
- Persist each user's choice across bot restarts and exchange cleanup. Existing
  users without a saved choice are asked on their next private `/start`.
- Store the minimum required association between a Telegram user ID and the
  selected language, protected at rest using the existing stable data key.
  Persist updates atomically and retain these preferences independently of
  exchange data; never log preference records.
- Provide a way for a user to delete their own saved preference. After deletion,
  the next private `/start` requests a new choice.
- Existing exchanges, organizer identities, and encrypted snapshots remain
  readable without resetting or recreating them. Existing callback controls
  must not acquire new permissions or expose other participants' information.
- Explicitly report preference storage failures; do not claim a choice was saved
  when it was not. Validate language selection and private-chat ownership on
  callbacks.

### Acceptance and verification
- A new or existing user can select either language, complete the normal
  onboarding flow, change language, and retain the selection after restart.
- Two users with different languages receive their own localized messages,
  including pair notifications, draw assignments, and receipt controls.
- A saved preference survives exchange cleanup; deleting a preference causes
  a new choice to be requested on the next private `/start`.
- Automated tests cover language selection and changes, restart persistence,
  cleanup and deletion, malformed or unauthorized callbacks, storage failures,
  mixed-language notifications, and existing exchange recovery.
- Use JUnit tests under `src/test/java/com/secretsanta/`, run `mvn test` with
  JDK 27, and verify both languages through the offline simulator. No new
  dependency or external service is required.

## Testing Strategy
- Use JUnit Jupiter tests under `src/test/java/com/secretsanta/`.
- Test private onboarding, Telegram identity/name confirmation, invite
  validation/expiry, organizer auto-enrollment, duplicate joins, restart
  recovery, manual reset confirmation, and authorization.
- Test pair request send, accept, decline, cancel, one-open-request limits,
  unpaired-only rules, organizer overrides, notifications, and restart recovery.
- Test complete assignment constraints, impossible configurations, no partial
  delivery, and inclusion of the organizer as a participant.
- Test that each fake private recipient receives only their own assignment and
  the organizer-facing UI/log output contains no other assignment mappings.
- Test encrypted persistence/tamper rejection, replacement-draw generations,
  failed-delivery retry without redrawing, stale buttons, acknowledgement
  tracking/reset on reroll, and deletion only after all current participants
  confirm.
- Use the offline simulator for manual interaction tests without Telegram
  credentials or network access. Use a separate test bot for Telegram transport
  verification before any real exchange.
- Run `mvn clean test` and `mvn clean package` on Java 27 before approval for
  release. No numeric coverage threshold is currently defined.

## Boundaries
- **Always:** Keep all participant actions and assignment delivery in private
  chats; validate all incoming text/callbacks; recheck organizer permissions
  for each protected action; persist exchange state before acknowledging
  success; encrypt assignment data; redact recipient mappings from organizer
  UI and logs; retry the same draw after a possible delivery; make destructive
  reset/cleanup actions explicit and confirmed.
- **Ask first:** Add dependencies or external services; change the single-active-
  exchange scope; collect additional Telegram profile data; change assignment,
  pairing-consent, persistence, reassignment, or cleanup policies; deploy to a
  production host.
- **Never:** Commit real secrets; send assignments to a group; show or log the
  full assignment mapping to the organizer; replace a draw while its delivery
  may be partially complete; reset an in-progress draw; delete retained
  participant/assignment state before the required confirmations.

## Success Criteria
- Organizer and participants can complete the entire flow with Telegram buttons
  and private text prompts, without relying on command syntax for routine tasks.
- The organizer is automatically included and receives one assignment.
- Participants enter the correct exchange, confirm/edit their names, and create
  partner exclusions only by mutual acceptance.
- Assignment cannot start while any participant pair request remains unresolved.
- The organizer can review and override pairs without seeing assignment
  mappings.
- Active exchange and pending delivery survive restarts; a confirmed pre-draw
  reset clears only the intended exchange.
- Every completed draw is one-to-one and excludes self and accepted partners.
- Each participant receives only their own current assignment privately.
- Organizer-facing messages and application logs reveal no full mapping.
- Failed delivery never triggers a new draw; explicit complete redraws notify
  everyone and replace all assignments consistently.
- Cleanup is unavailable until every participant confirms the current draw and
  the organizer confirms deletion.
- Tests/build pass and a separate test-bot walkthrough verifies Telegram
  transport before real use.

## Open Questions
- No remaining product-flow questions from the confirmed intent.
- The hosting platform and production dependency-audit method remain launch
  decisions; they do not block this product specification.
