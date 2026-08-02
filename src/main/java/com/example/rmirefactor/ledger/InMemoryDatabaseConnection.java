package com.example.rmirefactor.ledger;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Small in-memory stand-in for the database used by the baseline application.
 */
public class InMemoryDatabaseConnection implements DatabaseConnection {
    private final Map<String, BigDecimal> balances = new HashMap<>();

    public void createPlan(String planId, BigDecimal openingBalance) {
        balances.put(planId, openingBalance);
    }

    @Override
    public boolean planExists(String planId) {
        return balances.containsKey(planId);
    }

    @Override
    public BigDecimal getBalance(String planId) {
        return balances.get(planId);
    }

    @Override
    public void updateBalance(String planId, BigDecimal balance) {
        balances.put(planId, balance);
    }
}
