package com.example.rmirefactor.contribution;

import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;

import java.math.BigDecimal;
import java.rmi.RemoteException;

public class Contribution {
    private final LedgerRemote ledger;

    public Contribution(LedgerRemote ledger) {
        this.ledger = ledger;
    }

    public void contribute(String planId, BigDecimal amount)
            throws RemoteException, LedgerException {
        ledger.addOrSubtract(planId, amount, LedgerOperation.ADD);
    }
}
