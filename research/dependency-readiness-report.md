# Dependency Readiness Report

Date: 2026-08-04

## Maven dependency resolution

A temporary Maven project was created outside the repository at `C:\Users\rskin\temp-otel-deps` with the exact requested coordinates and versions. `mvn -B dependency:resolve` successfully fetched all resolvable artifacts but failed overall because the semconv artifact below is unavailable from Maven Central.

| Dependency | Version | Result |
|---|---:|---|
| `org.slf4j:slf4j-api` | 2.0.18 | Resolved |
| `ch.qos.logback:logback-classic` | 1.5.18 | Resolved |
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | Resolved |
| `io.micrometer:micrometer-core` | 1.17.0 | Resolved |
| `io.micrometer:micrometer-registry-prometheus` | 1.17.0 | Resolved |
| `io.opentelemetry:opentelemetry-api` | 1.64.0 | Resolved |
| `io.opentelemetry:opentelemetry-sdk` | 1.64.0 | Resolved |
| `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure` | 1.64.0 | Resolved |
| `io.opentelemetry:opentelemetry-exporter-otlp` | 1.64.0 | Resolved |
| `io.opentelemetry.semconv:opentelemetry-semconv-incubating` | 1.30.1-beta | **Failed: not found in Maven Central** |
| `io.opentelemetry:opentelemetry-sdk-testing` (test) | 1.64.0 | Resolved |

**Maven result:** `dependency:resolve` exited with code 1 solely because `io.opentelemetry.semconv:opentelemetry-semconv-incubating:1.30.1-beta` could not be found at `https://repo.maven.apache.org/maven2`.

## Jaeger Docker readiness

- Image pull: **Passed** — `jaegertracing/all-in-one:1.76.0` downloaded successfully (digest `sha256:ab6f1a1f0fb49ea08bcd19f6b84f6081d0d44b364b6de148e1798eb5816bacac`).
- Container start: **Passed** — container `jaeger-readiness` started successfully with ports 16686, 4317, and 4318 published.
- Jaeger UI: **Passed** — `curl.exe -s http://localhost:16686` succeeded and returned 69 bytes of HTML.
- OTLP HTTP endpoint: **Reachable** — `curl.exe` returned HTTP status `404` for `/`; this is an HTTP response and indicates the port/service was accessible. The root path is not an OTLP ingest path.
- Container stop: **Passed** — `jaeger-readiness` stopped successfully.

## Blockers

The requested semconv coordinate/version is unavailable from Maven Central and blocks a fully successful Maven dependency resolution. All other requested dependencies resolved successfully. No Docker blockers were found.
