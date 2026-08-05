package com.example.rmirefactor.client;

import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import java.math.BigDecimal;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RmiClient {
  private static final Logger LOG = LoggerFactory.getLogger(RmiClient.class);

  private static final String DEFAULT_HOST = "localhost";

  private static final int DEFAULT_PORT = 1099;

  private RmiClient() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (IllegalArgumentException e) {
      LOG.error("event=client.command_failed", e);
      System.out.println("Error: " + e.getMessage());
    } catch (RemoteException e) {
      LOG.error("event=client.command_failed", e);
      System.out.println("Error: Unable to reach the ledger service. Is the server running?");
    } catch (LedgerException e) {
      LOG.error("event=client.command_failed", e);
      System.out.println("Error: " + e.getMessage());
    } catch (NotBoundException e) {
      LOG.error("event=client.command_failed", e);
      System.out.println("Error: Ledger service not found in registry. Is the server running?");
    }
  }

  private static void run(String[] args)
      throws RemoteException, NotBoundException, LedgerException {
    if (args.length < 2) {
      printUsage();
      return;
    }

    String command = args[0].toLowerCase();
    String planId = args[1];

    LOG.info("event=client.connecting host={} port={}", DEFAULT_HOST, DEFAULT_PORT);
    Registry registry = LocateRegistry.getRegistry(DEFAULT_HOST, DEFAULT_PORT);
    LedgerRemote ledger = (LedgerRemote) registry.lookup("LedgerRemote");
    LOG.info("event=client.connected service=LedgerRemote");

    switch (command) {
      case "contribute" -> {
        BigDecimal amount = requireAmount(args, command);
        ledger.addOrSubtract(planId, amount, LedgerOperation.ADD);
        System.out.printf("Contributed %s to %s%n", amount, planId);
      }
      case "withdraw" -> {
        BigDecimal amount = requireAmount(args, command);
        ledger.addOrSubtract(planId, amount, LedgerOperation.SUBTRACT);
        System.out.printf("Withdrew %s from %s%n", amount, planId);
      }
      case "balance" -> {
        if (args.length != 2) {
          throw new IllegalArgumentException("balance does not accept an amount");
        }
        System.out.printf("Balance for %s: %s%n", planId, ledger.getBalance(planId));
      }
      default -> throw new IllegalArgumentException("unknown command: " + command);
    }
  }

  private static BigDecimal requireAmount(String[] args, String command) {
    if (args.length != 3) {
      throw new IllegalArgumentException(command + " requires an amount");
    }
    return new BigDecimal(args[2]);
  }

  private static void printUsage() {
    System.out.println("Usage:");
    System.out.println("  RmiClient contribute <planId> <amount>");
    System.out.println("  RmiClient withdraw   <planId> <amount>");
    System.out.println("  RmiClient balance    <planId>");
  }
}
