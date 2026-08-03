package com.example.rmirefactor.ledger;

import java.math.BigDecimal;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface LedgerRemote extends Remote {
  void addOrSubtract(String planId, BigDecimal amount, LedgerOperation operation)
      throws RemoteException, LedgerException;

  BigDecimal getBalance(String planId) throws RemoteException, LedgerException;
}
