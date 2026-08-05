# OpenTelemetry Java RMI Instrumentation Research

Research into instrumenting a Java 17 / Maven monolith that uses Java RMI (an
RMI server binds a `LedgerRemote` implementation to a registry on port 1099; a
CLI client looks up and invokes methods on it) with OpenTelemetry distributed
tracing.

All version numbers and facts below were verified against primary sources
(Maven Central, the `opentelemetry-java` and `opentelemetry-java-instrumentation`
GitHub repos, the official OpenTelemetry docs at opentelemetry.io, and the
Jaeger docs) as of late July 2026.

---

## 1. OpenTelemetry Java SDK setup for Maven

### Latest stable versions (verified July 2026)

| Artifact | Latest version | Purpose |
| --- | --- | --- |
| `io.opentelemetry:opentelemetry-api` | `1.64.0` | API only (Tracer, Span, Context). No-op if no SDK present. |
| `io.opentelemetry:opentelemetry-sdk` | `1.64.0` | SDK reference implementation (SdkTracerProvider, BatchSpanProcessor, samplers). |
| `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure` | `1.64.0` | Reads `otel.*` env vars / system properties to auto-configure the SDK. |
| `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi` | `1.64.0` | SPI companions for autoconfigure (ResourceProviders, etc.). Pulled transitively. |
| `io.opentelemetry:opentelemetry-exporter-otlp` | `1.64.0` | OTLP span/metric/log exporters (gRPC + HTTP/protobuf). |
| `io.opentelemetry:opentelemetry-semconv` | `1.30.1-beta` (or `opentelemetry-semconv-incubating`) | Semantic-convention attribute constants (`service.name`, `rpc.system`, etc.). |
| `io.opentelemetry:opentelemetry-sdk-testing` | `1.64.0` | In-memory exporters + JUnit 5 `OpenTelemetryExtension` for tests. |

> The OpenTelemetry Java agent **v2.30.0** (released 2026-07-21) "targets the
> OpenTelemetry SDK 1.64.0", confirming 1.64.0 is the current stable SDK line.
> Source: https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.30.0

> There is **no standalone "RMI instrumentation library" artifact** on Maven
> Central. RMI instrumentation is delivered only through the Java agent
> (see section 2). For manual instrumentation you use the plain `opentelemetry-api`
> / `opentelemetry-sdk` artifacts.

### `pom.xml` dependencies (manual SDK + OTLP, no agent)

```xml
<properties>
  <otel.version>1.64.0</otel.version>
</properties>

<dependencies>
  <!-- OpenTelemetry API -->
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>${otel.version}</version>
  </dependency>

  <!-- OpenTelemetry SDK -->
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>${otel.version}</version>
  </dependency>

  <!-- Autoconfigure SDK from otel.* env vars / system properties -->
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-extension-autoconfigure</artifactId>
    <version>${otel.version}</version>
  </dependency>

  <!-- OTLP exporter (both gRPC and HTTP/protobuf are in this artifact) -->
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>${otel.version}</version>
  </dependency>

  <!-- Semantic convention attributes (optional but recommended) -->
  <dependency>
    <groupId>io.opentelemetry.semconv</groupId>
    <artifactId>opentelemetry-semconv-incubating</artifactId>
    <version>1.30.1-beta</version>
  </dependency>

  <!-- Test helpers: in-memory exporters + JUnit5 extension -->
  <dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-testing</artifactId>
    <version>${otel.version}</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Note: `opentelemetry-sdk` is an "all" uber-artifact that bundles
`opentelemetry-sdk-trace`, `opentelemetry-sdk-metrics`, `opentelemetry-sdk-common`,
`opentelemetry-sdk-logs`, and the samplers. You can depend on the more granular
artifacts if you want to minimize the footprint, but `opentelemetry-sdk` is the
simplest choice.

Source: https://opentelemetry.io/docs/languages/java/sdk/

---

## 2. RMI instrumentation approach

### Auto-instrumentation via the Java agent (recommended, zero-code)

The `opentelemetry-java-instrumentation` project ships a dedicated **RMI
instrumentation module** at
`instrumentation/rmi` (https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/rmi).

Its `metadata.yaml` states verbatim:

> *"This instrumentation enables RPC client spans and RPC server spans for Java
> RMI (Remote Method Invocation)."*
>
> Semantic conventions: `RPC_CLIENT_SPANS`, `RPC_SERVER_SPANS`

The module lives under `instrumentation/rmi/javaagent` plus a `bootstrap`
helper package — i.e. it is **agent-only** bytecode instrumentation. There is
no published library JAR you add to `pom.xml`. You attach the agent:

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=ledger-server \
     -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
     -jar ledger-server.jar
```

