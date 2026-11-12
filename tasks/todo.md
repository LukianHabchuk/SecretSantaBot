# Secret Santa Bot Implementation Tasks

Tasks 1-6 below record the completed technical foundation. The approved revised
specification supersedes the earlier in-memory pre-draw behavior. Tasks 7-15
implement the revised private, durable exchange and must not start until this
plan is approved.

## Completed Baseline
- [x] Task 1: Resolving product and architecture decisions for the original
  single-group bot.
- [x] Task 2: Establishing Java 27 build and JUnit tooling.
- [x] Task 3: Implementing and testing one-to-one assignment generation.
- [x] Task 4: Implementing organizer claim and exchange setup.
- [x] Task 5: Implementing participant enrollment and organizer-managed pairs.
- [x] Task 6: Delivering private assignments with encrypted pending-draw retry.

## Task 7: Creating an encrypted durable exchange snapshot

**Status:** Complete.

**Description:** Add a versioned, validated snapshot for all active exchange
state. Encrypt it with AES-256-GCM and persist it atomically under the existing
data directory, reusing current locking and file-replacement patterns.

**Acceptance criteria:**
- [x] Snapshot represents registration/invite state, participant identities and
  names, accepted pairs, pending requests, draw generation, assignments,
  per-recipient delivery, and current-generation confirmations.
- [x] Save/load uses a fresh nonce and authenticated encryption; invalid,
  truncated, oversized, or wrong-key state fails explicitly.
- [x] Atomic-write failure preserves the previous readable snapshot.

**Verification:**
- [x] Focused store tests cover round-trip, encryption, tampering, malformed
  state, fresh nonces, and failed atomic replacement.
- [x] Full test suite passes; Java 27 Docker build runs tests and packages.

**Dependencies:** Completed Tasks 1-6

**Files likely touched:**
- `src/main/java/com/secretsanta/ExchangeStore.java` (new)
- `src/main/java/com/secretsanta/ExchangeState.java` (new)
- `src/test/java/com/secretsanta/ExchangeStoreTest.java` (new)

**Estimated scope:** Medium

## Task 8: Persisting exchange lifecycle and organizer enrollment

**Status:** Complete.

**Description:** Connect organizer and exchange services to the durable snapshot.
Persist invites, registration, roster, and pair state; automatically enroll the
organizer using their Telegram identity; allow a confirmed reset only before a
draw begins.

**Acceptance criteria:**
- [x] Active exchange fields reload consistently after restart, including its
  invite code and organizer participant.
- [x] Organizer enrollment is unique and persists the Telegram user ID and
  confirmed display name.
- [x] A confirmed pre-draw reset clears exchange data but retains organizer
  identity; reset is rejected after draw starts.

**Verification:**
- [x] Service tests cover restart recovery, organizer auto-enrollment,
  duplicate enrollment, reset confirmation/state restrictions, and write
  failures.
- [x] `mvn test` passes; the Docker Java 27 build also runs the complete suite.

**Dependencies:** Task 7

**Files likely touched:**
- `src/main/java/com/secretsanta/ExchangeService.java`
- `src/main/java/com/secretsanta/OrganizerService.java`
- `src/main/java/com/secretsanta/ExchangeStore.java`
- `src/test/java/com/secretsanta/ExchangeServiceTest.java`
- `src/test/java/com/secretsanta/OrganizerServiceTest.java`

**Estimated scope:** Medium

## Task 9: Adding private button-driven onboarding

**Status:** Complete.

**Description:** Replace routine command-based onboarding with private prompts
and buttons for organizer claim/open, participant invite entry, and name
confirmation/editing. Keep text input only for codes and edited names.

**Acceptance criteria:**
- [x] `/start` presents a private button-oriented flow; organizer claim and
  exchange opening are available only in the organizer's private chat.
- [x] Organizer is included automatically; invite code is sent only to them.
- [x] Organizer can request and confirm a pre-draw reset; reset is rejected
  after assignment starts.
- [x] A participant can enter the valid invite, confirm/edit their Telegram first
  name, and join once; registration closes at draw start.
