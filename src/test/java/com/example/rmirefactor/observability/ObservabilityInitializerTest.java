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
@SuppressWarnings("PMD.TooManyMethods")
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

  @Test
  void jvmMemoryMetricsArePresentInPrometheusScrape() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, null);

    String scrape = context.getPrometheusRegistry().scrape();
    assertTrue(
        scrape.contains("jvm_memory_used"),
        "Prometheus scrape should contain jvm_memory_used metrics");
    assertTrue(
        scrape.contains("jvm_memory_max"),
        "Prometheus scrape should contain jvm_memory_max metrics");

    context.close();
  }

  @Test
  void jvmGcMetricsArePresentInPrometheusScrape() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, null);

    String scrape = context.getPrometheusRegistry().scrape();
    assertTrue(scrape.contains("jvm_gc_"), "Prometheus scrape should contain jvm_gc_ metrics");

    context.close();
  }

  @Test
  void jvmThreadMetricsArePresentInPrometheusScrape() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, null);

    String scrape = context.getPrometheusRegistry().scrape();
    assertTrue(
        scrape.contains("jvm_threads_"), "Prometheus scrape should contain jvm_threads_ metrics");

    context.close();
  }

  @Test
  void jvmMetricsArePresentForAllBackendConfigurations() {
    String[] backends = {null, "prometheus", "datadog", "composite"};
    for (String backend : backends) {
      String apiKey = "datadog".equals(backend) || "composite".equals(backend) ? "test-key" : null;
      ObservabilityContext context =
          ObservabilityInitializer.initialize(backend, apiKey, null, null, null);

      String scrape = context.getPrometheusRegistry().scrape();
      assertTrue(
          scrape.contains("jvm_memory_"),
          "JVM memory metrics should be present for backend: " + backend);
      assertTrue(
          scrape.contains("jvm_gc_"), "JVM GC metrics should be present for backend: " + backend);
      assertTrue(
          scrape.contains("jvm_threads_"),
          "JVM thread metrics should be present for backend: " + backend);

      context.close();
    }
  }

  @Test
  void customMetersAppearInPrometheusScrapeViaCompositeRegistry() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize(null, null, null, null, null);

    context
        .getMeterRegistry()
        .counter("rmi_operations_total", "operation", "add", "result", "success")
        .increment();

    String scrape = context.getPrometheusRegistry().scrape();
    assertTrue(
        scrape.contains("rmi_operations_total"),
        "Custom counter should appear in Prometheus scrape");

    context.close();
  }

  @Test
  void datadogRegistryReceivesSameCustomMeters() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("datadog", "test-api-key", null, null, null);

    context
        .getMeterRegistry()
        .counter("rmi_operations_total", "operation", "add", "result", "success")
        .increment();

    CompositeMeterRegistry composite = context.getMeterRegistry();
    boolean datadogHasMeter =
        composite.getRegistries().stream()
            .filter(r -> r.getClass().getName().contains("Datadog"))
            .anyMatch(
                r ->
                    r.find("rmi_operations_total")
                            .tag("operation", "add")
                            .tag("result", "success")
                            .counter()
                        != null);
    assertTrue(datadogHasMeter, "Datadog registry should receive the custom counter meter");

    context.close();
  }

  @Test
  void compositeBackendReceivesSameCustomMetersInBothRegistries() {
    ObservabilityContext context =
        ObservabilityInitializer.initialize("composite", "test-api-key", null, null, null);

    context
        .getMeterRegistry()
        .counter("rmi_operations_total", "operation", "balance", "result", "success")
        .increment();

    CompositeMeterRegistry composite = context.getMeterRegistry();
    boolean prometheusHasMeter =
        composite.getRegistries().stream()
            .filter(r -> r instanceof PrometheusMeterRegistry)
            .anyMatch(
                r ->
                    r.find("rmi_operations_total")
                            .tag("operation", "balance")
                            .tag("result", "success")
                            .counter()
                        != null);
    boolean datadogHasMeter =
        composite.getRegistries().stream()
            .filter(r -> r.getClass().getName().contains("Datadog"))
            .anyMatch(
                r ->
                    r.find("rmi_operations_total")
                            .tag("operation", "balance")
                            .tag("result", "success")
                            .counter()
                        != null);

    assertTrue(prometheusHasMeter, "Prometheus registry should receive the custom counter");
    assertTrue(datadogHasMeter, "Datadog registry should receive the custom counter");

    context.close();
  }
}
