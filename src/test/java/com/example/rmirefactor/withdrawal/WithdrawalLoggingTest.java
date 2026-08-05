package com.example.rmirefactor.withdrawal;

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

class WithdrawalLoggingTest extends LoggingTestSupport {
  @Override
  protected Class<?> getLoggerClass() {
    return Withdrawal.class;
  }

  @Test
  void logsStartAndCompletionOnSuccess() throws Exception {
    LedgerRemote ledger = mock(LedgerRemote.class);
    new Withdrawal(ledger).withdraw("plan-1", new BigDecimal("25.00"), null);

    List<ILoggingEvent> events = appender.list;
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("operation.started")
                        && e.getFormattedMessage().contains("withdraw")));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("operation.completed")
                        && e.getFormattedMessage().contains("withdraw")));
  }

  @Test
  void logsFailureWithOperationNameAndErrorLevel() throws Exception {
    LedgerRemote ledger = mock(LedgerRemote.class);
    doThrow(new LedgerException("insufficient balance for plan: plan-1"))
        .when(ledger)
        .addOrSubtract("plan-1", new BigDecimal("11.00"), LedgerOperation.SUBTRACT, null);

    assertThrows(
        LedgerException.class,
        () -> new Withdrawal(ledger).withdraw("plan-1", new BigDecimal("11.00"), null));

    boolean hasError =
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("operation.failed")
                        && e.getFormattedMessage().contains("withdraw"));
    assertTrue(hasError, "Should log ERROR with 'operation.failed' and 'withdraw'");
  }
}
