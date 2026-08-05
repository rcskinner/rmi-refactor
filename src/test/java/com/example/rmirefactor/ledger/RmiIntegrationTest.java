package com.example.rmirefactor.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RmiIntegrationTest {
  private InMemoryDatabaseConnection database;

  private LedgerRemoteImpl implementation;

  private Registry registry;

  @BeforeEach
  void setUp() throws Exception {
    database = new InMemoryDatabaseConnection();
    database.createPlan("plan-1", new BigDecimal("100.00"));

    implementation = new LedgerRemoteImpl(database);
    registry = LocateRegistry.createRegistry(0);
    registry.rebind("LedgerRemote", implementation);
  }

  @AfterEach
  void tearDown() throws Exception {
    UnicastRemoteObject.unexportObject(implementation, true);
    UnicastRemoteObject.unexportObject(registry, true);
  }

  @Test
  void invokesTheLedgerThroughRmi() throws Exception {
    LedgerRemote remoteLedger = (LedgerRemote) registry.lookup("LedgerRemote");

    remoteLedger.addOrSubtract("plan-1", new BigDecimal("25.00"), LedgerOperation.SUBTRACT, null);

    assertEquals(new BigDecimal("75.00"), remoteLedger.getBalance("plan-1", null));
  }
}
