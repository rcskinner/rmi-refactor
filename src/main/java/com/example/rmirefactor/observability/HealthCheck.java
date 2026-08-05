package com.example.rmirefactor.observability;

/** Named dependency health check used by the readiness endpoint. */
public interface HealthCheck {
  /**
   * Returns a stable, non-empty name identifying this check.
   *
   * @return the check name
   */
  String getName();

  /**
   * Returns whether the dependency is currently healthy.
   *
   * @return {@code true} when healthy, {@code false} otherwise
   */
  boolean isHealthy();
}
