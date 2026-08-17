package com.example.rmirefactor.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

/** Adds the active OpenTelemetry trace identifiers to structured logs for one scope. */
public final class TraceLogContext implements AutoCloseable {

  private static final String TRACE_ID = "trace_id";

  private static final String SPAN_ID = "span_id";

  private final String previousTraceId;

  private final String previousSpanId;

  private TraceLogContext(SpanContext spanContext) {
    previousTraceId = MDC.get(TRACE_ID);
    previousSpanId = MDC.get(SPAN_ID);
    if (spanContext.isValid()) {
      MDC.put(TRACE_ID, spanContext.getTraceId());
      MDC.put(SPAN_ID, spanContext.getSpanId());
    }
  }

  /**
   * Creates a logging scope for a span.
   *
   * @param span span whose identifiers should be added to logs
   * @return a scope that restores the previous MDC values when closed
   */
  public static TraceLogContext forSpan(Span span) {
    return new TraceLogContext(span.getSpanContext());
  }

  @Override
  public void close() {
    restore(TRACE_ID, previousTraceId);
    restore(SPAN_ID, previousSpanId);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }
}
