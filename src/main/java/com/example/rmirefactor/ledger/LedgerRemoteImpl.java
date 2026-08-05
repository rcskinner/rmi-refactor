package com.example.rmirefactor.ledger;

import com.example.rmirefactor.observability.SafeLog;
import com.example.rmirefactor.observability.TraceContextCarrier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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

  static final String ATTR_RPC_SYSTEM = "rpc.system";

  static final String ATTR_RPC_METHOD = "rpc.method";

  static final String ATTR_PLAN_ID = "plan.id";

  static final String ATTR_OPERATION = "operation";

  static final String ATTR_AMOUNT = "amount";

  static final String RPC_SYSTEM_VALUE = "java_rmi";

  private final DatabaseConnection database;

  private final MeterRegistry meterRegistry;

  private final Tracer tracer;

  private final AtomicInteger inFlightOperations;

  public LedgerRemoteImpl(DatabaseConnection database) throws RemoteException {
    this(database, new SimpleMeterRegistry(), OpenTelemetry.noop().getTracer("rmi-ledger"));
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is shared by design for metrics collection")
  public LedgerRemoteImpl(DatabaseConnection database, MeterRegistry meterRegistry)
      throws RemoteException {
    this(database, meterRegistry, OpenTelemetry.noop().getTracer("rmi-ledger"));
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry and Tracer are shared by design for observability")
  public LedgerRemoteImpl(DatabaseConnection database, MeterRegistry meterRegistry, Tracer tracer)
      throws RemoteException {
    super();
    this.database = database;
    this.meterRegistry = meterRegistry;
    this.tracer = tracer;
    this.inFlightOperations = new AtomicInteger(0);
    meterRegistry.gauge(GAUGE_NAME, inFlightOperations);
  }

  @Override
  public void addOrSubtract(
      String planId, BigDecimal amount, LedgerOperation operation, String traceContext)
      throws RemoteException, LedgerException {
    Context parentContext = TraceContextCarrier.extract(traceContext);
    Span span =
        tracer
            .spanBuilder("addOrSubtract")
            .setSpanKind(SpanKind.SERVER)
            .setParent(parentContext)
            .startSpan();
    String opName = operationName(operation);
    span.setAttribute(ATTR_RPC_SYSTEM, RPC_SYSTEM_VALUE);
    span.setAttribute(ATTR_RPC_METHOD, "addOrSubtract");
    span.setAttribute(ATTR_PLAN_ID, planId);
    span.setAttribute(ATTR_OPERATION, opName);
    if (amount != null) {
      span.setAttribute(ATTR_AMOUNT, amount.doubleValue());
    }

    LOG.info("event=operation.started operation={} planId={}", opName, SafeLog.last4(planId));
    inFlightOperations.incrementAndGet();
    Timer.Sample timerSample = Timer.start(meterRegistry);
    String result = RESULT_SUCCESS;
    try (Scope scope = span.makeCurrent()) {
      validateAndApply(planId, amount, operation);
      LOG.info("event=operation.completed operation={} planId={}", opName, SafeLog.last4(planId));
    } catch (LedgerException | RemoteException e) {
      result = RESULT_FAILURE;
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      LOG.error("event=operation.failed operation={} planId={}", opName, SafeLog.last4(planId), e);
      throw e;
    } finally {
      timerSample.stop(meterRegistry.timer(TIMER_NAME, TAG_OPERATION, opName));
      meterRegistry.counter(COUNTER_NAME, TAG_OPERATION, opName, TAG_RESULT, result).increment();
      inFlightOperations.decrementAndGet();
      span.end();
    }
  }

  @Override
  public BigDecimal getBalance(String planId, String traceContext)
      throws RemoteException, LedgerException {
    Context parentContext = TraceContextCarrier.extract(traceContext);
    Span span =
        tracer
            .spanBuilder("getBalance")
            .setSpanKind(SpanKind.SERVER)
            .setParent(parentContext)
            .startSpan();
    span.setAttribute(ATTR_RPC_SYSTEM, RPC_SYSTEM_VALUE);
    span.setAttribute(ATTR_RPC_METHOD, "getBalance");
    span.setAttribute(ATTR_PLAN_ID, planId);

    LOG.info("event=operation.started operation=balance planId={}", SafeLog.last4(planId));
    inFlightOperations.incrementAndGet();
    Timer.Sample timerSample = Timer.start(meterRegistry);
    String result = RESULT_SUCCESS;
    try (Scope scope = span.makeCurrent()) {
      BigDecimal balance = lookupBalance(planId);
      LOG.info("event=operation.completed operation=balance planId={}", SafeLog.last4(planId));
      return balance;
    } catch (LedgerException | RemoteException e) {
      result = RESULT_FAILURE;
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      LOG.error("event=operation.failed operation=balance planId={}", SafeLog.last4(planId), e);
      throw e;
    } finally {
      timerSample.stop(meterRegistry.timer(TIMER_NAME, TAG_OPERATION, "balance"));
      meterRegistry.counter(COUNTER_NAME, TAG_OPERATION, "balance", TAG_RESULT, result).increment();
      inFlightOperations.decrementAndGet();
      span.end();
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
