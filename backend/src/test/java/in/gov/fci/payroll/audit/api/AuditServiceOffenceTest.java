package in.gov.fci.payroll.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.gov.fci.payroll.audit.api.AuditService.Security;
import in.gov.fci.payroll.audit.business.model.SecurityEventRecord;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;

/** OD-130: the offence record's reads, and which events are critical. */
class AuditServiceOffenceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T06:00:00Z");
    private static final String LONG_LOGIN = "x".repeat(60);

    private final AuditRepositories.SecurityEvents events =
            mock(AuditRepositories.SecurityEvents.class);

    @SuppressWarnings("unchecked")
    private final AuditService audit =
            new AuditService(
                    mock(AuditRepositories.Audit.class),
                    events,
                    new SecurityAuditLogger(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(ApiProperties.class),
                    (ObjectProvider<CurrentAccess>) mock(ObjectProvider.class));

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

    private static SecurityEventRecord row() {
        return SecurityEventRecord.of(
                "UNAUTHORIZED_ACCESS", "u", "api", "GET /x", "detail", "10.0.0.1", NOW);
    }

    private List<String> critical() {
        return lines.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("severity=CRITICAL"))
                .toList();
    }

    @Test
    void OD_130_anActorIsCountedAsTheColumnHoldsIt() {
        when(events.countByActingUserAndEventTypeAndOccurredAtGreaterThan(
                        "x".repeat(50), "UNAUTHORIZED_ACCESS", Instant.EPOCH))
                .thenReturn(2L);

        assertThat(
                        audit.countSecurityEvents(
                                LONG_LOGIN, Security.UNAUTHORIZED_ACCESS, Instant.EPOCH))
                .isEqualTo(2);
    }

    @Test
    void OD_130_aSessionsCountedOffenceIsFound() {
        when(events.existsByActingUserAndEventTypeAndTargetEntity(
                        "x".repeat(50), "UNAUTHORIZED_ACCESS", "api:s1"))
                .thenReturn(true);

        assertThat(audit.hasSecurityEvent(LONG_LOGIN, Security.UNAUTHORIZED_ACCESS, "api:s1"))
                .isTrue();
        assertThat(audit.hasSecurityEvent(LONG_LOGIN, Security.UNAUTHORIZED_ACCESS, "api:s2"))
                .isFalse();
    }

    @Test
    void OD_130_theLatestEventOfATarget() {
        when(events.findFirstByEventTypeAndTargetEntityAndTargetKeyOrderByOccurredAtDesc(
                        "OFFENCE_COUNT_RESET", "app_user", "7"))
                .thenReturn(Optional.of(row()));

        assertThat(audit.latestSecurityEvent(Security.OFFENCE_COUNT_RESET, "app_user", "7"))
                .contains(NOW);
    }

    @Test
    void OD_130_theRecordReadsAsTheAdministratorSeesIt() {
        when(events.findByActingUserAndEventTypeAndOccurredAtGreaterThanOrderByOccurredAtDesc(
                        eq("u"), eq("UNAUTHORIZED_ACCESS"), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(row()));
        when(events.findByActingUserAndEventTypeInOrderByOccurredAtDesc(
                        eq("u"), eq(List.of("UNAUTHORIZED_ACCESS")), any()))
                .thenReturn(new PageImpl<>(List.of(row())));
        AuditService.SecurityEventView view =
                new AuditService.SecurityEventView(
                        "UNAUTHORIZED_ACCESS", "GET /x", "detail", "10.0.0.1", NOW);

        assertThat(audit.recentSecurityEvents("u", Security.UNAUTHORIZED_ACCESS, Instant.EPOCH, 10))
                .containsExactly(view);
        assertThat(audit.securityEventsOf("u", List.of(Security.UNAUTHORIZED_ACCESS), 0, 20))
                .containsExactly(view);
    }

    @Test
    void OD_130_theCriticalEventsOfTheSecurityEventTypes() {
        audit.securityEvent(Security.ACCOUNT_LOCKOUT, "login@x.test", "app_user", "7", "d");
        audit.securityEvent(Security.OTP_LOCKOUT, "login@x.test", "otp_challenge", "8", "d");
        audit.securityEvent(Security.AUDIT_TAMPER, "login@x.test", "audit_event", "9", "d");
        audit.securityEvent(Security.SESSION_HIJACK_SUSPECTED, "user:7", "app_session", "a", "d");

        assertThat(critical())
                .hasSize(4)
                .anyMatch(m -> m.startsWith("event=ACCOUNT_LOCKED severity=CRITICAL"))
                .anyMatch(m -> m.startsWith("event=OTP_LIMIT_EXCEEDED severity=CRITICAL"))
                .anyMatch(m -> m.startsWith("event=AUDIT_TAMPER severity=CRITICAL"))
                .anyMatch(m -> m.startsWith("event=SESSION_HIJACK_SUSPECTED severity=CRITICAL"))
                .noneMatch(m -> m.contains("login@x.test"));
    }

    @Test
    void OD_130_anEventOutsideTheListIsNotCritical() {
        audit.securityEvent(Security.OUT_OF_SCOPE_ACCESS, "u", "org_location", "1", "d");
        audit.securityEvent(Security.UNAUTHORIZED_ACCESS, "u", "api", "GET /x", "d");

        assertThat(critical()).isEmpty();
    }
}
