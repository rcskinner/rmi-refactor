package com.example.rmirefactor.contribution;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.observability.LoggingTestSupport;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContributionLoggingTest extends LoggingTestSupport {
  @Override
  protected Class<?> getLoggerClass() {
    return Contribution.class;
  }

  @Test
  void logsStartAndCompletionOnSuccess() throws Exception {
    LedgerRemote ledger = mock(LedgerRemote.class);
    new Contribution(ledger).contribute("plan-1", new BigDecimal("50.00"), null);

    List<ILoggingEvent> events = appender.list;
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("operation.started")
                        && e.getFormattedMessage().contains("contribute")));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("operation.completed")
                        && e.getFormattedMessage().contains("contribute")));
  }

  @Test
  void logsFailureWithOperationNameAndErrorLevel() throws Exception {
    LedgerRemote ledger = mock(LedgerRemote.class);
    doThrow(new LedgerException("plan does not exist: missing"))
        .when(ledger)
        .addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD, null);

    assertThrows(
        LedgerException.class,
        () -> new Contribution(ledger).contribute("missing", new BigDecimal("1.00"), null));

    boolean hasError =
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("operation.failed")
                        && e.getFormattedMessage().contains("contribute"));
    assertTrue(hasError, "Should log ERROR with 'operation.failed' and 'contribute'");
  }
}