- [x] Unauthorized, group-chat, invalid, duplicate, or closed-registration
  interactions are rejected without changing exchange state.

**Verification:**
- [x] Bot tests exercise callback and text flows with fake Telegram users and
  assert private delivery and organizer authorization.
- [x] The complete Maven test suite and Java 27 Docker build pass.

**Dependencies:** Task 8

**Files likely touched:**
- `src/main/java/com/secretsanta/SecretSantaBot.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium

## Checkpoint: After Tasks 7-9
- [x] Encrypted state survives restart, and organizer/participant onboarding
  works end-to-end with a fake messenger.
- [x] Pre-draw reset is confirmed and cannot clear an in-progress draw.
- [ ] Human reviews the durable onboarding slice before pairing work.

## Task 10: Implementing mutual participant pair consent

**Description:** Add pending pair requests with accept, decline, and requester
cancel actions. Persist requests and enforce reciprocal, one-partner exclusions.

**Acceptance criteria:**
- [ ] Only unpaired participants without another open request can request or
  accept a partner; self-pairing and duplicate requests are rejected.
- [ ] Acceptance creates a reciprocal pair; decline/cancel clears only the
  relevant request; accepted pairs cannot be changed by participants.
- [ ] Pending requests and accepted pairs survive restart.
- [ ] Assignment cannot start while any pair request remains unresolved.

**Verification:**
- [ ] Domain tests cover request state transitions, conflicts, restart recovery,
  organizer changes, and invalid participant IDs.
- [ ] `mvn test` passes.

**Dependencies:** Task 8

**Files likely touched:**
- `src/main/java/com/secretsanta/ExchangeService.java`
- `src/main/java/com/secretsanta/ExchangeState.java`
- `src/test/java/com/secretsanta/ExchangeServiceTest.java`
- `src/test/java/com/secretsanta/ExchangeStoreTest.java`

**Estimated scope:** Medium

## Task 11: Adding pairing buttons and organizer overrides

**Description:** Expose participant request/response actions through private
buttons, add organizer roster/request review and direct pairing overrides, and
notify affected participants privately when the organizer changes a pair.

**Acceptance criteria:**
- [ ] Participant callbacks are private, authorized against the callback user,
  and invalidated when the request or exchange state changes.
- [ ] Organizer can review roster, pair and pending-request status, then create
  or replace pairs without exposing assignment recipients.
- [ ] Affected participants are notified of organizer pairing changes; messages
  and logs contain no assignment mapping.

**Verification:**
- [ ] Bot tests cover request lifecycle buttons, stale callbacks, unauthorized
  callbacks, organizer overrides, private notifications, and redaction.
- [ ] `mvn test` passes.

**Dependencies:** Task 10

**Files likely touched:**
- `src/main/java/com/secretsanta/SecretSantaBot.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium

## Task 12: Retaining and delivering durable draws

**Description:** Integrate assignment generation with the exchange snapshot.
Close registration, persist a complete draw before sending, track delivery, and
retry that exact draw. Migrate any existing encrypted pending draw without
discarding partially delivered assignments.

**Acceptance criteria:**
- [ ] A complete valid draw is persisted before any Telegram send; invalid draws
  send nothing and leave the roster correctable.
- [ ] A draw is rejected until all pair requests are accepted, declined, or
  canceled.
- [ ] Each participant, including the organizer, receives only their own
  assignment; completed delivery retains the exchange and encrypted draw.
- [ ] Failed delivery and restart retry only undelivered recipients using the
  same assignments.
- [ ] Legacy `pending-draw.enc` is migrated idempotently; the legacy file is
  removed only after the new encrypted state is saved successfully.

**Verification:**
- [ ] Service/store tests cover no partial draw, same-draw retry, restart,
  legacy migration, write failure, wrong key, and retained completed delivery.
- [ ] Captured organizer messages and logs contain no full assignment mapping.
- [ ] `mvn test` passes.

**Dependencies:** Tasks 7, 8, and 11

