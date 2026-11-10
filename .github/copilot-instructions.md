# Project Guidelines

## Context
- This is a single-friend-group Secret Santa Telegram bot. The approved intent is
  in [docs/intent/secret-santa-telegram-bot.md](../docs/intent/secret-santa-telegram-bot.md).
- The product requirements and unresolved choices are in
  [docs/SPEC-secret-santa-telegram-bot.md](../docs/SPEC-secret-santa-telegram-bot.md).
- The implementation sequence and acceptance checks are in
  [tasks/plan.md](../tasks/plan.md) and [tasks/todo.md](../tasks/todo.md).
- The intended runtime target is Java 27. The current Maven compiler configuration
  still targets Java 17; do not assume Java 27 setup is complete.
- The approved target layout is `src/main/java` and `src/test/java`, with JUnit
  Jupiter and Maven Exec Plugin.

## Working in This Repository
- Read the relevant spec and task before implementation. Treat unresolved spec
  questions as decision gates; do not silently choose behavior or architecture.
- Preserve existing local edits. Inspect `git status` before editing, and do not
  revert changes you did not make.
- Move the root-level bot into the approved Maven source layout as specified.
- Ask before adding dependencies other than the approved JUnit Jupiter and Maven
  Exec Plugin, adding CI configuration, or changing the approved storage policies.
- Keep participant assignments private: never put them in shared-chat messages or
  logs. Do not hard-code or commit Telegram credentials.
- Surface Telegram and other operational failures explicitly; do not report a
  successful draw when delivery is incomplete.
- Keep registrations in memory; persist the organizer ID locally; encrypt pending
  assignments with AES-256-GCM and retain the same draw for retries until all
  messages are delivered.
- Add focused tests for behavior changes and run the relevant checks.

## Build and Test
After Java 27 build tooling is configured, use:
- Tests: `mvn test`
- Full build: `mvn clean package`

Use the approved JUnit Jupiter and Maven Exec Plugin configuration recorded in
the spec. These are not configured in the project yet.
