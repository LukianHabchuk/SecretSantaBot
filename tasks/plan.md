# Implementation Plan: Secret Santa Telegram Bot

## Overview
Turn the existing root-level Java Telegram bot into a reliable, single-group
Secret Santa bot. The organizer claims control, opens registration and shares a
generated invite code; participants join in private chats. The organizer pairs
participants and starts the draw, and each participant privately receives exactly
one assignment. Product and technical decisions are recorded in the updated spec;
implementation is gated on human approval of that update.

## Existing Project Baseline
- Java source is currently at the repository root in `SecretSantaBot.java`.
- `pom.xml` targets Java 17 and includes Telegram Bots 6.9.7.1 and SLF4J Simple
  2.0.9.
- The current bot keeps participants in memory, uses a shared couple ID, and has
  `/register`, `/list`, and `/run` commands.
- No test framework or persistent store is configured.
- `SecretSantaBot.java` and `pom.xml` already have uncommitted changes. Preserve
  and integrate those changes; do not revert them.

## Architecture Decisions
- Target Java 27 as specified. Validate the installed JDK, Maven compiler support,
  and existing dependency compatibility before building on that target.
- Keep Telegram private messages as the participant interaction and assignment
  delivery channel.
- Organizer claims control with a one-time setup code. Persist only the
  organizer's Telegram user ID locally.
- Organizer opens registration and shares a generated invite code. Keep the
  roster and open-registration state in memory; a restart before the draw loses
  both.
- Organizer pairs registered participants. Setting a new pair replaces any
  existing pair for either selected participant and notifies the organizer.
- Persist a pending draw locally with AES-256-GCM and a stable external key.
  Retry the same assignments after delivery failures or restart; never redraw
  after any assignment may have been sent. Delete the pending draw when all
  deliveries succeed.
- Use JUnit Jupiter, the standard Maven source layout, and Maven Exec Plugin.
- Keep assignment generation separate from Telegram update handling so its
  constraints can be tested without network access.
- Provide bot credentials and the encryption key through environment variables;
  never add them to source.

## Dependency Graph

```text
Approve updated spec
  └── Java 27 build, test, Maven layout, and run setup
        ├── Assignment generator and automated tests
        └── Organizer claim and exchange opening
              └── Participant invite registration and partner pairing
                    └── Encrypted pending draw, private delivery, and retry
                          └── End-to-end verification and operating documentation
```

## Task List

### Phase 1: Decisions and Foundation
1. Approve the resolved decisions in the updated spec.
2. Establish the Java 27 build, test, and run foundation.
3. Implement and test valid one-to-one assignment generation.

### Checkpoint: Foundation
- Java 27 build and test commands run successfully.
- Assignment tests cover all constraints and impossible configurations.
- Human review confirms the resolved choices recorded in the spec.

### Phase 2: Participant and Organizer Flows
4. Implement organizer claim and exchange setup.
5. Implement participant enrollment and partner pairing.
6. Deliver assignments privately and handle delivery failures accurately.

### Checkpoint: Core Features
- Registration, organizer control, and draw transitions work together.
- No assignment is sent for an invalid draw.
- Successful delivery sends exactly one private assignment to each participant;
  failures are visible and never reported as complete success.
- Human review confirms the implementation still matches the approved spec.

### Phase 3: Integration and Handoff
7. Verify the complete Telegram flow and document setup and operation.

### Checkpoint: Complete
- Automated tests and `mvn clean package` pass on Java 27.
- Test-bot flow verifies registration through assignment delivery.
- Setup instructions require no committed secrets and define data cleanup.
- All success criteria in the approved spec are met.

## Risks and Mitigations
| Risk | Impact | Mitigation |
|---|---|---|
| Java 27 toolchain or an existing library is incompatible | High | Confirm JDK and Maven compiler support and compile a minimal baseline before feature work. |
| Random search may fail to find an existing valid assignment | High | Use a constraint-aware complete assignment algorithm and test valid and impossible configurations. |
| A restart before the draw loses the in-memory roster | Medium | Tell the organizer to reopen registration and share a new invite code; do not imply roster recovery. |
| Telegram send failures can leave some participants informed and others not | High | Save the validated draw encrypted before sending, record delivery progress, retry the same recipients, and report partial delivery accurately. |
| Encryption key loss makes a pending draw unrecoverable | High | Require a stable externally supplied key and document secure backup; fail explicitly if the key is missing or invalid. |
| Unauthorized users claim control or access organizer actions | High | Require the configured one-time claim code and enforce the persisted organizer identity on organizer-only commands. |
| Relationship data or assignment details leak through logs or shared chats | High | Keep the roster in memory, encrypt pending assignments, keep assignments out of logs/shared messages, and test privacy boundaries. |
| Existing local edits overlap implementation changes | Medium | Inspect and preserve current edits in `SecretSantaBot.java` and `pom.xml`; avoid destructive git operations. |

## Decision Gate
The user has selected the decisions recorded in
`docs/SPEC-secret-santa-telegram-bot.md`. The updated spec must be explicitly
approved before implementation begins. JUnit Jupiter, the Maven Exec Plugin,
standard Maven source layout, and encrypted pending-draw persistence are approved;
other dependencies, CI configuration, or changes to the chosen behavior require
approval.

## Verification Commands
- Focused tests: `mvn test`
- Full build: `mvn clean package`
- End-to-end: manually exercise the test bot using the documented run command and
  test credentials supplied outside source control.
