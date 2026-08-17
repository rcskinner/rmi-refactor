# Droid development guide

## Repository context

This is a new, language-neutral repository for an RMI refactor. Confirm the
runtime and package manager before adding implementation code or dependencies.

## Working rules

- Read `README.md` and the relevant files in `docs/` before making changes.
- Prefer small, focused changes that preserve existing behavior.
- With Maven, keep production code in `src/main/` and automated tests in
  `src/test/`. Keep test strategy and supporting documentation in `tests/`.
- Add or update tests for every behavior change.
- Do not commit secrets, generated artifacts, local environment files, or build
  output.
- Do not rewrite or discard existing user changes.
- Before finishing, run the narrowest applicable formatter, linter, type check,
  and test command. If the project has no tooling yet, say so explicitly.
- Review `git diff` and `git status` before handing work back.
- Java formatting is checked with `mvn spotless:check`; use
  `mvn spotless:apply` to format sources. The committed pre-commit
  configuration runs the formatter check and unit tests.

## Task workflow

For each task:

1. Inspect the repository and identify the smallest relevant change.
2. State assumptions when requirements or technology choices are ambiguous.
3. Implement the change with matching tests and documentation.
4. Validate locally and report commands and results.

## Local observability workflow

When validating metrics, traces, logs, or Grafana dashboards on Windows, use
the master scripts rather than starting individual processes:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local.ps1 `
  -GenerateLoad -DurationSeconds 120 -RequestsPerSecond 3 -Workers 2
```

The load generator is intentionally bounded. Confirm that the Java server is
still listening on ports `1099` and `8081` before diagnosing an empty dashboard.
Verify the Grafana dashboard at
`http://localhost:3000/d/rmi-refactor-overview/rmi-refactor-deployment-overview`,
then stop the environment with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-local.ps1
```

For trace-specific checks, use the Tempo datasource in Grafana Explore and
look for `ledger-loadgen` within the active time range. For failure-path
checks, run the load generator with `-Mode failure`; this exercises the
error-rate, top-erroring-resources, and failed-traced-operations panels.

## Suggested Droid roles

- **Explorer:** map the codebase, dependencies, and likely change points without
  editing files.
- **Implementer:** make one bounded change and add focused tests.
- **Reviewer:** inspect the diff for correctness, regressions, security issues,
  and missing tests.
- **Test validator:** run the relevant checks and investigate failures.

Do not have multiple Droids edit the same files concurrently. Parallelize only
independent read-only investigation or validation tasks.
