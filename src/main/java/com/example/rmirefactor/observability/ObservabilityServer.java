package com.example.rmirefactor.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDK {@link HttpServer} bound to loopback ({@code 127.0.0.1}) providing health-check and metrics
 * endpoints.
 *
 * <ul>
 *   <li>{@code GET /health/live} &mdash; 200 {@code {"status":"UP"}} always.
 *   <li>{@code GET /health/ready} &mdash; 200 UP when all registered {@link HealthCheck} instances
 *       pass, 503 DOWN when any fails.
 *   <li>{@code GET /metrics} &mdash; Prometheus text format scraped from the {@link
 *       PrometheusMeterRegistry}.
 * </ul>
 *
 * <p>All non-GET methods return 405. Health responses include {@code Cache-Control: no-store} and
 * {@code Content-Type: application/json}.
 */
public final class ObservabilityServer {

  private static final Logger LOG = LoggerFactory.getLogger(ObservabilityServer.class);

  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String CONTENT_TYPE_PROMETHEUS = "text/plain; version=0.0.4; charset=utf-8";
  private static final String CACHE_CONTROL_NO_STORE = "no-store";

  private static final String LOOPBACK_HOST = "127.0.0.1";

  private final HttpServer server;
  private final List<HealthCheck> healthChecks;
  private final PrometheusMeterRegistry prometheusRegistry;

  /**
   * Creates a new server bound to {@code 127.0.0.1} on the given port.
   *
   * @param prometheusRegistry the registry to scrape for the {@code /metrics} endpoint
   * @param port the TCP port to listen on
   * @throws IOException if the server cannot be created
   */
  public ObservabilityServer(PrometheusMeterRegistry prometheusRegistry, int port)
      throws IOException {
    this.prometheusRegistry = prometheusRegistry;
    this.healthChecks = new CopyOnWriteArrayList<>();
    this.server = HttpServer.create(new InetSocketAddress(LOOPBACK_HOST, port), 0);
    server.createContext("/health/live", this::handleLive);
    server.createContext("/health/ready", this::handleReady);
    server.createContext("/metrics", this::handleMetrics);
  }

  /**
   * Registers a health check to be aggregated by the readiness endpoint.
   *
   * @param check the health check to register
   */
  public void registerHealthCheck(HealthCheck check) {
    healthChecks.add(check);
  }

  /** Starts the HTTP server. */
  public void start() {
    server.start();
    LOG.info(
        "event=health_server.started host={} port={}",
        LOOPBACK_HOST,
        server.getAddress().getPort());
  }

  /** Stops the HTTP server, releasing the listening port immediately. */
  public void stop() {
    server.stop(0);
    LOG.info("event=health_server.stopped");
  }

  /**
   * Returns the address the server is bound to.
   *
   * @return the bound socket address
   */
  public InetSocketAddress getAddress() {
    return server.getAddress();
  }

  private void handleLive(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      sendMethodNotAllowed(exchange);
      return;
    }
    sendJsonResponse(exchange, 200, "{\"status\":\"UP\"}");
  }

  private void handleReady(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      sendMethodNotAllowed(exchange);
      return;
    }
    boolean allHealthy = true;
    StringBuilder checksJson = new StringBuilder("[");
    for (int i = 0; i < healthChecks.size(); i++) {
      HealthCheck check = healthChecks.get(i);
      boolean healthy = check.isHealthy();
      if (!healthy) {
        allHealthy = false;
      }
      if (i > 0) {
        checksJson.append(",");
      }
      checksJson
          .append("{\"name\":\"")
          .append(escapeJson(check.getName()))
          .append("\",\"healthy\":")
          .append(healthy)
          .append("}");
    }
    checksJson.append("]");

    String status = allHealthy ? "UP" : "DOWN";
    int statusCode = allHealthy ? 200 : 503;
    String body = "{\"status\":\"" + status + "\",\"checks\":" + checksJson + "}";
    sendJsonResponse(exchange, statusCode, body);
  }

  private void handleMetrics(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      sendMethodNotAllowed(exchange);
      return;
    }
    String body = prometheusRegistry.scrape();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_PROMETHEUS);
    exchange.getResponseHeaders().set("Cache-Control", CACHE_CONTROL_NO_STORE);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    } finally {
      exchange.close();
    }
  }

  private void sendJsonResponse(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
    exchange.getResponseHeaders().set("Cache-Control", CACHE_CONTROL_NO_STORE);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    } finally {
      exchange.close();
    }
  }

  private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
    String body = "{\"status\":\"METHOD_NOT_ALLOWED\"}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
    exchange.getResponseHeaders().set("Cache-Control", CACHE_CONTROL_NO_STORE);
    exchange.sendResponseHeaders(405, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    } finally {
      exchange.close();
    }
  }

  private static String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
