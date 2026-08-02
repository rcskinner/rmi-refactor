package com.example.rmirefactor.ledger;

import java.math.BigDecimal;

/**
 * Database seam used by the monolith's remote ledger.
 */
public interface DatabaseConnection {
    boolean planExists(String planId);

    BigDecimal getBalance(String planId);

    void updateBalance(String planId, BigDecimal balance);
}
