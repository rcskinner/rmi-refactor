package com.example.rmirefactor.observability;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.trace.Tracer;

/**
 * Holds the initialized observability components created by {@link ObservabilityInitializer}.
 *
 * <p>The {@code CompositeMeterRegistry} wraps the active backends (always Prometheus, optionally
 * Datadog) and should be injected into components that record metrics. The {@code
 * PrometheusMeterRegistry} is exposed separately for the {@code /metrics} scraping endpoint.
 */
public final class ObservabilityContext implements AutoCloseable {

  private final CompositeMeterRegistry meterRegistry;
  private final PrometheusMeterRegistry prometheusRegistry;
  private final Tracer tracer;

  public ObservabilityContext(
      CompositeMeterRegistry meterRegistry,
      PrometheusMeterRegistry prometheusRegistry,
      Tracer tracer) {
    this.meterRegistry = meterRegistry;
    this.prometheusRegistry = prometheusRegistry;
    this.tracer = tracer;
  }

  public CompositeMeterRegistry getMeterRegistry() {
    return meterRegistry;
  }

  public PrometheusMeterRegistry getPrometheusRegistry() {
    return prometheusRegistry;
  }

  public Tracer getTracer() {
    return tracer;
  }

  @Override
  public void close() {
    meterRegistry.close();
  }
}
