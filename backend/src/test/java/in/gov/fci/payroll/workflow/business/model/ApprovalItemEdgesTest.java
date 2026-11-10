package in.gov.fci.payroll.workflow.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** R-MC: an approval's payload and decision reason; an inbox item handed up a level. */
class ApprovalItemEdgesTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private static ApprovalItem item(String payload) {
        return ApprovalItem.submit(
                "M-01",
                1L,
                2,
                "employee",
                "106620",
                ActionType.UPDATE,
                payload,
                null,
                "maker",
                "r",
                null,
                null,
                NOW);
    }

    @Test
    void R_MC_aMissingPayloadIsAnEmptyObjectAGivenOneIsKept() {
        assertThat(item(null).getPayload()).isEqualTo("{}");
        assertThat(item("{\"a\":1}").getPayload()).isEqualTo("{\"a\":1}");
    }

    @Test
    void R_MC_theCheckersReasonIsKeptAndAMissingOneLeavesTheMakersReason() {
        ApprovalItem given = item("{}");
        given.decide(ApprovalStatus.APPROVED, "checker", "in order", NOW);
        assertThat(given.getDecisionReason()).isEqualTo("in order");
        ApprovalItem none = item("{}");
        none.decide(ApprovalStatus.REJECTED, "checker", null, NOW);
        assertThat(none.getDecisionReason()).isEqualTo("r");
    }

    @Test
    void R_MC_eachHandOverIsOneMoreEscalationStep() {
        WorkflowInboxItem i = WorkflowInboxItem.open(1L, "PAYROLL_CHECKER", 10L, NOW);
        i.moveTo("ZONAL_CHECKER", 20L, NOW.plusSeconds(60), NOW);
        assertThat(i.getEscalationLevel()).isEqualTo(1);
        i.moveTo("HQ_CHECKER", 30L, NOW.plusSeconds(120), NOW);
        assertThat(i.getEscalationLevel()).isEqualTo(2);
        assertThat(i.getSlaDueAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(i.isBreached()).isFalse();
    }
}
