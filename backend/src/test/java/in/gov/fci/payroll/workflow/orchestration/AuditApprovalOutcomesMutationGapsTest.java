package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.audit.api.ApprovalOutcomes.Outcome;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SRS-G-2 mutation gap: for no audit rows the answer is an ordinary, mutable (empty) map. */
class AuditApprovalOutcomesMutationGapsTest {

    private final AuditApprovalOutcomes outcomes =
            new AuditApprovalOutcomes(mock(WorkflowRepositories.Approvals.class));

    @Test
    void SRS_G_2_noApprovalsAnswerAnEmptyMapThatCallersMayFill() {
        Map<Long, Outcome> forNull = outcomes.outcomes(null);
        forNull.put(1L, new Outcome(1L, "APPROVED", "maker", "checker", null, "I-20"));
        assertThat(forNull).containsOnlyKeys(1L);

        Map<Long, Outcome> forEmpty = outcomes.outcomes(List.of());
        forEmpty.put(2L, new Outcome(2L, "APPROVED", "maker", "checker", null, "I-20"));
        assertThat(forEmpty).containsOnlyKeys(2L);
    }
}
