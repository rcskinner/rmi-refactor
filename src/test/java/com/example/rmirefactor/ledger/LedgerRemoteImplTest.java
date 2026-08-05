package com.example.rmirefactor.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.rmi.server.UnicastRemoteObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class LedgerRemoteImplTest {
  @Mock private DatabaseConnection database;

  private LedgerRemoteImpl ledger;

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    ledger = new LedgerRemoteImpl(database);
  }

  @AfterEach
  void tearDown() throws Exception {
    UnicastRemoteObject.unexportObject(ledger, true);
  }

  @Test
  void addsAmountToAnExistingPlan() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.ADD);

    verify(database).updateBalance("plan-1", new BigDecimal("125.00"));
  }

  @Test
  void subtractsAmountFromAnExistingPlan() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("100.00"));

    ledger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.SUBTRACT);

    verify(database).updateBalance("plan-1", new BigDecimal("75.00"));
  }

  @Test
  void rejectsNegativeAmounts() throws Exception {
    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("plan-1", new BigDecimal("-1.00"), LedgerOperation.ADD));

    verify(database, never())
        .updateBalance(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsZeroAmounts() throws Exception {
    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("plan-1", BigDecimal.ZERO, LedgerOperation.ADD));
  }

  @Test
  void rejectsMissingPlans() throws Exception {
    when(database.planExists("missing")).thenReturn(false);

    LedgerException exception =
        assertThrows(
            LedgerException.class,
            () -> ledger.addOrSubtract("missing", new BigDecimal("1.00"), LedgerOperation.ADD));

    assertEquals("plan does not exist: ****sing", exception.getMessage());
    verify(database, never())
        .updateBalance(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsAnInvalidOperation() throws Exception {
    LedgerException exception =
        assertThrows(
            LedgerException.class,
            () -> ledger.addOrSubtract("plan-1", new BigDecimal("1.00"), null));

    assertEquals("operation is required", exception.getMessage());
  }

  @Test
  void rejectsWithdrawalsThatWouldOverdrawThePlan() throws Exception {
    when(database.planExists("plan-1")).thenReturn(true);
    when(database.getBalance("plan-1")).thenReturn(new BigDecimal("10.00"));

    assertThrows(
        LedgerException.class,
        () -> ledger.addOrSubtract("plan-1", new BigDecimal("11.00"), LedgerOperation.SUBTRACT));

    verify(database, never())
        .updateBalance(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }
}
