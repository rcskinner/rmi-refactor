package com.example.rmirefactor.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.example.rmirefactor.observability.LoggingTestSupport;
import java.math.BigDecimal;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SuppressWarnings("PMD.TooManyMethods")
class LedgerRemoteImplLoggingTest extends LoggingTestSupport {
  @Mock private DatabaseConnection database;

  private LedgerRemoteImpl ledger;

  @Override
  protected Class<?> getLoggerClass() {
    return LedgerRemoteImpl.class;
  }

  @BeforeEach
  void setUpLedger() throws Exception {
    MockitoAnnotations.openMocks(this);
    ledger = new LedgerRemoteImpl(database);
  }

  @AfterEach
  void tearDownLedger() throws Exception {
    UnicastRemoteObject.unexportObject(ledger, true);
  }

  @Test
  void logsOperationStartAndCompletionForAdd() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    List<ILoggingEvent> events = appender.list;
    assertTrue(events.size() >= 2);
    assertEquals(Level.INFO, events.get(0).getLevel());
    assertTrue(events.get(0).getFormattedMessage().contains("operation.started"));
    assertEquals(Level.INFO, events.get(1).getLevel());
    assertTrue(events.get(1).getFormattedMessage().contains("operation.completed"));
  }

  @Test
  void logsOperationStartAndCompletionForBalance() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.getBalance("plan-1", null);

    List<ILoggingEvent> events = appender.list;
    assertTrue(events.size() >= 2);
    assertEquals(Level.INFO, events.get(0).getLevel());
    assertTrue(events.get(0).getFormattedMessage().contains("operation.started"));
    assertEquals(Level.INFO, events.get(1).getLevel());
    assertTrue(events.get(1).getFormattedMessage().contains("operation.completed"));
  }

  @Test
  void logsOperationFailureAtErrorLevel() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD, null));

    List<ILoggingEvent> events = appender.list;
    assertTrue(events.size() >= 2);
    assertEquals(Level.INFO, events.get(0).getLevel());
    assertTrue(events.get(0).getFormattedMessage().contains("operation.started"));

    ILoggingEvent errorEvent =
        events.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No ERROR log event found"));
    assertTrue(errorEvent.getFormattedMessage().contains("operation.failed"));
  }

  @Test
  void emitsExactlyOneStartAndOneCompletionPerSuccess() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    List<ILoggingEvent> events = appender.list;
    long startCount =
        events.stream().filter(e -> e.getFormattedMessage().contains("operation.started")).count();
    long completeCount =
        events.stream()
            .filter(e -> e.getFormattedMessage().contains("operation.completed"))
            .count();
    long failCount =
        events.stream().filter(e -> e.getFormattedMessage().contains("operation.failed")).count();

    assertEquals(1, startCount);
    assertEquals(1, completeCount);
    assertEquals(0, failCount);
  }

  @Test
  void emitsExactlyOneStartAndOneFailurePerError() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD, null));

    List<ILoggingEvent> events = appender.list;
    long startCount =
        events.stream().filter(e -> e.getFormattedMessage().contains("operation.started")).count();
    long failCount =
        events.stream().filter(e -> e.getFormattedMessage().contains("operation.failed")).count();
    long completeCount =
        events.stream()
            .filter(e -> e.getFormattedMessage().contains("operation.completed"))
            .count();

    assertEquals(1, startCount);
    assertEquals(1, failCount);
    assertEquals(0, completeCount);
  }

  @Test
  void redactsPlanIdInAllLogStatementsForAddOrSubtract() throws Exception {
    String sentinel = "sentinel-plan-abc123def456";
    when(database.planExists(sentinel)).thenReturn(true);
    when(database.getBalance(sentinel)).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract(sentinel, new BigDecimal("25.00"), LedgerOperation.ADD, null);

    for (ILoggingEvent event : appender.list) {
      assertFalse(
          event.getFormattedMessage().contains(sentinel),
          "Raw planId must not appear in log message: " + event.getFormattedMessage());
    }
  }

  @Test
  void redactsPlanIdInAllLogStatementsForGetBalance() throws Exception {
    String sentinel = "sentinel-plan-abc123def456";
    when(database.planExists(sentinel)).thenReturn(true);
    when(database.getBalance(sentinel)).thenReturn(new BigDecimal("100.00"));

    ledger.getBalance(sentinel, null);

    for (ILoggingEvent event : appender.list) {
      assertFalse(
          event.getFormattedMessage().contains(sentinel),
          "Raw planId must not appear in log message: " + event.getFormattedMessage());
    }
  }

  @Test
  void redactsPlanIdInExceptionMessageAndStackTraceOnFailure() throws Exception {
    String sentinel = "sentinel-plan-abc123def456";
    when(database.planExists(sentinel)).thenReturn(false);

    LedgerException ex =
        assertThrows(
            LedgerException.class,
            () ->
                ledger.addOrSubtract(sentinel, new BigDecimal("1.00"), LedgerOperation.ADD, null));

    assertFalse(ex.getMessage().contains(sentinel), "LedgerException message must be redacted");

    ILoggingEvent errorEvent =
        appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No ERROR log event found"));
    assertFalse(
        errorEvent.getFormattedMessage().contains(sentinel),
        "Raw planId must not appear in error log message");

    IThrowableProxy proxy = errorEvent.getThrowableProxy();
    if (proxy != null) {
      assertFalse(
          proxy.getMessage().contains(sentinel),
          "Raw planId must not appear in throwable proxy message");
    }
  }

  @Test
  void redactsPlanIdInInsufficientBalanceException() throws Exception {
    String sentinel = "sentinel-plan-abc123def456";
    when(database.planExists(sentinel)).thenReturn(true);
    when(database.getBalance(sentinel)).thenReturn(new BigDecimal("5.00"));

    LedgerException ex =
        assertThrows(
            LedgerException.class,
            () ->
                ledger.addOrSubtract(
                    sentinel, new BigDecimal("50.00"), LedgerOperation.SUBTRACT, null));

    assertFalse(
        ex.getMessage().contains(sentinel), "Insufficient balance exception must be redacted");
  }
}
