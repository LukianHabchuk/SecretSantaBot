# Implementation Plan: Private Secret Santa Exchange

## Status
Previously approved phases remain as recorded. The late-enrollment extension
below is approved. The separate live test-bot walkthrough remains pending
test-bot access.

## Overview
Extend the existing Java 27 Telegram bot from transient registration and
command-driven pairing into a durable, button-driven private exchange. Preserve
the tested assignment generator, organizer identity store, encrypted file
handling patterns, fake Telegram messenger, and offline simulator. Persist one
versioned exchange state, add participant-consented pairs, and retain each
draw until everyone confirms the current assignment and the organizer deletes
the exchange.

## Existing Project Baseline
- Java 27, Maven, Telegram Bots 6.9.7.1, JUnit Jupiter, and a Docker pilot are
already configured.
- `AssignmentGenerator` enforces one-to-one assignments and rejects self and
partner assignments.
- `OrganizerStore` persists organizer identity. `ExchangeService` currently
holds the invite, roster, and organizer-managed pairs in memory.
- `PendingDrawStore` provides AES-256-GCM encrypted, atomic file persistence,
file locks, and delivery progress. `PendingDrawService` deletes the draw and
roster after all messages are delivered, so it must be changed to retain an
exchange until cleanup.
- `SecretSantaBot` currently parses commands and has organizer-only pairing
callbacks. `TelegramMessenger` and tests provide a fakeable transport boundary.
- `OfflineBotSimulator.java` has a pre-existing uncommitted modification.
Preserve and integrate it only in the simulator task; do not revert or overwrite
unrelated changes.
- Tasks 1-6 in `tasks/todo.md` are completed baseline work. Their previous
in-memory pre-draw behavior is superseded by the approved specification.

## Architecture Decisions
- Keep the existing Java/Maven stack and local Docker volume; do not add a
database, service, or dependency without approval.
- Introduce one versioned exchange snapshot in the existing data directory.
Reuse the current AES-GCM, fresh nonce, atomic replacement, validation, and
single-process/file-lock patterns. Encrypt the whole snapshot, including
participant and pairing data, so assignments and other exchange details share
one durable, consistent state. Keep the existing stable data key backed up
until the exchange is deleted.
- Keep organizer identity separate from exchange lifecycle so cleanup does not
remove the organizer claim. The snapshot contains the invite/registration
state, participant identities/display names, reciprocal pairs, pending pair
requests, draw generation, current assignment data, delivery progress, and
receipt confirmations.
- Treat the exchange as a state machine:

  `EMPTY -> REGISTRATION_OPEN -> DRAW_DELIVERING -> AWAITING_CONFIRMATIONS`

  The organizer may reset only `REGISTRATION_OPEN` after confirmation. A fully
  delivered draw may transition to a new `DRAW_DELIVERING` generation after an
  explicit replacement confirmation. Cleanup requires all current-generation
  receipts plus organizer confirmation, then returns to `EMPTY`.
- Migrate an existing encrypted `pending-draw.enc` before normal startup
  operations. Convert its assignments and delivery progress into the new
  snapshot, save and verify that snapshot first, and only then remove the legacy
  file. A partially delivered legacy draw must resume unchanged. A pre-draw
  roster from the old in-memory implementation cannot be recovered after a
  restart; do not claim otherwise.
- Keep domain rules in services and Telegram updates/buttons in
  `SecretSantaBot`. Authenticate organizer actions on every callback and bind
  assignment confirmations to the current draw generation.
- Extend the fake messenger and offline simulator for deterministic interaction
  and privacy checks. No test should need a real Telegram credential.

## Dependency Graph

```text
Completed Java/Maven, assignment, organizer, and delivery foundations (Tasks 1-6)
  -> 7 encrypted durable exchange snapshot
  -> 8 persisted lifecycle and organizer enrollment
  -> 9 private onboarding buttons
  -> 10 mutual pair-consent rules
  -> 11 pairing buttons and organizer overrides
  -> 12 retained draw delivery and legacy migration
  -> 13 receipt confirmation and deletion
  -> 14 complete replacement draws and generation-safe callbacks
  -> 15 simulator, operating docs, and test-bot walkthrough
```

The name-edit branch depends on existing onboarding and persistence:
Task 8 -> Task 16; Tasks 9 and 16 -> Task 17 -> Task 18.

The late-enrollment extension depends on the completed replacement-draw and
pairing foundations: Tasks 14 and 19 -> Tasks 20-25.

