package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SRS-20-2: an applied master change is audited field by field with the reason entered; a reason
 * that is missing or only spaces is recorded as "Approved", never blank.
 */
class MasterAuditBranchGapsTest {

    private final AuditService audit = mock(AuditService.class);
    private final MasterAudit master = new MasterAudit(audit);

    private static ApprovalDecided approved() {
        return new ApprovalDecided(
                9L,
                "M-27",
                "operation_master",
                "OP1",
                ApprovalStatus.APPROVED,
                null,
                null,
                "rule.approver@x",
                Instant.parse("2026-10-29T06:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private Collection<AuditService.Entry> recorded() {
        ArgumentCaptor<Collection<AuditService.Entry>> entries = ArgumentCaptor.captor();
        verify(audit, org.mockito.Mockito.atLeastOnce())
                .recordAll(eq("rule.approver@x"), entries.capture());
        return entries.getValue();
    }

    @Test
    void SRS_20_2_aBlankReasonIsRecordedAsApproved() {
        master.applied(
                approved(),
                "operation_master",
                "OP1",
                "M-27",
                LocalDate.of(2026, 11, 1),
                null,
                Map.of("operation_name", "Wagon"),
                List.of(LocalDate.of(2026, 10, 31)),
                "   ");
        assertThat(recorded())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.reason()).isEqualTo("Approved"));
    }

    @Test
    void SRS_20_2_theEnteredReasonIsKept() {
        master.applied(
                approved(),
                "operation_master",
                "OP1",
                "M-27",
                LocalDate.of(2026, 11, 1),
                null,
                Map.of("operation_name", "Wagon"),
                List.of(),
                "Revised norm");
        assertThat(recorded())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.reason()).isEqualTo("Revised norm"));
    }
}
