# Droid development guide

## Repository context

This is a new, language-neutral repository for an RMI refactor. Confirm the
runtime and package manager before adding implementation code or dependencies.

## Working rules

- Read `README.md` and the relevant files in `docs/` before making changes.
- Prefer small, focused changes that preserve existing behavior.
- Keep production code in `src/` and automated tests in `tests/`.
- Add or update tests for every behavior change.
- Do not commit secrets, generated artifacts, local environment files, or build
  output.
- Do not rewrite or discard existing user changes.
- Before finishing, run the narrowest applicable formatter, linter, type check,
  and test command. If the project has no tooling yet, say so explicitly.
- Review `git diff` and `git status` before handing work back.

## Task workflow

For each task:

1. Inspect the repository and identify the smallest relevant change.
2. State assumptions when requirements or technology choices are ambiguous.
3. Implement the change with matching tests and documentation.
4. Validate locally and report commands and results.

## Suggested Droid roles

- **Explorer:** map the codebase, dependencies, and likely change points without
  editing files.
- **Implementer:** make one bounded change and add focused tests.
- **Reviewer:** inspect the diff for correctness, regressions, security issues,
  and missing tests.
- **Test validator:** run the relevant checks and investigate failures.

Do not have multiple Droids edit the same files concurrently. Parallelize only
independent read-only investigation or validation tasks.
