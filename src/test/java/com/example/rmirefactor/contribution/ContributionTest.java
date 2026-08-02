package com.example.rmirefactor.contribution;

import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContributionTest {
    @Test
    void delegatesToTheRemoteLedgerWithAdd() throws Exception {
        LedgerRemote ledger = mock(LedgerRemote.class);
        Contribution contribution = new Contribution(ledger);

        contribution.contribute("plan-1", new BigDecimal("50.00"));

        verify(ledger).addOrSubtract("plan-1", new BigDecimal("50.00"), LedgerOperation.ADD);
    }
}
