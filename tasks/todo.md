# Secret Santa Bot Implementation Tasks

Tasks are ordered by dependency. Do not start implementation tasks until this plan
and the resolved spec decisions have been reviewed and approved.

## Task 1: Resolving product and architecture decisions

**Description:** Record the decisions for organizer authority, participant
enrollment, partner pairing, persistence and retention, delivery recovery, test
tooling, and project layout in the spec and obtain approval before implementation.

**Acceptance criteria:**
- [x] Decisions are recorded in `docs/SPEC-secret-santa-telegram-bot.md`.
- [x] Human explicitly approves the updated spec.

**Status:** Complete.

**Verification:**
- [x] Human reviewed and explicitly approved the updated spec.

**Dependencies:** None

**Files likely touched:**
- `docs/SPEC-secret-santa-telegram-bot.md`

**Estimated scope:** Small

## Task 2: Establishing Java 27 build and test tooling

**Status:** Complete; Telegram network startup will be verified with a test bot
in Task 7.

**Description:** Configure the project to compile on Java 27, add the approved
JUnit Jupiter test setup, move code into the standard Maven source layout, and
provide a safe Maven Exec Plugin launch using external credentials.

**Acceptance criteria:**
- [x] Maven compiler configuration targets Java 27 and all current dependencies
  compile successfully with the selected JDK.
- [x] JUnit Jupiter runs through Maven, and a minimal test proves the setup is
  active.
- [x] A documented run command reads the bot token and username from the agreed
  environment configuration, along with the setup code and encryption key, and
  has no hard-coded real credentials.

**Verification:**
- [x] `mvn clean package` passes on Java 27.
- [x] `mvn exec:java` resolves the configured entry point and fails fast without
  required configuration. A live Telegram connection is deferred to Task 7,
  which requires a test bot credential configured locally.

**Dependencies:** Task 1

**Files likely touched:**
- `pom.xml`
- `.gitignore` or configuration example, if needed
- `README.md` or run documentation
- Minimal test source

**Estimated scope:** Medium

## Task 3: Implementing and testing assignment generation

**Description:** Extract assignment generation from Telegram handling and produce
a complete one-to-one draw that excludes self-assignments and partner pairs.

**Acceptance criteria:**
- [x] Every generated result includes each registered participant exactly once as
  giver and exactly once as recipient.
- [x] No generated result assigns a participant to themselves or their partner.
- [x] Impossible configurations fail explicitly without returning a partial draw.

**Verification:**
- [x] Focused assignment tests pass, including small valid cases and impossible
  partner configurations.
- [x] `mvn test` and `mvn package` pass.

**Dependencies:** Tasks 1 and 2

**Files likely touched:**
- Assignment generator source
- Participant or assignment model source
- Assignment generator tests

**Estimated scope:** Medium

## Checkpoint: After Tasks 1-3
- [ ] Resolved spec decisions are approved.
- [x] Java 27 compilation and tests pass.
- [x] Assignment constraints and impossible cases are covered by tests.
- [ ] Human reviews the foundation before participant-flow work proceeds.

## Task 4: Implementing organizer claim and exchange setup

**Description:** Let the organizer claim control with the one-time setup code,
persist the organizer ID, open an exchange, and receive a generated invite code
to share with participants.

**Acceptance criteria:**
- [ ] Only the first valid claim can establish the organizer; the organizer ID
  persists across restart and unauthorized organizer commands are rejected.
- [ ] The organizer can open registration and receive a generated event invite
  code privately.
- [ ] A pre-draw restart loses the active roster and requires a newly opened
  registration and invite code.

**Verification:**
- [ ] Tests cover claim-code validation, organizer authorization, and restart.
- [ ] Tests cover opening registration and invite-code behavior.
- [ ] `mvn test` and `mvn package` pass.

**Dependencies:** Tasks 1-3

**Files likely touched:**
- Organizer/configuration source
- Exchange lifecycle source
- Local organizer state source
- Telegram command handling
- Organizer and lifecycle tests

**Estimated scope:** Medium

## Task 5: Implementing participant enrollment and partner pairing

**Description:** Let participants join the currently open exchange with its
invite code, register their display name, and let the organizer create partner
pairs from the roster.

**Acceptance criteria:**
- [ ] Only users with the current invite code can join; repeated registration by
  one Telegram user does not create duplicates.
- [ ] Only the organizer can inspect the roster or manage pairs.
- [ ] Pairing one participant with another blocks both directions; selecting an
  already paired participant replaces the previous pair and notifies the
  organizer.

**Verification:**
- [ ] Tests cover valid/invalid invite codes, duplicate joins, access control,
  reciprocal partner restrictions, and pair replacement.
- [ ] `mvn test` and `mvn package` pass.

**Dependencies:** Task 4

**Files likely touched:**
- Participant/registration source
- Partner-pair source
- Telegram command handling
- Registration and pairing tests

**Estimated scope:** Medium

## Task 6: Delivering assignments privately and reporting failures

**Description:** Send each validated assignment only to the corresponding
participant's private chat, persist the pending draw encrypted, and apply the
approved retry policy when Telegram delivery fails.

**Acceptance criteria:**
- [ ] No result is sent until the complete assignment satisfies all constraints.
- [ ] Each participant receives only their own recipient; assignment details are
  absent from group messages and logs.
- [ ] The draw is encrypted with AES-256-GCM before sending, can be recovered
  after restart, and is deleted only after all messages succeed.
- [ ] Failed sends can be retried with the original recipient; no replacement
  draw is allowed after any assignment may have been sent.
- [ ] Delivery success is reported only when all required sends succeed.

**Verification:**
- [ ] Tests use a controllable Telegram sender to verify private destinations,
  message contents, partial-delivery recovery, encryption-key failures, and
  deletion after success.
- [ ] `mvn test` and `mvn package` pass.

**Dependencies:** Tasks 3-5

**Files likely touched:**
- Assignment delivery source
- Telegram send/error handling
- Exchange draw state source
- Delivery tests

**Estimated scope:** Medium

## Checkpoint: After Tasks 4-6
- [ ] Registration and partner constraints are integrated with organizer controls.
- [ ] Invalid draws send no assignments.
- [ ] Delivery failures are explicit; the same encrypted draw is recoverable and
  never silently replaced.
- [ ] Human reviews the complete core flow before final handoff.

## Task 7: Verifying the end-to-end flow and documenting operation

**Description:** Run the bot against a test Telegram bot, verify the approved
participant-to-draw flow, and document setup, commands, privacy limits, and data
cleanup.

**Acceptance criteria:**
- [ ] Test-bot verification covers participant registration, partner exclusions,
  organizer draw, private delivery, and the agreed failure/restart cases.
- [ ] Documentation explains Java 27 setup, required external configuration,
  Maven build/test/run commands, organizer usage, and data retention/deletion.
- [ ] No real bot credentials or assignment details are committed or written to
  logs.

**Verification:**
- [ ] `mvn clean test` and `mvn clean package` pass on Java 27.
- [ ] Complete test-bot walkthrough succeeds and any limitations are documented.

**Dependencies:** Tasks 2-6

**Files likely touched:**
- `README.md` or operating documentation
- Existing specification, if implementation decisions changed
- Minimal configuration example, if approved

**Estimated scope:** Small

## Checkpoint: Complete
- [ ] All approved spec success criteria are met.
- [ ] Automated tests and Java 27 build pass.
- [ ] Test-bot walkthrough is complete.
- [ ] Human approves the implementation handoff.
