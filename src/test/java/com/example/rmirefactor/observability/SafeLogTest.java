package com.example.rmirefactor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SafeLogTest {

  @Test
  void last4MasksAllButLastFourCharacters() {
    assertEquals("****3456", SafeLog.last4("abcdef123456"));
  }

  @Test
  void last4MasksSixCharacterValue() {
    assertEquals("****cdef", SafeLog.last4("abcdef"));
  }

  @Test
  void last4ReturnsMaskForFourCharacterValue() {
    assertEquals("****", SafeLog.last4("abcd"));
  }

  @Test
  void last4ReturnsMaskForShortValue() {
    assertEquals("****", SafeLog.last4("abc"));
  }

  @Test
  void last4ReturnsRedactedForEmptyString() {
    assertEquals("[REDACTED]", SafeLog.last4(""));
  }

  @Test
  void last4ReturnsRedactedForNull() {
    assertEquals("[REDACTED]", SafeLog.last4(null));
  }
}
