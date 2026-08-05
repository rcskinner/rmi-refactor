package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * End-to-end integration tests verifying that traces appear in the Jaeger HTTP API after running
 * traced operations through the full RMI stack with OTLP export.
 *
 * <p>These tests require a running Jaeger all-in-one container on ports 16686 (API) and 4317 (OTLP
 * gRPC). They are skipped automatically when Jaeger is not reachable.
 *
 * <p>Fulfills VAL-TRACE-010 (end-to-end trace visible in Jaeger) and VAL-CROSS-004 (first-visit
 * setup and verification flow).
 */
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
class JaegerEndToEndIntegrationTest {
  private static final String JAEGER_API = "http://localhost:16686";

  private static final String OTLP_ENDPOINT = "http://localhost:4317";

  private static final ObjectMapper mapper = new ObjectMapper();

  private static HttpClient httpClient;

  private static ObservabilityContext serverCtx;

  private static ObservabilityContext clientCtx;

  private static PrometheusMeterRegistry prometheusRegistry;

  private static ObservabilityServer healthServer;

  private static Registry registry;

  private static int testRmiPort;

  private static LedgerRemoteImpl ledger;

  private static LedgerRemote remote;

  private static Tracer clientTracer;

  private static ListAppender<ILoggingEvent> logAppender;

  @BeforeAll
  static void setUp() throws Exception {
    assumeTrue(jaegerIsAvailable(), "Jaeger must be running on port 16686 for this test");

    httpClient = HttpClient.newHttpClient();

    // Initialize server observability with OTLP export to Jaeger
    GlobalOpenTelemetry.resetForTest();
    serverCtx =
        ObservabilityInitializer.initialize(
            null, null, null, OTLP_ENDPOINT, "grpc", "ledger-server");

    prometheusRegistry = serverCtx.getPrometheusRegistry();

    InMemoryDatabaseConnection database = new InMemoryDatabaseConnection();
    database.createPlan("demo-plan", new BigDecimal("100.00"));

    ledger = new LedgerRemoteImpl(database, serverCtx.getMeterRegistry(), serverCtx.getTracer());
    testRmiPort = findAvailablePort();
    registry = LocateRegistry.createRegistry(testRmiPort);
    registry.rebind("LedgerRemote", ledger);

    healthServer = new ObservabilityServer(prometheusRegistry, 8081);
    healthServer.registerHealthCheck(
        new HealthCheck() {
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
        });
    healthServer.start();

    // Initialize client observability with a different service name
    GlobalOpenTelemetry.resetForTest();
    clientCtx =
        ObservabilityInitializer.initialize(null, null, null, OTLP_ENDPOINT, "grpc", "ledger-cli");
    clientTracer = clientCtx.getTracer();

    remote = (LedgerRemote) registry.lookup("LedgerRemote");

    // Attach log appender to capture server-side log events
    Logger ledgerLogger = (Logger) LoggerFactory.getLogger(LedgerRemoteImpl.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    ledgerLogger.addAppender(logAppender);
  }

  private static int findAvailablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (logAppender != null) {
      Logger ledgerLogger = (Logger) LoggerFactory.getLogger(LedgerRemoteImpl.class);
      ledgerLogger.detachAppender(logAppender);
    }
    if (healthServer != null) {
      healthServer.stop();
    }
    if (ledger != null) {
      UnicastRemoteObject.unexportObject(ledger, true);
    }
    if (registry != null) {
      UnicastRemoteObject.unexportObject(registry, true);
    }
    if (clientCtx != null) {
      clientCtx.close();
    }
    if (serverCtx != null) {
      serverCtx.close();
    }
  }

