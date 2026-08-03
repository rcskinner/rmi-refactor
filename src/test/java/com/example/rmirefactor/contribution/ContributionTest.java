package com.example.rmirefactor.contribution;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ContributionTest {
  @Test
  void delegatesToTheRemoteLedgerWithAdd() throws Exception {
    LedgerRemote ledger = mock(LedgerRemote.class);
    Contribution contribution = new Contribution(ledger);

    contribution.contribute("plan-1", new BigDecimal("50.00"));

    verify(ledger).addOrSubtract("plan-1", new BigDecimal("50.00"), LedgerOperation.ADD);
  }
}
