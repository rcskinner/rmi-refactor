package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;

/** Verifies {@link ObservabilityInitializer} creates the correct registries from configuration. */
class ObservabilityInitializerTest {

  @Test
  void createsPrometheusRegistryByDefault() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, null);

    assertNotNull(context.getMeterRegistry());
    assertNotNull(context.getPrometheusRegistry());
    assertTrue(context.getMeterRegistry() instanceof CompositeMeterRegistry);
    assertNotNull(context.getTracer());

    context.close();
  }

  @Test
  void createsPrometheusRegistryForExplicitPrometheusBackend() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("prometheus", null, null, null, null);

    assertNotNull(context.getPrometheusRegistry());
    context.close();
  }

  @Test
  void doesNotCreateDatadogRegistryWithoutApiKey() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("datadog", null, null, null, null);

    CompositeMeterRegistry composite = context.getMeterRegistry();
    boolean hasDatadog =
        composite.getRegistries().stream()
            .anyMatch(r -> r.getClass().getName().contains("Datadog"));
    assertFalse(hasDatadog, "Should not create Datadog registry without API key");

    context.close();
  }

  @Test
  void createsDatadogRegistryWhenBackendIsDatadogAndApiKeyIsSet() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("datadog", "test-api-key", null, null, null);

    CompositeMeterRegistry composite = context.getMeterRegistry();
    boolean hasPrometheus =
        composite.getRegistries().stream().anyMatch(r -> r instanceof PrometheusMeterRegistry);
    boolean hasDatadog =
        composite.getRegistries().stream()
            .anyMatch(r -> r.getClass().getName().contains("Datadog"));

    assertTrue(hasPrometheus, "Prometheus registry should always be present");
    assertTrue(hasDatadog, "Datadog registry should be created when configured");

    context.close();
  }

  @Test
  void createsBothRegistriesForCompositeBackend() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("composite", "test-api-key", null, null, null);

    CompositeMeterRegistry composite = context.getMeterRegistry();
    boolean hasPrometheus =
        composite.getRegistries().stream().anyMatch(r -> r instanceof PrometheusMeterRegistry);
    boolean hasDatadog =
        composite.getRegistries().stream()
            .anyMatch(r -> r.getClass().getName().contains("Datadog"));

    assertTrue(hasPrometheus, "Prometheus registry should always be present");
    assertTrue(hasDatadog, "Datadog registry should be created for composite with API key");

    context.close();
  }

  @Test
  void fallsBackToPrometheusForUnsupportedBackend() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("invalid-backend", "some-key", null, null, null);

    CompositeMeterRegistry composite = context.getMeterRegistry();
    boolean hasPrometheus =
        composite.getRegistries().stream().anyMatch(r -> r instanceof PrometheusMeterRegistry);
    boolean hasDatadog =
        composite.getRegistries().stream()
            .anyMatch(r -> r.getClass().getName().contains("Datadog"));

    assertTrue(hasPrometheus, "Prometheus should be present as fallback");
    assertFalse(hasDatadog, "Datadog should not be created for unsupported backend");

    context.close();
  }

  @Test
  void doesNotCreateDatadogForCompositeWithoutApiKey() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("composite", null, null, null, null);

    CompositeMeterRegistry composite = context.getMeterRegistry();
    boolean hasDatadog =
        composite.getRegistries().stream()
            .anyMatch(r -> r.getClass().getName().contains("Datadog"));
    assertFalse(hasDatadog, "Datadog should not be created without API key even for composite");

    context.close();
  }

  @Test
  void prometheusRegistryIsUsableForScraping() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, null);

    PrometheusMeterRegistry prometheus = context.getPrometheusRegistry();
    String scrape = prometheus.scrape();
    assertNotNull(scrape);

    context.close();
  }

  @Test
  void meterRegistryAcceptsMeterRegistration() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, null);

    MeterRegistry registry = context.getMeterRegistry();
    registry.counter("test.counter", "tag", "value").increment();
    assertEquals(1.0, registry.counter("test.counter", "tag", "value").count(), 0.001);

    context.close();
  }

  @Test
  void tracerIsNotNullNoOpStub() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, "ledger-server");

    Tracer tracer = context.getTracer();
    assertNotNull(tracer, "Tracer should be a non-null no-op stub");

    context.close();
  }
}
