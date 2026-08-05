package com.example.rmirefactor.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Verifies Micrometer metrics instrumentation in {@link LedgerRemoteImpl}. */
@SuppressWarnings("PMD.TooManyMethods")
class LedgerRemoteImplMetricsTest {
  @Mock private DatabaseConnection database;

  private SimpleMeterRegistry registry;

  private LedgerRemoteImpl ledger;

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    registry = new SimpleMeterRegistry();
    ledger = new LedgerRemoteImpl(database, registry);
  }

  @AfterEach
  void tearDown() throws Exception {
    UnicastRemoteObject.unexportObject(ledger, true);
    registry.close();
  }

  @Test
  void counterIncrementsWithResultSuccessForSuccessfulAdd() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    Counter counter =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_SUCCESS)
            .counter();
    assertNotNull(counter, "Success counter should be registered");
    assertEquals(1.0, counter.count(), 0.001);
  }

  @Test
  void counterIncrementsWithResultSuccessForSuccessfulSubtract() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.SUBTRACT, null);

    Counter counter =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "subtract")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_SUCCESS)
            .counter();
    assertNotNull(counter, "Success counter for subtract should be registered");
    assertEquals(1.0, counter.count(), 0.001);
  }

  @Test
  void counterIncrementsWithResultFailureForFailedOperation() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD, null));

    Counter counter =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_FAILURE)
            .counter();
    assertNotNull(counter, "Failure counter should be registered");
    assertEquals(1.0, counter.count(), 0.001);
  }

  @Test
  void counterIncrementsWithResultFailureForFailedGetBalance() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(LedgerException.class, () -> ledger.getBalance("missing", null));

    Counter counter =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "balance")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_FAILURE)
            .counter();
    assertNotNull(counter, "Failure counter for balance should be registered");
    assertEquals(1.0, counter.count(), 0.001);
  }

  @Test
  void counterIncrementsWithResultSuccessForSuccessfulGetBalance() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.getBalance("plan-1", null);

    Counter counter =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "balance")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_SUCCESS)
            .counter();
    assertNotNull(counter, "Success counter for balance should be registered");
    assertEquals(1.0, counter.count(), 0.001);
  }

  @Test
  void counterIsMonotonicallyIncreasingPerTagCombination() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);
    ledger.addOrSubtract("plan-1", new BigDecimal("10.00"), LedgerOperation.ADD, null);

    Counter counter =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_SUCCESS)
            .counter();
    assertNotNull(counter);
    assertEquals(2.0, counter.count(), 0.001);
  }

  @Test
  void timerRecordsDurationForCompletedAddOperation() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    Timer timer =
        registry
            .find(LedgerRemoteImpl.TIMER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .timer();
    assertNotNull(timer, "Timer should be registered for add operation");
    assertEquals(1, timer.count(), "Timer count should be 1");
    assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0, "Timer total time should be positive");
  }

  @Test
  void timerRecordsDurationForCompletedSubtractOperation() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.SUBTRACT, null);

    Timer timer =
        registry
            .find(LedgerRemoteImpl.TIMER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "subtract")
            .timer();
    assertNotNull(timer, "Timer should be registered for subtract operation");
    assertEquals(1, timer.count());
    assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
  }

  @Test
  void timerRecordsDurationForCompletedGetBalanceOperation() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.getBalance("plan-1", null);

    Timer timer =
        registry
            .find(LedgerRemoteImpl.TIMER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "balance")
            .timer();
    assertNotNull(timer, "Timer should be registered for balance operation");
    assertEquals(1, timer.count());
    assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
  }

  @Test
  void timerRecordsDurationEvenForFailedOperation() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD, null));

    Timer timer =
        registry
            .find(LedgerRemoteImpl.TIMER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .timer();
    assertNotNull(timer, "Timer should be registered even for failed operation");
    assertEquals(1, timer.count(), "Timer should record the failed operation");
    assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
  }

  @Test
  void timerHasSeparateSeriesForDifferentOperationTypes() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);
    ledger.addOrSubtract("plan-1", new BigDecimal("10.00"), LedgerOperation.SUBTRACT, null);
    ledger.getBalance("plan-1", null);

    Timer addTimer =
        registry
            .find(LedgerRemoteImpl.TIMER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .timer();
    Timer subtractTimer =
        registry
            .find(LedgerRemoteImpl.TIMER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "subtract")
            .timer();
    Timer balanceTimer =
        registry
            .find(LedgerRemoteImpl.TIMER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "balance")
            .timer();

    assertNotNull(addTimer);
    assertNotNull(subtractTimer);
    assertNotNull(balanceTimer);
    assertEquals(1, addTimer.count());
    assertEquals(1, subtractTimer.count());
    assertEquals(1, balanceTimer.count());
  }

  @Test
  void gaugeIsRegisteredAtConstruction() {
    Gauge gauge = registry.find(LedgerRemoteImpl.GAUGE_NAME).gauge();
    assertNotNull(gauge, "In-flight gauge should be registered at construction");
    assertEquals(0.0, gauge.value(), 0.001, "Gauge should start at zero");
  }

  @Test
  void gaugeReturnsToZeroAfterSuccessfulOperation() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);

    Gauge gauge = registry.find(LedgerRemoteImpl.GAUGE_NAME).gauge();
    assertNotNull(gauge);
    assertEquals(0.0, gauge.value(), 0.001, "Gauge should return to zero after operation");
  }

  @Test
  void gaugeReturnsToZeroAfterFailedOperation() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD, null));

    Gauge gauge = registry.find(LedgerRemoteImpl.GAUGE_NAME).gauge();
    assertNotNull(gauge);
    assertEquals(0.0, gauge.value(), 0.001, "Gauge should return to zero after failed operation");
  }

  @Test
  void gaugeIncreasesDuringOperation() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch proceedLatch = new CountDownLatch(1);

    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1"))
        .thenAnswer(
            invocation -> {
              startLatch.countDown();
              proceedLatch.await(5, TimeUnit.SECONDS);
              return new BigDecimal("100.00");
            });

    CompletableFuture<Void> future =
        CompletableFuture.runAsync(
            () -> {
              try {
                ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);
              } catch (RemoteException | LedgerException e) {
                throw new RuntimeException(e);
              }
            });

    assertTrue(startLatch.await(5, TimeUnit.SECONDS), "Operation should have started");

    Gauge gauge = registry.find(LedgerRemoteImpl.GAUGE_NAME).gauge();
    assertNotNull(gauge);
    assertEquals(1.0, gauge.value(), 0.001, "Gauge should be 1 during in-flight operation");

    proceedLatch.countDown();
    future.get(5, TimeUnit.SECONDS);

    assertEquals(0.0, gauge.value(), 0.001, "Gauge should return to zero after operation");
  }

  @Test
  void allMetricsWorkWithMultipleOperations() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD, null);
    ledger.addOrSubtract("plan-1", new BigDecimal("10.00"), LedgerOperation.SUBTRACT, null);
    ledger.getBalance("plan-1", null);
    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("plan-1", BigDecimal.ZERO, LedgerOperation.ADD, null));

    Counter addSuccess =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_SUCCESS)
            .counter();
    Counter addFailure =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "add")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_FAILURE)
            .counter();
    Counter subtractSuccess =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "subtract")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_SUCCESS)
            .counter();
    Counter balanceSuccess =
        registry
            .find(LedgerRemoteImpl.COUNTER_NAME)
            .tag(LedgerRemoteImpl.TAG_OPERATION, "balance")
            .tag(LedgerRemoteImpl.TAG_RESULT, LedgerRemoteImpl.RESULT_SUCCESS)
            .counter();

    assertNotNull(addSuccess);
    assertNotNull(addFailure);
    assertNotNull(subtractSuccess);
    assertNotNull(balanceSuccess);
    assertEquals(1.0, addSuccess.count(), 0.001);
    assertEquals(1.0, addFailure.count(), 0.001);
    assertEquals(1.0, subtractSuccess.count(), 0.001);
    assertEquals(1.0, balanceSuccess.count(), 0.001);

    Gauge gauge = registry.find(LedgerRemoteImpl.GAUGE_NAME).gauge();
    assertNotNull(gauge);
    assertEquals(0.0, gauge.value(), 0.001);
  }
}