**Files likely touched:**
- `src/main/java/com/secretsanta/PendingDrawService.java`
- `src/main/java/com/secretsanta/PendingDrawStore.java`
- `src/main/java/com/secretsanta/ExchangeStore.java`
- `src/test/java/com/secretsanta/PendingDrawServiceTest.java`
- `src/test/java/com/secretsanta/PendingDrawStoreTest.java`

**Estimated scope:** Medium

## Task 13: Adding draw controls, receipt confirmation, and guarded cleanup

**Description:** Add the organizer's private assignment button, participant
Received/OK action, current-draw confirmation tracking, organizer status view,
and explicit deletion after all participants confirm.

**Acceptance criteria:**
- [ ] Confirmation is recorded only for the current draw and is idempotent.
- [ ] Organizer can start assignment from a private button and see delivery
  progress without seeing assignment recipients.
- [ ] Organizer can see who confirmed and aggregate status, but never their
  assignment recipients.
- [ ] Cleanup requires every current participant's confirmation and a separate
  organizer confirmation; it removes exchange data but preserves organizer
  identity.
- [ ] Until cleanup, the exchange survives restart.

**Verification:**
- [ ] Tests cover participant confirmation, duplicate and unauthorized actions,
  cleanup denial before all confirmations, confirmed deletion, and restart.
- [ ] `mvn test` passes.

**Dependencies:** Task 12

**Files likely touched:**
- `src/main/java/com/secretsanta/PendingDrawService.java`
- `src/main/java/com/secretsanta/SecretSantaBot.java`
- `src/test/java/com/secretsanta/PendingDrawServiceTest.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium

## Task 14: Implementing complete replacement draws

**Description:** Allow the organizer to explicitly supersede a fully delivered
draw before cleanup. Generate a complete replacement for everyone, notify all
participants, and bind receipt callbacks to the new generation.

**Acceptance criteria:**
- [ ] Replacement is unavailable during incomplete delivery and requires
  explicit organizer confirmation.
- [ ] A full replacement is persisted before delivery, supersedes all previous
  assignments, and is retried unchanged if any send fails.
- [ ] All participants receive a private replacement notification and only
  their current recipient; previous confirmations are cleared.
- [ ] Stale prior-draw confirmation callbacks cannot acknowledge the new draw.

**Verification:**
- [ ] Tests cover valid replacement constraints, delivery failure/restart,
  generation changes, cleared confirmations, stale callbacks, and privacy.
- [ ] `mvn test` passes.

**Dependencies:** Task 13

**Files likely touched:**
- `src/main/java/com/secretsanta/PendingDrawService.java`
- `src/main/java/com/secretsanta/SecretSantaBot.java`
- `src/test/java/com/secretsanta/PendingDrawServiceTest.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium

## Task 15: Updating the simulator, documentation, and end-to-end verification

**Description:** Bring the no-network simulator and operator documentation into
line with the approved private exchange flow, then verify transport with a
separate Telegram test bot.

**Acceptance criteria:**
- [ ] Simulator supports private onboarding, mutual pairing, organizer draw,
  delivery retry, confirmation, redraw, and cleanup without Telegram access.
- [ ] README explains the button-driven flow, persistent state, key/volume
  backup, legacy migration limits, reset, redraw, confirmations, and cleanup.
- [ ] A separate test-bot walkthrough verifies private interactions and
  delivery; no real credentials or assignments are committed or logged.

**Verification:**
- [ ] `mvn clean test` and `mvn clean package` pass on Java 27.
- [ ] Offline simulator walkthrough succeeds without credentials/network.
- [ ] Test-bot results and any environment limitation are recorded for handoff.

**Dependencies:** Tasks 9, 11, 12, 13, and 14

**Files likely touched:**
- `src/main/java/com/secretsanta/OfflineBotSimulator.java`
- `src/test/java/com/secretsanta/OfflineBotSimulatorTest.java`
- `README.md`

**Estimated scope:** Medium

## Checkpoint: Complete
- [ ] All approved specification criteria have automated or documented manual
  verification.
- [ ] Full test suite and Java 27 build pass.
- [ ] Test-bot walkthrough is complete or the credential/availability blocker
  is explicitly documented.
- [ ] Human approves the implementation handoff.
