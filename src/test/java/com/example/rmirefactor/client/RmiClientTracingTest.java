package com.example.rmirefactor.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.observability.SpanAssertions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/**
 * Verifies OpenTelemetry client-side tracing instrumentation in {@link RmiClient}.
 *
 * <p>Tests assert CLIENT span creation, kind, attributes, trace context injection, error status
 * recording, and context propagation using {@link OpenTelemetryExtension} and a mock {@link
 * LedgerRemote} to avoid RMI complexity.
 */
@SuppressWarnings("PMD.TooManyMethods")
class RmiClientTracingTest {
  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  private Tracer tracer;

  private RmiClient client;

  private LedgerRemote ledger;

  @BeforeEach
  void setUp() {
    tracer = otel.getOpenTelemetry().getTracer("test");
    ledger = mock(LedgerRemote.class);
    client = new RmiClient(tracer);
  }

  @Test
  void contributeCreatesClientSpan() throws Exception {
    client.executeCommand(
        "contribute", "plan-1", new String[] {"contribute", "plan-1", "25.00"}, ledger);

    assertSingleSpan("contribute", SpanKind.CLIENT);
  }

  @Test
  void withdrawCreatesClientSpan() throws Exception {
    client.executeCommand(
        "withdraw", "plan-1", new String[] {"withdraw", "plan-1", "10.00"}, ledger);

    assertSingleSpan("withdraw", SpanKind.CLIENT);
  }

  @Test
  void balanceCreatesClientSpan() throws Exception {
    when(ledger.getBalance(anyString(), any())).thenReturn(new BigDecimal("100.00"));
    client.executeCommand("balance", "plan-1", new String[] {"balance", "plan-1"}, ledger);

    assertSingleSpan("balance", SpanKind.CLIENT);
  }

  @Test
  void contributeSpanHasRpcAndBusinessAttributes() throws Exception {
    client.executeCommand(
        "contribute", "plan-1", new String[] {"contribute", "plan-1", "25.00"}, ledger);

    SpanData span = otel.getSpans().get(0);
    SpanAssertions.assertRpcAttributes(span, "addOrSubtract", "plan-1");
    assertEquals(
        "add",
        span.getAttributes().get(AttributeKey.stringKey("operation")),
        "operation should be add");
    assertEquals(
        25.0, span.getAttributes().get(AttributeKey.doubleKey("amount")), "amount should be 25.0");
  }

  @Test
  void withdrawSpanHasRpcAndBusinessAttributes() throws Exception {
    client.executeCommand(
        "withdraw", "plan-1", new String[] {"withdraw", "plan-1", "10.00"}, ledger);

    SpanData span = otel.getSpans().get(0);
    SpanAssertions.assertRpcAttributes(span, "addOrSubtract", "plan-1");
    assertEquals(
        "subtract",
        span.getAttributes().get(AttributeKey.stringKey("operation")),
        "operation should be subtract");
    assertEquals(
        10.0, span.getAttributes().get(AttributeKey.doubleKey("amount")), "amount should be 10.0");
  }

  @Test
  void balanceSpanHasRpcAndPlanAttributesButNoOperationOrAmount() throws Exception {
    when(ledger.getBalance(anyString(), any())).thenReturn(new BigDecimal("100.00"));
    client.executeCommand("balance", "plan-1", new String[] {"balance", "plan-1"}, ledger);

    SpanData span = otel.getSpans().get(0);
    SpanAssertions.assertRpcAttributes(span, "getBalance", "plan-1");
    assertEquals(
        null,
        span.getAttributes().get(AttributeKey.stringKey("operation")),
        "balance span should not have operation attribute");
    assertEquals(
        null,
        span.getAttributes().get(AttributeKey.doubleKey("amount")),
        "balance span should not have amount attribute");
  }

