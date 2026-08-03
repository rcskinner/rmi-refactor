package com.example.rmirefactor.server;

import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import java.math.BigDecimal;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public final class RmiServer {
  private RmiServer() {}

  public static void main(String[] args) throws Exception {
    InMemoryDatabaseConnection database = new InMemoryDatabaseConnection();
    database.createPlan("demo-plan", BigDecimal.ZERO);

    LedgerRemote ledger = new LedgerRemoteImpl(database);
    Registry registry = LocateRegistry.createRegistry(1099);
    registry.rebind("LedgerRemote", ledger);

    System.out.println("LedgerRemote bound on RMI registry as LedgerRemote");
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    UnicastRemoteObject.unexportObject(registry, true);
                  } catch (NoSuchObjectException ignored) {
                    // The registry may already be unexported during shutdown.
                  }
                }));
  }
}
