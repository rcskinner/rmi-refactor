package com.example.rmirefactor.server;

import com.example.rmirefactor.ledger.InMemoryDatabaseConnection;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.ledger.LedgerRemoteImpl;
import com.example.rmirefactor.observability.HealthCheck;
import com.example.rmirefactor.observability.ObservabilityContext;
import com.example.rmirefactor.observability.ObservabilityInitializer;
import com.example.rmirefactor.observability.ObservabilityServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RmiServer {
  private static final Logger LOG = LoggerFactory.getLogger(RmiServer.class);

  private static final int RMI_PORT = 1099;

  private static final int HEALTH_PORT = 8081;

  private static final String DEFAULT_OBSERVABILITY_BIND_HOST = "127.0.0.1";

  private RmiServer() {}

  public static void main(String[] args) {
    try {
      start();
    } catch (IOException e) {
      LOG.error("event=server.startup_failed", e);
      System.exit(1);
    }
  }

  private static void start() throws IOException {
    ObservabilityContext observability = ObservabilityInitializer.initialize();

    InMemoryDatabaseConnection database = new InMemoryDatabaseConnection();
    database.createPlan("demo-plan", BigDecimal.ZERO);

    LedgerRemote ledger =
        new LedgerRemoteImpl(database, observability.getMeterRegistry(), observability.getTracer());
    Registry registry = LocateRegistry.createRegistry(RMI_PORT);
    registry.rebind("LedgerRemote", ledger);

    ObservabilityServer healthServer =
        new ObservabilityServer(
            observability.getPrometheusRegistry(), resolveObservabilityBindHost(), HEALTH_PORT);
    healthServer.registerHealthCheck(new RmiRegistryHealthCheck(registry));
    healthServer.start();

    LOG.info("event=server.started service=LedgerRemote port={}", RMI_PORT);
    LOG.info("event=server.bound service=LedgerRemote name=LedgerRemote");
    LOG.info(
        "event=health_server.started host={} port={}",
        healthServer.getAddress().getHostString(),
        healthServer.getAddress().getPort());

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  healthServer.stop();
                  try {
                    UnicastRemoteObject.unexportObject(registry, true);
                    LOG.info("event=server.stopped service=LedgerRemote");
                  } catch (NoSuchObjectException e) {
                    LOG.debug("event=server.shutdown registry already unexported");
                  }
                  observability.close();
                }));
  }

  private static String resolveObservabilityBindHost() {
    String configured = System.getenv("OBSERVABILITY_BIND_HOST");
    return configured == null || configured.isBlank()
        ? DEFAULT_OBSERVABILITY_BIND_HOST
        : configured.trim();
  }

  /** Health check that verifies the RMI registry has the LedgerRemote service bound. */
  static final class RmiRegistryHealthCheck implements HealthCheck {
    private final Registry registry;

    RmiRegistryHealthCheck(Registry registry) {
      this.registry = registry;
    }

    @Override
    public String getName() {
      return "rmi-registry";
    }

    @Override
    public boolean isHealthy() {
      try {
        registry.lookup("LedgerRemote");
        return true;
      } catch (RemoteException | NotBoundException e) {
        return false;
      }
    }
  }
}
