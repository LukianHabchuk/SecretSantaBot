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
- [x] Only unpaired participants without another open request can request or
  accept a partner; self-pairing and duplicate requests are rejected.
- [x] Acceptance creates a reciprocal pair; decline/cancel clears only the
  relevant request; accepted pairs cannot be changed by participants.
- [x] Pending requests and accepted pairs survive restart.
- [x] Assignment cannot start while any pair request remains unresolved.

**Verification:**
- [x] Domain tests cover request state transitions, conflicts, restart recovery,
  organizer changes, and invalid participant IDs.
- [x] `mvn clean package` passes; all 94 tests pass locally and in the Java 27
  Docker build.

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
- [x] Participant callbacks are private, authorized against the callback user,
  and invalidated when the request or exchange state changes.
- [x] Organizer can review roster, pair and pending-request status, then create
  or replace pairs without exposing assignment recipients.
- [x] Affected participants are notified of organizer pairing changes; messages
  and logs contain no assignment mapping.

**Verification:**
- [x] Bot tests cover request lifecycle buttons, stale callbacks, unauthorized
  callbacks, organizer overrides, private notifications, and redaction.
- [x] Full Maven suite passes (99 tests); Java 27 Docker build passes.

**Dependencies:** Task 10

**Files likely touched:**
- `src/main/java/com/secretsanta/SecretSantaBot.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium

## Task 12: Retaining and delivering durable draws

**Status:** Complete.

**Description:** Integrate assignment generation with the exchange snapshot.
Close registration, persist a complete draw before sending, track delivery, and
retry that exact draw. Migrate any existing encrypted pending draw without
discarding partially delivered assignments.

**Acceptance criteria:**
- [x] A complete valid draw is persisted before any Telegram send; invalid draws
  send nothing and leave the roster correctable.
- [x] A draw is rejected until all pair requests are accepted, declined, or
  canceled.
- [x] Each participant, including the organizer, receives only their own
  assignment; completed delivery retains the exchange and encrypted draw.
- [x] Failed delivery and restart retry only undelivered recipients using the
  same assignments.
- [x] Legacy `pending-draw.enc` is migrated idempotently; the legacy file is
  removed only after the new encrypted state is saved successfully.

**Verification:**
- [x] Service/store tests cover no partial draw, same-draw retry, restart,
  legacy migration, write failure, wrong key, and retained completed delivery.
- [x] Private delivery tests confirm participants, including the organizer,
  receive only their own assignment; logs contain no assignment mapping.
- [x] `mvn test` passes (106 tests); Java 27 Docker build passes.

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
- [x] Confirmation is recorded only for the current draw and is idempotent.
- [x] Organizer can start assignment from a private button and see delivery
  progress without seeing assignment recipients.
- [x] Organizer can see who confirmed and aggregate status, but never their
  assignment recipients.
- [x] Cleanup requires every current participant's confirmation and a separate
  organizer confirmation; it removes exchange data but preserves organizer
  identity.
- [x] Until cleanup, the exchange survives restart.

**Verification:**
- [x] Tests cover participant confirmation, duplicate and unauthorized actions,
  cleanup denial before all confirmations, confirmed deletion, and restart.
- [x] `mvn test` passes.

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
- [x] Replacement is unavailable during incomplete delivery and requires
  explicit organizer confirmation.
- [x] A full replacement is persisted before delivery, supersedes all previous
  assignments, and is retried unchanged if any send fails.
- [x] All participants receive a private replacement notification and only
  their current recipient; previous confirmations are cleared.
- [x] Stale prior-draw confirmation callbacks cannot acknowledge the new draw.

**Verification:**
- [x] Tests cover valid replacement constraints, delivery failure/restart,
  generation changes, cleared confirmations, stale callbacks, and privacy.
- [x] `mvn test` passes.

**Dependencies:** Task 13

**Files likely touched:**
- `src/main/java/com/secretsanta/PendingDrawService.java`
- `src/main/java/com/secretsanta/SecretSantaBot.java`
- `src/test/java/com/secretsanta/PendingDrawServiceTest.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium

## Task 15: Updating the simulator, documentation, and end-to-end verification

**Status:** Implemented; live test-bot verification is pending access to a
separate test bot.

**Description:** Bring the no-network simulator and operator documentation into
line with the approved private exchange flow, then verify transport with a
separate Telegram test bot.

