package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import net.logstash.logback.composite.JsonProviders;
import net.logstash.logback.composite.loggingevent.LogLevelJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggerNameJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggingEventFormattedTimestampJsonProvider;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Verifies structured JSON log output format, redaction, and Datadog compatibility. */
class StructuredLoggingTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private LoggingEventCompositeJsonEncoder createEncoderWithMasking() {
    LoggingEventCompositeJsonEncoder encoder = new LoggingEventCompositeJsonEncoder();

    JsonProviders<ch.qos.logback.classic.spi.ILoggingEvent> providers = encoder.getProviders();
    LoggingEventFormattedTimestampJsonProvider timestampProvider =
        new LoggingEventFormattedTimestampJsonProvider();
    providers.addProvider(timestampProvider);

    LogLevelJsonProvider levelProvider = new LogLevelJsonProvider();
    providers.addProvider(levelProvider);

    LoggerNameJsonProvider loggerProvider = new LoggerNameJsonProvider();
    loggerProvider.setFieldName("logger");
    providers.addProvider(loggerProvider);

    MessageJsonProvider messageProvider = new MessageJsonProvider();
    providers.addProvider(messageProvider);

    MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
    decorator.setDefaultMask("****");
    decorator.addPath("password");
    decorator.addPath("token");
    decorator.addPath("access_token");
    decorator.addPath("authorization");
    decorator.addPath("private_key");
    decorator.addPath("connection_string");
    decorator.addPath("email");
    decorator.addPath("secret");
    decorator.addPath("api_key");

    MaskingJsonGeneratorDecorator.ValueMask passwordMask =
        new MaskingJsonGeneratorDecorator.ValueMask();
    passwordMask.addValue("(?i)password\\s*[=:]\\s*\\S+");
    passwordMask.setMask("password=****");
    decorator.addValueMask(passwordMask);

    MaskingJsonGeneratorDecorator.ValueMask tokenMask =
        new MaskingJsonGeneratorDecorator.ValueMask();
    tokenMask.addValue("(?i)token\\s*[=:]\\s*\\S+");
    tokenMask.setMask("token=****");
    decorator.addValueMask(tokenMask);

    MaskingJsonGeneratorDecorator.ValueMask apiKeyMask =
        new MaskingJsonGeneratorDecorator.ValueMask();
    apiKeyMask.addValue("(?i)api[_-]?key\\s*[=:]\\s*\\S+");
    apiKeyMask.setMask("api_key=****");
    decorator.addValueMask(apiKeyMask);

    MaskingJsonGeneratorDecorator.ValueMask bearerMask =
        new MaskingJsonGeneratorDecorator.ValueMask();
    bearerMask.addValue("(?i)bearer\\s+[A-Za-z0-9\\-_.=]+");
    bearerMask.setMask("bearer ****");
    decorator.addValueMask(bearerMask);

    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    decorator.start();

