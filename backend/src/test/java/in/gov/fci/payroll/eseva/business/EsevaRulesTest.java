package in.gov.fci.payroll.eseva.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents.Status;
import org.junit.jupiter.api.Test;

/**
 * eSeva rules (plan 14; SRS-17-3, SRS-17-8, SRS-17-10; R-ASCII, R-PII): a log row moves forward
 * only; the summary is printable and bounded; a preview reason is required, printable and bounded;
 * PAN, Aadhaar, bank account and mobile are masked.
 */
class EsevaRulesTest {

    @Test
    void SRS_17_3_aLogRowMovesForwardOnly() {
        assertThat(EsevaRules.moves(Status.SUBMITTED, Status.ROUTED)).isTrue();
        assertThat(EsevaRules.moves(Status.SUBMITTED, Status.ACTIONED)).isTrue();
        assertThat(EsevaRules.moves(Status.SUBMITTED, Status.REJECTED)).isTrue();
        assertThat(EsevaRules.moves(Status.SUBMITTED, Status.SUBMITTED)).isFalse();
        assertThat(EsevaRules.moves(Status.ROUTED, Status.ACTIONED)).isTrue();
        assertThat(EsevaRules.moves(Status.ROUTED, Status.REJECTED)).isTrue();
        assertThat(EsevaRules.moves(Status.ROUTED, Status.ROUTED)).isFalse();
        assertThat(EsevaRules.moves(Status.ROUTED, Status.SUBMITTED)).isFalse();
        for (Status to : Status.values()) {
            assertThat(EsevaRules.moves(Status.ACTIONED, to)).isFalse();
            assertThat(EsevaRules.moves(Status.REJECTED, to)).isFalse();
        }
        assertThat(EsevaRules.isFinal(Status.ACTIONED)).isTrue();
        assertThat(EsevaRules.isFinal(Status.REJECTED)).isTrue();
        assertThat(EsevaRules.isFinal(Status.ROUTED)).isFalse();
        assertThat(EsevaRules.isFinal(Status.SUBMITTED)).isFalse();
    }

    @Test
    void R_ASCII_theSummaryIsPrintableAndBounded() {
        assertThat(EsevaRules.value(null)).isNull();
        assertThat(EsevaRules.value(" CPF\tADVANCE é ")).isEqualTo("CPF ADVANCE");
        assertThat(EsevaRules.value("x".repeat(1200))).hasSize(EsevaRules.VALUE_MAX);
        assertThat(EsevaRules.value("x".repeat(1000))).hasSize(1000);
    }

    @Test
    void SRS_17_8_aPreviewNamesAPrintableReasonOfAtMost500Characters() {
        assertThat(EsevaRules.reason(null)).isEmpty();
        assertThat(EsevaRules.reason("  ")).isEmpty();
        assertThat(EsevaRules.reason("x".repeat(501))).isEmpty();
        assertThat(EsevaRules.reason("helpdesk ticket é")).isEmpty();
        assertThat(EsevaRules.reason(" x".repeat(250))).contains(" x".repeat(250).strip());
        assertThat(EsevaRules.reason(" Ticket 42 ")).contains("Ticket 42");
    }

    @Test
    void SRS_17_10_panAadhaarAccountAndMobileAreMasked() {
        // BR-SEC-001 (SRS l.754, l.802; BMAD TR-035): the last 4 characters only.
        assertThat(EsevaRules.maskPan("ABCPK1234D")).isEqualTo("******234D");
        assertThat(EsevaRules.maskPan(" ABCPK1234D ")).isEqualTo("******234D");
        assertThat(EsevaRules.maskPan("ABC")).isEqualTo("****");
        assertThat(EsevaRules.maskPan(null)).isNull();
        assertThat(EsevaRules.maskPan(" ")).isNull();
        assertThat(EsevaRules.maskTail("234567891234")).isEqualTo("********1234");
        assertThat(EsevaRules.maskTail(" 1234 ")).isEqualTo("****");
        assertThat(EsevaRules.maskTail(null)).isNull();
        assertThat(EsevaRules.maskTail("")).isNull();
        assertThat(EsevaRules.maskMobile("9876543210")).isEqualTo("********10");
        assertThat(EsevaRules.maskMobile("12")).isEqualTo("**");
        assertThat(EsevaRules.maskMobile(null)).isNull();
        assertThat(EsevaRules.maskMobile(" ")).isNull();
    }

    @Test
    void SRS_17_12_fcisTemplateIsFilledByNameAndTheMessageIsBounded() {
        assertThat(EsevaRules.render(null, java.util.Map.of("a", "b"))).isEmpty();
        assertThat(
                        EsevaRules.render(
                                "Dear {name}, {missing} {name}", java.util.Map.of("name", "R")))
                .isEqualTo("Dear R, {missing} R");
        java.util.Map<String, String> blank = new java.util.HashMap<>();
        blank.put("name", null);
        assertThat(EsevaRules.render("[{name}]", blank)).isEqualTo("[]");
        assertThat(EsevaRules.message(null)).isNull();
        assertThat(EsevaRules.message("x".repeat(2001))).hasSize(EsevaRules.MESSAGE_MAX);
        assertThat(EsevaRules.message("short")).isEqualTo("short");
    }

    @Test
    void BR_ESEVA_006_aReasonOfTheLongestLengthIsAcceptedAndOneMoreIsNot() {
        assertThat(EsevaRules.reason("r".repeat(EsevaRules.REASON_MAX))).isPresent();
        assertThat(EsevaRules.reason("r".repeat(EsevaRules.REASON_MAX + 1))).isEmpty();
        assertThat(EsevaRules.value("v".repeat(EsevaRules.VALUE_MAX + 1)))
                .hasSize(EsevaRules.VALUE_MAX);
        assertThat(EsevaRules.message("m".repeat(EsevaRules.MESSAGE_MAX + 1)))
                .hasSize(EsevaRules.MESSAGE_MAX);
    }

    /**
     * SRS-17-8 / R-ASCII: the support reason is a text area: line breaks kept, controls refused.
     */
    @Test
    void SRS_17_8_aPreviewReasonMayRunOverSeveralLines() {
        assertThat(EsevaRules.reason(" Ticket 42\r\ncaller verified "))
                .contains("Ticket 42\r\ncaller verified");
        assertThat(EsevaRules.reason("Ticket\u000c42")).isEmpty();
    }
}
