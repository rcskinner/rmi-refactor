package com.example.rmirefactor.server;

import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import java.math.BigDecimal;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RmiServer {
  private static final Logger LOG = LoggerFactory.getLogger(RmiServer.class);

  private static final int RMI_PORT = 1099;

  private RmiServer() {}

  public static void main(String[] args) {
    try {
      start();
    } catch (RemoteException e) {
      LOG.error("event=server.startup_failed", e);
      System.exit(1);
    }
  }

  private static void start() throws RemoteException {
    InMemoryDatabaseConnection database = new InMemoryDatabaseConnection();
    database.createPlan("demo-plan", BigDecimal.ZERO);

    LedgerRemote ledger = new LedgerRemoteImpl(database);
    Registry registry = LocateRegistry.createRegistry(RMI_PORT);
    registry.rebind("LedgerRemote", ledger);

    LOG.info("event=server.started service=LedgerRemote port={}", RMI_PORT);
    LOG.info("event=server.bound service=LedgerRemote name=LedgerRemote");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    UnicastRemoteObject.unexportObject(registry, true);
                    LOG.info("event=server.stopped service=LedgerRemote");
                  } catch (NoSuchObjectException e) {
                    LOG.debug("event=server.shutdown registry already unexported");
                  }
                }));
  }
}