    encoder.setContext(context);
    encoder.setJsonGeneratorDecorator(decorator);
    encoder.setProviders(providers);
    encoder.start();
    return encoder;
  }

  private LoggingEvent createEvent(
      Level level, String loggerName, String message, Throwable throwable) {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(context);
    event.setLevel(level);
    event.setLoggerName(loggerName);
    event.setMessage(message);
    event.setTimeStamp(System.currentTimeMillis());
    if (throwable != null) {
      event.setThrowableProxy(new ThrowableProxy(throwable));
    }
    return event;
  }

  private JsonNode encodeAndParse(LoggingEventCompositeJsonEncoder encoder, LoggingEvent event)
      throws Exception {
    byte[] bytes = encoder.encode(event);
    String json = new String(bytes, StandardCharsets.UTF_8).trim();
    return mapper.readTree(json);
  }

  @Test
  void producesValidJsonWithTimestampLevelLoggerMessage() throws Exception {
    LoggingEventCompositeJsonEncoder encoder = createEncoderWithMasking();
    LoggingEvent event = createEvent(Level.INFO, "com.example.test", "event=test.started", null);

    JsonNode node = encodeAndParse(encoder, event);

    assertTrue(node.has("@timestamp"));
    assertNotNull(node.get("@timestamp").asText());
    assertEquals("INFO", node.get("level").asText());
    assertEquals("com.example.test", node.get("logger").asText());
    assertEquals("event=test.started", node.get("message").asText());

    encoder.stop();
  }

  @Test
  void redactsPasswordInMessageText() throws Exception {
    LoggingEventCompositeJsonEncoder encoder = createEncoderWithMasking();
    String sentinel = "supersecret123";
    LoggingEvent event = createEvent(Level.INFO, "com.example.test", "password=" + sentinel, null);

    JsonNode node = encodeAndParse(encoder, event);
    String json = mapper.writeValueAsString(node);

    assertFalse(json.contains(sentinel), "Sentinel value must not appear in log output");
    assertTrue(json.contains("password=****"), "Password should be replaced with mask");

    encoder.stop();
  }

  @Test
  void redactsTokenInMessageText() throws Exception {
    LoggingEventCompositeJsonEncoder encoder = createEncoderWithMasking();
    String sentinel = "tkn_abc123xyz789";
    LoggingEvent event = createEvent(Level.INFO, "com.example.test", "token=" + sentinel, null);

    JsonNode node = encodeAndParse(encoder, event);
    String json = mapper.writeValueAsString(node);

    assertFalse(json.contains(sentinel), "Sentinel value must not appear in log output");
    assertTrue(json.contains("token=****"), "Token should be replaced with mask");

    encoder.stop();
  }

  @Test
  void redactsApiKeyInMessageText() throws Exception {
    LoggingEventCompositeJsonEncoder encoder = createEncoderWithMasking();
    String sentinel = "ak_live_999888777";
    LoggingEvent event = createEvent(Level.INFO, "com.example.test", "api_key=" + sentinel, null);

    JsonNode node = encodeAndParse(encoder, event);
    String json = mapper.writeValueAsString(node);

    assertFalse(json.contains(sentinel), "Sentinel value must not appear in log output");
    assertTrue(json.contains("api_key=****"), "API key should be replaced with mask");

    encoder.stop();
  }

  @Test
  void redactsBearerTokenInMessageText() throws Exception {
    LoggingEventCompositeJsonEncoder encoder = createEncoderWithMasking();
    String sentinel = "testBearerTokenValueXYZ";
    LoggingEvent event = createEvent(Level.INFO, "com.example.test", "Bearer " + sentinel, null);

    JsonNode node = encodeAndParse(encoder, event);
    String json = mapper.writeValueAsString(node);

    assertFalse(json.contains(sentinel), "Sentinel value must not appear in log output");
    assertTrue(json.contains("bearer ****"), "Bearer token should be replaced with mask");

    encoder.stop();
  }

  @Test
  void levelMapsToDatadogSeverityForInfoAndError() throws Exception {
    LoggingEventCompositeJsonEncoder encoder = createEncoderWithMasking();

    LoggingEvent infoEvent =
        createEvent(Level.INFO, "com.example.test", "event=operation.started", null);
    JsonNode infoNode = encodeAndParse(encoder, infoEvent);
    String infoLevel = infoNode.get("level").asText();
    assertEquals("INFO", infoLevel);
    assertEquals("info", infoLevel.toLowerCase(), "INFO maps to Datadog 'info' status");

    LoggingEvent errorEvent =
        createEvent(
            Level.ERROR,
            "com.example.test",
            "event=operation.failed",
            new RuntimeException("boom"));
    JsonNode errorNode = encodeAndParse(encoder, errorEvent);
    String errorLevel = errorNode.get("level").asText();
    assertEquals("ERROR", errorLevel);
    assertEquals("error", errorLevel.toLowerCase(), "ERROR maps to Datadog 'error' status");

    encoder.stop();
  }

  @Test
  void preservesValidJsonAfterRedaction() throws Exception {
    LoggingEventCompositeJsonEncoder encoder = createEncoderWithMasking();
    LoggingEvent event =
        createEvent(
            Level.INFO,
            "com.example.test",
            "password=testpwABC token=testtokDEF api_key=testkeyGHI",
            null);

    JsonNode node = encodeAndParse(encoder, event);

    assertNotNull(node);
    assertTrue(node.has("message"));
    String message = node.get("message").asText();
    assertFalse(message.contains("testpwABC"));
    assertFalse(message.contains("testtokDEF"));
    assertFalse(message.contains("testkeyGHI"));

    encoder.stop();
  }
}
