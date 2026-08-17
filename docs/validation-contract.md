# Validation Contract: RMI Baseline Observability + QA Framework

## Area: Logging

### VAL-LOG-001: Server lifecycle events are logged via SLF4J
Starting `RmiServer` emits a structured INFO startup event identifying the RMI service and successful `LedgerRemote` binding. Startup failures emit an ERROR event with exception details. `RmiServer` and `LedgerRemoteImpl` contain no operational `System.out`/`System.err` writes — all lifecycle and operation diagnostics go through SLF4J.
Tool: `process-execution`, `mvn-test`
Evidence: Captured server stdout/stderr showing JSON log records, source scan confirming no System.out in server classes, parsed log levels (INFO for startup, ERROR for failures).

### VAL-LOG-002: Operation lifecycle events are logged
Each remote ledger operation (`contribute`, `withdraw`, `getBalance`) emits an INFO start event when invocation begins and an INFO completion event when it succeeds. Failed operations emit an ERROR event identifying the operation and failure outcome with exception data. Exactly one start and one completion/failure event per operation.
Tool: `mvn-test`
Evidence: Ordered log records for each operation type, one start + one completion per success, one start + one ERROR per failure.

### VAL-LOG-003: Log records are valid structured JSON
Every application log line is independently parseable as a JSON object containing stable fields: timestamp (parseable, ordered), level/severity, logger/service name, and message/event name. No plain-text logger prefixes or malformed JSON in structured mode.
Tool: `process-execution`, `mvn-test`
Evidence: Raw console capture and JSON parser results for every nonblank log line including startup, operation, and error records.

### VAL-LOG-004: Sensitive data is redacted in all log output
Passwords, access tokens, API keys, bearer tokens, private keys, connection strings, and personal data are masked in log messages, structured fields, exception text, and stack traces. A known sentinel value must not appear anywhere in captured log output. Control characters are sanitized to prevent log injection. Redaction is consistent across all appenders.
Tool: `mvn-test`
Evidence: Sentinel fixtures for each sensitive data class, raw-output scans proving no full secret, masked-value assertions, and JSON validity after control-character injection.

### VAL-LOG-005: SafeLog preserves only the last four characters
For a nonempty sensitive value, `SafeLog.last4()` returns a mask plus exactly the final four characters. Null and empty input return `[REDACTED]`. Values of four characters or fewer never expose more than allowed.
Tool: `mvn-test`
Evidence: Unit-test cases for long, four-character, short, empty, and null values with exact expected outputs.

### VAL-LOG-006: Client preserves human-readable output on stdout
For successful `contribute`, `withdraw`, and `balance` commands, `RmiClient` prints the documented human-readable result to stdout (not JSON). Internal diagnostics and errors use SLF4J. User-facing output does not contain structured-log noise or stack traces.
Tool: `process-execution`, `mvn-test`
Evidence: Captured client stdout for all commands showing readable text, separate from internal SLF4J log output.

### VAL-LOG-007: Logging configuration is active by default and Datadog-compatible
A normal application launch loads `logback.xml`, selects the JSON structured appender with masking, and does not fall back to a no-op logger. JSON records are compatible with Datadog log ingestion (severity maps to Datadog status: `info` for INFO, `error` for ERROR). Logging failures do not change business operation outcomes.
Tool: `process-execution`, `mvn-test`
Evidence: Startup capture showing configured appender active, parsed JSON records, Datadog-compatible parser/fixture output with field mapping.

## Area: Metrics

### VAL-METRICS-001: Metrics endpoint returns Prometheus text format
`GET /metrics` on port 8081 returns HTTP 200 with `Content-Type: text/plain; version=0.0.4` containing parseable Prometheus text exposition. Non-GET methods return 405.
Tool: `curl`
Evidence: Response status, Content-Type header, and parser output confirming valid Prometheus exposition.

### VAL-METRICS-002: Custom operation meters are present after operations
After at least one RMI operation, `/metrics` contains `rmi_operations_total` (counter), `rmi_operation_duration` (timer with count and sum), and `rmi_operations_in_flight` (gauge). Counters have `operation` and `result` tags. Timers have `operation` tags.
Tool: `curl`, `mvn-test`
Evidence: Scraped metric names, types, HELP metadata, and label sets after operations.

