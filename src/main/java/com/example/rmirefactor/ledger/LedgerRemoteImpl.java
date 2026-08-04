package com.example.rmirefactor.ledger;

import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RMI-facing ledger implementation for the intentionally flawed baseline. It combines remote
 * transport, validation, business rules, and persistence.
 */
public class LedgerRemoteImpl extends UnicastRemoteObject implements LedgerRemote {
  private static final Logger LOG = LoggerFactory.getLogger(LedgerRemoteImpl.class);

  private final DatabaseConnection database;

  public LedgerRemoteImpl(DatabaseConnection database) throws RemoteException {
    super();
    this.database = database;
  }

  @Override
  public void addOrSubtract(String planId, BigDecimal amount, LedgerOperation operation)
      throws RemoteException, LedgerException {
    LOG.info("event=operation.started operation={} planId={}", operationName(operation), planId);
    try {
      validateAndApply(planId, amount, operation);
      LOG.info(
          "event=operation.completed operation={} planId={}", operationName(operation), planId);
    } catch (LedgerException | RemoteException e) {
      LOG.error(
          "event=operation.failed operation={} planId={}", operationName(operation), planId, e);
      throw e;
    }
  }

  @Override
  public BigDecimal getBalance(String planId) throws RemoteException, LedgerException {
    LOG.info("event=operation.started operation=balance planId={}", planId);
    try {
      BigDecimal balance = lookupBalance(planId);
      LOG.info("event=operation.completed operation=balance planId={}", planId);
      return balance;
    } catch (LedgerException | RemoteException e) {
      LOG.error("event=operation.failed operation=balance planId={}", planId, e);
      throw e;
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
      throw new LedgerException("plan does not exist: " + planId);
    }

    BigDecimal currentBalance = database.getBalance(planId);
    BigDecimal updatedBalance =
        operation == LedgerOperation.ADD
            ? currentBalance.add(amount)
            : currentBalance.subtract(amount);

    if (updatedBalance.signum() < 0) {
      throw new LedgerException("insufficient balance for plan: " + planId);
    }

    database.updateBalance(planId, updatedBalance);
  }

  private BigDecimal lookupBalance(String planId) throws RemoteException, LedgerException {
    if (planId == null || planId.isBlank()) {
      throw new LedgerException("planId is required");
    }
    if (!database.planExists(planId)) {
      throw new LedgerException("plan does not exist: " + planId);
    }
    return database.getBalance(planId);
  }

  private static String operationName(LedgerOperation operation) {
    return operation == null ? "unknown" : operation.name().toLowerCase();
  }
}
