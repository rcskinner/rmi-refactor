package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;

/** Shared assertion helpers for OpenTelemetry span unit tests. */
public final class SpanAssertions {
  private SpanAssertions() {}

  /**
   * Asserts the standard RPC span attributes are present and correct.
   *
   * @param span the span to check
   * @param rpcMethod the expected rpc.method value
   * @param planId the expected plan.id value
   */
  public static void assertRpcAttributes(SpanData span, String rpcMethod, String planId) {
    assertEquals(
        "java_rmi",
        span.getAttributes().get(AttributeKey.stringKey("rpc.system")),
        "rpc.system should be java_rmi");
    assertEquals(
        rpcMethod,
        span.getAttributes().get(AttributeKey.stringKey("rpc.method")),
        "rpc.method should match expected value");
    assertEquals(
        planId,
        span.getAttributes().get(AttributeKey.stringKey("plan.id")),
        "plan.id should match expected value");
  }

  /**
   * Finds the first span of the given kind in the list and returns it.
   *
   * @param spans the list of completed spans
   * @param kind the span kind to find
   * @return the first matching span
   * @throws AssertionError if no span of the given kind is found
   */
  public static SpanData findSpanByKind(List<SpanData> spans, SpanKind kind) {
    return spans.stream()
        .filter(s -> s.getKind() == kind)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No " + kind + " span found"));
  }

  /**
   * Asserts that a server span is a child of a client span (trace ID match, parent span ID match).
   *
   * @param spans the list of completed spans containing both client and server spans
   */
  public static void assertParentChildRelationship(List<SpanData> spans) {
    SpanData serverSpan = findSpanByKind(spans, SpanKind.SERVER);
    SpanData clientSpan = findSpanByKind(spans, SpanKind.CLIENT);
    assertNotNull(serverSpan, "Server span should exist");
    assertNotNull(clientSpan, "Client span should exist");
    assertEquals(
        clientSpan.getSpanContext().getTraceId(),
        serverSpan.getSpanContext().getTraceId(),
        "Server span trace ID should match client span trace ID");
    assertEquals(
        clientSpan.getSpanContext().getSpanId(),
        serverSpan.getParentSpanContext().getSpanId(),
        "Server span parent span ID should equal client span span ID");
    assertTrue(
        serverSpan.getParentSpanContext().isValid(),
        "Server span should have a valid parent when context is propagated");
  }
}
