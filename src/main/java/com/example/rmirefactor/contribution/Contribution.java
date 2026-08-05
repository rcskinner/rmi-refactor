package com.example.rmirefactor.contribution;

import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.observability.SafeLog;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Contribution {
  private static final Logger LOG = LoggerFactory.getLogger(Contribution.class);

  private final LedgerRemote ledger;

  public Contribution(LedgerRemote ledger) {
    this.ledger = ledger;
  }

  public void contribute(String planId, BigDecimal amount, String traceContext)
      throws RemoteException, LedgerException {
    LOG.info("event=operation.started operation=contribute planId={}", SafeLog.last4(planId));
    try {
      ledger.addOrSubtract(planId, amount, LedgerOperation.ADD, traceContext);
      LOG.info("event=operation.completed operation=contribute planId={}", SafeLog.last4(planId));
    } catch (RemoteException | LedgerException e) {
      LOG.error("event=operation.failed operation=contribute planId={}", SafeLog.last4(planId), e);
      throw e;
    }
  }
}
