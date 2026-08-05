---
name: qa
description: >
  Run functional QA for the Java RMI ledger, its CLI, HTTP observability
  endpoints, and Jaeger API. Selects flows based on the git diff.
---

# QA Orchestrator

This skill performs functional verification only. Do not substitute Maven unit
tests, linting, or static analysis for the user-facing flows below.

## Configuration and scope

Read `config.yaml`, then inspect `git diff`. Run only the sub-skills whose
path patterns match application changes. QA-only, documentation-only, and CI
changes are INCONCLUSIVE because no application behavior changed.

The default target is local. Use `services.yaml` as the sole source of truth
for starting and stopping Jaeger and the RMI server. Never use ports outside
the declared mission ports.

## Sub-skills

- `qa-cli`: process-execution checks for contribute, withdraw, balance, and errors.
- `qa-http`: curl checks for liveness, readiness, metrics, headers, and methods.
- `qa-observability`: Jaeger API and cross-signal checks for logs, metrics, and traces.

Load each affected module and select the smallest relevant menu of flows,
including at least one negative case. Capture command output, HTTP response
headers, and Jaeger JSON as evidence.

## Reporting

Write `qa-results/report.md` from `REPORT-TEMPLATE.md`. Use only the result
values specified there. Never silently skip a flow: report it as BLOCKED with
the attempted action and remediation. For new environment-related failures,
add suggested skill updates to the report; do not modify skills automatically.
