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
`bash ./scripts/setup-pre-commit.sh`.

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

## Observability

The server and client include structured JSON logging with SLF4J and Logback.
Sensitive values are redacted through the `SafeLog` utility. Micrometer
provides JVM metrics and custom RMI meters: `rmi_operations_total`,
`rmi_operation_duration`, and `rmi_operations_in_flight`.

The HTTP observability server binds to `127.0.0.1:8081` and exposes:

- `/health/live` — liveness
- `/health/ready` — readiness, including an RMI registry check
- `/metrics` — Prometheus or selected backend metrics

Non-GET health requests return `405`, and health responses use
`Cache-Control: no-store`. Distributed traces use OpenTelemetry with W3C
trace-context propagation through RMI and OTLP gRPC export. The default local
backends are Prometheus and Jaeger; Datadog can be selected through environment
variables.

## Environment Variables

- `METRICS_BACKEND` (default `prometheus`) — `prometheus`, `datadog`, or
  `composite`
- `DD_API_KEY` — Datadog API key, required for the Datadog registry
- `DD_URI` (default `https://api.datadoghq.com`) — Datadog API endpoint
- `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4317`) — OTLP gRPC
  target
- `OTEL_SERVICE_NAME` — trace service name, such as `ledger-server` or
  `ledger-cli`

## Starting with Observability

Start Jaeger and copy the runtime dependencies:

```shell
docker run --rm --name jaeger -d -p 16686:16686 -p 4317:4317 -p 4318:4318 jaegertracing/all-in-one:1.76.0
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency -q
```

Start the server, then run the CLI from another terminal:

```shell
OTEL_SERVICE_NAME=ledger-server java -cp "target/classes;target/dependency/*" com.example.rmirefactor.server.RmiServer
java -cp "target/classes;target/dependency/*" com.example.rmirefactor.client.RmiClient balance demo-plan
```

On Windows PowerShell, set the service name separately with
`$env:OTEL_SERVICE_NAME = "ledger-server"` before starting the server.

Check the local endpoints:

```shell
curl http://127.0.0.1:8081/health/live
curl http://127.0.0.1:8081/metrics
```

The observability ports are `8081` for HTTP health and metrics, `16686` for
the Jaeger UI, `4317` for Jaeger OTLP gRPC, and `4318` for Jaeger OTLP HTTP.

## Viewing Traces

Open [http://localhost:16686](http://localhost:16686) in a browser and select
the `ledger-server` or `ledger-cli` service to inspect RMI operation traces.
The OTLP endpoint can also be pointed at a Datadog Agent with
`OTEL_EXPORTER_OTLP_ENDPOINT`.

## QA Framework

The repository includes generated QA skills for CLI, HTTP, and observability
flows (`qa-cli`, `qa-http`, and `qa-observability`). GitHub Actions runs the QA
workflow in `.github/workflows/qa.yml`, and the associated report template
documents the results.

## Droid workflow

Use the instructions in `AGENTS.md` when asking a Droid to explore or modify
this repository. Keep tasks narrow, require tests for behavior changes, and
review the diff before committing.
