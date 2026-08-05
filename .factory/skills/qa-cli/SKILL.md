---
name: qa-cli
description: >
  Functional QA for the Java RMI ledger CLI, including successful commands and
  user-facing validation errors.
---

# CLI QA

Build with `mvn compile`. Start the `rmi-server` service from `services.yaml`,
then run the client with:
`java -cp "target/classes;target/dependency/*" com.example.rmirefactor.client.RmiClient`.
On Windows, classpath entries use `;`; on Linux and other Unix-like systems,
use `:` instead.

## Flow menu

1. **Contribute**: run `contribute demo-plan 10`; verify human-readable stdout
   contains the contribution confirmation.
2. **Withdraw**: run `withdraw demo-plan 1`; verify the withdrawal confirmation.
3. **Balance**: run `balance demo-plan`; verify a readable balance.
4. **Invalid amount**: run zero and negative amounts; verify a clear rejection,
   no stack trace, and no success output.
5. **Missing plan**: run a valid operation against `missing-plan`; verify a
   clear missing-plan error without a stack trace.

Capture stdout and stderr separately. JSON SLF4J diagnostics belong on stderr;
stdout must remain human-readable. Do not rely solely on PowerShell's
`$LASTEXITCODE` when stderr is non-empty.

## Known failure modes

- The server must already own ports 1099 and 8081.
- Only `demo-plan` is pre-seeded for successful operations.
- A running server conflicts with integration tests that bind port 1099.
