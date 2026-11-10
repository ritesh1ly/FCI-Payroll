package in.gov.fci.payroll.integration.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import org.junit.jupiter.api.Test;

/** SRS-INT-6: the exact edges of a successful status (200 to 299) and of the message cut. */
class HrmsReplyMutationGapsTest {

    @Test
    void SRS_INT_6_onlyStatus200To299IsASuccess() {
        assertThat(HrmsReply.parse(199, "{\"message\":\"m\"}", 1).outcome())
                .isEqualTo(Outcome.REJECTED);
        assertThat(HrmsReply.parse(200, "{\"message\":\"m\"}", 1).outcome())
                .isEqualTo(Outcome.ACCEPTED);
        assertThat(HrmsReply.parse(299, "{\"message\":\"m\"}", 1).outcome())
                .isEqualTo(Outcome.ACCEPTED);
        assertThat(HrmsReply.parse(300, "{\"message\":\"m\"}", 1).outcome())
                .isEqualTo(Outcome.REJECTED);
        assertThat(HrmsReply.parse(200, "", 1).outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(HrmsReply.parse(300, "", 1).outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void SRS_INT_6_aMessageOfExactlyTheLimitIsKeptAndOneLongerIsCut() {
        String at = "a".repeat(300);
        assertThat(HrmsReply.parse(200, "{\"message\":\"" + at + "\"}", 1).message()).isEqualTo(at);
        assertThat(HrmsReply.parse(200, "{\"message\":\"" + at + "b\"}", 1).message())
                .isEqualTo(at);
        assertThat(HrmsReply.parse(200, "{\"message\":\"" + "a".repeat(299) + "\"}", 1).message())
                .hasSize(299);
    }
}
