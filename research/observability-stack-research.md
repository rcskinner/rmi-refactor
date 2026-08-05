# Java 17 observability stack research

**Checked:** 2026 documentation/release pages available during this research. Pin versions in the application's dependency-management policy and re-check Maven Central before release; the versions below are concrete coordinates, not dynamic ranges.

## Recommendation

For this RMI process, use SLF4J 2.0.18 + Logback 1.5.18, Micrometer 1.17.0 with the Prometheus registry, and the JDK `jdk.httpserver` module. The JDK server has no Maven dependency and is sufficient for three small, loopback/private-network endpoints. Choose Jetty or Undertow only when the service needs a larger HTTP feature set (Servlets, HTTP/2, WebSockets, routing middleware, TLS integration, or higher-volume traffic).

## 1. SLF4J and Logback

### Maven coordinates

SLF4J 2.0.18 is the current actively-developed SLF4J line listed by the SLF4J download/manual pages. Logback 1.5.18 is a Java 11+ line and is compatible with SLF4J 2.x. `logback-classic` brings `logback-core` transitively; declaring the API explicitly makes the application contract clear.

```xml
<properties>
  <slf4j.version>2.0.18</slf4j.version>
  <logback.version>1.5.18</logback.version>
  <logstash.encoder.version>8.1</logstash.encoder.version>
</properties>
<dependencies>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>${slf4j.version}</version>
  </dependency>
  <dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>${logback.version}</version>
  </dependency>
  <!-- JSON encoder; not needed if plain text is acceptable. -->
  <dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>${logstash.encoder.version}</version>
  </dependency>
</dependencies>
```

Do not add another SLF4J binding (for example `slf4j-simple`) to the runtime class path. Inspect the dependency tree for multiple bindings. Keep the encoder version aligned with its published compatibility matrix before upgrading Logback.

### JSON configuration with masking

Put this at `src/main/resources/logback.xml`. JSON logs are one event per line and include the timestamp, level, logger, thread, message, MDC, and exception. The encoder decorator masks configured JSON paths and regex matches. Path names must match the application's event fields; regexes are defense-in-depth, not a substitute for not logging secrets.

```xml
<configuration>
  <appender name="JSON_CONSOLE"
            class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
      <providers>
        <timestamp><fieldName>@timestamp</fieldName></timestamp>
        <logLevel/><loggerName/><threadName/>
        <mdc/>
        <message/>
        <stackTrace/>
      </providers>
      <decorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
        <defaultMask>[REDACTED]</defaultMask>
        <path>password</path><path>token</path><path>access_token</path>
        <path>authorization</path><path>private_key</path>
        <path>connection_string</path><path>email</path>
        <!-- Catch common key/value or JSON-like text in messages/MDC. -->
        <regex>(?i)(password|passwd|token|access[_-]?token|authorization|private[_-]?key|connection[_-]?string)([=:])([^,\s}]+)</regex>
        <regex>(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]+</regex>
      </decorator>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="JSON_CONSOLE"/></root>
</configuration>
```

The project security rule is stricter than masking: output must not contain passwords, access tokens, private keys, connection strings, personal data, or complete request credentials. Prefer event names and stable non-sensitive IDs. If troubleshooting requires a value, use a narrowly scoped helper that keeps only the final four characters and never logs the original value through another argument, exception, MDC field, or HTTP access log:

```java
public final class SafeLog {
  private SafeLog() {}

  public static String last4(String value) {
    if (value == null || value.isEmpty()) return "[REDACTED]";
    return "[REDACTED]..." + value.substring(Math.max(0, value.length() - 4));
  }
}
```

Also avoid `logger.info("request={}", request)` and `logger.info("headers={}", headers)`: object `toString()` can expose credentials/PII. Allow-list fields, scrub exception messages before logging, avoid query strings, and test rendered logs for forbidden patterns. Do not put secrets in MDC. A custom encoder decorator is useful as a last line of defense, but redaction at the logging boundary cannot undo a secret already sent to another appender or exporter.

