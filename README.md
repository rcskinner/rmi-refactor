# rmi-refactor

Project workspace for the RMI refactor.

The current implementation is the intentionally flawed baseline described in
`plan-files/01-monolith-rmi`. It uses Java RMI inside a monolith and exposes the
generic `LedgerRemote.addOrSubtract(...)` operation. The follow-up refactor is
planned separately.

## Repository layout

```text
.
├── docs/                 Design notes and decision records
├── plan-files/           Implementation plans and architecture sketches
├── src/main/             Application and library source code
├── src/test/             Automated tests
├── tests/                Test documentation
├── pom.xml               Maven build configuration
├── AGENTS.md             Instructions for Droid-assisted development
├── CONTRIBUTING.md       Contribution and review workflow
└── .gitignore
```

## Getting started

The baseline uses Java 17 and Maven.

Run the tests:

```shell
mvn test
```

Run tests and the Java linter:

```shell
mvn verify
```

Checkstyle runs during Maven's `verify` phase and checks production and test
sources for import hygiene, whitespace consistency, visibility, brace usage,
and common Java correctness issues. Its configuration is in
`config/checkstyle/checkstyle.xml`.

Spotless checks Java formatting with Google Java Format during `mvn verify`.
Run `mvn spotless:apply` to format sources locally. If pre-commit is
installed, `.pre-commit-config.yaml` runs the formatter check and unit tests
before commits. From WSL Bash, install it with
`./scripts/setup-pre-commit.sh`.

The same command enforces a 60% line-coverage minimum with JaCoCo. GitHub
Actions runs the pre-commit hooks and `mvn verify` on pushes to `main` and pull
requests, retries a failed test once, and uploads Surefire and JaCoCo reports
for diagnosis.
The build also generates Javadoc and runs SpotBugs. Dependency usage and
TODO/FIXME issue references are checked in CI, while Gitleaks scans repository
history for secrets. Dependabot checks Maven and GitHub Actions dependencies
weekly with a seven-day cooldown. CodeQL scans Java code on pull requests,
pushes to `main`, and a weekly schedule. PMD and CPD check maintainability,
complexity, and duplication during `mvn verify`. CI also rejects Java source
files over 1,200 lines. See `docs/security.md` for secret handling and
log-scrubbing guidance. `.env.example` documents the local environment
template; it currently contains no secrets.

Start the example RMI server:

```shell
mvn compile
java -cp target/classes com.example.rmirefactor.server.RmiServer
```

In a second terminal, interact with the running server:

```shell
java -cp target/classes com.example.rmirefactor.client.RmiClient balance demo-plan
java -cp target/classes com.example.rmirefactor.client.RmiClient contribute demo-plan 100.00
java -cp target/classes com.example.rmirefactor.client.RmiClient withdraw demo-plan 25.00
java -cp target/classes com.example.rmirefactor.client.RmiClient balance demo-plan
```

The client connects to `localhost:1099`, which is the registry started by
`RmiServer`.

## Droid workflow

Use the instructions in `AGENTS.md` when asking a Droid to explore or modify
this repository. Keep tasks narrow, require tests for behavior changes, and
review the diff before committing.
