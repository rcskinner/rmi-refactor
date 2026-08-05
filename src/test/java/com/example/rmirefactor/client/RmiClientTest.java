package com.example.rmirefactor.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RmiClientTest {
  private Registry registry;

  private LedgerRemoteImpl ledger;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryDatabaseConnection database = new InMemoryDatabaseConnection();
    database.createPlan("plan-1", new BigDecimal("100.00"));
    ledger = new LedgerRemoteImpl(database);
    registry = LocateRegistry.createRegistry(1099);
    registry.rebind("LedgerRemote", ledger);
  }

  @AfterEach
  void tearDown() throws Exception {
    UnicastRemoteObject.unexportObject(ledger, true);
    UnicastRemoteObject.unexportObject(registry, true);
  }

  @Test
  void handlesSupportedCommands() throws Exception {
    RmiClient.main(new String[] {"contribute", "plan-1", "25.00"});
    RmiClient.main(new String[] {"withdraw", "plan-1", "10.00"});
    RmiClient.main(new String[] {"balance", "plan-1"});
  }

  @Test
  void keepsUserFacingOutputOnStdoutWithoutJsonLogLines() throws Exception {
    String logbackConfiguration =
        Files.readString(
            Path.of("src", "main", "resources", "logback.xml"), StandardCharsets.UTF_8);
    assertTrue(
        logbackConfiguration.contains("<target>System.err</target>"),
        "JSON logs must be configured for stderr");

    PrintStream originalOut = System.out;
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
    try {
      RmiClient.main(new String[] {"balance", "plan-1"});
    } finally {
      System.setOut(originalOut);
    }

    String stdout = outContent.toString(StandardCharsets.UTF_8);
    assertTrue(stdout.contains("Balance for plan-1:"), "stdout should contain the client result");
    assertFalse(
        stdout.lines().anyMatch(line -> line.trim().startsWith("{")),
        "stdout must not contain JSON log lines");
  }

  @Test
  void printsUsageWhenArgumentsAreMissing() throws Exception {
    RmiClient.main(new String[] {"balance"});
  }

  @Test
  void printsUserFriendlyErrorForUnknownCommand() throws Exception {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    System.setErr(new PrintStream(errContent));
    try {
      RmiClient.main(new String[] {"unknown-command", "plan-1"});
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }

    String stdout = outContent.toString();
    String stderr = errContent.toString();
    assertTrue(stdout.contains("Error:"), "stdout should contain user-friendly error message");
    assertFalse(
        stdout.contains("Exception") || stdout.contains("at com.example"),
        "stdout should not contain stack traces");
    assertTrue(
        stderr.contains("\"level\":\"ERROR\""), "stderr should contain structured error logs");
  }

  @Test
  void printsUserFriendlyErrorForNonexistentPlan() throws Exception {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    System.setErr(new PrintStream(errContent));
    try {
      RmiClient.main(new String[] {"balance", "nonexistent-plan-12345"});
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }

    String stdout = outContent.toString();
    String stderr = errContent.toString();
    assertTrue(stdout.contains("Error:"), "stdout should contain user-friendly error message");
    assertFalse(
        stdout.contains("Exception") || stdout.contains("at com.example"),
        "stdout should not contain stack traces");
    assertTrue(
        stderr.contains("\"level\":\"ERROR\""), "stderr should contain structured error logs");
    assertFalse(
        stdout.contains("nonexistent-plan-12345"),
        "stdout should not contain raw planId in error message");
  }

  @Test
  void doesNotThrowExceptionOnError() throws Exception {
    RmiClient.main(new String[] {"unknown-command", "plan-1"});
    RmiClient.main(new String[] {"balance", "nonexistent-plan-12345"});
  }
}
