package in.gov.fci.payroll.audit.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.HistoryTamperAttempt;
import in.gov.fci.payroll.platform.export.ExportDelivered;
import in.gov.fci.payroll.platform.export.ExportFailed;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * History is never changed and every export leaves a trace:
 *
 * <ul>
 *   <li>SRS-20-19 / SRS-20-21: a request that tries to create, change or delete an audit or Legacy
 *       Audit row is refused (AUD-405-IMMUTABLE) and logged as a security event; a database refusal
 *       by an immutability trigger (FCIPAY-AUD-001 / 002) is logged the same way.
 *   <li>SRS-20-24: every export is logged with the requester, the scope and the time.
 * </ul>
 */
@Service
public class HistoryGuard {

    private final AuditService audit;
    private final ObjectProvider<CurrentAccess> access;

    public HistoryGuard(AuditService audit, ObjectProvider<CurrentAccess> access) {
        this.audit = audit;
        this.access = access;
    }

    /** SRS-20-19 / SRS-20-21: records the attempt, then refuses it. */
    public void refuseWrite(String loginId, String method, String path) {
        audit.securityEvent(
                AuditService.Security.HISTORY_EDIT_ATTEMPT,
                loginId,
                path != null && path.contains("/legacy") ? "legacy_audit" : "audit_event",
                null,
                method + " " + path);
        throw new FciException(ApiErrorCode.AUDIT_IMMUTABLE);
    }

    /** SRS-20-19 / SRS-20-21: the database refused a change to history. */
    @EventListener
    public void on(HistoryTamperAttempt attempt) {
        audit.securityEvent(
                AuditService.Security.AUDIT_TAMPER,
                currentLogin(),
                "FCIPAY-AUD-002".equals(attempt.code()) ? "legacy_audit" : "audit_event",
                null,
                attempt.code() + " " + attempt.method() + " " + attempt.path());
    }

    /**
     * SRS-20-24: the export, in its own transaction (the export's snapshot has ended), with its
     * parameters (SRS-22-29: "Log every report download with user, report, parameters and time"). A
     * failure here aborts the download (the event is published before the file is closed).
     */
    @EventListener
    public void on(ExportDelivered export) {
        audit.recordIndependently(
                export.loginId(),
                AuditService.Entry.change(
                        "export",
                        export.name(),
                        "EXPORT",
                        null,
                        export.rows()
                                + " rows "
                                + export.format()
                                + ";office="
                                + export.locationId()
                                + ";groups="
                                + (export.scope() == null ? "" : export.scope())
                                + (export.parameters() == null || export.parameters().isEmpty()
                                        ? ""
                                        : ";parameters=" + export.parameters()),
                        "List exported"));
    }

    /**
     * OD-144, SRS-20-24: a document that could not be rendered is audited as a failed export (its
     * name and error class), in its own transaction; nothing was sent.
     */
    @EventListener
    public void on(ExportFailed failed) {
        audit.recordIndependently(
                currentLogin(),
                AuditService.Entry.change(
                        "export",
                        failed.name(),
                        "EXPORT_FAILED",
                        null,
                        failed.format() + ";error=" + failed.error(),
                        "Document not produced"));
    }

    private String currentLogin() {
        CurrentAccess current = access.getIfAvailable();
        if (current == null) {
            return null;
        }
        try {
            return current.current().loginId();
        } catch (RuntimeException noSession) {
            return null;
        }
    }
}
