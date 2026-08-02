package com.example.rmirefactor.withdrawal;

import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WithdrawalTest {
    @Test
    void delegatesToTheRemoteLedgerWithSubtract() throws Exception {
        LedgerRemote ledger = mock(LedgerRemote.class);
        Withdrawal withdrawal = new Withdrawal(ledger);

        withdrawal.withdraw("plan-1", new BigDecimal("25.00"));

        verify(ledger).addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.SUBTRACT);
    }
}
