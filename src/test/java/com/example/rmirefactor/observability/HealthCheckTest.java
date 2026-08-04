package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies the {@link HealthCheck} interface contract via a simple test implementation. */
class HealthCheckTest {

  @Test
  void getNameReturnsStableNonEmptyName() {
    HealthCheck check = new StubHealthCheck("my-check", true);
    assertEquals("my-check", check.getName());
    assertFalse(check.getName().isEmpty());
  }

  @Test
  void isHealthyReturnsTrueWhenHealthy() {
    HealthCheck check = new StubHealthCheck("healthy-check", true);
    assertTrue(check.isHealthy());
  }

  @Test
  void isHealthyReturnsFalseWhenUnhealthy() {
    HealthCheck check = new StubHealthCheck("unhealthy-check", false);
    assertFalse(check.isHealthy());
  }

  @Test
  void getNameIsConsistentAcrossCalls() {
    HealthCheck check = new StubHealthCheck("consistent", true);
    assertEquals(check.getName(), check.getName());
  }

  private static final class StubHealthCheck implements HealthCheck {
    private final String name;
    private final boolean healthy;

    StubHealthCheck(String name, boolean healthy) {
      this.name = name;
      this.healthy = healthy;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean isHealthy() {
      return healthy;
    }
  }

  @Test
  void interfaceCanBeImplementedAsLambda() {
    HealthCheck lambdaCheck =
        new HealthCheck() {
          @Override
          public String getName() {
            return "lambda-check";
          }

          @Override
          public boolean isHealthy() {
            return true;
          }
        };
    assertNotNull(lambdaCheck.getName());
    assertTrue(lambdaCheck.isHealthy());
  }
}
