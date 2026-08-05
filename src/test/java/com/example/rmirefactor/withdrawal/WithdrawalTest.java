package com.example.rmirefactor.withdrawal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WithdrawalTest {
  @Test
  void delegatesToTheRemoteLedgerWithSubtract() throws Exception {
    LedgerRemote ledger = mock(LedgerRemote.class);
    Withdrawal withdrawal = new Withdrawal(ledger);

    withdrawal.withdraw("plan-1", new BigDecimal("25.00"), null);

    verify(ledger).addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.SUBTRACT, null);
  }
}