## Task List

### Phase 1: Durable Exchange and Onboarding
- Task 7: Create the encrypted, versioned exchange snapshot.
- Task 8: Persist exchange lifecycle and automatically enroll the organizer.
- Task 9: Add private button-driven onboarding and pre-draw reset.

### Checkpoint: Durable Onboarding
- Exchange state reloads after restart and fails explicitly on invalid or
  undecryptable data.
- Organizer and participants can join using private chats, and the organizer is
  included in the persisted roster.
- A confirmed pre-draw reset clears only the active exchange.

### Phase 2: Pair Consent and Organizer Controls
- Task 10: Implement mutual participant pair requests and consent rules.
- Task 11: Add participant pairing buttons and organizer pairing overrides.

### Checkpoint: Pairing
- Consent, one-open-request limits, and reciprocal exclusions survive restart.
- The organizer can review and change pairs without seeing assignment mappings.
- Assignment remains blocked until all pending requests are accepted, declined,
  or canceled by their requester.

### Phase 3: Draw, Receipt, and Cleanup
- Task 12: Retain and deliver a durable draw with same-draw retries and legacy
  migration.
- Task 13: Add the assignment button, receipt confirmations, and guarded cleanup.
- Task 14: Add complete replacement draws and reject stale callbacks.

### Checkpoint: Exchange Lifecycle
- Partial delivery always retries the same assignments, including after restart.
- Every participant receives only their own current assignment.
- A replacement draw reaches everyone as one generation and clears old receipts.
- Cleanup is available only after everyone confirms the current draw.

### Phase 4: Verification and Handoff
- Task 15: Update the offline simulator and operating documentation, then
  verify the full flow with a separate Telegram test bot.

### Phase 5: Pre-draw Name Editing
- Task 16: Add an authorized, persisted display-name update to the exchange
  service.
- Task 17: Add private name-edit controls for participants and the organizer.
- Task 18: Document participant and organizer name-edit actions in the README.

### Checkpoint: Name Editing
- Participants can privately edit their own names and the organizer can edit
  any participant's name while registration is open.
- Name edits persist across restart, preserve participant identity and pairs,
  reject invalid or post-draw edits, and report persistence failures.
- The README describes both name-edit paths and their pre-draw availability.

### Phase 6: Pair Meaning
- Task 19: Explain to participants that a pair only excludes them from drawing
  each other.

### Phase 7: Late Enrollment and Complete Replacement

**Status:** Approved.

**Overview:** Allow the organizer to reopen registration after a fully
delivered draw so a missed friend can join privately with a fresh invite code
for that window. Retain the prior draw until the organizer explicitly confirms
a complete replacement for the expanded roster. Previously assigned
participants receive a replacement notice; late joiners receive normal
first-assignment wording.

**Architecture decisions:**
- Reuse the encrypted `ExchangeState` snapshot and append a
  `LATE_REGISTRATION_OPEN` phase without renumbering existing serialized phases.
  Generate a fresh invite code when the organizer opens late registration, and
  persist it with the prior draw, delivery state, and confirmations while late
  participants are added. The current implementation does not retain the
  initial code after the first draw, so late windows must not promise to reuse
  it.
- Reopen late enrollment through `PendingDrawService` while holding the
  existing draw lock, then persist the phase and invite through
  `ExchangeService`. If persistence fails, close the in-memory invite and leave
  the completed draw unchanged.
- In the late-registration phase, the retained draw assignments cover the
  original roster, which is a subset of the expanded participant roster. Do
  not deliver or expose any assignment to a late participant until the full
  replacement is persisted.
- Persist which recipients are receiving their first assignment in the
  replacement generation so private delivery wording remains correct across
  retries and restarts. Read existing version-1 encrypted states with empty
  first-assignment metadata.
- Reuse the existing assignment generator, explicit replacement confirmation,
  per-recipient private delivery, and same-generation retry behavior. Preserve
  accepted pairs; do not add a dependency or external service.

**Dependency graph:**

```text
Task 14 complete replacement draws + Task 19 pair semantics
  -> 20 persisted late-registration phase
  -> 21 organizer-controlled late enrollment
  -> 22 replacement draw for the expanded roster
  -> 23 guard the legacy /run command
  -> 24 organizer buttons and localized flow
  -> 25 simulator and operating documentation
```

**Task List:**
- Task 20: Model and persist late registration while retaining the prior draw.
- Task 21: Reopen enrollment with a fresh invite and register late
  participants.