### Java usage

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RmiService {
  private static final Logger LOG = LoggerFactory.getLogger(RmiService.class);

  public Result execute(String operation, String requestId) {
    LOG.info("operation_started operation={} request_id={}", operation, SafeLog.last4(requestId));
    try {
      Result result = doWork(operation);
      LOG.info("operation_completed operation={}", operation);
      return result;
    } catch (RuntimeException ex) {
      LOG.error("operation_failed operation={} error_type={}", operation, ex.getClass().getSimpleName());
      throw ex;
    }
  }
}
```

Use parameterized messages (not string concatenation), stable event names, and an allow-list of safe fields. Add correlation IDs to MDC only after validating that they are non-sensitive.

## 2. Micrometer and Prometheus

### Maven coordinates

Use the Micrometer BOM so `micrometer-core` and the Prometheus registry stay compatible. Micrometer's current stable documentation identifies 1.17.0.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.micrometer</groupId><artifactId>micrometer-bom</artifactId>
      <version>1.17.0</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-core</artifactId></dependency>
  <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
</dependencies>
```

### Registry, meters, and instrumentation

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import java.util.concurrent.atomic.AtomicInteger;

PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
Counter completed = Counter.builder("rmi_operations_completed")
    .description("Completed RMI operations").tag("service", "rmi").register(registry);
Timer latency = Timer.builder("rmi_operation_duration")
    .description("RMI operation duration").publishPercentileHistogram().register(registry);
AtomicInteger inFlight = new AtomicInteger();
Gauge.builder("rmi_operations_in_flight", inFlight, AtomicInteger::get).register(registry);

