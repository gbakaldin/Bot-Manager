package com.vingame.bot.plugin.it;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures what reaches one logger of the application's log4j2 {@link LoggerContext}, at a
 * chosen level, and puts everything back on {@link #close()}. The IT JVM has no log4j2
 * configuration file, so a missing {@code LoggerConfig} is created for the duration.
 */
final class LogCapture implements AutoCloseable {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    private final String loggerName;
    private final LoggerConfig loggerConfig;
    private final boolean created;
    private final Level previousLevel;
    private final Capturing appender;

    LogCapture(String loggerName, Level level) {
        this.loggerName = loggerName;
        Configuration config = context.getConfiguration();
        LoggerConfig existing = config.getLoggerConfig(loggerName);
        if (existing.getName().equals(loggerName)) {
            loggerConfig = existing;
            created = false;
            previousLevel = existing.getLevel();
            existing.setLevel(level);
        } else {
            loggerConfig = new LoggerConfig(loggerName, level, true);
            config.addLogger(loggerName, loggerConfig);
            created = true;
            previousLevel = null;
        }
        appender = new Capturing("it-capture-" + SEQ.incrementAndGet());
        appender.start();
        loggerConfig.addAppender(appender, Level.ALL, null);
        context.updateLoggers();
    }

    LoggerContext context() {
        return context;
    }

    void setLevel(Level level) {
        loggerConfig.setLevel(level);
        context.updateLoggers();
    }

    List<LogEvent> events() {
        return new ArrayList<>(appender.events);
    }

    List<String> messages(Level level) {
        return appender.events.stream()
                .filter(e -> e.getLevel() == level)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    void clear() {
        appender.events.clear();
    }

    @Override
    public void close() {
        loggerConfig.removeAppender(appender.getName());
        appender.stop();
        if (created) {
            context.getConfiguration().removeLogger(loggerName);
        } else {
            loggerConfig.setLevel(previousLevel);
        }
        context.updateLoggers();
    }

    private static final class Capturing extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        Capturing(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