- Task 22: Replace the retained draw for the expanded roster and retry that
  generation safely.
- Task 23: Prevent `/run` from bypassing the explicit replacement confirmation.
- Task 24: Add localized organizer controls for late enrollment and redraw.
- Task 25: Verify the late-join flow in the offline simulator and document it.

**Checkpoints:**
- After Tasks 20-21: encrypted state round-trips; opening and joining preserve
  the old draw; prior delivery and confirmations remain valid.
- After Tasks 22-24: an organizer-confirmed replacement includes late
  participants, sends each recipient only their own assignment, and resumes the
  same generation after failure; no command or stale callback bypasses
  confirmation.
- After Task 25: simulator and README cover the complete flow; the full Maven
  suite and package build pass.

**Risks and mitigations:**
| Risk | Impact | Mitigation |
|---|---|---|
| Old assignments no longer cover the expanded roster during late enrollment | High | Add phase-specific state validation; allow the retained draw to reference only its original participants, and test encrypted restart recovery. |
| Anyone with the fresh late-window code can join before the organizer replaces the draw | Medium | Require an explicit organizer action to open the window, send the code only in the organizer's private chat, persist it across restarts, and invalidate it when the replacement is confirmed. |
| A late joiner is told their first draw replaces a result they never received | Medium | Send replacement wording to previously assigned participants and first-assignment wording to late joiners. |
| A failed replacement could mix assignments from two generations | High | Persist the complete replacement before sending and retry only its undelivered recipients. |

**Open Questions:** None; the late-enrollment, confirmation, and message
behavior is specified.

### Checkpoint: Complete
- All approved specification criteria have tests or a documented manual check.
- `mvn clean test` and `mvn clean package` pass on Java 27.
- The simulator works without Telegram access; a test-bot walkthrough verifies
  private Telegram delivery before a real exchange.
- Documentation describes persistent-state backup, key retention, migration,
  reset, redraw, confirmation, and cleanup.

## Risks and Mitigations
| Risk | Impact | Mitigation |
|---|---|---|
| Snapshot corruption or a lost/wrong key blocks access to the open exchange | High | Authenticate encrypted state, validate every decoded field, fail explicitly, preserve old files on failed replacement, and document key/volume backups. |
| Existing `pending-draw.enc` is partially delivered at upgrade time | High | Add tested, idempotent migration that persists the new snapshot before removing the legacy file; resume the same recipients. |
| Legacy pre-draw roster exists only in memory | High | It cannot be migrated. Before deploying, ensure the old bot has no active pre-draw exchange; communicate this limitation. |
| Telegram send succeeds but progress write fails | High | Retry only the persisted current generation; duplicate delivery may occur, but no replacement assignment is generated. |
| Stale pairing or receipt callback is replayed | High | Use flow tokens for pairing and draw-generation identifiers for receipt callbacks; revalidate authorization and state on every callback. |
| UI or logs leak the assignment map | High | Keep mapping out of organizer-facing status and log fields; test captured messages/log output using fake transport. |
| Existing uncommitted simulator work conflicts with changes | Medium | Leave it untouched until Task 15, inspect and integrate its changes, and never discard it as cleanup. |
| Telegram test-bot credentials are unavailable | Medium | Keep all domain and simulator checks offline; perform live transport verification only with a separate test bot configured locally. |
| Organizer roster contains duplicate display names | Low | Keep participant IDs as callback targets and disambiguate duplicate labels in the selection keyboard. |

## Open Questions
- No product-flow questions remain; assignment is blocked until every open pair
  request is resolved.
- A separate Telegram test bot must be available locally for Task 15; never use
  production credentials for verification.
- Production hosting and dependency-audit method remain launch decisions and are
  outside this implementation plan.
- Name editing is restricted to the pre-draw registration phase; no new
  dependencies or profile fields are required.

## Verification Commands
Run from the directory containing `pom.xml`, using JDK 27:

```powershell
mvn clean test
mvn clean package
docker build --target build --tag secret-santa-bot:offline-tests .
docker run --rm -it --entrypoint java secret-santa-bot:offline-tests -cp "/workspace/target/classes:/workspace/target/dependency/*" com.secretsanta.OfflineBotSimulator
```

## Approval Gate
Review and approve this plan before implementation starts. Any change to pairing,
assignment, privacy, reset, redraw, retention, or cleanup behavior requires
updating the approved specification first.