  /**
   * Verifies that the server starts with the complete observability stack: HTTP on 8081, RMI on
   * 1099, health endpoints responding, and metrics available.
   */
  @Test
  @Order(1)
  void serverStartsWithCompleteObservabilityStack() throws Exception {
    // RMI registry on port 1099
    assertNotNull(registry.lookup("LedgerRemote"), "LedgerRemote should be bound in RMI registry");

    // HTTP health server — liveness
    HttpResponse<String> liveResponse = httpGet("http://127.0.0.1:8081/health/live");
    assertEquals(200, liveResponse.statusCode(), "Liveness should return 200");
    assertTrue(liveResponse.body().contains("\"UP\""), "Liveness should report UP");

    // HTTP health server — readiness
    HttpResponse<String> readyResponse = httpGet("http://127.0.0.1:8081/health/ready");
    assertEquals(200, readyResponse.statusCode(), "Readiness should return 200");
    assertTrue(readyResponse.body().contains("\"UP\""), "Readiness should report UP");

    // Metrics endpoint available
    HttpResponse<String> metricsResponse = httpGet("http://127.0.0.1:8081/metrics");
    assertEquals(200, metricsResponse.statusCode(), "Metrics should return 200");
    assertFalse(metricsResponse.body().isEmpty(), "Metrics body should not be empty");
  }

  /**
   * End-to-end flow: runs multiple traced operations, verifies metrics/logs, flushes spans to
   * Jaeger, then verifies Jaeger API shows both service names, traces with parent-child
   * relationships, and distinct traces for each operation.
   */
  @Test
  @Order(2)
  void endToEndFlowAllSignalsVerified() throws Exception {
    // Run multiple operations to generate distinct traces
    performContributeOperation("demo-plan", "25.00");
    performBalanceOperation("demo-plan");
    performWithdrawOperation("demo-plan", "10.00");

    // Verify metrics signal before closing contexts
    HttpResponse<String> metricsAfter = httpGet("http://127.0.0.1:8081/metrics");
    assertTrue(
        metricsAfter.body().contains("rmi_operations_total"),
        "Metrics should contain rmi_operations_total after operations");

    // Verify structured log signal
    assertFalse(logAppender.list.isEmpty(), "Structured log entries should be present");
    boolean hasOperationLog =
        logAppender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("operation."));
    assertTrue(hasOperationLog, "A structured log entry for the operation should be written");

    // Close contexts to flush all buffered spans to Jaeger via OTLP
    clientCtx.close();
    clientCtx = null;
    serverCtx.close();
    serverCtx = null;

    // Wait for Jaeger to index the exported traces
    awaitTracesInJaeger();

    // Verify /api/services contains both service names
    JsonNode services = fetchJson("/api/services");
    JsonNode serviceData = services.get("data");
    assertNotNull(serviceData, "Jaeger /api/services response should have a data array");
    boolean hasServer = false;
    boolean hasClient = false;
    for (JsonNode service : serviceData) {
      String name = service.asText();
      if ("ledger-server".equals(name)) {
        hasServer = true;
      }
      if ("ledger-cli".equals(name)) {
        hasClient = true;
      }
    }
    assertTrue(hasServer, "Jaeger should report ledger-server service");
    assertTrue(hasClient, "Jaeger should report ledger-cli service");

    // Verify /api/traces shows traces with parent-child relationship
    JsonNode traces = fetchJson("/api/traces?service=ledger-server");
    JsonNode traceData = traces.get("data");
    assertNotNull(traceData, "Jaeger /api/traces response should have a data array");
    assertTrue(traceData.size() > 0, "At least one trace should be returned for ledger-server");

    boolean foundParentChild = false;
    for (JsonNode trace : traceData) {
      JsonNode spans = trace.get("spans");
      JsonNode processes = trace.get("processes");
      if (spans == null || processes == null) {
        continue;
      }
      String clientSpanId = findSpanIdByService(spans, processes, "ledger-cli");
      String serverParentId = findParentSpanId(spans, processes, "ledger-server");
      if (clientSpanId != null && clientSpanId.equals(serverParentId)) {
        foundParentChild = true;
        break;
      }
    }
    assertTrue(
        foundParentChild,
        "At least one trace should show a server span whose parent is a client span");

