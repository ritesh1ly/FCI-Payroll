package in.gov.fci.payroll.integration.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-INT-6, SRS-INT-31; R-PII: how an HRMS answer is classified. The collection has no saved
 * response, so the reading is defensive: an answer that does not fit is UNREADABLE, never guessed.
 */
class HrmsReplyTest {

    @Test
    void SRS_INT_6_aSuccessEnvelopeIsAcceptedWithItsData() {
        HrmsReply r =
                HrmsReply.parse(
                        200,
                        "{\"status\":\"success\",\"status_code\":200,\"message\":\"done\",\"data\":{\"emp_ota_id\":900001}}",
                        1);
        assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(r.accepted()).isTrue();
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.message()).isEqualTo("done");
        assertThat(r.data().get("emp_ota_id").asInt()).isEqualTo(900001);
        assertThat(r.errorCode()).isNull();
        assertThat(r.duplicate()).isFalse();
        assertThat(r.attempts()).isEqualTo(1);
    }

    @Test
    void SRS_INT_6_aRepeatIsMarkedDuplicateWhereverHrmsPutsTheFlag() {
        assertThat(
                        HrmsReply.parse(200, "{\"status\":\"success\",\"duplicate\":true}", 1)
                                .duplicate())
                .isTrue();
        assertThat(
                        HrmsReply.parse(
                                        200,
                                        "{\"status\":\"success\",\"data\":{\"duplicate\":true}}",
                                        1)
                                .duplicate())
                .isTrue();
        assertThat(
                        HrmsReply.parse(200, "{\"status\":\"success\",\"duplicate\":\"yes\"}", 1)
                                .duplicate())
                .isFalse();
    }

    @Test
    void SRS_INT_6_aBodyWithoutAStatusFieldIsAcceptedOn2xx() {
        HrmsReply r = HrmsReply.parse(204, "{\"ok\":true}", 2);
        assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.data()).isNull();
    }

    @Test
    void SRS_INT_31_aRefusalKeepsTheErrorCodeMessageAndFieldProblems() {
        HrmsReply r =
                HrmsReply.parse(
                        400,
                        "{\"status\":\"error\",\"error_code\":\"VALIDATION_FAILED\",\"message\":\"bad\",\"errors\":[{\"field\":\"emp_num\",\"message\":\"must"
                            + " be an integer\"},{\"message\":\"general\"},{\"field\":\"x\"}]}",
                        1);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.accepted()).isFalse();
        assertThat(r.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(r.problems())
                .containsExactly("emp_num: must be an integer", "general", "x: null");
    }

    @Test
    void SRS_INT_31_aBusinessRefusalWithoutErrorsIsStillRejected() {
        HrmsReply r =
                HrmsReply.parse(
                        422,
                        "{\"status\":\"error\",\"error_code\":\"LIMIT_EXCEEDED\",\"errors\":{}}",
                        1);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.problems()).isEmpty();
        assertThat(HrmsReply.parse(401, "{\"error_code\":\"TOKEN_EXPIRED\"}", 1).errorCode())
                .isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void SRS_INT_31_a200WithAnErrorStatusInTheBodyIsNotASuccess() {
        HrmsReply r =
                HrmsReply.parse(200, "{\"status\":\"ERROR\",\"message\":\"logical failure\"}", 1);
        assertThat(r.outcome()).isEqualTo(Outcome.FAILED_IN_BODY);
        assertThat(r.message()).isEqualTo("logical failure");
    }

    @Test
    void SRS_INT_31_anEmptyTruncatedOrHtmlAnswerOn2xxIsUnreadable() {
        for (String body :
                new String[] {
                    null,
                    "",
                    "   ",
                    "{\"status\":\"succ",
                    "<html>bad gateway</html>",
                    "[1,2]",
                    "\"text\""
                }) {
            HrmsReply r = HrmsReply.parse(200, body, 1);
            assertThat(r.outcome()).as(String.valueOf(body)).isEqualTo(Outcome.UNREADABLE);
            assertThat(r.errorCode()).isNull();
        }
        assertThat(HrmsReply.parse(200, "", 1).message()).isEqualTo("HTTP 200: empty body");
        assertThat(HrmsReply.parse(200, "<html/>", 1).message())
                .isEqualTo("HTTP 200: not a JSON object");
    }

    @Test
    void SRS_INT_31_aNonJsonRefusalIsRejectedNotUnreadable() {
        HrmsReply r = HrmsReply.parse(404, "<html>nope</html>", 1);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.message()).isEqualTo("HTTP 404: not a JSON object");
        assertThat(HrmsReply.parse(403, null, 1).message()).isEqualTo("HTTP 403: empty body");
    }

    @Test
    void R_PII_aLongMessageIsCutAndNothingElseOfTheBodyIsKept() {
        String long400 = "x".repeat(500);
        HrmsReply r =
                HrmsReply.parse(200, "{\"status\":\"success\",\"message\":\"" + long400 + "\"}", 1);
        assertThat(r.message()).hasSize(300);
        assertThat(r.toString()).doesNotContain("password");
    }

    @Test
    void SRS_INT_12_aCallThatWasNeverMadeCarriesTheDefects() {
        HrmsReply r = HrmsReply.invalid(List.of("a", "b"));
        assertThat(r.outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(r.httpStatus()).isZero();
        assertThat(r.attempts()).isZero();
        assertThat(r.errorCode()).isEqualTo("CONTRACT_MISMATCH");
        assertThat(r.problems()).containsExactly("a", "b");
        assertThat(r.accepted()).isFalse();
    }
}
