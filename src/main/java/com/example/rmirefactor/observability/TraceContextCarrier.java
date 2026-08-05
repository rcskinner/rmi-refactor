package com.example.rmirefactor.observability;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper for W3C trace-context propagation through RMI calls.
 *
 * <p>Since RMI has no header channel, the W3C {@code traceparent} must travel inside the RMI call
 * itself. This utility serializes and deserializes the trace context to/from a string carrier using
 * {@link TextMapPropagator}.
 *
 * <p>The default propagator is a composite of {@link W3CTraceContextPropagator} (for {@code
 * traceparent} and {@code tracestate}) and {@link W3CBaggagePropagator} (for baggage).
 */
public final class TraceContextCarrier {

  private static final TextMapPropagator PROPAGATOR =
      TextMapPropagator.composite(
          W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());

  private static final TextMapGetter<Map<String, String>> MAP_GETTER =
      new TextMapGetter<Map<String, String>>() {
        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }
      };

  private TraceContextCarrier() {}

  /**
   * Serializes an OpenTelemetry context to a string carrier.
   *
   * <p>Uses {@link TextMapPropagator#inject(Context, Object,
   * io.opentelemetry.context.propagation.TextMapSetter)} to inject the W3C {@code traceparent} (and
   * baggage) into a {@link Map}, then encodes the map as a string.
   *
   * @param context the context to serialize (may be {@code null})
   * @return a string encoding of the trace context, or an empty string when the context has no
   *     trace context to propagate
   */
  public static String inject(Context context) {
    if (context == null) {
      return "";
    }
    Map<String, String> carrier = new HashMap<>();
    PROPAGATOR.inject(context, carrier, Map::put);
    return encodeCarrier(carrier);
  }

  /**
   * Reconstructs an OpenTelemetry context from a string carrier.
   *
   * <p>Decodes the string to a {@link Map}, then uses {@link TextMapPropagator#extract(Context,
   * Object, TextMapGetter)} to reconstruct the {@link Context} with the W3C {@code traceparent}.
   *
   * @param encoded the string carrier (may be {@code null}, empty, or malformed)
   * @return the reconstructed context, or {@link Context#root()} when the input is null, empty, or
   *     contains no valid trace context
   */
  public static Context extract(String encoded) {
    if (encoded == null || encoded.isEmpty()) {
      return Context.root();
    }
    Map<String, String> carrier = decodeCarrier(encoded);
    return PROPAGATOR.extract(Context.root(), carrier, MAP_GETTER);
  }

  private static String encodeCarrier(Map<String, String> carrier) {
    if (carrier.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : carrier.entrySet()) {
      if (sb.length() > 0) {
        sb.append("\n");
      }
      sb.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return sb.toString();
  }

  private static Map<String, String> decodeCarrier(String encoded) {
    Map<String, String> carrier = new HashMap<>();
    for (String line : encoded.split("\n")) {
      int idx = line.indexOf('=');
      if (idx > 0) {
        carrier.put(line.substring(0, idx), line.substring(idx + 1));
      }
    }
    return carrier;
  }
}
