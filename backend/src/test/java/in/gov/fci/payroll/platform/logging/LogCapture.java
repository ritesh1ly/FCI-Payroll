package in.gov.fci.payroll.platform.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures events of one logger for assertions. Close it to detach.
 *
 * <p>The unit tests run their classes in parallel (junit-platform.properties), and a logger is
 * shared by every test of the fork: a capture opened with {@link #ofThisThread} keeps only the
 * events of the thread that opened it, so a test that calls the code under test directly never sees
 * a line another test logged at the same moment (seen 2026-11-09: ProblemResponderMutationGapsTest
 * caught another class's 413 line). {@link #of} keeps every thread, for code that logs from its own
 * executor threads.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(String loggerName, Level level, String onlyThread) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        this.previousLevel = logger.getLevel();
        logger.setLevel(level);
        if (onlyThread != null) {
            // Filtered before the append, so another thread never reaches the (unsynchronised)
            // list.
            appender.addFilter(
                    new Filter<>() {
                        @Override
                        public FilterReply decide(ILoggingEvent event) {
                            return onlyThread.equals(event.getThreadName())
                                    ? FilterReply.NEUTRAL
                                    : FilterReply.DENY;
                        }
                    });
        }
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type, Level level) {
        return new LogCapture(type.getName(), level, null);
    }

    public static LogCapture of(String loggerName, Level level) {
        return new LogCapture(loggerName, level, null);
    }

    /** As {@link #of(Class, Level)}, keeping only the events logged by the calling thread. */
    public static LogCapture ofThisThread(Class<?> type, Level level) {
        return new LogCapture(type.getName(), level, Thread.currentThread().getName());
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}