    // Verify multiple operations produce distinct traces (no cross-request leakage)
    assertTrue(traceData.size() >= 3, "At least 3 distinct traces should be present");
    long distinctTraceIds =
        StreamSupport.stream(traceData.spliterator(), false)
            .map(t -> t.get("traceID").asText())
            .distinct()
            .count();
    assertEquals(traceData.size(), distinctTraceIds, "All trace IDs should be distinct");
  }

  private void performContributeOperation(String planId, String amount) throws Exception {
    Span span = startClientSpan("contribute", "addOrSubtract", planId);
    span.setAttribute("operation", "add");
    span.setAttribute("amount", new BigDecimal(amount).doubleValue());
    try (Scope scope = span.makeCurrent()) {
      String traceContext = TraceContextCarrier.inject(Context.current());
      remote.addOrSubtract(planId, new BigDecimal(amount), LedgerOperation.ADD, traceContext);
    } finally {
      span.end();
    }
  }

  private void performWithdrawOperation(String planId, String amount) throws Exception {
    Span span = startClientSpan("withdraw", "addOrSubtract", planId);
    span.setAttribute("operation", "subtract");
    span.setAttribute("amount", new BigDecimal(amount).doubleValue());
    try (Scope scope = span.makeCurrent()) {
      String traceContext = TraceContextCarrier.inject(Context.current());
      remote.addOrSubtract(planId, new BigDecimal(amount), LedgerOperation.SUBTRACT, traceContext);
    } finally {
      span.end();
    }
  }

  private void performBalanceOperation(String planId) throws Exception {
    Span span = startClientSpan("balance", "getBalance", planId);
    try (Scope scope = span.makeCurrent()) {
      String traceContext = TraceContextCarrier.inject(Context.current());
      remote.getBalance(planId, traceContext);
    } finally {
      span.end();
    }
  }

  private Span startClientSpan(String spanName, String rpcMethod, String planId) {
    Span span = clientTracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
    span.setAttribute("rpc.system", "java_rmi");
    span.setAttribute("rpc.method", rpcMethod);
    span.setAttribute("plan.id", planId);
    return span;
  }

  private void awaitTracesInJaeger() throws InterruptedException {
    for (int i = 0; i < 30; i++) {
      try {
        JsonNode traces = fetchJson("/api/traces?service=ledger-server");
        JsonNode data = traces.get("data");
        if (data != null && data.size() > 0) {
          return;
        }
      } catch (IOException ignored) {
        // Jaeger not ready yet, keep polling
      }
      Thread.sleep(Duration.ofSeconds(2).toMillis());
    }
  }

  private static boolean jaegerIsAvailable() {
    try {
      java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(JAEGER_API + "/api/services"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private JsonNode fetchJson(String path) throws IOException, InterruptedException {
    HttpResponse<String> response = httpGet(JAEGER_API + path);
    if (response.statusCode() != 200) {
      throw new IOException("Jaeger API " + path + " returned status " + response.statusCode());
    }
    return mapper.readTree(response.body());
  }

  private HttpResponse<String> httpGet(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String findSpanIdByService(JsonNode spans, JsonNode processes, String serviceName) {
    for (JsonNode span : spans) {
      if (isSpanFromService(span, processes, serviceName)) {
        return span.get("spanID").asText();
      }
    }
    return null;
  }

  /**
   * Finds the parent span ID for a span belonging to the given service by inspecting the Jaeger
   * {@code references} array for a {@code CHILD_OF} reference.
   *
   * @param spans the spans array in a Jaeger trace
   * @param processes the processes map in a Jaeger trace
   * @param serviceName the service name to find the span for
   * @return the parent span ID from the CHILD_OF reference, or {@code null} if not found
   */
  private String findParentSpanId(JsonNode spans, JsonNode processes, String serviceName) {
    for (JsonNode span : spans) {
      if (isSpanFromService(span, processes, serviceName)) {
        String parentId = extractChildOfParentId(span);
        if (parentId != null) {
          return parentId;
        }
      }
    }
    return null;
  }

  private boolean isSpanFromService(JsonNode span, JsonNode processes, String serviceName) {
    String processId = span.get("processID").asText();
    JsonNode process = processes.get(processId);
    return process != null && serviceName.equals(process.get("serviceName").asText());
  }

  private String extractChildOfParentId(JsonNode span) {
    JsonNode references = span.get("references");
    if (references == null || !references.isArray()) {
      return null;
    }
    for (JsonNode ref : references) {
      if ("CHILD_OF".equals(ref.get("refType").asText())) {
        return ref.get("spanID").asText();
      }
    }
    return null;
  }
}
