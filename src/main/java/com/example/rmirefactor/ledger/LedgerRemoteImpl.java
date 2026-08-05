package com.example.rmirefactor.ledger;

import com.example.rmirefactor.observability.SafeLog;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RMI-facing ledger implementation for the intentionally flawed baseline. It combines remote
 * transport, validation, business rules, and persistence.
 *
 * <p>Each remote operation is instrumented with Micrometer metrics:
 *
 * <ul>
 *   <li>Counter {@code rmi_operations_total} tagged by {@code operation} and {@code result}.
 *   <li>Timer {@code rmi_operation_duration} tagged by {@code operation}.
 *   <li>Gauge {@code rmi_operations_in_flight} reflecting active operation count.
 * </ul>
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
public class LedgerRemoteImpl extends UnicastRemoteObject implements LedgerRemote {
  private static final Logger LOG = LoggerFactory.getLogger(LedgerRemoteImpl.class);

  static final String COUNTER_NAME = "rmi_operations_total";

  static final String TIMER_NAME = "rmi_operation_duration";

  static final String GAUGE_NAME = "rmi_operations_in_flight";

  static final String TAG_OPERATION = "operation";

  static final String TAG_RESULT = "result";

  static final String RESULT_SUCCESS = "success";

  static final String RESULT_FAILURE = "failure";

  private final DatabaseConnection database;

  private final MeterRegistry meterRegistry;

  private final AtomicInteger inFlightOperations;

  public LedgerRemoteImpl(DatabaseConnection database) throws RemoteException {
    this(database, new SimpleMeterRegistry());
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is shared by design for metrics collection")
  public LedgerRemoteImpl(DatabaseConnection database, MeterRegistry meterRegistry)
      throws RemoteException {
    super();
    this.database = database;
    this.meterRegistry = meterRegistry;
    this.inFlightOperations = new AtomicInteger(0);
    meterRegistry.gauge(GAUGE_NAME, inFlightOperations);
  }

  @Override
  public void addOrSubtract(
      String planId, BigDecimal amount, LedgerOperation operation, String traceContext)
      throws RemoteException, LedgerException {
    String opName = operationName(operation);
    LOG.info("event=operation.started operation={} planId={}", opName, SafeLog.last4(planId));
    inFlightOperations.incrementAndGet();
    Timer.Sample timerSample = Timer.start(meterRegistry);
    String result = RESULT_SUCCESS;
    try {
      validateAndApply(planId, amount, operation);
      LOG.info("event=operation.completed operation={} planId={}", opName, SafeLog.last4(planId));
    } catch (LedgerException | RemoteException e) {
      result = RESULT_FAILURE;
      LOG.error("event=operation.failed operation={} planId={}", opName, SafeLog.last4(planId), e);
      throw e;
    } finally {
      timerSample.stop(meterRegistry.timer(TIMER_NAME, TAG_OPERATION, opName));
      meterRegistry.counter(COUNTER_NAME, TAG_OPERATION, opName, TAG_RESULT, result).increment();
      inFlightOperations.decrementAndGet();
    }
  }

  @Override
  public BigDecimal getBalance(String planId, String traceContext)
      throws RemoteException, LedgerException {
    LOG.info("event=operation.started operation=balance planId={}", SafeLog.last4(planId));
    inFlightOperations.incrementAndGet();
    Timer.Sample timerSample = Timer.start(meterRegistry);
    String result = RESULT_SUCCESS;
    try {
      BigDecimal balance = lookupBalance(planId);
      LOG.info("event=operation.completed operation=balance planId={}", SafeLog.last4(planId));
      return balance;
    } catch (LedgerException | RemoteException e) {
      result = RESULT_FAILURE;
      LOG.error("event=operation.failed operation=balance planId={}", SafeLog.last4(planId), e);
      throw e;
    } finally {
      timerSample.stop(meterRegistry.timer(TIMER_NAME, TAG_OPERATION, "balance"));
      meterRegistry.counter(COUNTER_NAME, TAG_OPERATION, "balance", TAG_RESULT, result).increment();
      inFlightOperations.decrementAndGet();
    }
  }

  private void validateAndApply(String planId, BigDecimal amount, LedgerOperation operation)
      throws RemoteException, LedgerException {
    if (planId == null || planId.isBlank()) {
      throw new LedgerException("planId is required");
    }
    if (amount == null || amount.signum() <= 0) {
      throw new LedgerException("amount must be greater than zero");
    }
    if (operation == null) {
      throw new LedgerException("operation is required");
    }
    if (!database.planExists(planId)) {
      throw new LedgerException("plan does not exist: " + SafeLog.last4(planId));
    }

    BigDecimal currentBalance = database.getBalance(planId);
    BigDecimal updatedBalance =
        operation == LedgerOperation.ADD
            ? currentBalance.add(amount)
            : currentBalance.subtract(amount);

    if (updatedBalance.signum() < 0) {
      throw new LedgerException("insufficient balance for plan: " + SafeLog.last4(planId));
    }

    database.updateBalance(planId, updatedBalance);
  }

  private BigDecimal lookupBalance(String planId) throws RemoteException, LedgerException {
    if (planId == null || planId.isBlank()) {
      throw new LedgerException("planId is required");
    }
    if (!database.planExists(planId)) {
      throw new LedgerException("plan does not exist: " + SafeLog.last4(planId));
    }
    return database.getBalance(planId);
  }

  private static String operationName(LedgerOperation operation) {
    return operation == null ? "unknown" : operation.name().toLowerCase();
  }
}