### VAL-METRICS-003: Operation counter increments for success and failure
A successfully completed RMI operation increases `rmi_operations_total` by one with `result=success`. A failed operation increases it by one with `result=failure`. The counter is monotonically increasing per tag combination.
Tool: `mvn-test`, `curl`
Evidence: Before/after counter samples for success and failure paths, tag values, and exact positive deltas.

### VAL-METRICS-004: Duration timer records completed operations
For completed operations, `rmi_operation_duration` shows a count increase and positive sum/duration. Timer samples carry the `operation` tag with separate series for different operation types.
Tool: `mvn-test`, `curl`
Evidence: Before/after timer count and sum samples for at least two operation types.

### VAL-METRICS-005: In-flight gauge reflects active operations
`rmi_operations_in_flight` increases while an operation is in progress and returns to its prior value (normally zero) after completion or failure.
Tool: `mvn-test`
Evidence: Scrapes during and after a blocked operation showing positive active count and subsequent decrement.

### VAL-METRICS-006: JVM metrics are present
The Prometheus scrape includes JVM memory (`jvm_memory_*`), garbage-collection (`jvm_gc_*`), and thread (`jvm_threads_*`) metrics from Micrometer's standard binders.
Tool: `curl`
Evidence: Metric names and samples for each JVM category.

### VAL-METRICS-007: Prometheus registry is the default and always active
With no `METRICS_BACKEND` override (or `METRICS_BACKEND=prometheus`), the service uses a Prometheus registry and `/metrics` exposes custom and JVM metrics. The Prometheus endpoint remains available regardless of backend selection.
Tool: `curl`, `mvn-test`
Evidence: Endpoint status and scraped metrics for default and explicit prometheus configurations.

### VAL-METRICS-008: Datadog backend is configurable via environment variables
When `METRICS_BACKEND=datadog` and `DD_API_KEY` is set, a `DatadogMeterRegistry` is created and receives the same custom meters. When `METRICS_BACKEND=composite` and `DD_API_KEY` is set, both Prometheus and Datadog registries are active. When `DD_API_KEY` is absent, no Datadog registry is created. Unsupported `METRICS_BACKEND` values fall back to the safe default without disabling Prometheus.
Tool: `mvn-test`
Evidence: Registry type/presence for each configuration, meter registration assertions, Prometheus availability in all cases, and graceful handling of missing API key.

## Area: Health

### VAL-HEALTH-001: Liveness endpoint returns UP while process runs
`GET /health/live` on port 8081 returns HTTP 200 with `{"status":"UP"}` when the process is running. Liveness does not depend on RMI, database, metrics, or tracing health.
Tool: `curl`
Evidence: Request to `http://127.0.0.1:8081/health/live`, HTTP 200, parsed JSON body with `status: UP`.

### VAL-HEALTH-002: Readiness endpoint reflects dependency health
`GET /health/ready` returns HTTP 200 with `{"status":"UP"}` when all registered `HealthCheck` instances pass. Returns HTTP 503 with `{"status":"DOWN"}` when any check fails. Each `HealthCheck` has a stable non-empty name.
Tool: `curl`, `mvn-test`
Evidence: Response status and body for all-pass and individual-check-failure scenarios, registered check names.

### VAL-HEALTH-003: Health endpoints reject non-GET methods and disable caching
POST, PUT, DELETE, and other non-GET methods against `/health/live` and `/health/ready` return HTTP 405. All health responses include `Cache-Control: no-store` and `Content-Type: application/json`.
Tool: `curl`, `mvn-test`
Evidence: Response status for each non-GET method, header values for 200, 503, and 405 responses.

### VAL-HEALTH-004: Health server binds to loopback on port 8081
The HTTP observability server listens on `127.0.0.1:8081` (loopback only, not `0.0.0.0`). Health endpoints are accessible locally but not from external addresses.
Tool: `curl`, `process-execution`
Evidence: Listener table showing `127.0.0.1:8081`, successful local request, and no `0.0.0.0:8081` bind.

