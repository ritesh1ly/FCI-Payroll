package in.gov.fci.payroll.audit.business;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SRS-G-2 / FAP-13: the X-04 Status / Outcome of each native audit row. */
class AuditOutcomeTest {

    @Test
    void SRS_G_2_eachRowReadsAsSubmittedApprovedReturnedRejectedPostedOrFailed() {
        assertThat(AuditOutcome.of("EDIT", "PENDING_APPROVAL")).isEqualTo("SUBMITTED");
        assertThat(AuditOutcome.of("DECISION", "APPROVED")).isEqualTo("APPROVED");
        assertThat(AuditOutcome.of("DECISION", "REJECTED")).isEqualTo("REJECTED");
        assertThat(AuditOutcome.of("DECISION", "RETURNED")).isEqualTo("RETURNED");
        assertThat(AuditOutcome.of("LEVEL", "2")).isEqualTo("APPROVED");
        assertThat(AuditOutcome.of("LOGIN", "FAILURE:BAD_PASSWORD")).isEqualTo("FAILED");
        assertThat(AuditOutcome.of("scalar_value", "10")).isEqualTo("POSTED");
        assertThat(AuditOutcome.of("effective_to", null)).isEqualTo("POSTED");
    }

    @Test
    void aDecisionKindOutsideTheListIsShownAsRecordedNeverRelabelled() {
        assertThat(AuditOutcome.of("DECISION", "WITHDRAWN")).isEqualTo("WITHDRAWN");
        assertThat(AuditOutcome.of("DECISION", null)).isNull();
    }
}
