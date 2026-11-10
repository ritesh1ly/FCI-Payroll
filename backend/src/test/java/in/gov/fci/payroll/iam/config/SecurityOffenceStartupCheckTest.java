package in.gov.fci.payroll.iam.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.gov.fci.payroll.platform.config.SecurityOffenceProperties;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/** OD-130: off outside development is allowed, and an alert can catch it. */
class SecurityOffenceStartupCheckTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityAuditLogger.LOGGER_NAME);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void capture() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
    }

    private void report(String enabled, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        new SecurityOffenceStartupCheck(new SecurityOffenceProperties(enabled, "Y", 3), environment)
                .report();
    }

    @ParameterizedTest
    @ValueSource(strings = {"uat", "prod"})
    void OD_130_offOutsideDevelopmentWritesTheCriticalEntryAndDoesNotRefuse(String profile) {
        report("N", "online", profile);

        assertThat(lines.list).hasSize(1);
        assertThat(lines.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(lines.list.getFirst().getFormattedMessage())
                .isEqualTo(
                        "event=SECURITY_OFFENCE_DISABLED severity=CRITICAL"
                                + " key=fci.security.offence.enabled value=N");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "demo"})
    void OD_130_offInDevelopmentIsSilent(String profile) {
        report("N", "online", profile);

        assertThat(lines.list).isEmpty();
    }

    @Test
    void OD_130_onIsSilentEverywhere() {
        report("Y", "online", "prod");

        assertThat(lines.list).isEmpty();
    }

    @Test
    void OD_130_aSecretNamedFieldIsRedactedAndALineBreakForgesNothing() {
        CriticalLog.log(
                CriticalLog.Event.BATCH_FAILED,
                Map.of("password", "hunter2", "job", "x\nevent=SECURITY_OFFENCE"));

        String line = lines.list.getFirst().getFormattedMessage();
        assertThat(line).doesNotContain("hunter2").doesNotContain("\n");
    }

    @Test
    void OD_130_anOddFieldListDropsTheTrailingKey() {
        assertThat(CriticalLog.fields("a", 1, "b")).containsOnlyKeys("a");
    }
}
