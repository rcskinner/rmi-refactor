package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the {@link ObservabilityServer} HTTP endpoints, headers, and lifecycle. */
class ObservabilityServerTest {

  private PrometheusMeterRegistry prometheusRegistry;
  private ObservabilityServer server;
  private HttpClient client;
  private String baseUrl;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws IOException {
    prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    server = new ObservabilityServer(prometheusRegistry, 0);
    server.start();
    client = HttpClient.newHttpClient();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop();
    prometheusRegistry.close();
  }

  @Test
  void bindsToLoopbackOnly() {
    assertEquals("127.0.0.1", server.getAddress().getAddress().getHostAddress());
  }

  @Test
  void getLiveReturns200Up() throws Exception {
    HttpResponse<String> response = sendGet("/health/live");

    assertEquals(200, response.statusCode());
    JsonNode body = mapper.readTree(response.body());
    assertEquals("UP", body.get("status").asText());
  }

  @Test
  void getReadyReturns200UpWhenAllChecksPass() throws Exception {
    server.registerHealthCheck(new StubHealthCheck("check-1", true));
    server.registerHealthCheck(new StubHealthCheck("check-2", true));

    HttpResponse<String> response = sendGet("/health/ready");

    assertEquals(200, response.statusCode());
    JsonNode body = mapper.readTree(response.body());
    assertEquals("UP", body.get("status").asText());
    assertTrue(body.has("checks"));
    assertTrue(body.get("checks").isArray());
    assertEquals(2, body.get("checks").size());
  }

  @Test
  void getReadyReturns200UpWhenNoChecksRegistered() throws Exception {
    HttpResponse<String> response = sendGet("/health/ready");

    assertEquals(200, response.statusCode());
    JsonNode body = mapper.readTree(response.body());
    assertEquals("UP", body.get("status").asText());
  }

  @Test
  void getReadyReturns503DownWhenAnyCheckFails() throws Exception {
    server.registerHealthCheck(new StubHealthCheck("healthy", true));
    server.registerHealthCheck(new StubHealthCheck("unhealthy", false));

    HttpResponse<String> response = sendGet("/health/ready");

    assertEquals(503, response.statusCode());
    JsonNode body = mapper.readTree(response.body());
    assertEquals("DOWN", body.get("status").asText());
    assertTrue(body.has("checks"));
    JsonNode checks = body.get("checks");
    assertEquals(2, checks.size());
    boolean hasUnhealthy = false;
    for (JsonNode check : checks) {
      if ("unhealthy".equals(check.get("name").asText())) {
        assertEquals(false, check.get("healthy").asBoolean());
        hasUnhealthy = true;
      }
    }
    assertTrue(hasUnhealthy, "Should include the failing check");
  }

  @Test
  void readyResponseIncludesCheckNamesAndStatus() throws Exception {
    server.registerHealthCheck(new StubHealthCheck("rmi-registry", true));

    HttpResponse<String> response = sendGet("/health/ready");

    JsonNode body = mapper.readTree(response.body());
    JsonNode check = body.get("checks").get(0);
    assertEquals("rmi-registry", check.get("name").asText());
    assertTrue(check.get("healthy").asBoolean());
  }

  @Test
  void postOnLiveReturns405() throws Exception {
    HttpResponse<String> response = sendPost("/health/live");
    assertEquals(405, response.statusCode());
  }

  @Test
  void putOnLiveReturns405() throws Exception {
    HttpResponse<String> response = sendMethod("/health/live", "PUT");
    assertEquals(405, response.statusCode());
  }

  @Test
  void deleteOnLiveReturns405() throws Exception {
    HttpResponse<String> response = sendMethod("/health/live", "DELETE");
    assertEquals(405, response.statusCode());
  }

  @Test
  void postOnReadyReturns405() throws Exception {
    HttpResponse<String> response = sendPost("/health/ready");
    assertEquals(405, response.statusCode());
  }

  @Test
  void putOnReadyReturns405() throws Exception {
    HttpResponse<String> response = sendMethod("/health/ready", "PUT");
    assertEquals(405, response.statusCode());
  }

  @Test
  void deleteOnReadyReturns405() throws Exception {
    HttpResponse<String> response = sendMethod("/health/ready", "DELETE");
    assertEquals(405, response.statusCode());
  }

  @Test
  void healthLiveResponseHasNoStoreCacheControl() throws Exception {
    HttpResponse<String> response = sendGet("/health/live");
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  void healthReadyResponseHasNoStoreCacheControl() throws Exception {
    HttpResponse<String> response = sendGet("/health/ready");
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  void healthLiveResponseHasJsonContentType() throws Exception {
    HttpResponse<String> response = sendGet("/health/live");
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    assertTrue(contentType.contains("application/json"), "Content-Type should be application/json");
  }

  @Test
  void healthReadyResponseHasJsonContentType() throws Exception {
    HttpResponse<String> response = sendGet("/health/ready");
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    assertTrue(contentType.contains("application/json"), "Content-Type should be application/json");
  }

  @Test
  void methodNotAllowedResponseHasNoStoreCacheControl() throws Exception {
    HttpResponse<String> response = sendPost("/health/live");
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  void methodNotAllowedResponseHasJsonContentType() throws Exception {
    HttpResponse<String> response = sendPost("/health/live");
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    assertTrue(
        contentType.contains("application/json"), "405 Content-Type should be application/json");
  }

  @Test
  void getMetricsReturns200WithPrometheusText() throws Exception {
    HttpResponse<String> response = sendGet("/metrics");

    assertEquals(200, response.statusCode());
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    assertTrue(
        contentType.contains("text/plain"),
        "Metrics Content-Type should be text/plain, got: " + contentType);
  }

  @Test
  void getMetricsHasNoStoreCacheControl() throws Exception {
    HttpResponse<String> response = sendGet("/metrics");
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  void postOnMetricsReturns405() throws Exception {
    HttpResponse<String> response = sendPost("/metrics");
    assertEquals(405, response.statusCode());
  }

  @Test
  void serverStartsAndStopsCleanly() throws Exception {
    assertNotNull(server.getAddress());
    assertTrue(server.getAddress().getPort() > 0);

    HttpResponse<String> liveResponse = sendGet("/health/live");
    assertEquals(200, liveResponse.statusCode());

    server.stop();

    ObservabilityServer newServer = new ObservabilityServer(prometheusRegistry, 0);
    newServer.start();
    assertTrue(newServer.getAddress().getPort() > 0);
    newServer.stop();
  }

  @Test
  void metricsEndpointReflectsRegisteredMeters() throws Exception {
    prometheusRegistry.counter("test.counter", "tag", "value").increment();

    HttpResponse<String> response = sendGet("/metrics");
    String body = response.body();
    assertTrue(body.contains("test_counter"), "Metrics should contain registered counter");
  }

  private HttpResponse<String> sendGet(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
    return client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> sendPost(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .POST(BodyPublishers.noBody())
            .build();
    return client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> sendMethod(String path, String method) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .method(method, BodyPublishers.noBody())
            .build();
    return client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static final class StubHealthCheck implements HealthCheck {
    private final String name;
    private final boolean healthy;

    StubHealthCheck(String name, boolean healthy) {
      this.name = name;
      this.healthy = healthy;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean isHealthy() {
      return healthy;
    }
  }
}
