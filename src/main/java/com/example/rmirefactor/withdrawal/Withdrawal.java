package com.example.rmirefactor.withdrawal;

import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.observability.SafeLog;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Withdrawal {
  private static final Logger LOG = LoggerFactory.getLogger(Withdrawal.class);

  private final LedgerRemote ledger;

  public Withdrawal(LedgerRemote ledger) {
    this.ledger = ledger;
  }

  public void withdraw(String planId, BigDecimal amount) throws RemoteException, LedgerException {
    LOG.info("event=operation.started operation=withdraw planId={}", SafeLog.last4(planId));
    try {
      ledger.addOrSubtract(planId, amount, LedgerOperation.SUBTRACT);
      LOG.info("event=operation.completed operation=withdraw planId={}", SafeLog.last4(planId));
    } catch (RemoteException | LedgerException e) {
      LOG.error("event=operation.failed operation=withdraw planId={}", SafeLog.last4(planId), e);
      throw e;
    }
  }
}
