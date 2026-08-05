package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies that server-side classes use SLF4J instead of System.out/System.err. */
class NoSystemOutTest {

  @Test
  void rmiServerHasNoSystemOutOrErr() throws IOException {
    String source =
        Files.readString(Path.of("src/main/java/com/example/rmirefactor/server/RmiServer.java"));
    assertFalse(
        source.contains("System.out"), "RmiServer must not contain System.out — use SLF4J instead");
    assertFalse(
        source.contains("System.err"), "RmiServer must not contain System.err — use SLF4J instead");
  }

  @Test
  void ledgerRemoteImplHasNoSystemOutOrErr() throws IOException {
    String source =
        Files.readString(
            Path.of("src/main/java/com/example/rmirefactor/ledger/LedgerRemoteImpl.java"));
    assertFalse(
        source.contains("System.out"),
        "LedgerRemoteImpl must not contain System.out — use SLF4J instead");
    assertFalse(
        source.contains("System.err"),
        "LedgerRemoteImpl must not contain System.err — use SLF4J instead");
  }
}
