package com.example.rmirefactor.ledger;

import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

/**
 * RMI-facing ledger implementation for the intentionally flawed baseline.
 * It combines remote transport, validation, business rules, and persistence.
 */
public class LedgerRemoteImpl extends UnicastRemoteObject implements LedgerRemote {
    private final DatabaseConnection database;

    public LedgerRemoteImpl(DatabaseConnection database) throws RemoteException {
        super();
        this.database = database;
    }

    @Override
    public void addOrSubtract(String planId, BigDecimal amount, LedgerOperation operation)
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
        BigDecimal updatedBalance = operation == LedgerOperation.ADD
                ? currentBalance.add(amount)
                : currentBalance.subtract(amount);

        if (updatedBalance.signum() < 0) {
            throw new LedgerException("insufficient balance for plan: " + planId);
        }

        database.updateBalance(planId, updatedBalance);
    }

    @Override
    public BigDecimal getBalance(String planId) throws RemoteException, LedgerException {
        if (planId == null || planId.isBlank()) {
            throw new LedgerException("planId is required");
        }
        if (!database.planExists(planId)) {
            throw new LedgerException("plan does not exist: " + planId);
        }
        return database.getBalance(planId);
    }
}
