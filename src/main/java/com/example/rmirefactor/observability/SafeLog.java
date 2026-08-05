package com.example.rmirefactor.observability;

/** Utility for redacting sensitive values before they appear in log output. */
public final class SafeLog {

  private static final String REDACTED = "[REDACTED]";

  private static final String MASK = "****";

  private SafeLog() {}

  /**
   * Masks all but the last four characters of a sensitive value.
   *
   * @param value the sensitive value to mask (may be {@code null})
   * @return a masked string showing only the last four characters, or {@code [REDACTED]} when the
   *     input is {@code null}, empty, or too short to expose safely
   */
  public static String last4(String value) {
    if (value == null || value.isEmpty()) {
      return REDACTED;
    }
    if (value.length() <= 4) {
      return MASK;
    }
    return MASK + value.substring(value.length() - 4);
  }
}
