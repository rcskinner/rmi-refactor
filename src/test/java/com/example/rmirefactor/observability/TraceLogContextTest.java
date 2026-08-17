package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;

/** Verifies trace identifiers are available to the structured logging backend. */
class TraceLogContextTest {

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void addsTraceAndSpanIdsAndRestoresPreviousValues() {
    MDC.put("trace_id", "previous-trace");
    MDC.put("span_id", "previous-span");
    Tracer tracer = otel.getOpenTelemetry().getTracer("test");

    var span = tracer.spanBuilder("operation").startSpan();
    try (TraceLogContext ignored = TraceLogContext.forSpan(span)) {
      assertEquals(span.getSpanContext().getTraceId(), MDC.get("trace_id"));
      assertEquals(span.getSpanContext().getSpanId(), MDC.get("span_id"));
    } finally {
      span.end();
    }

    assertEquals("previous-trace", MDC.get("trace_id"));
    assertEquals("previous-span", MDC.get("span_id"));
  }

  @Test
  void removesIdentifiersWhenNoPreviousValuesExist() {
    Tracer tracer = otel.getOpenTelemetry().getTracer("test");

    var span = tracer.spanBuilder("operation").startSpan();
    try (TraceLogContext ignored = TraceLogContext.forSpan(span)) {
      // The scope is exercised here; assertions below verify cleanup.
    } finally {
      span.end();
    }

    assertNull(MDC.get("trace_id"));
    assertNull(MDC.get("span_id"));
  }
}
