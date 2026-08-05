package com.example.rmirefactor.client;

import com.example.rmirefactor.ledger.LedgerException;
import com.example.rmirefactor.ledger.LedgerOperation;
import com.example.rmirefactor.ledger.LedgerRemote;
import com.example.rmirefactor.observability.ObservabilityContext;
import com.example.rmirefactor.observability.ObservabilityInitializer;
import com.example.rmirefactor.observability.TraceContextCarrier;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.math.BigDecimal;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("PMD.CyclomaticComplexity")
public final class RmiClient {
  private static final Logger LOG = LoggerFactory.getLogger(RmiClient.class);

  static final String DEFAULT_HOST = "localhost";

  static final int DEFAULT_PORT = 1099;

  static final String ATTR_RPC_SYSTEM = "rpc.system";

  static final String ATTR_RPC_METHOD = "rpc.method";

  static final String ATTR_PLAN_ID = "plan.id";

  static final String ATTR_OPERATION = "operation";

  static final String ATTR_AMOUNT = "amount";

  static final String RPC_SYSTEM_VALUE = "java_rmi";

  private final Tracer tracer;

  private final String host;

  private final int port;

  RmiClient(Tracer tracer) {
    this(tracer, DEFAULT_HOST, DEFAULT_PORT);
  }

  RmiClient(Tracer tracer, String host, int port) {
    this.tracer = tracer;
    this.host = host;
    this.port = port;
  }

  public static void main(String[] args) {
    ObservabilityContext observability = ObservabilityInitializer.initialize();
    RmiClient client = new RmiClient(observability.getTracer());
    try {
      client.run(args);
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

  void run(String[] args) throws RemoteException, NotBoundException, LedgerException {
    if (args.length < 2) {
      printUsage();
      return;
    }

    String command = args[0].toLowerCase();
    String planId = args[1];

    LOG.info("event=client.connecting host={} port={}", host, port);
    Registry registry = LocateRegistry.getRegistry(host, port);
    LedgerRemote ledger = (LedgerRemote) registry.lookup("LedgerRemote");
    LOG.info("event=client.connected service=LedgerRemote");

    executeCommand(command, planId, args, ledger);
  }

  void executeCommand(String command, String planId, String[] args, LedgerRemote ledger)
      throws RemoteException, LedgerException {
    switch (command) {
      case "contribute" -> executeContribute(planId, args, ledger);
      case "withdraw" -> executeWithdraw(planId, args, ledger);
      case "balance" -> executeBalance(planId, args, ledger);
      default -> throw new IllegalArgumentException("unknown command: " + command);
    }
  }

  private void executeContribute(String planId, String[] args, LedgerRemote ledger)
      throws RemoteException, LedgerException {
    BigDecimal amount = requireAmount(args, "contribute");
    Span span = startClientSpan("contribute", "addOrSubtract", planId);
    span.setAttribute(ATTR_OPERATION, "add");
    span.setAttribute(ATTR_AMOUNT, amount.doubleValue());
    try (Scope scope = span.makeCurrent()) {
      String traceContext = TraceContextCarrier.inject(Context.current());
      ledger.addOrSubtract(planId, amount, LedgerOperation.ADD, traceContext);
      System.out.printf("Contributed %s to %s%n", amount, planId);
    } catch (RemoteException | LedgerException e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      throw e;
    } finally {
      span.end();
    }
  }

  private void executeWithdraw(String planId, String[] args, LedgerRemote ledger)
      throws RemoteException, LedgerException {
    BigDecimal amount = requireAmount(args, "withdraw");
    Span span = startClientSpan("withdraw", "addOrSubtract", planId);
    span.setAttribute(ATTR_OPERATION, "subtract");
    span.setAttribute(ATTR_AMOUNT, amount.doubleValue());
    try (Scope scope = span.makeCurrent()) {
      String traceContext = TraceContextCarrier.inject(Context.current());
      ledger.addOrSubtract(planId, amount, LedgerOperation.SUBTRACT, traceContext);
      System.out.printf("Withdrew %s from %s%n", amount, planId);
    } catch (RemoteException | LedgerException e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      throw e;
    } finally {
      span.end();
    }
  }

  private void executeBalance(String planId, String[] args, LedgerRemote ledger)
      throws RemoteException, LedgerException {
    if (args.length != 2) {
      throw new IllegalArgumentException("balance does not accept an amount");
    }
    Span span = startClientSpan("balance", "getBalance", planId);
    try (Scope scope = span.makeCurrent()) {
      String traceContext = TraceContextCarrier.inject(Context.current());
      BigDecimal balance = ledger.getBalance(planId, traceContext);
      System.out.printf("Balance for %s: %s%n", planId, balance);
    } catch (RemoteException | LedgerException e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      throw e;
    } finally {
      span.end();
    }
  }

  private Span startClientSpan(String spanName, String rpcMethod, String planId) {
    Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
    span.setAttribute(ATTR_RPC_SYSTEM, RPC_SYSTEM_VALUE);
    span.setAttribute(ATTR_RPC_METHOD, rpcMethod);
    span.setAttribute(ATTR_PLAN_ID, planId);
    return span;
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
