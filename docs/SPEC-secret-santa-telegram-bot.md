# Spec: Secret Santa Telegram Bot

## Status
Updated draft — decisions are recorded below; requires human approval before
implementation.

## Objective
Build a simple Telegram bot for one friend group's Christmas gift exchange. Friends
start a private conversation with the bot and register themselves. The organizer
starts one draw after registration is complete. Each participant privately receives
one recipient.

The draw must assign every participant exactly once, never assign someone to
themselves, and never assign either partner to the other. The goal is to avoid
sharing participant details with a separate Secret Santa website while making a
useful project the group can change later.

## Scope and user stories
- As a participant, I can start the bot, join with the current event invite code,
  and register my name.
- As a participant, I receive my recipient privately after the organizer starts the
  draw.
- As the organizer, I can open registration, inspect the roster, pair participants,
  and start the draw when the group is ready.
- As the organizer, I get a clear error if there is no valid assignment, and no
  participant is sent a partial or invalid result.

Email delivery, a public multi-group service, and arbitrary non-partner exclusions
are out of scope for the first version.

## Assumptions
- Extend the existing Maven Telegram bot project and its Telegram Bots
  library rather than selecting a new stack.
- Target the latest generally available Java release, Java 27, as of November 10,
  2026. Java 27 is a non-LTS release.
- Use Telegram private chats for registration and delivery. A participant must
  start the bot before it can message them.
- This is a single-group bot. Telegram is not end-to-end encrypted between a
  participant and a bot; Telegram and the machine/service hosting the bot can
  access messages the bot processes.

## Tech Stack
- Java 27 (latest generally available release as of November 10, 2026; non-LTS)
- Maven
- Existing Telegram Bots Java library, version 6.9.7.1
- Existing SLF4J Simple logger, version 2.0.9
- JUnit Jupiter for automated tests
- Standard Maven source layout: `src/main/java` and `src/test/java`
- Maven Exec Plugin for local execution
- Java Cryptography Architecture (AES-256-GCM) for encrypting pending assignments;
  no additional cryptography library is required

The project currently has a root-level `SecretSantaBot.java` and no configured
test framework or persistent store. Its Maven compiler is currently configured
for Java 17; implementation must update the compiler configuration and verify
dependency compatibility with Java 27. Implementation will move the bot into the
standard Maven source layout.

Java release reference: [Oracle Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)

## Commands
- Test: `mvn test`
- Build: `mvn clean package`
- Run: `mvn exec:java`
- Configure `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`,
  `SECRET_SANTA_SETUP_CODE`, and `SECRET_SANTA_DATA_KEY` in the process
  environment before running. The data key is a stable Base64-encoded 256-bit
  key. Never place real values in source control.

## Project Structure
- `src/main/java/` — bot entry point, Telegram update handling, assignment logic,
  and state handling
- `src/test/java/` — JUnit Jupiter unit and integration tests
- `pom.xml` — Maven project and dependencies
- `docs/intent/secret-santa-telegram-bot.md` — confirmed project intent
- `docs/SPEC-secret-santa-telegram-bot.md` — this specification
- `.secretsanta-telegram-bot/` under the user's home directory — local organizer
  identity and encrypted pending-draw data; never part of the repository

## Code Style
Follow Java conventions supported by Java 27 and the existing project style: descriptive
PascalCase type names, camelCase methods and fields, immutable records for simple
data values where appropriate, and explicit handling of Telegram API errors.

```java
public record Participant(long chatId, String name) {}

private void registerParticipant(Participant participant) {
    participants.put(participant.chatId(), participant);
}
```

Do not place bot tokens or other credentials in source files or documentation.

## Functional Requirements
1. The first user who claims organizer control using the one-time setup code
   configured in `SECRET_SANTA_SETUP_CODE` becomes the sole organizer. The bot
   persists only that organizer's Telegram user ID locally so the code cannot be
   reused after restart.
2. The organizer opens registration. The bot generates an event invite code and
   sends it privately to the organizer. Participants join through a private chat
   using that code and register a display name. The code is valid only for the
   currently open exchange.
3. The organizer can inspect the roster and pair two registered participants by
   selecting them from the roster. A participant can have at most one partner. If
   the organizer pairs someone who already has a partner, the old pair is removed,
   the new pair is set, and the organizer is told which pairing changed.
4. The roster and open-registration state are held in memory. A restart before a
   draw loses that roster; the organizer must open a new registration and share a
   newly generated invite code.
