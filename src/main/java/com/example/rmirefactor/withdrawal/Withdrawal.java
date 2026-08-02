package com.example.rmirefactor.withdrawal;

import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;

import java.math.BigDecimal;
import java.rmi.RemoteException;

public class Withdrawal {
    private final LedgerRemote ledger;

    public Withdrawal(LedgerRemote ledger) {
        this.ledger = ledger;
    }

    public void withdraw(String planId, BigDecimal amount)
            throws RemoteException, LedgerException {
        ledger.addOrSubtract(planId, amount, LedgerOperation.SUBTRACT);
    }
}