What the agent does for RMI:
- Creates an **RPC client span** when a caller invokes a method on a remote
  stub (the client side of the RMI call).
- Creates an **RPC server span** when the server-side skeleton dispatches the
  incoming invocation to the `UnicastRemoteObject` implementation.
- **Propagates the W3C `traceparent`/`tracestate` context** across the RMI
  boundary automatically by instrumenting the RMI transport/serialization layer
  (see section 6). This is the key benefit: you get a connected distributed
  trace across the client -> registry -> server hop with **no code changes**.

Span attributes follow the RPC semantic conventions: `rpc.system="java_rmi"`,
`rpc.method`, plus `server.address` / `server.port` under the stable rpc semconv
opt-in (`otel.semconv-stability.opt-in=rpc`).

Download the agent (v2.30.0, 23.5 MB):
https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar

### Manual instrumentation (the alternative)

If you cannot run the agent (e.g. you want explicit control, or the agent
conflicts with something), you manually create spans around RMI calls using the
`opentelemetry-api` and you must propagate context yourself (section 6). The
trade-off: more code, and you own context propagation.

A hybrid is common and officially supported: run the **agent** for automatic
RMI/HTTP/JDBC spans **and** add `@WithSpan` or manual `tracer.spanBuilder(...)`
calls for business-logic spans. The agent bridges any manual API usage to its
SDK automatically.

Source:
- https://opentelemetry.io/docs/zero-code/java/agent/
- https://opentelemetry.io/docs/zero-code/java/agent/api/ (extending agent telemetry with the API)

---

## 3. OTLP exporter configuration

### Endpoint formats

| Transport | Default endpoint | Path / detail |
| --- | --- | --- |
| OTLP **gRPC** | `http://localhost:4317` | Plain HTTP; exporter auto-negotiates. No path. |
| OTLP **HTTP/protobuf** | `http://localhost:4318/v1/spans` (spans), `/v1/metrics`, `/v1/logs` | Full path including `/v1/spans` required. |

The `setEndpoint(...)` value for the gRPC exporter is the base URL
(`http://localhost:4317`); for the HTTP exporter it is the **full spans path**
(`http://localhost:4318/v1/spans`).

### Programmatic configuration

```java
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;

// HTTP/protobuf (easiest locally; no gRPC dependency needed)
SpanExporter httpExporter = OtlpHttpSpanExporter.builder()
    .setEndpoint("http://localhost:4318/v1/spans")
    .setTimeout(Duration.ofSeconds(10))
    .build();

// gRPC
SpanExporter grpcExporter = OtlpGrpcSpanExporter.builder()
    .setEndpoint("http://localhost:4317")
    .setTimeout(Duration.ofSeconds(10))
    .build();
```

Wire it into a tracer provider:

```java
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;

Resource resource = Resource.getDefault().toBuilder()
    .put(ServiceAttributes.SERVICE_NAME, "ledger-server")
    .build();

SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .setResource(resource)
    .addSpanProcessor(BatchSpanProcessor.builder(httpExporter).build())
    .build();

OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .build();

// Register globally so library instrumentation can find it
GlobalOpenTelemetry.set(otelSdk);

// IMPORTANT: register a shutdown hook so buffered spans are flushed
Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
```

### Environment-variable configuration (via autoconfigure)

If you depend on `opentelemetry-sdk-extension-autoconfigure`, a single call
builds the whole SDK from env vars:

```java
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

OpenTelemetry sdk = AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetry();
```

Then configure via environment variables / `-D` system properties:

| Variable | Default | Example |
| --- | --- | --- |
| `OTEL_SERVICE_NAME` | `unknown_service` | `ledger-server` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | `http://localhost:4317` (gRPC) or `http://localhost:4318` (HTTP; auto-appends `/v1/{signal}`) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` (Java default) | `grpc` or `http/protobuf` |
| `OTEL_EXPORTER_OTLP_HEADERS` | (none) | `api-key=secret` |
| `OTEL_TRACES_EXPORTER` | `otlp` | `otlp`, `logging`, `none` |
| `OTEL_PROPAGATORS` | `tracecontext,baggage` | keep default (W3C) |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | `always_on`, `traceidratio` |

> Gotcha: with the Java autoconfigure module, `OTEL_EXPORTER_OTLP_ENDPOINT` for
> the HTTP protocol should be the **base** URL (`http://localhost:4318`); the
> SDK appends `/v1/spans`. With the programmatic `OtlpHttpSpanExporter.builder()`
> you must supply the **full** path including `/v1/spans`.