**Acceptance criteria:**
- [x] Simulator supports private onboarding, mutual pairing, organizer draw,
  delivery retry, confirmation, redraw, and cleanup without Telegram access.
- [x] README explains the button-driven flow, persistent state, key/volume
  backup, legacy migration limits, reset, redraw, confirmations, and cleanup.
- [ ] A separate test-bot walkthrough verifies private interactions and
  delivery; no real credentials or assignments are committed or logged.

**Verification:**
- [x] `mvn clean test` and `mvn clean package` pass on Java 27; all 121 tests
  pass in the Java 27 build image.
- [x] Offline simulator walkthrough succeeds without credentials/network.
- [x] Live test-bot verification limitation is documented: no separate test-bot
  credentials were provided. README includes a safe operator walkthrough.

**Dependencies:** Tasks 9, 11, 12, 13, and 14

**Files likely touched:**
- `src/main/java/com/secretsanta/OfflineBotSimulator.java`
- `src/test/java/com/secretsanta/OfflineBotSimulatorTest.java`
- `README.md`

**Estimated scope:** Medium

## Checkpoint: Final handoff
- [x] All approved specification criteria have automated or documented manual
  verification.
- [x] Full test suite and Java 27 build pass.
- [x] Test-bot walkthrough is complete or the credential/availability blocker
  is explicitly documented.
- [ ] Human approves the implementation handoff.

## Task 16: Persisting authorized participant name edits

**Status:** Complete.

**Description:** Add a domain operation for a participant to edit their own
display name or the organizer to edit any participant's name while registration
is open. Reuse signup validation and atomically persist the changed participant
record without changing identity or pair state.

**Acceptance criteria:**
- [x] Participant can edit only their own name; the organizer can edit any
  registered participant's name.
- [x] Names use signup validation, are stored normalized, and cannot be changed
  after assignment starts.
- [x] Successful edits survive restart; failed writes leave in-memory and
  persisted state unchanged.

**Verification:**
- [x] `ExchangeServiceTest` covers permissions, invalid/closed edits,
  persistence, pair preservation, and failed writes.
- [x] `ExchangeServiceTest` passes in the Java 27 Docker build.

**Dependencies:** Task 8

**Files likely touched:**
- `src/main/java/com/secretsanta/ExchangeService.java`
- `src/test/java/com/secretsanta/ExchangeServiceTest.java`

**Estimated scope:** Small

## Task 17: Adding private name-edit controls

**Status:** Complete.

**Description:** Add a private edit-name action for registered participants and
an organizer flow to select and rename any roster participant. Use the existing
pending-input and localized button patterns; disambiguate duplicate display
names and reject stale callbacks.

**Acceptance criteria:**
- [x] A registered participant can edit their own name in private chat before
  the draw; the organizer can select and edit any participant.
- [x] Organizer target selection remains unambiguous when names are duplicated,
  and callback actions are re-authorized and bound to the current selection.
- [x] Invalid names, closed registration, and persistence failures are reported
  explicitly without claiming success.

**Verification:**
- [x] `SecretSantaBotTest` covers participant edits, organizer edits
  (including duplicate names), private-only behavior, and post-draw rejection.
- [x] Java 27 Docker build passes, including `SecretSantaBotTest` and
  `EnglishMessagesTest`.

**Dependencies:** Tasks 9 and 16

