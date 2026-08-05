package com.example.rmirefactor.observability;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

  private final Runnable sdkShutdown;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Registries are shared by design for metrics collection")
  public ObservabilityContext(
      CompositeMeterRegistry meterRegistry,
      PrometheusMeterRegistry prometheusRegistry,
      Tracer tracer,
      Runnable sdkShutdown) {
    this.meterRegistry = meterRegistry;
    this.prometheusRegistry = prometheusRegistry;
    this.tracer = tracer;
    this.sdkShutdown = sdkShutdown;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Registry is shared by design for metrics collection")
  public CompositeMeterRegistry getMeterRegistry() {
    return meterRegistry;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Registry is shared by design for metrics scraping")
  public PrometheusMeterRegistry getPrometheusRegistry() {
    return prometheusRegistry;
  }

  public Tracer getTracer() {
    return tracer;
  }

  @Override
  public void close() {
    try {
      if (sdkShutdown != null) {
        sdkShutdown.run();
      }
    } finally {
      meterRegistry.close();
    }
  }
}
