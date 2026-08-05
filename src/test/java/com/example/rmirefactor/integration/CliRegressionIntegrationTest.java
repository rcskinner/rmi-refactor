package com.example.rmirefactor.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rmirefactor.client.RmiClient;
import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests verifying CLI regression through the full RMI stack.
 *
 * <p>Sets up an in-process RMI registry on port 1099 with a real {@link LedgerRemoteImpl} backed by
 * an {@link InMemoryDatabaseConnection}, then invokes {@link RmiClient#main(String[])} and verifies
 * user-facing stdout output.
 *
 * <p>Fulfills VAL-CLI-001 (contribute), VAL-CLI-002 (withdraw), VAL-CLI-003 (balance), VAL-CLI-004
 * (invalid amounts), and VAL-CLI-005 (nonexistent plans).
 */
@Timeout(30)
class CliRegressionIntegrationTest {

  private Registry registry;

  private LedgerRemoteImpl ledger;

  private PrintStream originalOut;

  private PrintStream originalErr;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryDatabaseConnection database = new InMemoryDatabaseConnection();
    database.createPlan("demo-plan", new BigDecimal("100.00"));
    database.createPlan("savings", new BigDecimal("500.00"));
    ledger = new LedgerRemoteImpl(database);
    registry = LocateRegistry.createRegistry(1099);
    registry.rebind("LedgerRemote", ledger);
    originalOut = System.out;
    originalErr = System.err;
  }

  @AfterEach
  void tearDown() throws Exception {
    System.setOut(originalOut);
    System.setErr(originalErr);
    UnicastRemoteObject.unexportObject(ledger, true);
    UnicastRemoteObject.unexportObject(registry, true);
  }

  @Test
  void contributeCommandWorksAndDisplaysExpectedOutput() {
    String stdout =
        captureStdout(() -> RmiClient.main(new String[] {"contribute", "demo-plan", "50.00"}));

    assertTrue(stdout.contains("Contributed"), "stdout should contain contribution confirmation");
    assertTrue(stdout.contains("50.00"), "stdout should contain the contributed amount");
    assertTrue(stdout.contains("demo-plan"), "stdout should contain the plan ID");
    assertFalse(stdout.contains("Error:"), "stdout should not contain an error message");
  }

  @Test
  void withdrawCommandWorksAndDisplaysExpectedOutput() {
    String stdout =
        captureStdout(() -> RmiClient.main(new String[] {"withdraw", "demo-plan", "30.00"}));

    assertTrue(stdout.contains("Withdrew"), "stdout should contain withdrawal confirmation");
    assertTrue(stdout.contains("30.00"), "stdout should contain the withdrawn amount");
    assertTrue(stdout.contains("demo-plan"), "stdout should contain the plan ID");
    assertFalse(stdout.contains("Error:"), "stdout should not contain an error message");
  }

  @Test
  void balanceCommandWorksAndDisplaysExpectedBalance() {
    String stdout = captureStdout(() -> RmiClient.main(new String[] {"balance", "demo-plan"}));

    assertTrue(stdout.contains("Balance for"), "stdout should contain balance header");
    assertTrue(stdout.contains("demo-plan"), "stdout should contain the plan ID");
    assertTrue(stdout.contains("100"), "stdout should contain the expected balance value");
    assertFalse(stdout.contains("Error:"), "stdout should not contain an error message");
  }

  @Test
  void negativeAmountIsRejectedWithClearError() {
    String stdout =
        captureStdout(() -> RmiClient.main(new String[] {"contribute", "demo-plan", "-50.00"}));

    assertTrue(stdout.contains("Error:"), "stdout should contain an error message");
    assertTrue(
        stdout.contains("amount must be greater than zero"),
        "stdout should explain the amount constraint");
    assertFalse(stdout.contains("at com.example"), "stdout should not contain a stack trace");
  }

  @Test
  void zeroAmountIsRejectedWithClearError() {
    String stdout =
        captureStdout(() -> RmiClient.main(new String[] {"withdraw", "demo-plan", "0"}));

    assertTrue(stdout.contains("Error:"), "stdout should contain an error message");
    assertTrue(
        stdout.contains("amount must be greater than zero"),
        "stdout should explain the amount constraint");
    assertFalse(stdout.contains("at com.example"), "stdout should not contain a stack trace");
  }

  @Test
  void nonexistentPlanFailsWithClearActionableError() {
    String stdout =
        captureStdout(() -> RmiClient.main(new String[] {"balance", "nonexistent-plan-xyz"}));

    assertTrue(stdout.contains("Error:"), "stdout should contain an error message");
    assertTrue(
        stdout.contains("plan does not exist"),
        "stdout should identify that the plan does not exist");
    assertFalse(stdout.contains("at com.example"), "stdout should not contain a stack trace");
    assertFalse(
        stdout.contains("nonexistent-plan-xyz"),
        "stdout should not expose the raw plan ID (redacted via SafeLog)");
  }

  @Test
  void contributeThenWithdrawThenBalanceProducesConsistentState() {
    captureStdout(() -> RmiClient.main(new String[] {"contribute", "demo-plan", "50.00"}));
    captureStdout(() -> RmiClient.main(new String[] {"withdraw", "demo-plan", "20.00"}));
    String stdout = captureStdout(() -> RmiClient.main(new String[] {"balance", "demo-plan"}));

    assertTrue(stdout.contains("130"), "balance should reflect 100 + 50 - 20 = 130");
  }

  private String captureStdout(Runnable action) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setOut(originalOut);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
