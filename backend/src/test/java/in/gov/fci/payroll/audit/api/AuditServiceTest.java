package in.gov.fci.payroll.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.business.model.AuditEvent;
import in.gov.fci.payroll.audit.business.model.SecurityEventRecord;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.logging.SecurityAuditLogger;
import in.gov.fci.payroll.platform.logging.SecurityEvent;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SRS-20-18 (every change audited with who, when and why): the event written for a change, what
 * stands in for a missing actor, reason or session, and the security events beside it.
 */
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:30:00.123456789Z");

    private final AuditRepositories.Audit audit = mock(AuditRepositories.Audit.class);
    private final AuditRepositories.SecurityEvents securityEvents =
            mock(AuditRepositories.SecurityEvents.class);
    private final SecurityAuditLogger securityLog = mock(SecurityAuditLogger.class);
    private final CurrentAccess access = mock(CurrentAccess.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectProvider<CurrentAccess> provider = mock(ObjectProvider.class);

    private final AuditService service =
            new AuditService(
                    audit,
                    securityEvents,
                    securityLog,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    properties(),
                    provider);

    private static ApiProperties properties() {
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.businessZone()).thenReturn(ZoneId.of("Asia/Kolkata"));
        return properties;
    }

    @Test
    void securityEventTypesAreUpperSnakeCase() {
        assertThat(AuditService.eventType("LOGIN")).isEqualTo("LOGIN");
        assertThat(AuditService.eventType("SCOPE_SWITCH")).isEqualTo("SCOPE_SWITCH");
        assertThat(AuditService.eventType("mobileNumber")).isEqualTo("AUDIT_MOBILE_NUMBER");
        assertThat(AuditService.eventType("revoked_at")).isEqualTo("AUDIT_REVOKED_AT");
        assertThat(AuditService.eventType("granted")).isEqualTo("AUDIT_GRANTED");
        // An upper-case field name shorter than a type may be stays a valid type.
        assertThat(AuditService.eventType("AB")).isEqualTo("AUDIT_AB");
    }

    @Test
    void SRS_20_18_anyFieldNameGivesAValidTypeSoTheChangeItRecordsNeverFails() {
        // Digits and signs of a field name are dropped (a level, a rate line key).
        assertThat(AuditService.eventType("level_1")).isEqualTo("AUDIT_LEVEL");
        assertThat(AuditService.eventType("rate[12|A|IDA|0-0|X|HRA]"))
                .isEqualTo("AUDIT_RATE_A_IDA_X_HRA");
        assertThat(AuditService.eventType("scalar_value")).isEqualTo("AUDIT_SCALAR_VALUE");
        assertThat(AuditService.eventType("x")).isEqualTo("AUDIT_X");
        assertThat(AuditService.eventType("1")).isEqualTo("AUDIT_CHANGE");
        assertThat(AuditService.eventType("a".repeat(100)))
                .hasSizeLessThanOrEqualTo(64)
                .matches("^[A-Z][A-Z_]{2,63}$");
    }

    /**
     * SRS §6.4 l.1204 / SRS-20-18 (US-GAP-131): a change's audit rows are written inside the
     * change's own transaction - the write paths refuse to run without one, so a row can never be
     * written apart from the change it records (only the refusal-trace paths run on their own).
     */
    @Test
    void SRS_20_18_aChangesAuditRowsJoinTheChangesTransactionAndCannotRunWithoutOne()
            throws Exception {
        for (java.lang.reflect.Method m :
                new java.lang.reflect.Method[] {
                    AuditService.class.getMethod("record", String.class, AuditService.Entry.class),
                    AuditService.class.getMethod(
                            "recordAll", String.class, java.util.Collection.class)
                }) {
            org.springframework.transaction.annotation.Transactional tx =
                    m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertThat(tx.propagation())
                    .as(m.getName())
                    .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
        }
    }

    @Test
    void entriesCarryApprovalModuleAndType() {
        AuditService.Entry entry =
                AuditService.Entry.change("employee", "1", "f", "a", "b", "r")
                        .approval(7L)
                        .module("M-01")
                        .type(ChangeType.REVERSAL);
        assertThat(entry.approvalId()).isEqualTo(7L);
        assertThat(entry.moduleCode()).isEqualTo("M-01");
        assertThat(entry.changeType()).isEqualTo(ChangeType.REVERSAL);
    }

    @Test
    void SRS_20_18_aChangeWithoutAnActorIsTheSystemsOnTheBusinessDay() {
        when(provider.getIfAvailable()).thenReturn(null);

        service.record(
                "  ",
                AuditService.Entry.change(
                        "employee", "k".repeat(120), "basic_pay", "1", "2", "Run result"));

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.captor();
        verify(audit).save(saved.capture());
        AuditEvent event = saved.getValue();
        assertThat(event.getChangedBy()).isEqualTo("system");
        assertThat(event.getReason()).isEqualTo("Run result");
        assertThat(event.getEntityKey()).isEqualTo("k".repeat(100));
        assertThat(event.getChangedAt()).isEqualTo(Instant.parse("2026-10-24T06:30:00.123456Z"));
        // 06:30 UTC is noon in India: the business day, not the UTC one, is the effective date.
        assertThat(event.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(event.getLocationId()).isNull();
        ArgumentCaptor<SecurityEvent> logged = ArgumentCaptor.captor();
        verify(securityLog).log(logged.capture());
        assertThat(String.valueOf(logged.getValue())).contains("AUDIT_BASIC_PAY", "system");
    }

    /**
     * BR-AUDIT-003 / SRS-20-2: a row without a reason is not a valid audit entry: it is refused
     * (blank too), in every write path, and nothing is saved or logged; a stated effective date is
     * kept as given.
     */
    @Test
    void BR_AUDIT_003_aRowWithoutAReasonIsRefusedAndNothingIsWritten() {
        for (String missing : new String[] {null, "", "   "}) {
            AuditService.Entry entry =
                    AuditService.Entry.change("employee", "7", "basic_pay", "1", "2", missing);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.record("u", entry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BR-AUDIT-003")
                    .hasMessageContaining("employee.basic_pay");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.recordIndependently("u", entry))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.recordAll("u", java.util.List.of(entry)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(audit, times(0)).save(org.mockito.ArgumentMatchers.any());
        verify(securityLog, times(0)).log(org.mockito.ArgumentMatchers.any());

        when(provider.getIfAvailable()).thenReturn(null);
        service.record(
                "u",
                AuditService.Entry.change("employee", "7", "basic_pay", "1", "2", "Fixation")
                        .effective(LocalDate.of(2026, 4, 1)));
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.captor();
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getEffectiveDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    /**
     * BR-AUDIT-003 / SRS-20-2, X-04 Effective Date (US-GAP-130, OD-50): every row carries its
     * effective date. A change effective at once carries the India business date it is recorded on,
     * also in the first minutes after India's midnight (UTC still the day, and the month, before);
     * a stated date, past or future, is kept as given, never replaced by the recording date.
     */
    @Test
    void BR_AUDIT_003_everyRowCarriesItsEffectiveDateTheRecordingDayInIndiaWhenNoneIsStated() {
        when(provider.getIfAvailable()).thenReturn(null);
        // 18:31 UTC on 31 October is 00:01 IST on 1 November.
        AuditService atMidnight =
                new AuditService(
                        audit,
                        securityEvents,
                        securityLog,
                        Clock.fixed(Instant.parse("2026-10-31T18:31:00Z"), ZoneOffset.UTC),
                        properties(),
                        provider);
        atMidnight.record(
                "u", AuditService.Entry.change("employee", "7", "basic_pay", "1", "2", "Fixation"));
        atMidnight.recordIndependently(
                "u", AuditService.Entry.change("employee", "7", "flag", "N", "Y", "Flag"));
        atMidnight.record(
                "u",
                AuditService.Entry.change("employee", "7", "basic_pay", "2", "3", "Revision")
                        .effective(LocalDate.of(2026, 12, 1)));
        atMidnight.record(
                "u",
                AuditService.Entry.change("employee", "7", "basic_pay", "3", "4", "Arrear")
                        .effective(LocalDate.of(2026, 10, 31)));
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.captor();
        verify(audit, times(4)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(AuditEvent::getEffectiveDate)
                .containsExactly(
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 12, 1),
                        LocalDate.of(2026, 10, 31));
    }

    @Test
    void theActingOfficeIsTheSessionsAndStaysEmptyForAJobOrASignInAttempt() {
        when(provider.getIfAvailable()).thenReturn(access);
        AccessContext context = mock(AccessContext.class);
        when(context.locationId()).thenReturn(300L);
        when(access.current()).thenReturn(context);

        service.record(
                "a".repeat(60),
                AuditService.Entry.change("employee", "1", "basic_pay", "1", "2", "r")
                        .effective(LocalDate.of(2026, 9, 1)));

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.captor();
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getLocationId()).isEqualTo(300L);
        assertThat(saved.getValue().getChangedBy()).isEqualTo("a".repeat(50));
        assertThat(saved.getValue().getEffectiveDate()).isEqualTo(LocalDate.of(2026, 9, 1));

        // No session on this thread (a sign-in attempt, a batch job): the location stays empty.
        when(access.current()).thenThrow(new IllegalStateException("no request bound"));
        service.recordIndependently(
                null,
                AuditService.Entry.change(
                        "app_user", "u-1", "LOGIN", null, "FAILURE: bad password", "r"));
        verify(audit, times(2)).save(saved.capture());
        assertThat(saved.getValue().getLocationId()).isNull();
        assertThat(saved.getValue().getChangedBy()).isEqualTo("system");
        ArgumentCaptor<SecurityEvent> logged = ArgumentCaptor.captor();
        verify(securityLog, times(2)).log(logged.capture());
        assertThat(String.valueOf(logged.getValue())).contains("LOGIN", "FAILURE");
    }

    @Test
    void aSecurityEventWithoutAnActorIsAnonymousAndAnAdministratorsActionIsNoFailure() {
        service.securityEvent(
                AuditService.Security.OUT_OF_SCOPE_ACCESS, "", "employee", "9", "scope=DO-LKO");
        service.securityEvent(
                AuditService.Security.SESSION_TERMINATED, "admin.one", "app_session", "s-1", null);

        ArgumentCaptor<SecurityEventRecord> saved = ArgumentCaptor.captor();
        verify(securityEvents, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(SecurityEventRecord::getActingUser)
                .containsExactly("anonymous", "admin.one");
        assertThat(saved.getAllValues())
                .extracting(SecurityEventRecord::getOccurredAt)
                .containsOnly(Instant.parse("2026-10-24T06:30:00.123456Z"));
        ArgumentCaptor<SecurityEvent> logged = ArgumentCaptor.captor();
        verify(securityLog, times(2)).log(logged.capture());
        assertThat(String.valueOf(logged.getAllValues().get(0)))
                .contains("OUT_OF_SCOPE_ACCESS", "anonymous", "scope=DO-LKO");
        assertThat(String.valueOf(logged.getAllValues().get(1)))
                .contains("SESSION_TERMINATED", "admin.one");
    }

    /** SRS-20-27: a blocked submission names its record, or "-" while the record does not exist. */
    @Test
    void SRS_20_27_aBlockedSubmissionNamesItsRecordOrADash() {
        when(provider.getIfAvailable()).thenReturn(null);
        service.submissionBlocked("maker", "loan", null, "L-01", "order copy");
        service.submissionBlocked("maker", "loan", "  ", "L-01", "order copy");
        service.submissionBlocked("maker", "loan", "LN-7", "L-01", "receipt");
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.captor();
        verify(audit, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(AuditEvent::getEntityKey)
                .containsExactly("-", "-", "LN-7");
        assertThat(saved.getAllValues().get(2).getReason())
                .isEqualTo("Submission blocked: receipt missing");
    }

    /**
     * SRS-20-18 / DDL audit_event.entity_key NOT NULL: a change without its record key is refused,
     * and a reason is cut to its 500 characters.
     */
    @Test
    void SRS_20_18_aChangeWithoutAKeyIsRefusedAndALongReasonIsCut() {
        when(provider.getIfAvailable()).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                service.record(
                                        "maker",
                                        AuditService.Entry.change(
                                                "employee", null, "basic_pay", "1", "2", "r")))
                .isInstanceOf(NullPointerException.class);
        service.record(
                "maker",
                AuditService.Entry.change("employee", "7", "basic_pay", "1", "2", "r".repeat(501)));
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.captor();
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("r".repeat(500));
    }
}
