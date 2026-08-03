# Contributing

## Before coding

- Confirm the task scope and acceptance criteria.
- Check `git status` and preserve unrelated work.
- Record architectural decisions in `docs/architecture.md`.

## Pull request checklist

- [ ] Source changes are under `src/`.
- [ ] Tests cover changed behavior under `tests/`.
- [ ] Documentation and examples are updated when needed.
- [ ] Formatting, linting, type checks, and tests pass.
- [ ] `mvn spotless:check` passes (or `mvn spotless:apply` was run first).
- [ ] `pre-commit run --all-files` passes.
- [ ] No secrets or generated files are included.
- [ ] The final diff contains only task-related changes.
