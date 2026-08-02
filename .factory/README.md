# rmi-refactor

Project workspace for the RMI refactor.

## Repository layout

```text
.
├── docs/                 Design notes and decision records
├── scripts/              Repeatable local development utilities
├── src/                  Application and library source code
├── tests/                Automated tests
├── AGENTS.md             Instructions for Droid-assisted development
├── CONTRIBUTING.md       Contribution and review workflow
└── .gitignore
```

## Getting started

The runtime and package manager have not been selected yet. Before implementing
features, record that choice in `docs/architecture.md`, then add the relevant
dependency manifest and commands to this README.

Recommended first steps:

1. Define the language, runtime, and package manager.
2. Add the minimal build, test, and lint commands.
3. Create the first vertical slice under `src/`.
4. Add tests under `tests/` for the public behavior.

## Droid workflow

Use the instructions in `AGENTS.md` when asking a Droid to explore or modify
this repository. Keep tasks narrow, require tests for behavior changes, and
review the diff before committing.
