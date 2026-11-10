package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.notification.MemberDocumentsReleased;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The document e-mail outbox's edges (OD-148; SRS-17-12): a staff user without an own record reads
 * no own e-mails; a release without items queues and audits nothing; each document is listed on its
 * own screen (payslip C-02, Form 16 C-10). The queueing itself runs in DocumentMailIT.
 */
class DocumentMailsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final DocumentMails mails =
            new DocumentMails(
                    jdbc,
                    policy,
                    audit,
                    Clock.fixed(Instant.parse("2026-11-09T05:00:00Z"), ZoneOffset.UTC),
                    mock(MailChannel.class));

    @Test
    void SRS_17_12_aUserWithoutAnOwnRecordReadsNoOwnEmails() {
        AccessContext staff =
                new AccessContext(
                        1L, "m", "M", "s", "P", 5L, null, List.of(), Map.of(), Set.of(), Set.of(),
                        Set.of());
        assertThatThrownBy(() -> mails.mine(staff, new PageSpec(0, 20)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
    }

    @Test
    void SRS_17_12_aReleaseWithoutItemsOrAlreadyQueuedQueuesAndAuditsNothing() {
        mails.on(new MemberDocumentsReleased("FORM16", 3L, "c", List.of()));
        when(jdbc.queryForObject(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(Long.class),
                        any(Object[].class)))
                .thenReturn(1L);
        mails.on(
                new MemberDocumentsReleased(
                        "FORM16",
                        3L,
                        "c",
                        List.of(new MemberDocumentsReleased.Item(7, "2025-26", "1"))));
        verify(audit, never()).record(anyString(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void SRS_17_1_eachDocumentIsListedOnItsOwnScreen() {
        assertThat(DocumentMails.screenOf("PAYSLIP")).isEqualTo("C-02");
        assertThat(DocumentMails.screenOf("FORM16")).isEqualTo("C-10");
        assertThat(DocumentMails.name("Rakesh", " ")).isEqualTo("Rakesh");
        assertThat(DocumentMails.name("Rakesh", null)).isEqualTo("Rakesh");
        assertThat(DocumentMails.name("Rakesh", "Sharma")).isEqualTo("Rakesh Sharma");
    }
}