  @Test
  void contributeInjectsTraceContextIntoRemoteCall() throws Exception {
    client.executeCommand(
        "contribute", "plan-1", new String[] {"contribute", "plan-1", "25.00"}, ledger);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(ledger)
        .addOrSubtract(
            eq("plan-1"), eq(new BigDecimal("25.00")), eq(LedgerOperation.ADD), captor.capture());
    assertValidTraceparent(captor.getValue());
  }

  @Test
  void withdrawInjectsTraceContextIntoRemoteCall() throws Exception {
    client.executeCommand(
        "withdraw", "plan-1", new String[] {"withdraw", "plan-1", "10.00"}, ledger);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(ledger)
        .addOrSubtract(
            eq("plan-1"),
            eq(new BigDecimal("10.00")),
            eq(LedgerOperation.SUBTRACT),
            captor.capture());
    assertValidTraceparent(captor.getValue());
  }

  @Test
  void balanceInjectsTraceContextIntoRemoteCall() throws Exception {
    when(ledger.getBalance(anyString(), any())).thenReturn(new BigDecimal("100.00"));
    client.executeCommand("balance", "plan-1", new String[] {"balance", "plan-1"}, ledger);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(ledger).getBalance(eq("plan-1"), captor.capture());
    assertValidTraceparent(captor.getValue());
  }

  @Test
  void successfulContributeSpanHasNoErrorStatus() throws Exception {
    client.executeCommand(
        "contribute", "plan-1", new String[] {"contribute", "plan-1", "25.00"}, ledger);

    SpanData span = otel.getSpans().get(0);
    assertEquals(
        StatusCode.UNSET,
        span.getStatus().getStatusCode(),
        "Successful span should have UNSET status");
    assertTrue(span.getEvents().isEmpty(), "Successful span should have no events");
  }

  @Test
  void failedContributeRecordsErrorStatusAndException() throws Exception {
    doThrow(new LedgerException("plan does not exist"))
        .when(ledger)
        .addOrSubtract(anyString(), any(BigDecimal.class), any(LedgerOperation.class), anyString());

    assertThrows(
        LedgerException.class,
        () ->
            client.executeCommand(
                "contribute", "missing", new String[] {"contribute", "missing", "1.00"}, ledger));

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
  void failedBalanceRecordsErrorStatusAndException() throws Exception {
    when(ledger.getBalance(anyString(), any())).thenThrow(new LedgerException("plan not found"));

    assertThrows(
        LedgerException.class,
        () ->
            client.executeCommand(
                "balance", "missing", new String[] {"balance", "missing"}, ledger));

    SpanData span = otel.getSpans().get(0);
    assertEquals(
        StatusCode.ERROR,
        span.getStatus().getStatusCode(),
        "Failed balance span should have ERROR status");
    assertFalse(span.getEvents().isEmpty(), "Failed balance span should have exception event");
  }

  @Test
  void clientSpanHasValidTraceId() throws Exception {
    when(ledger.getBalance(anyString(), any())).thenReturn(new BigDecimal("100.00"));
    client.executeCommand("balance", "plan-1", new String[] {"balance", "plan-1"}, ledger);

    SpanData clientSpan = otel.getSpans().get(0);
    assertTrue(clientSpan.getSpanContext().isValid(), "Client span context should be valid");
  }

  private void assertSingleSpan(String expectedName, SpanKind expectedKind) {
    List<SpanData> spans = otel.getSpans();
    assertEquals(1, spans.size(), "Exactly one span should be created");
    assertEquals(expectedName, spans.get(0).getName(), "Span name should match");
    assertEquals(expectedKind, spans.get(0).getKind(), "Span kind should match");
  }

  private static void assertValidTraceparent(String traceContext) {
    assertNotNull(traceContext, "traceContext should not be null");
    assertFalse(traceContext.isEmpty(), "traceContext should not be empty");
    assertTrue(
        traceContext.contains("traceparent"),
        "traceContext should contain a W3C traceparent header");
  }
}