### VAL-HEALTH-005: Health server starts and stops with the application
The health server starts as part of `RmiServer` initialization and stops cleanly on shutdown, releasing port 8081. No listener or non-daemon thread remains after shutdown.
Tool: `process-execution`, `mvn-test`
Evidence: Startup lifecycle showing server creation, successful requests during runtime, and port release after shutdown.

## Area: Tracing

### VAL-TRACE-001: OpenTelemetry SDK initializes successfully
The server and client initialize an OpenTelemetry SDK with OTLP exporter without startup failure. Instrumentation can create and end spans. A shutdown hook flushes buffered spans before process exit.
Tool: `mvn-test`, `process-execution`
Evidence: Startup output showing successful SDK initialization, completed in-memory span, and flush on shutdown.

### VAL-TRACE-002: OTLP endpoint is configurable with default for local Jaeger
When `OTEL_EXPORTER_OTLP_ENDPOINT` is absent, the exporter targets `http://localhost:4317`. When set to a valid alternate endpoint, the SDK exports to that endpoint instead. Changing only this variable to a Datadog Agent OTLP endpoint exports the same spans with no code changes.
Tool: `mvn-test`, `curl`
Evidence: Effective exporter endpoint for default and overridden configurations, exported span received at configured target.

### VAL-TRACE-003: Service names identify server and client processes
Server spans are associated with `service.name=ledger-server` and client spans with `service.name=ledger-cli`. Both service names are queryable from the tracing backend.
Tool: `mvn-test`, `curl`
Evidence: Exported resource attributes and Jaeger API `/api/services` showing both service names.

### VAL-TRACE-004: Server spans are created for all remote operations
Each `addOrSubtract` invocation creates exactly one completed server-side span (`SpanKind.SERVER`). Each `getBalance` invocation creates exactly one completed server-side span. Span names identify the invoked method.
Tool: `mvn-test`
Evidence: In-memory span assertions with `OpenTelemetryExtension` showing span kind, name, and completion for each operation type.

### VAL-TRACE-005: Client spans are created for all CLI commands
The `contribute`, `withdraw`, and `balance` CLI commands each create and complete a client-side span (`SpanKind.CLIENT`) around their remote operation.
Tool: `mvn-test`
Evidence: Completed spans for each command with `SpanKind.CLIENT`, associated with `ledger-cli`.

### VAL-TRACE-006: Spans have correct RPC and business attributes
Every client and server RPC span has `rpc.system=java_rmi` and `rpc.method` matching the invoked method. Operation spans include `plan.id` and `operation` attributes. Arithmetic operations include `amount`. Attributes are consistent across client and server spans for the same operation.
Tool: `mvn-test`
Evidence: Span attribute maps for both sides showing exact expected values for each operation type.

### VAL-TRACE-007: Trace context propagates across the RMI boundary
The client injects the active W3C `traceparent` into the `traceContext` parameter. The server extracts it and uses it as the parent context for the server span. The server span's trace ID matches the client span's trace ID, and the server span's parent span ID equals the client span's span ID.
Tool: `mvn-test`
Evidence: Captured remote arguments containing valid `traceparent`, parent-child span relationship assertions in `OpenTelemetryExtension`.

### VAL-TRACE-008: Null or invalid traceContext is handled safely
When `traceContext` is null, the server creates a root server span (no parent) and the operation succeeds. Malformed `traceContext` values do not crash the operation or contaminate tracing — the server creates a valid span using fallback behavior.
Tool: `mvn-test`
Evidence: Successful invocations with null, empty, and malformed values, valid server spans with correct parent/no-parent relationships.

### VAL-TRACE-009: Span status reflects operation outcome
Successful operation spans do not report error status or exception events. Failed operations record `StatusCode.ERROR` and the thrown exception on both server and client spans before the span ends.
Tool: `mvn-test`
Evidence: Span status assertions for success and failure paths, exception event presence/absence.

