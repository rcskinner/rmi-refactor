package com.example.rmirefactor.client;

import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import java.math.BigDecimal;
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
  void printsUsageWhenArgumentsAreMissing() throws Exception {
    RmiClient.main(new String[] {"balance"});
  }
}
