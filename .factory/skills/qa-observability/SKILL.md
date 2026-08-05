---
name: qa-observability
description: >
  Functional QA for structured logs, Micrometer metrics, OpenTelemetry traces,
  and the Jaeger HTTP API.
---

# Observability QA

Start Jaeger and the RMI server using the commands in `services.yaml`. Run a
CLI operation with `OTEL_SERVICE_NAME=ledger-cli` and
`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`.

## Flow menu

1. **Jaeger services**: `curl.exe http://localhost:16686/api/services` includes
   `ledger-server` after a traced operation and `ledger-cli` when client export
   is enabled.
2. **Trace details**: query `/api/traces?service=ledger-server`; verify a
   completed server span, RPC attributes, and client/server parent-child
   relationship.
3. **Cross-signal operation**: perform one CLI operation, then capture the
   matching metric counter, structured JSON log event, and Jaeger trace.
4. **Failure signal**: perform a rejected operation and verify failure metrics,
   error log output, and a failed span without leaking sensitive values.

Wait several seconds for OTLP export before querying Jaeger. Capture raw JSON
and Prometheus responses as evidence. Never print credentials or API keys.

## Known failure modes

- Jaeger must be reachable on 16686 and accept OTLP gRPC on 4317.
- Export is asynchronous; retry Jaeger queries after a short delay.
- Integration tests that bind port 1099 cannot run while the shared server is up.
