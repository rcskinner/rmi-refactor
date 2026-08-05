package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Verifies {@link TraceContextCarrier} inject/extract round-trip and edge-case handling. */
class TraceContextCarrierTest {

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  private Tracer tracer() {
    return otel.getOpenTelemetry().getTracer("test");
  }

  @Test
  void injectAndExtractRoundTripsTraceContext() {
    Span span = tracer().spanBuilder("test-span").startSpan();
    try (Scope scope = span.makeCurrent()) {
      String encoded = TraceContextCarrier.inject(Context.current());

      assertNotNull(encoded);
      assertFalse(encoded.isEmpty(), "Injected carrier should not be empty");
      assertTrue(encoded.contains("traceparent"), "Carrier should contain traceparent header");

      Context extracted = TraceContextCarrier.extract(encoded);
      SpanContext extractedSpanContext = Span.fromContext(extracted).getSpanContext();

      assertTrue(extractedSpanContext.isValid(), "Extracted span context should be valid");
      assertEquals(
          span.getSpanContext().getTraceId(),
          extractedSpanContext.getTraceId(),
          "Trace ID should match after round-trip");
      assertEquals(
          span.getSpanContext().getSpanId(),
          extractedSpanContext.getSpanId(),
          "Span ID should match after round-trip");
    } finally {
      span.end();
    }
  }

  @Test
  void injectNullContextReturnsEmptyString() {
    String encoded = TraceContextCarrier.inject(null);
    assertEquals("", encoded, "Injecting null context should return empty string");
  }

  @Test
  void injectContextWithoutSpanReturnsEmptyString() {
    String encoded = TraceContextCarrier.inject(Context.root());
    assertEquals(
        "",
        encoded,
        "Injecting context without a span should return empty string (no traceparent to propagate)");
  }

  @Test
  void extractNullReturnsRootContext() {
    Context result = TraceContextCarrier.extract(null);
    assertNotNull(result);
    assertFalse(
        Span.fromContext(result).getSpanContext().isValid(),
        "Root context should not have a valid span context");
  }

  @Test
  void extractEmptyStringReturnsRootContext() {
    Context result = TraceContextCarrier.extract("");
    assertNotNull(result);
    assertFalse(
        Span.fromContext(result).getSpanContext().isValid(),
        "Empty carrier should yield root context without valid span");
  }

  @Test
  void extractMalformedStringReturnsRootContext() {
    Context result = TraceContextCarrier.extract("not-a-valid-traceparent");
    assertNotNull(result);
    assertFalse(
        Span.fromContext(result).getSpanContext().isValid(),
        "Malformed carrier should yield root context without valid span");
  }

  @Test
  void extractMalformedTraceparentReturnsRootContext() {
    Context result = TraceContextCarrier.extract("traceparent=invalid-value");
    assertNotNull(result);
    assertFalse(
        Span.fromContext(result).getSpanContext().isValid(),
        "Malformed traceparent value should yield root context without valid span");
  }
}