**Files likely touched:**
- `src/main/java/com/secretsanta/BotButtonController.java`
- `src/main/java/com/secretsanta/BotButtons.java`
- `src/main/java/com/secretsanta/BotMessages.java`
- `src/main/java/com/secretsanta/EnglishMessages.java`
- `src/main/java/com/secretsanta/BotLogMessages.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium (six files including focused tests)

## Task 18: Documenting pre-draw name edits

**Status:** Complete.

**Description:** Update the operator walkthrough to explain that participants
can edit their own registered names and the organizer can edit any participant's
name before the draw.

**Acceptance criteria:**
- [x] README explains where participants and organizers access name editing.
- [x] README states that edits are unavailable after the draw starts.

**Verification:**
- [x] Reviewed the README flow against the localized button labels.

**Dependencies:** Task 17

**Files likely touched:**
- `README.md`

**Estimated scope:** Small

## Task 19: Clarifying participant pair notifications

**Status:** Complete.

**Description:** Explain in pair confirmations, organizer change notices, and
pair status messages that pairing only prevents participants from being
assigned to each other in the draw.

**Acceptance criteria:**
- [x] English and Ukrainian participant messages clarify that a pair excludes
  each participant from drawing the other and does not assign them to each
  other.
- [x] The README and product specification describe the same pair semantics.

**Verification:**
- [x] `SecretSantaBotTest` covers accepted pairs, organizer changes, pair
  status, and both languages.
- [x] Java 27 Docker build passes.

**Dependencies:** Task 11

**Files likely touched:**
- `src/main/java/com/secretsanta/BotMessages.java`
- `src/main/java/com/secretsanta/EnglishMessages.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`
- `README.md`
- `docs/SPEC-secret-santa-telegram-bot.md`

**Estimated scope:** Small

## Checkpoint: Name Editing
- [x] Domain and private UI tests pass.
- [x] Name edits preserve participant identities/pairings and are blocked after
  draw start.
- [x] README documents the name-edit flow.
- [x] Full Maven test suite passes in the Java 27 Docker build.

## Phase 7: Late Enrollment and Complete Replacement

The approved specification adds organizer-controlled late enrollment after a
fully delivered draw. The organizer reuses the current invite code, then
explicitly confirms a complete replacement draw for the expanded roster.
Existing participants receive replacement notices; late joiners receive
first-assignment notices. Do not start these tasks until the Phase 7 plan
extension in `tasks/plan.md` is approved.

## Task 20: Modeling late registration while retaining the prior draw

**Description:** Add a persisted late-registration phase that keeps the current
invite code, original assignments, delivery progress, and confirmations valid
while the participant roster expands. Append the new phase to preserve existing
encrypted phase ordinals.

**Acceptance criteria:**
- [ ] Late-registration state retains a fully delivered prior draw and can
  include participants who are not part of that draw.
- [ ] State validation rejects assignments, deliveries, or confirmations that
  reference unknown users and preserves the existing self/pair constraints.
- [ ] Encrypted save/load round-trips the new phase without changing the
  interpretation of existing phases.

**Verification:**
- [ ] `mvn -Dtest=ExchangeStateTest,ExchangeStoreTest test`
- [ ] Existing snapshots and normal draw states remain readable in regression
  tests.

**Dependencies:** Completed Task 14; proposed Phase 7 plan approval.

**Files likely touched:**
- `src/main/java/com/secretsanta/ExchangeState.java`
- `src/test/java/com/secretsanta/ExchangeStateTest.java`
- `src/test/java/com/secretsanta/ExchangeStoreTest.java`

**Estimated scope:** Small

## Task 21: Reopening enrollment with the existing invite

**Description:** Add an organizer-authorized transition to late registration
after a fully delivered draw and allow new identities to join through the
existing invite/name flow. Preserve the current draw until replacement is
confirmed.

**Acceptance criteria:**
- [ ] Only the organizer can open late enrollment, and only after all current
  assignments are delivered and before cleanup.
- [ ] The same invite code accepts valid new participants; invalid codes,
  duplicate users, and joins outside the late window are rejected.
- [ ] Existing participants, pairs, assignments, generation, delivery, and
  confirmations remain unchanged; late participants cannot confirm an old
  assignment they were never sent.

**Verification:**
- [ ] `mvn -Dtest=ExchangeServiceTest test`
- [ ] Tests cover restart recovery, authorization, duplicate/out-of-window
  joins, and retained-draw invariants.

**Dependencies:** Task 20

**Files likely touched:**
- `src/main/java/com/secretsanta/ExchangeService.java`
- `src/test/java/com/secretsanta/ExchangeServiceTest.java`

**Estimated scope:** Small

## Task 22: Replacing the draw for the expanded roster

**Description:** Extend the confirmed replacement flow to generate and persist
a full assignment set for the expanded roster, close late enrollment, and
deliver to every participant. Preserve the existing pairing constraints and
same-generation retry guarantees.

**Acceptance criteria:**
- [ ] Replacement is unavailable until prior delivery is complete and creates
  a valid one-to-one draw for every current participant.
- [ ] The complete next generation is persisted before any message; successful
  replacement closes registration and clears old confirmations.
- [ ] Existing participants receive replacement wording; late joiners receive
  first-assignment wording; each sees only their own assignment.
- [ ] Failures and restarts retry the persisted generation without mixing or
  redrawing assignments.

**Verification:**
- [ ] `mvn -Dtest=PendingDrawServiceTest test`
- [ ] Tests cover private delivery, pairing constraints, persistence failure,
  partial delivery, and restart retry with a late participant.

**Dependencies:** Task 21

**Files likely touched:**
- `src/main/java/com/secretsanta/ExchangeService.java`
- `src/main/java/com/secretsanta/PendingDrawService.java`
- `src/test/java/com/secretsanta/PendingDrawServiceTest.java`

**Estimated scope:** Medium

## Checkpoint: Late Enrollment Domain and Delivery
- [ ] Late enrollment survives encrypted restart and retains the old draw.
- [ ] Confirmed replacement includes the late participant and safely retries.
- [ ] Focused domain and delivery tests pass.
- [ ] Human reviews the domain and delivery slice before UI work.

## Task 23: Preventing `/run` from bypassing replacement confirmation

**Description:** Keep the existing `/run` command from silently replacing a
draw while late registration is open. Explain that the organizer must use the
explicitly confirmed replacement action.

**Acceptance criteria:**
- [ ] `/run` during late enrollment does not generate or send assignments.
- [ ] The organizer receives clear English/Ukrainian guidance to confirm the
  replacement from the organizer controls.
- [ ] Existing `/run` behavior for initial draws and pending-delivery retries
  is unchanged.

**Verification:**
- [ ] `mvn -Dtest=SecretSantaBotTest,EnglishMessagesTest test`
- [ ] Tests prove the command cannot bypass the confirmation gate.

**Dependencies:** Task 22

**Files likely touched:**
- `src/main/java/com/secretsanta/BotCommandController.java`
- `src/main/java/com/secretsanta/BotMessages.java`
- `src/main/java/com/secretsanta/EnglishMessages.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`
- `src/test/java/com/secretsanta/EnglishMessagesTest.java`

**Estimated scope:** Medium

## Task 24: Adding localized organizer controls for late enrollment

**Description:** Add a private organizer control to reopen enrollment after a
fully delivered draw, show the existing invite code only to the organizer, and
reuse the confirmed replacement action once late participants have joined.

**Acceptance criteria:**
- [ ] The late-enrollment action is shown only when the organizer can safely
  open it; unauthorized or stale callbacks do not change state.
- [ ] Opening it sends the existing invite code only to the organizer and does
  not automatically reroll.
- [ ] The organizer can explicitly confirm or cancel the replacement after
  late enrollment; both languages describe the same behavior.
- [ ] The participant's existing private signup flow works during late
  enrollment without reopening post-draw name editing.

**Verification:**
- [ ] `mvn -Dtest=SecretSantaBotTest,EnglishMessagesTest test`
- [ ] Bot tests cover organizer authorization, private invite delivery,
  registration timing, cancellation, and absence of an automatic reroll.

**Dependencies:** Tasks 21, 22, and 23

**Files likely touched:**
- `src/main/java/com/secretsanta/BotButtons.java`
- `src/main/java/com/secretsanta/BotButtonController.java`
- `src/main/java/com/secretsanta/BotMessages.java`
- `src/main/java/com/secretsanta/EnglishMessages.java`
- `src/test/java/com/secretsanta/SecretSantaBotTest.java`

**Estimated scope:** Medium

## Checkpoint: Organizer Flow
- [ ] Late enrollment and explicit replacement are available through private
  organizer buttons in both languages.
- [ ] Unauthorized, stale, and premature actions leave state unchanged.
- [ ] Human reviews the organizer flow before final documentation.

## Task 25: Verifying and documenting late enrollment

**Description:** Extend the offline simulator walkthrough and README to cover
late registration, the replacement confirmation, and the distinct assignment
messages for existing participants and the late joiner.

**Acceptance criteria:**
- [ ] The simulator demonstrates a late participant joining by the existing
  invite and a complete replacement delivered to everyone.
- [ ] README explains the eligibility, invite-code window, explicit reroll,
  replacement impact, and restart-safe delivery.

**Verification:**
- [ ] `mvn -Dtest=OfflineBotSimulatorTest test`
- [ ] `mvn clean test`
- [ ] `mvn clean package` on JDK 27.

**Dependencies:** Task 24

**Files likely touched:**
- `src/main/java/com/secretsanta/OfflineBotSimulator.java`
- `src/test/java/com/secretsanta/OfflineBotSimulatorTest.java`
- `README.md`

**Estimated scope:** Small

## Checkpoint: Late Enrollment Complete
- [ ] All Phase 7 acceptance criteria pass.
- [ ] Offline simulator and README reflect the approved specification.
- [ ] Full Maven tests and package build pass on JDK 27.
- [ ] Human approves the implementation handoff.