Source: https://opentelemetry.io/docs/languages/java/sdk/ and
https://opentelemetry.io/docs/languages/java/configuration/

---

## 4. Tracing backend (local dev/testing)

**Jaeger all-in-one accepts OTLP natively** (since Jaeger v1.35, May 2022). It
is the simplest option for local development — single container, in-memory
storage, built-in UI. No separate OTel Collector needed.

### Docker — Jaeger all-in-one v1.76.0

```bash
docker run --rm --name jaeger \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  jaegertracing/all-in-one:1.76.0
```

| Port | Protocol | Function |
| --- | --- | --- |
| `16686` | HTTP | Jaeger UI (open in browser) |
| `4317` | gRPC | Accept OTLP over gRPC |
| `4318` | HTTP | Accept OTLP over HTTP (`/v1/spans`, `/v1/metrics`, `/v1/logs`) |

Point the app at it:
- gRPC: `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` (default protocol)
- HTTP: `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318` + `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`

Then open http://localhost:16686 to view traces. Storage is in-memory (lost on
container restart) — fine for dev.

### Alternative backends

- **Jaeger v2.x** (`jaegertracing/all-in-one:2.x`) — the newer line, built on
  the OpenTelemetry Collector internally; same OTLP ports.
- **Grafana Tempo** (`grafana/tempo`) — OTLP-native, pairs with Grafana.
- **OTel Collector + any backend** — run
  `otel/opentelemetry-collector-contrib` if you want batching/re-sampling or to
  fan out to multiple backends. For a single dev box Jaeger all-in-one is enough.
- **No backend at all** — use `LoggingSpanExporter` / `OtlpStdoutSpanExporter`
  (`opentelemetry-exporter-logging` / `opentelemetry-exporter-logging-otlp`) to
  print spans to stdout. Great for unit tests and quick smoke checks.

Source: https://www.jaegertracing.io/docs/1.76/getting-started/

---

## 5. Manual span creation for RMI calls

### Server side (`LedgerRemoteImpl`)

Wrap the remote method body in a server span, extracting the propagated parent
context (see section 6 for how the context arrives):

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;

public class LedgerRemoteImpl extends UnicastRemoteObject implements LedgerRemote {

  private static final Tracer TRACER =
      GlobalOpenTelemetry.getTracer("ledger-rmi", "1.0.0");

  @Override
  public long postEntry(String account, double amount, String traceContext /* see sect. 6 */)
      throws RemoteException {

    // Extract the parent context the client sent (section 6)
    Context parent = TraceContextCarrier.fromRmi(traceContext);

    Span serverSpan = TRACER.spanBuilder("LedgerRemote.postEntry")
        .setSpanKind(SpanKind.SERVER)
        .setParent(parent)              // link to the client span
        .setAttribute("rpc.system", "java_rmi")
        .setAttribute("rpc.method", "postEntry")
        .setAttribute("ledger.account", account)
        .startSpan();

    try (var scope = serverSpan.makeCurrent()) {
      // ... actual ledger logic ...
      long newBalance = ledger.post(account, amount);
      serverSpan.setAttribute("ledger.new_balance", newBalance);
      return newBalance;
    } catch (Exception e) {
      serverSpan.recordException(e);
      serverSpan.setStatus(StatusCode.ERROR, e.getMessage());
      throw e;
    } finally {
      serverSpan.end();
    }
  }
}
```

### Client side (CLI client)

Wrap the remote invocation in a client span, then inject the current context
into something the server can read (section 6):

```java
public class LedgerClient {

  private static final Tracer TRACER =
      GlobalOpenTelemetry.getTracer("ledger-cli", "1.0.0");

