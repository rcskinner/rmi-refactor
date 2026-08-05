package com.example.rmirefactor.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.example.rmirefactor.observability.SpanAssertions;
import com.example.rmirefactor.observability.TraceContextCarrier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.math.BigDecimal;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Verifies OpenTelemetry tracing instrumentation in {@link LedgerRemoteImpl}.
 *
 * <p>Tests assert server-side span creation, kind, attributes, status, context propagation, and
 * null/invalid traceContext handling using {@link OpenTelemetryExtension}.
 */
@SuppressWarnings("PMD.TooManyMethods")
class LedgerRemoteImplTracingTest {
  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  @Mock private DatabaseConnection database;

  private SimpleMeterRegistry registry;

  private Tracer tracer;

  private LedgerRemoteImpl ledger;

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    tracer = otel.getOpenTelemetry().getTracer("test");
    registry = new SimpleMeterRegistry();
    ledger = new LedgerRemoteImpl(database, registry, tracer);
  }

  @AfterEach
  void tearDown() throws Exception {
    UnicastRemoteObject.unexportObject(ledger, true);
    registry.close();
  }

  @Test
  void addOrSubtractCreatesServerSpan() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    List<SpanData> spans = otel.getSpans();
    assertEquals(1, spans.size(), "Exactly one span should be created");
    assertEquals("addOrSubtract", spans.get(0).getName(), "Span name should match method name");
    assertEquals(SpanKind.SERVER, spans.get(0).getKind(), "Span kind should be SERVER");
  }

  @Test
  void getBalanceCreatesServerSpan() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.getBalance("plan-1", null);

    List<SpanData> spans = otel.getSpans();
    assertEquals(1, spans.size(), "Exactly one span should be created");
    assertEquals("getBalance", spans.get(0).getName(), "Span name should match method name");
    assertEquals(SpanKind.SERVER, spans.get(0).getKind(), "Span kind should be SERVER");
  }

  @Test
  void addOrSubtractSpanHasRpcAndBusinessAttributes() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    SpanData span = otel.getSpans().get(0);
    SpanAssertions.assertRpcAttributes(span, "addOrSubtract", "plan-1");
    assertEquals(
        "add",
        span.getAttributes().get(AttributeKey.stringKey("operation")),
        "operation should be add");
    assertEquals(
        25.0,
        span.getAttributes().get(AttributeKey.doubleKey("amount")),
        "amount should match as double");
  }

  @Test
  void subtractSpanHasCorrectOperationAttribute() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.SUBTRACT, null);

    SpanData span = otel.getSpans().get(0);
    assertEquals(
        "subtract",
        span.getAttributes().get(AttributeKey.stringKey("operation")),
        "operation should be subtract");
  }

  @Test
  void getBalanceSpanHasRpcAndPlanAttributesButNoOperationOrAmount() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.getBalance("plan-1", null);

    SpanData span = otel.getSpans().get(0);
    SpanAssertions.assertRpcAttributes(span, "getBalance", "plan-1");
    assertNull(
        span.getAttributes().get(AttributeKey.stringKey("operation")),
        "getBalance span should not have operation attribute");
    assertNull(
        span.getAttributes().get(AttributeKey.doubleKey("amount")),
        "getBalance span should not have amount attribute");
  }

  @Test
  void nullTraceContextCreatesRootServerSpanWithNoParent() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    SpanData span = otel.getSpans().get(0);
    assertFalse(
        span.getParentSpanContext().isValid(),
        "Root server span should have no valid parent span context");
  }

  @Test
  void emptyTraceContextCreatesRootServerSpan() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, "");

    SpanData span = otel.getSpans().get(0);
    assertFalse(
        span.getParentSpanContext().isValid(),
        "Empty traceContext should produce root span with no parent");
  }

  @Test
  void malformedTraceContextCreatesRootServerSpanAndOperationSucceeds() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, "garbage-value");

    SpanData span = otel.getSpans().get(0);
    assertNotNull(span, "A valid span should be created even with malformed traceContext");
    assertFalse(
        span.getParentSpanContext().isValid(),
        "Malformed traceContext should produce root span with no parent");
  }

  @Test
  void malformedTraceparentCreatesRootServerSpan() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract(
        "plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, "traceparent=invalid-value");

    SpanData span = otel.getSpans().get(0);
    assertFalse(
        span.getParentSpanContext().isValid(),
        "Malformed traceparent should produce root span with no parent");
  }

  @Test
  void successfulSpanHasNoErrorStatus() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    SpanData span = otel.getSpans().get(0);
    assertEquals(
        StatusCode.UNSET,
        span.getStatus().getStatusCode(),
        "Success span should have UNSET status");
    assertTrue(span.getEvents().isEmpty(), "Success span should have no events");
  }

  @Test
  void failedAddOrSubtractRecordsErrorStatusAndException() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD, null));

    SpanData span = otel.getSpans().get(0);
    assertEquals(
        StatusCode.ERROR, span.getStatus().getStatusCode(), "Failed span should have ERROR status");
    assertFalse(span.getEvents().isEmpty(), "Failed span should have exception event");
    assertEquals(
        "exception",
        span.getEvents().get(0).getName(),
        "Exception event should be named 'exception'");
  }

  @Test
  void failedGetBalanceRecordsErrorStatusAndException() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(LedgerException.class, () -> ledger.getBalance("missing", null));

    SpanData span = otel.getSpans().get(0);
    assertEquals(
        StatusCode.ERROR, span.getStatus().getStatusCode(), "Failed span should have ERROR status");
    assertFalse(span.getEvents().isEmpty(), "Failed span should have exception event");
  }

  @Test
  void serverSpanParentMatchesClientSpanWhenTraceContextProvided() throws Exception {
    setupPlanBalance("plan-1", "100.00");
    runWithClientSpanAndPropagate(
        "contribute",
        () ->
            ledger.addOrSubtract(
                "plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, injectContext()));

    SpanAssertions.assertParentChildRelationship(otel.getSpans());
  }

  @Test
  void getBalanceContextPropagatesFromClientToServer() throws Exception {
    setupPlanBalance("plan-1", "100.00");
    runWithClientSpanAndPropagate("balance", () -> ledger.getBalance("plan-1", injectContext()));

    SpanAssertions.assertParentChildRelationship(otel.getSpans());
  }

  @Test
  void multipleOperationsProduceDistinctSpans() throws Exception {
    setupPlanBalance("plan-1", "100.00");

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);
    ledger.getBalance("plan-1", null);
    ledger.addOrSubtract("plan-1", new BigDecimal("10.00"), LedgerOperation.SUBTRACT, null);

    List<SpanData> spans = otel.getSpans();
    assertEquals(3, spans.size(), "Three operations should produce three spans");
    assertEquals(2, spans.stream().filter(s -> s.getName().equals("addOrSubtract")).count());
    assertEquals(1, spans.stream().filter(s -> s.getName().equals("getBalance")).count());
  }

  private void setupPlanBalance(String planId, String balance) {
    when(database.planExists(planId)).thenReturn(true);
    when(database.getBalance(planId)).thenReturn(new BigDecimal(balance));
  }

  private String injectContext() {
    String traceContext = TraceContextCarrier.inject(Context.current());
    assertNotNull(traceContext, "Injected trace context should not be null");
    assertFalse(traceContext.isEmpty(), "Injected trace context should not be empty");
    return traceContext;
  }

  private void runWithClientSpanAndPropagate(String spanName, ThrowingRunnable operation)
      throws Exception {
    Span clientSpan = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
    try (Scope scope = clientSpan.makeCurrent()) {
      operation.run();
    } finally {
      clientSpan.end();
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