5. The organizer starts the draw after registration. Registration closes only
   after a valid full assignment has been generated. If no valid assignment is
   possible, the bot reports an actionable error, sends no assignments, and
   leaves the roster available for correction.
6. The draw produces a one-to-one assignment: every registered participant gives
   to exactly one participant and receives from exactly one participant.
7. No participant is assigned to themselves or their partner.
8. Before delivery begins, the bot persists the pending assignment locally in
   encrypted form using AES-256-GCM and `SECRET_SANTA_DATA_KEY`, with a fresh
   random nonce for each encryption. It records delivery progress and retries
   failed deliveries with the same assignment; it never generates a replacement
   draw after any assignment may have been sent. If the encrypted pending draw
   cannot be saved, no assignment is sent and the organizer is notified.
9. A pending draw remains stored until every participant's assignment is
   successfully delivered. If a recipient remains unreachable, the draw stays
   pending indefinitely for organizer retry; the organizer cannot cancel it and
   start a different draw through the bot.
10. If the process restarts during delivery, the bot reloads the pending draw and
    allows the organizer to retry. A crash between Telegram accepting a message
    and recording its delivery may cause that same assignment to be sent again;
    it must never change the recipient.
11. Assignment messages are sent only in private chats. The bot does not reveal
   recipient assignments in group messages or logs.
12. The bot reports Telegram delivery failures explicitly; a failed delivery must
   not be reported as a successful complete draw.
13. After all messages are delivered, the bot deletes the pending draw, clears the
    roster, and becomes ready for a new exchange. The locally persisted organizer
    ID remains.

## Testing Strategy
- Add automated tests for assignment constraints, completeness, uniqueness,
  impossible participant/partner configurations, and repeatability of validity
  across randomized draws.
- Add tests for registration and draw lifecycle behavior, including the no-partial-
  delivery behavior when a valid draw cannot be made.
- Verify that assignment details are only sent to each giver's private chat and
  failures are surfaced to the organizer.
- Test organizer claiming, event invite codes, partner replacement, and access
  control.
- Test encrypted pending-draw recovery, retry without changing assignments,
  deletion after full delivery, and failure when the configured key is missing or
  invalid.
- Manually verify the end-to-end Telegram flow with a test bot before using it
  for the Christmas exchange.

## Boundaries
- **Always:** Keep assignments private; validate the full assignment before sending
  any result; report invalid configurations and delivery failures explicitly; keep
  credentials out of source control; encrypt locally persisted assignments; delete
  the pending draw after full delivery.
- **Ask first:** Add dependencies beyond the approved JUnit Jupiter and Maven Exec
  Plugin; add CI configuration; broaden to multiple exchanges/groups or email; or
  change the approved organizer, pairing, data-retention, or delivery policies.
- **Never:** Send an incomplete or invalid draw as if it succeeded; publish
  assignments to a shared chat; commit real bot credentials.

## Success Criteria
- Every registered participant is included exactly once as a giver and exactly
  once as a recipient after a successful draw.
- No successful draw contains a self-assignment or a partner-to-partner
  assignment.
- An impossible draw sends zero assignment messages and gives the organizer an
  actionable error.
- Each participant receives only their own assignment in a private Telegram chat.
- Only the claimed organizer can inspect the roster, pair participants, or control
  the exchange.
- A partial draw is recoverable after restart without changing any assignment.
- Completed exchanges leave no roster or pending assignment data on disk.
- Build and automated tests pass, and the complete flow is verified with a test bot.

## Resolved Decisions
- Organizer: one-time setup-code claim, with the organizer's Telegram user ID
  persisted locally.
- Enrollment: organizer opens registration; the bot generates an invite code for
  participants to join privately.
- Partner rules: organizer pairs from the roster; pairing a participant again
  replaces their existing pair and notifies the organizer.
- Data: roster stays in memory; organizer ID is local; pending draw is encrypted
  locally and deleted after successful delivery.
- Delivery failure: retry the same draw indefinitely; never redraw after any
  assignment may have been sent.
- Lifecycle: one active exchange; after full delivery, clear the roster and draw.
- Build and tests: Java 27, Maven standard layout, JUnit Jupiter, and Maven Exec
  Plugin.
- Encryption key: stable Base64-encoded 256-bit key supplied as
  `SECRET_SANTA_DATA_KEY`; losing it makes an undelivered draw unrecoverable.