  public void post(String account, double amount, LedgerRemote remote)
      throws RemoteException {

    Span clientSpan = TRACER.spanBuilder("LedgerRemote.postEntry")
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute("rpc.system", "java_rmi")
        .setAttribute("rpc.method", "postEntry")
        .setAttribute("server.address", "localhost")
        .setAttribute("server.port", 1099)
        .startSpan();

    try (var scope = clientSpan.makeCurrent()) {
      // Inject current context into a string carrier to pass through RMI (sect. 6)
      String carrier = TraceContextCarrier.inject(Context.current());
      long balance = remote.postEntry(account, amount, carrier);
      clientSpan.setAttribute("ledger.new_balance", balance);
    } catch (Exception e) {
      clientSpan.recordException(e);
      clientSpan.setStatus(StatusCode.ERROR, e.getMessage());
      throw e;
    } finally {
      clientSpan.end();
    }
  }
}
```

### Using `@WithSpan` (with the agent only)

If you run the Java agent, you can skip manual span code for business methods
and annotate instead:

```java
@WithSpan
public long postEntry(String account, double amount) { ... }
```

`@WithSpan` requires `opentelemetry-instrumentation-annotations` on the
classpath and the agent to weave it (or the annotations incubator library).

### Tests

Use `OpenTelemetryExtension` to assert on spans without a backend:

```java
class LedgerRemoteImplTest {
  @RegisterExtension
  static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  @Test
  void postEntryCreatesServerSpan() {
    // ... invoke ...
    assertThat(otel.getSpans())
        .satisfiesExactly(span -> assertThat(span).hasName("LedgerRemote.postEntry"));
  }
}
```

Source: https://opentelemetry.io/docs/languages/java/sdk/#testing

---

## 6. Context propagation through RMI

### The core problem

Java RMI does **not** propagate OpenTelemetry context automatically. Unlike
HTTP (where `traceparent` rides in headers) or gRPC (metadata), RMI marshals
method arguments via Java serialization over a raw socket. There is no
out-of-band header channel, so the W3C `traceparent` must travel **inside the
RMI call itself** — either in the method parameters or via a custom
serialization hook. Without this, the server span will have no parent and the
trace will be broken across the client -> server hop.

### Option A — Let the agent do it (zero code)

The OpenTelemetry Java agent's RMI module instruments the RMI transport layer
so that `traceparent`/`tracestate` (and baggage) are injected on the client
side and extracted on the server side automatically. You attach the agent to
**both** the server JVM and the client JVM and get connected traces with no
parameter changes. This is by far the easiest path and is the recommended
approach unless you have a hard reason to avoid the agent.

### Option B — Manual propagation via an explicit carrier parameter

Add a `String contextCarrier` (or a small serializable holder object) to every
remote method signature. Inject the current context into it on the client;
extract it on the server and pass it as the span's parent.

This requires modifying the `LedgerRemote` interface, which is intrusive but
explicit and agent-free.

```java
// ---- Small helper that turns Context into a string and back ----
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;

public final class TraceContextCarrier {

  private static final TextMapSetter<Map<String, String>> SETTER =
      (carrier, key, value) -> { if (carrier != null) carrier.put(key, value); };

  private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
    @Override public Iterable<String> keys(Map<String, String> c) { return c.keySet(); }
    @Override public String get(Map<String, String> c, String key) {
      return c == null ? null : c.get(key);
    }
  };

  /** Serialize the *current* context to a string to send across RMI. */
  public static String inject(Context context) {
    Map<String, String> map = new HashMap<>();
    GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .inject(context, map, SETTER);
    // Encode map as "key1=val1;key2=val2" (or JSON / Base64, your choice)
    return encode(map);
  }

  /** Rebuild a Context on the server side from the RMI-delivered string. */
  public static Context extract(String carrier) {
    Map<String, String> map = decode(carrier);
    return GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .extract(Context.current(), map, GETTER);
  }

  // encode/decode: simple "k=v;k=v" implementation omitted for brevity
}
```

The client passes the carrier; the server uses it as the parent:

```java
// client
String carrier = TraceContextCarrier.inject(Context.current());
remote.postEntry(account, amount, carrier);

