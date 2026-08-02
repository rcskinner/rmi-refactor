# Architecture notes

## Status

The repository currently contains the intentionally flawed Java 17/Maven
baseline described in `plan-files/01-monolith-rmi`. It uses Java RMI and an
in-memory database stand-in. The concurrency limitation in that baseline is
documented in `baseline-concurrency.md`.

## Decisions to make

- Refactored language and runtime boundaries
- Dependency and package policy
- Application or library boundaries
- Public API and compatibility requirements
- Configuration and environment handling
- Logging, error handling, and observability
- Test strategy and CI checks
- Deployment and release process

## Decision record

When a decision is made, add a dated entry with the context, decision, and
consequences. Keep this document concise and link to deeper design documents
from here.
