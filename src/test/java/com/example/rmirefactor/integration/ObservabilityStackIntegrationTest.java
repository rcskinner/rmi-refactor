package com.example.rmirefactor.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.rmirefactor.client.RmiClient;
import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import com.example.rmirefactor.observability.HealthCheck;
import com.example.rmirefactor.observability.ObservabilityServer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/**
 * Integration tests verifying cross-area observability flows: the full server starts with the
 * complete observability stack, and after a CLI operation, the metrics counter increments, a trace
 * span is created, and a structured log entry is written.
 *
 * <p>Fulfills VAL-CROSS-001 (all signals fire for one operation) and VAL-CROSS-002 (server starts
 * with complete observability stack).
 */
@Timeout(30)
@SuppressWarnings("PMD.TooManyMethods")
class ObservabilityStackIntegrationTest {

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  private PrometheusMeterRegistry prometheusRegistry;

  private CompositeMeterRegistry compositeRegistry;

  private ObservabilityServer healthServer;

  private Registry registry;

  private LedgerRemoteImpl ledger;

  private HttpClient httpClient;

  private String baseUrl;

  private ListAppender<ILoggingEvent> logAppender;

  private PrintStream originalOut;

  private PrintStream originalErr;

  @BeforeEach
  void setUp() throws Exception {
    prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    compositeRegistry = new CompositeMeterRegistry();
    compositeRegistry.add(prometheusRegistry);
    new JvmMemoryMetrics().bindTo(compositeRegistry);
    new JvmGcMetrics().bindTo(compositeRegistry);
    new JvmThreadMetrics().bindTo(compositeRegistry);

    Tracer tracer = otel.getOpenTelemetry().getTracer("test-server");
    InMemoryDatabaseConnection database = new InMemoryDatabaseConnection();
    database.createPlan("demo-plan", new BigDecimal("100.00"));
    ledger = new LedgerRemoteImpl(database, compositeRegistry, tracer);

    registry = LocateRegistry.createRegistry(1099);
    registry.rebind("LedgerRemote", ledger);

    healthServer = new ObservabilityServer(prometheusRegistry, 8081);
    healthServer.registerHealthCheck(new RmiRegistryReadyCheck(registry));
    healthServer.start();

    httpClient = HttpClient.newHttpClient();
    baseUrl = "http://127.0.0.1:8081";

    Logger ledgerLogger = (Logger) LoggerFactory.getLogger(LedgerRemoteImpl.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    ledgerLogger.addAppender(logAppender);

    originalOut = System.out;
    originalErr = System.err;
  }

  @AfterEach
  void tearDown() throws Exception {
    System.setOut(originalOut);
    System.setErr(originalErr);
    Logger ledgerLogger = (Logger) LoggerFactory.getLogger(LedgerRemoteImpl.class);
    ledgerLogger.detachAppender(logAppender);
    healthServer.stop();
    compositeRegistry.close();
    UnicastRemoteObject.unexportObject(ledger, true);
    UnicastRemoteObject.unexportObject(registry, true);
  }

  @Test
  void serverStartsWithCompleteObservabilityStack() throws Exception {
    // RMI registry on port 1099
    assertNotNull(registry.lookup("LedgerRemote"), "LedgerRemote should be bound in RMI registry");

    // HTTP health server on port 8081 — liveness
    HttpResponse<String> liveResponse = httpGet("/health/live");
    assertEquals(200, liveResponse.statusCode(), "Liveness should return 200");
    assertTrue(liveResponse.body().contains("\"UP\""), "Liveness body should contain UP");

    // HTTP health server — readiness
    HttpResponse<String> readyResponse = httpGet("/health/ready");
    assertEquals(200, readyResponse.statusCode(), "Readiness should return 200");
    assertTrue(readyResponse.body().contains("\"UP\""), "Readiness body should contain UP");

    // Metrics endpoint available
    HttpResponse<String> metricsResponse = httpGet("/metrics");
    assertEquals(200, metricsResponse.statusCode(), "Metrics should return 200");
    assertTrue(
        metricsResponse.headers().firstValue("Content-Type").isPresent(),
        "Metrics should have Content-Type header");
    assertTrue(
        metricsResponse.headers().firstValue("Content-Type").get().contains("text/plain"),
        "Metrics Content-Type should be text/plain");
    assertFalse(metricsResponse.body().isEmpty(), "Metrics body should not be empty");
  }

  @Test
  void afterCliOperationMetricsCounterIncrements() throws Exception {
    String metricsBefore = httpGet("/metrics").body();
    double countBefore = extractCounterValue(metricsBefore, "rmi_operations_total");

    runCliSafely(new String[] {"contribute", "demo-plan", "50.00"});

    String metricsAfter = httpGet("/metrics").body();
    double countAfter = extractCounterValue(metricsAfter, "rmi_operations_total");

    assertTrue(
        countAfter > countBefore,
        "rmi_operations_total counter should increment after a CLI operation");
    assertTrue(
        metricsAfter.contains("rmi_operations_total"),
        "Metrics should contain rmi_operations_total counter");
    assertTrue(
        metricsAfter.contains("rmi_operation_duration"),
        "Metrics should contain rmi_operation_duration timer");
  }

  @Test
  void afterCliOperationTraceSpanIsCreated() throws Exception {
    runCliSafely(new String[] {"contribute", "demo-plan", "50.00"});

    List<SpanData> spans = otel.getSpans();
    assertFalse(
        spans.isEmpty(), "At least one server span should be created after a CLI operation");

    SpanData serverSpan =
        spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElse(null);
    assertNotNull(serverSpan, "A SERVER kind span should be present");
    assertEquals(
        "addOrSubtract", serverSpan.getName(), "Server span should be named addOrSubtract");
  }

  @Test
  void afterCliOperationStructuredLogEntryIsWritten() throws Exception {
    runCliSafely(new String[] {"contribute", "demo-plan", "50.00"});

    assertFalse(logAppender.list.isEmpty(), "At least one log event should be recorded");

    boolean hasStartEvent =
        logAppender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("operation.started"));
    boolean hasCompletedEvent =
        logAppender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("operation.completed"));

