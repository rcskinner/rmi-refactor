---
name: qa-http
description: >
  Functional curl QA for the loopback health and Prometheus metrics endpoints.
---

# HTTP Observability QA

Use `curl.exe` against `http://127.0.0.1:8081`. The RMI server must be started
through `services.yaml`.

## Flow menu

1. **Liveness**: `GET /health/live` returns 200 JSON `{"status":"UP"}`.
2. **Readiness**: `GET /health/ready` returns 200 JSON UP while the registry is
   available.
3. **Methods and headers**: POST, PUT, and DELETE to both health endpoints
   return 405, `Content-Type: application/json`, and `Cache-Control: no-store`.
4. **Metrics**: `GET /metrics` returns 200 with Prometheus text content type and
   includes `rmi_operations_total`, timers, in-flight gauge, and JVM metrics
   after a CLI operation.

Record status, headers, and response bodies as evidence. Do not target a remote
environment or bind the service to a non-loopback address.

## Known failure modes

- Port 8081 is intentionally loopback-only.
- Metrics may not contain operation series until a CLI request has completed.
