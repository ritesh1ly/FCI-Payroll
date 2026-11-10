package in.gov.fci.payroll.audit.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.HistoryTamperAttempt;
import in.gov.fci.payroll.platform.error.MessageSeverity;
import in.gov.fci.payroll.platform.export.ExportDelivered;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * History is never changed and every export leaves a trace (SRS-20-19, SRS-20-21, SRS-20-24): a
 * write to the audit trail is refused and logged against the audit or the legacy trail; a database
 * refusal is logged with the session's user, or none outside a session; an export without a group
 * scope records none.
 */
class HistoryGuardTest {

    private final AuditService audit = mock(AuditService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<CurrentAccess> access = mock(ObjectProvider.class);

    private final HistoryGuard guard = new HistoryGuard(audit, access);

    @Test
    void SRS_20_19_aWriteToHistoryIsRefusedAndLoggedAgainstItsTrail() {
        assertThatThrownBy(() -> guard.refuseWrite("u@x", "DELETE", "/v1/audit/legacy/5"))
                .isInstanceOf(FciException.class);
        verify(audit).securityEvent(any(), eq("u@x"), eq("legacy_audit"), isNull(), any());
        assertThatThrownBy(() -> guard.refuseWrite("u@x", "PUT", null))
                .isInstanceOf(FciException.class);
        verify(audit).securityEvent(any(), eq("u@x"), eq("audit_event"), isNull(), any());
    }

    /**
     * SRS-EH-5 (l.5664, US-GAP-22): an attempted edit of history is a Security condition. The
     * refusal is AUD-405-IMMUTABLE with severity SECURITY (whatever its 405 status would give), and
     * the attempt is written as a HISTORY_EDIT_ATTEMPT security event naming the method and path
     * before the refusal is thrown.
     */
    @Test
    void SRS_EH_5_AUD_405_IMMUTABLE_isLoggedAsASecurityEvent() {
        assertThatThrownBy(() -> guard.refuseWrite("u@x", "PATCH", "/v1/audit/events/9"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
                                    .isEqualTo(ApiErrorCode.AUDIT_IMMUTABLE);
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode().code())
                                    .isEqualTo("AUD-405-IMMUTABLE");
                            org.assertj.core.api.Assertions.assertThat(ex.getErrorCode().severity())
                                    .isEqualTo(MessageSeverity.SECURITY);
                        });
        verify(audit)
                .securityEvent(
                        eq(AuditService.Security.HISTORY_EDIT_ATTEMPT),
                        eq("u@x"),
                        eq("audit_event"),
                        isNull(),
                        eq("PATCH /v1/audit/events/9"));
    }

    @Test
    void SRS_20_21_aDatabaseRefusalIsLoggedWithTheSessionsUserOrNone() {
        when(access.getIfAvailable()).thenReturn(null);
        guard.on(new HistoryTamperAttempt("FCIPAY-AUD-002", "PUT", "/x"));
        verify(audit).securityEvent(any(), isNull(), eq("legacy_audit"), isNull(), any());

        CurrentAccess noSession = mock(CurrentAccess.class);
        when(noSession.current()).thenThrow(new IllegalStateException("no session"));
        when(access.getIfAvailable()).thenReturn(noSession);
        guard.on(new HistoryTamperAttempt("FCIPAY-AUD-001", "DELETE", "/y"));
        verify(audit).securityEvent(any(), isNull(), eq("audit_event"), isNull(), any());

        CurrentAccess session = mock(CurrentAccess.class);
        AccessContext context = mock(AccessContext.class);
        when(context.loginId()).thenReturn("admin@x");
        when(session.current()).thenReturn(context);
        when(access.getIfAvailable()).thenReturn(session);
        guard.on(new HistoryTamperAttempt("FCIPAY-AUD-001", "DELETE", "/z"));
        verify(audit).securityEvent(any(), eq("admin@x"), eq("audit_event"), isNull(), any());
    }

    @Test
    void SRS_20_24_anExportWithoutAGroupScopeRecordsNone() {
        guard.on(new ExportDelivered("x05", ExportFormat.CSV, 3, "u@x", 40L, null, null));
        guard.on(new ExportDelivered("x06", ExportFormat.CSV, 3, "u@y", 40L, null, ""));
        verify(audit)
                .recordIndependently(
                        eq("u@x"), argThat(e -> e.newValue().endsWith(";office=40;groups=")));
        verify(audit)
                .recordIndependently(
                        eq("u@y"), argThat(e -> e.newValue().endsWith(";office=40;groups=")));
    }

    @Test
    void SRS_22_29_aDownloadIsLoggedWithItsParameters() {
        guard.on(
                new ExportDelivered(
                        "m13", ExportFormat.XLSX, 2, "u@x", 40L, "AG-1", "status=ACTIVE"));
        verify(audit)
                .recordIndependently(
                        eq("u@x"),
                        argThat(
                                e ->
                                        e.newValue()
                                                .endsWith(
                                                        ";groups=AG-1;parameters=status=ACTIVE")));
    }

    /**
     * OD-144, SRS-20-24: a document that could not be rendered leaves a trace; nothing was sent.
     */
    @Test
    void OD_144_aFailedDocumentIsAuditedWithItsNameAndErrorOnly() {
        when(access.getIfAvailable()).thenReturn(null);
        guard.on(
                new in.gov.fci.payroll.platform.export.ExportFailed(
                        "payslip-202609", ExportFormat.PDF, "IllegalStateException"));
        verify(audit)
                .recordIndependently(
                        org.mockito.ArgumentMatchers.isNull(),
                        argThat(
                                e ->
                                        e.newValue().equals("PDF;error=IllegalStateException")
                                                && e.entityKey().equals("payslip-202609")
                                                && e.fieldName().equals("EXPORT_FAILED")));
    }
}