    assertTrue(hasStartEvent, "A operation.started log event should be written");
    assertTrue(hasCompletedEvent, "A operation.completed log event should be written");
  }

  @Test
  void allSignalsFireForOneOperation() throws Exception {
    String metricsBefore = httpGet("/metrics").body();
    double counterBefore = extractCounterValue(metricsBefore, "rmi_operations_total");
    int spanCountBefore = otel.getSpans().size();
    int logCountBefore = logAppender.list.size();

    runCliSafely(new String[] {"contribute", "demo-plan", "75.00"});

    // Metrics signal
    String metricsAfter = httpGet("/metrics").body();
    double counterAfter = extractCounterValue(metricsAfter, "rmi_operations_total");
    assertTrue(counterAfter > counterBefore, "Counter should increment");

    // Trace signal
    assertTrue(otel.getSpans().size() > spanCountBefore, "At least one new span should be created");

    // Log signal
    assertTrue(
        logAppender.list.size() > logCountBefore, "At least one new log entry should be written");

    // CLI output signal
    // (verified implicitly by the operation completing without error)
  }

  @Test
  void jvmMetricsArePresentInPrometheusOutput() throws Exception {
    String metrics = httpGet("/metrics").body();

    assertTrue(metrics.contains("jvm_memory_"), "JVM memory metrics should be present");
    assertTrue(metrics.contains("jvm_threads_"), "JVM thread metrics should be present");
  }

  private HttpResponse<String> httpGet(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void runCliSafely(String[] args) {
    ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8));
    try {
      RmiClient.main(args);
    } finally {
      System.setOut(originalOut);
    }
  }

  private double extractCounterValue(String metricsText, String metricName) {
    double total = 0;
    for (String line : metricsText.split("\n")) {
      if (line.startsWith(metricName) && !line.startsWith("#")) {
        String value = line.substring(line.lastIndexOf(' ') + 1);
        try {
          total += Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
          // Skip non-numeric lines
        }
      }
    }
    return total;
  }

  /** Simple health check that verifies the RMI registry has the LedgerRemote service bound. */
  private static final class RmiRegistryReadyCheck implements HealthCheck {
    private final Registry registry;

    RmiRegistryReadyCheck(Registry registry) {
      this.registry = registry;
    }

    @Override
    public String getName() {
      return "rmi-registry";
    }

    @Override
    public boolean isHealthy() {
      try {
        registry.lookup("LedgerRemote");
        return true;
      } catch (RemoteException | NotBoundException e) {
        return false;
      }
    }
  }
}