### VAL-TRACE-010: End-to-end trace is visible in Jaeger
After running a traced CLI operation against the server, the Jaeger HTTP API contains the exported trace with both client and server spans and their parent-child relationship. Multiple operations produce distinct traces with no cross-request leakage.
Tool: `curl`
Evidence: Jaeger API `/api/services` and `/api/traces` responses showing expected services, trace IDs, span names, kinds, attributes, and parent-child relationships.

## Area: CLI Regression

### VAL-CLI-001: Contribute command remains functional
The CLI accepts `contribute <planId> <amount>`, completes the operation, and displays the expected contribution confirmation on stdout. Exit status 0.
Tool: `process-execution`
Evidence: Captured command output, exit status, stdout showing contribution confirmation.

### VAL-CLI-002: Withdraw command remains functional
The CLI accepts `withdraw <planId> <amount>`, completes the operation, and displays the expected withdrawal confirmation on stdout. Exit status 0.
Tool: `process-execution`
Evidence: Captured command output, exit status, stdout showing withdrawal confirmation.

### VAL-CLI-003: Balance command remains functional
The CLI accepts `balance <planId>`, retrieves the balance, and displays it on stdout. Exit status 0.
Tool: `process-execution`
Evidence: Captured command output, exit status, stdout showing expected balance value.

### VAL-CLI-004: Invalid amounts are rejected
Negative and zero amounts are rejected with a clear error. No operation is performed against the ledger.
Tool: `process-execution`, `mvn-test`
Evidence: Captured output for negative and zero amounts, rejection text, no server-side operation recorded.

### VAL-CLI-005: Nonexistent plans fail clearly
Commands referencing a nonexistent `planId` produce a clear, actionable error identifying the missing plan. No stack trace is shown to the user.
Tool: `process-execution`
Evidence: Captured stdout/stderr, error text identifying missing plan, no stack trace.

## Cross-Area Flows

### VAL-CROSS-001: Operations emit all observability signals simultaneously
After a CLI operation (contribute or withdraw), the operation counter increments in `/metrics`, a corresponding trace appears in Jaeger, and a structured log entry is written — all correlated to the same operation.
Tool: `process-execution`, `curl`
Evidence: CLI output, before/after metric values, Jaeger trace ID and span details, structured log record — all for the same operation.

### VAL-CROSS-002: Server starts with the complete observability stack
The RMI server starts with the HTTP observability server on port 8081, the RMI registry on port 1099, and OTLP export to Jaeger. All health endpoints, metrics, and tracing are active after startup.
Tool: `process-execution`, `curl`
Evidence: Process checks showing ports 8081 and 1099 bound, successful health endpoint responses, metrics available, Jaeger receiving traces.

### VAL-CROSS-003: Readiness reflects RMI registry binding
The `/health/ready` endpoint reports ready (UP) when the RMI registry is bound and serving. It reports not ready (DOWN) when the registry is unavailable.
Tool: `curl`, `mvn-test`
Evidence: Health ready responses before and after registry binding, status codes and bodies.

### VAL-CROSS-004: First-visit setup and verification flow works
A first-time user can start Jaeger, start the RMI server, run a CLI command, and verify the resulting metrics, trace, log, and health signals end-to-end.
Tool: `process-execution`, `curl`
Evidence: Ordered startup transcript, service readiness checks, CLI output, metrics response, Jaeger trace, structured log, and health response.

## Area: QA Framework

### VAL-QA-001: QA testing skills are generated
The `install-qa` skill generates QA testing skills appropriate for the application and its observability integration, covering CLI, HTTP, and observability surfaces.
Tool: `install-qa`
Evidence: Generated skill files and directories with content demonstrating coverage of CLI, cross-area, and observability testing.

### VAL-QA-002: GitHub Actions QA workflow is created
The `install-qa` skill creates a GitHub Actions workflow that runs the configured QA tests with appropriate triggers and setup.
Tool: `install-qa`
Evidence: Workflow file path and contents showing triggers, required setup, and QA test execution commands.

### VAL-QA-003: QA report template is generated
The `install-qa` skill generates a QA report template for recording test results and evidence.
Tool: `install-qa`
Evidence: Report template path and contents showing sections for assertions, pass/fail status, evidence, and execution metadata.
