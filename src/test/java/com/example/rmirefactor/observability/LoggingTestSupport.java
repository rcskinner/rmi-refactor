package com.example.rmirefactor.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

/** Base class for tests that need to capture SLF4J log events via a ListAppender. */
public abstract class LoggingTestSupport {
  protected ListAppender<ILoggingEvent> appender;

  protected Logger logger;

  /** Returns the logger class whose events should be captured. */
  protected abstract Class<?> getLoggerClass();

  @BeforeEach
  void setUpAppender() {
    logger = (Logger) LoggerFactory.getLogger(getLoggerClass());
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDownAppender() {
    logger.detachAppender(appender);
  }
}