// server
Context parent = TraceContextCarrier.extract(traceContext);
spanBuilder.setParent(parent).setSpanKind(SpanKind.SERVER)...
```

The default propagator (`tracecontext,baggage`) encodes the standard W3C
`traceparent` header (`00-<trace-id>-<span-id>-<flags>`) plus `tracestate` and
any baggage entries into the map, so this is fully spec-compliant context
propagation — just tunneled through a method argument instead of HTTP headers.

### Option C — Transparent propagation via a custom RMI socket factory / stream

More advanced: install a custom `RMIClientSocketFactory` /
`RMIServerSocketFactory` (or wrap the object streams used by
`UnicastRemoteObject`) so the `traceparent` bytes are written/read around each
serialized RMI call without changing the remote interface. This is essentially
what the agent does internally, but doing it by hand is fiddly and version
fragile. Only consider if you cannot use the agent and cannot change the
interface.

### Recommendation for this codebase

Given a monolith with a `LedgerRemote` interface and a CLI client:

1. **First choice:** attach the OpenTelemetry Java agent (v2.30.0) to both the
   server and client JVMs. RMI client + server spans and W3C context
   propagation come for free; the only "code" is `-javaagent` + env vars.
2. **If the agent is ruled out:** add an explicit `String traceContext`
   parameter (Option B) to the remote methods and inject/extract with
   `TraceContextCarrier`. Pair with manual spans (section 5).

---

## Gotchas and limitations

- **No library-only RMI instrumentation.** The RMI module is agent-only
  (`instrumentation/rmi/javaagent`). There is no Maven artifact like
  `opentelemetry-rmi` you can depend on for programmatic instrumentation.
- **Context does not propagate by magic.** Without the agent you must carry
  `traceparent` in the RMI payload yourself (Option B/C above).
- **Both sides need instrumentation.** If only the client (or only the server)
  is instrumented, you get a half-trace with no parent link on the un-instrumented
  side. The agent must be attached to (or manual spans added in) **both** JVMs.
- **Registry lookup is a separate call.** `LocateRegistry.getRegistry(...)` /
  `registry.lookup(...)` is itself an RMI call to the registry on 1099. The agent
  will create small client spans for these too; expect a few extra spans per
  client run.
- **OTLP HTTP endpoint path.** Programmatic `OtlpHttpSpanExporter` needs the
  full `/v1/spans` path; the autoconfigure/env-var path wants just the base
  `http://localhost:4318`.
- **Flush on shutdown.** `BatchSpanProcessor` buffers spans. Register a JVM
  shutdown hook calling `tracerProvider.close()` / `.shutdown()` or short-lived
  CLI client runs will drop their last batch of spans before export completes.
- **Sampler default.** `parentbased_always_on` samples 100%. For a noisy
  monolith consider `traceidratio` or a rules-based sampler
  (`opentelemetry-contrib:opentelemetry-samplers`).
- **`-javaagent` must precede `-jar`.** Order on the command line matters;
  placing it after `-jar` silently disables the agent.
- **Java 17 is fully supported.** The agent and SDK target Java 8+; Java 17
  works without special flags. (No need for `--add-opens` for OTel itself.)
- **Jaeger v1.x is archived/in maintenance** as of 2025 — the active line is
  Jaeger v2.x (built on the OTel Collector). v1.76 all-in-one still works fine
  for local dev and accepts OTLP directly on 4317/4318; for anything long-term
  prefer `jaegertracing/all-in-one:2.x`.
- **In-memory storage is ephemeral.** Jaeger all-in-one stores traces in RAM;
  restarting the container wipes them. Use a persistent backend (Badger,
  Elasticsearch, or Jaeger v2 with a configured storage) for retained data.

---

## Quick-start summary

1. Start a backend:
   ```bash
   docker run --rm --name jaeger -p 16686:16686 -p 4317:4317 -p 4318:4318 \
     jaegertracing/all-in-one:1.76.0
   ```
2. Download the agent:
   ```bash
   curl -L -o opentelemetry-javaagent.jar \
     https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar
   ```
3. Run the RMI server:
   ```bash
   java -javaagent:opentelemetry-javaagent.jar \
        -Dotel.service.name=ledger-server \
        -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
        -jar ledger-server.jar
   ```
4. Run the CLI client (same agent + endpoint, different service name):
   ```bash
   java -javaagent:opentelemetry-javaagent.jar \
        -Dotel.service.name=ledger-cli \
        -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
        -jar ledger-cli.jar
   ```
5. Open http://localhost:16686 — select `ledger-server` / `ledger-cli` and
   inspect the connected trace spanning the RMI call.

For manual (agent-free) instrumentation, use the Maven dependencies in section
1, the span code in section 5, and the `TraceContextCarrier` propagation in
section 6.
