package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The audit rows of an approved configuration change, written in the decision's transaction
 * (SRS-20-2, SRS-20-18): one row per changed field (before → after, from the version in force
 * before), and an END_DATE row for each earlier version the change closed (BR-AUDIT-002). Each row
 * carries the approval, the screen and the date the change applies from.
 */
@Component
class MasterAudit {

    private final AuditService audit;

    MasterAudit(AuditService audit) {
        this.audit = audit;
    }

    /**
     * @param before the version in force before (null for a first version)
     * @param endedOn the new last day of each earlier version the change closed
     * @param reason the reason entered with the change (or the checker's, when the maker's is not
     *     kept)
     */
    void applied(
            ApprovalDecided decision,
            String entity,
            String key,
            String screen,
            LocalDate effectiveFrom,
            Map<String, String> before,
            Map<String, String> after,
            List<LocalDate> endedOn,
            String reason) {
        String why = reason == null || reason.isBlank() ? "Approved" : reason;
        List<AuditService.Entry> entries = new ArrayList<>();
        for (LocalDate end : endedOn) {
            entries.add(
                    AuditService.Entry.change(
                                    entity, key, "effective_to", null, end.toString(), why)
                            .type(ChangeType.END_DATE));
        }
        entries.addAll(FieldChanges.between(entity, key, before, after, why));
        audit.recordAll(
                decision.checkerLoginId(),
                entries.stream()
                        .map(
                                e ->
                                        e.approval(decision.approvalId())
                                                .module(screen)
                                                .effective(effectiveFrom))
                        .toList());
    }
}