public Result call(String operation) {
  inFlight.incrementAndGet();
  Timer.Sample sample = Timer.start(registry);
  try {
    Result result = delegate.call(operation);
    completed.increment();
    return result;
  } finally {
    sample.stop(latency);
    inFlight.decrementAndGet();
  }
}
```

Counters should be monotonically increasing; timers record duration and count. Tags must have bounded cardinality (never use user IDs, raw URLs, request values, or unbounded exception messages as tags). Gauges observe a live object, so retain the `AtomicInteger`/queue for as long as the registry needs it. For production, bind standard JVM/process meters with `new ClassLoaderMetrics().bindTo(registry)`, `new JvmMemoryMetrics().bindTo(registry)`, and related binders as appropriate.

### Prometheus endpoint

`PrometheusMeterRegistry.scrape()` returns Prometheus text. The HTTP handler below is enough for a JDK server:

```java
private static void metrics(HttpExchange exchange, PrometheusMeterRegistry registry)
    throws IOException {
  byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
  exchange.sendResponseHeaders(200, body.length);
  try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
}
```

## 3. Lightweight HTTP server

### Options

| Option | Maven coordinates | Trade-off |
|---|---|---|
| JDK `HttpServer` | None; module `jdk.httpserver` (add `requires jdk.httpserver;` for a named module) | Smallest footprint and simplest handlers; HTTP/1.1 only and intentionally limited. Best baseline for `/health` and `/metrics`. |
| Jetty 12.1 | `org.eclipse.jetty:jetty-server:12.1.11` (use the current 12.1.x patch); commonly add `org.eclipse.jetty:jetty-http` and `org.eclipse.jetty:jetty-io` as required by the selected APIs | Mature embedded server, handlers/Servlets, HTTP/2/WebSocket/TLS ecosystem; substantially more dependencies/configuration. Use Jetty's BOM or a current patch release rather than mixing versions. |
| Undertow 2.4 | `io.undertow:undertow-core:2.4.1.Final` (use the current 2.4.x patch) | Lightweight, fast non-blocking handlers and routing; more API/runtime dependencies than JDK server and a larger operational surface. |

Jetty and Undertow coordinates are release examples observed in 2026 Maven listings; verify the latest security patch before pinning. Do not use the Maven plugin as the embedded runtime dependency. For this application, JDK `HttpServer` is the minimal option that works directly with `PrometheusMeterRegistry.scrape()`.

### JDK server with health and metrics

```java
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class ObservabilityServer {
  private final HttpServer server;
  private final PrometheusMeterRegistry registry;
  private volatile boolean ready;

  public ObservabilityServer(PrometheusMeterRegistry registry, int port) throws IOException {
    this.registry = registry;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    this.server.createContext("/health/live", e -> respond(e, 200, "{\"status\":\"UP\"}"));
    this.server.createContext("/health/ready", e -> respond(e, ready ? 200 : 503,
        ready ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}"));
    this.server.createContext("/metrics", e -> {
      if (!"GET".equals(e.getRequestMethod())) { respond(e, 405, ""); return; }
      byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
      e.sendResponseHeaders(200, body.length);
      try (OutputStream out = e.getResponseBody()) { out.write(body); }
    });
    this.server.setExecutor(Executors.newFixedThreadPool(4));
  }

  public void start() { server.start(); }
  public void setReady(boolean value) { ready = value; }
  public void stop() { server.stop(1); }

  private static void respond(HttpExchange e, int status, String text) throws IOException {
    if (!"GET".equals(e.getRequestMethod())) { status = 405; text = ""; }
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    e.getResponseHeaders().set("Cache-Control", "no-store");
    e.sendResponseHeaders(status, body.length);
    try (OutputStream out = e.getResponseBody()) { out.write(body); }
  }
}
```

Bind observability to loopback or a protected management interface, firewall it, and authenticate/authorize `/metrics` when it could reveal operational data. Do not expose a `/trace` endpoint that accepts arbitrary user input or returns request bodies/headers; use bounded trace IDs and a proper tracing system if tracing is required.

For Jetty, the equivalent shape is `Server server = new Server(port); server.setHandler(new AbstractHandler() { handle(...) { ... }}); server.start();`; for Undertow, it is `Undertow.builder().addHttpListener(port, "127.0.0.1").setHandler(exchange -> {...}).build().start();`. Both still use the same `registry.scrape()` body and content type, so neither is required merely to integrate Prometheus.

## 4. Health checks

Micrometer core is a meter facade, not a general health-contributor framework. Spring Boot Actuator has `HealthIndicator`/health contributors, but importing that stack solely for an RMI server adds a large framework and should not be assumed available in plain Java. Implement a small application-owned health model instead:

* **Liveness**: process/JVM is running and able to answer; do not fail it for a temporarily unavailable dependency, or orchestration may create a restart loop.
* **Readiness**: startup completed and required dependencies (RMI export, configuration, database/broker if any) are usable. Return HTTP 503 until ready and after shutdown begins.
* **Dependency checks**: use short timeouts, bounded work, no credentials or connection strings in response bodies/logs, and cache expensive checks rather than doing blocking I/O on every probe.

A simple implementation is the `volatile boolean ready` above plus a `HealthCheck` interface for named checks:

```java
public interface HealthCheck { boolean isHealthy(); }

public int readinessStatus(List<HealthCheck> checks) {
  return checks.stream().allMatch(HealthCheck::isHealthy) ? 200 : 503;
}
```

Return a small stable JSON document such as `{"status":"UP"}` or `{"status":"DOWN"}`. Keep detailed dependency/error diagnostics behind authenticated administration, if needed. Optionally emit bounded metrics such as `health_check_status{check="rmi"} 1`, but do not treat a metric scrape as the health API: orchestrators need the explicit HTTP status code.

## Sources

* SLF4J manual/download: https://www.slf4j.org/manual.html and https://www.slf4j.org/download.html
* Logback setup/configuration/download: https://logback.qos.ch/setup.html, https://logback.qos.ch/manual/configuration.html, https://logback.qos.ch/download.html
* Logstash Logback Encoder: https://github.com/logfellow/logstash-logback-encoder
* Micrometer installing, registry, Prometheus, gauges: https://docs.micrometer.io/micrometer/reference/installing.html, https://docs.micrometer.io/micrometer/reference/concepts/registry.html, https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html, https://docs.micrometer.io/micrometer/reference/concepts/gauges.html
* Java 17 `HttpServer` and module: https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html and https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/module-summary.html
* Jetty 12 programming guide: https://jetty.org/docs/jetty/12/programming-guide/
* Undertow downloads/documentation: https://undertow.io/downloads.html
* Maven Central coordinates: https://central.sonatype.com/artifact/io.micrometer/micrometer-bom, https://central.sonatype.com/artifact/org.eclipse.jetty/jetty-server, https://central.sonatype.com/artifact/io.undertow/undertow-core
* Project security requirement: `docs/security.md`, section “Log scrubbing”.
