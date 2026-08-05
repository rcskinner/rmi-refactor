package com.example.rmirefactor.observability;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.datadog.DatadogConfig;
import io.micrometer.datadog.DatadogMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized initialization of the observability stack from environment variables.
 *
 * <p>Always creates a {@link PrometheusMeterRegistry} for the {@code /metrics} scraping endpoint.
 * Conditionally creates a {@link DatadogMeterRegistry} when {@code METRICS_BACKEND} is {@code
 * datadog} or {@code composite} and {@code DD_API_KEY} is set. Tracer initialization is a no-op
 * stub at this stage; full tracing comes in milestone 3.
 */
public final class ObservabilityInitializer {

  private static final Logger LOG = LoggerFactory.getLogger(ObservabilityInitializer.class);

  static final String DEFAULT_METRICS_BACKEND = "prometheus";

  static final String DEFAULT_DD_URI = "https://api.datadoghq.com";

  static final String DEFAULT_SERVICE_NAME = "unknown_service";

  private ObservabilityInitializer() {}

  /**
   * Initializes the observability stack from {@code System.getenv()} and registers a shutdown hook
   * to close the meter registries.
   *
   * @return the initialized {@link ObservabilityContext}
   */
  public static ObservabilityContext initialize() {
    ObservabilityContext context =
        initialize(
            System.getenv("METRICS_BACKEND"),
            System.getenv("DD_API_KEY"),
            System.getenv("DD_URI"),
            System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
            System.getenv("OTEL_SERVICE_NAME"));
    Runtime.getRuntime().addShutdownHook(new Thread(context::close));
    return context;
  }

  /**
   * Initializes the observability stack from the given configuration values without registering a
   * shutdown hook. Intended for unit testing.
   *
   * @param metricsBackend value of {@code METRICS_BACKEND} (may be {@code null})
   * @param ddApiKey value of {@code DD_API_KEY} (may be {@code null})
   * @param ddUri value of {@code DD_URI} (may be {@code null})
   * @param otlpEndpoint value of {@code OTEL_EXPORTER_OTLP_ENDPOINT} (may be {@code null}, reserved
   *     for milestone 3)
   * @param serviceName value of {@code OTEL_SERVICE_NAME} (may be {@code null})
   * @return the initialized {@link ObservabilityContext}
   */
  static ObservabilityContext initialize(
      String metricsBackend,
      String ddApiKey,
      String ddUri,
      String otlpEndpoint,
      String serviceName) {

    PrometheusMeterRegistry prometheusRegistry =
        new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    CompositeMeterRegistry composite = new CompositeMeterRegistry();
    composite.add(prometheusRegistry);

    // Bind JVM metrics (memory, GC, threads) to the composite so all backends receive them
    new JvmMemoryMetrics().bindTo(composite);
    new JvmGcMetrics().bindTo(composite);
    new JvmThreadMetrics().bindTo(composite);

    String backend = resolveBackend(metricsBackend);
    boolean datadogConfigured =
        ("datadog".equals(backend) || "composite".equals(backend))
            && ddApiKey != null
            && !ddApiKey.isBlank();

    if (datadogConfigured) {
      String resolvedDdUri = resolveDdUri(ddUri);
      DatadogMeterRegistry datadog = createDatadogRegistry(ddApiKey, resolvedDdUri);
      composite.add(datadog);
      LOG.info("event=metrics.datadog_configured uri={}", resolvedDdUri);
    }

    Tracer tracer = OpenTelemetry.noop().getTracer(resolveServiceName(serviceName));

    LOG.info("event=observability.initialized backend={}", backend);
    return new ObservabilityContext(composite, prometheusRegistry, tracer);
  }

  private static String resolveBackend(String metricsBackend) {
    if (metricsBackend == null || metricsBackend.isBlank()) {
      return DEFAULT_METRICS_BACKEND;
    }
    String normalized = metricsBackend.trim().toLowerCase();
    if ("prometheus".equals(normalized)
        || "datadog".equals(normalized)
        || "composite".equals(normalized)) {
      return normalized;
    }
    LOG.warn(
        "event=metrics.unsupported_backend value={} fallback={}",
        metricsBackend,
        DEFAULT_METRICS_BACKEND);
    return DEFAULT_METRICS_BACKEND;
  }

  private static String resolveDdUri(String ddUri) {
    if (ddUri == null || ddUri.isBlank()) {
      return DEFAULT_DD_URI;
    }
    return ddUri.trim();
  }

  private static String resolveServiceName(String serviceName) {
    if (serviceName == null || serviceName.isBlank()) {
      return DEFAULT_SERVICE_NAME;
    }
    return serviceName.trim();
  }

  private static DatadogMeterRegistry createDatadogRegistry(String apiKey, String uri) {
    DatadogConfig config =
        new DatadogConfig() {
          @Override
          public String apiKey() {
            return apiKey;
          }

          @Override
          public String uri() {
            return uri;
          }

          @Override
          public String get(String key) {
            return null;
          }
        };
    return new DatadogMeterRegistry(config, Clock.SYSTEM);
  }
}
