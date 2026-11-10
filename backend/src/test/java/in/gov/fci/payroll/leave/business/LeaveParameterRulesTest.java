package in.gov.fci.payroll.leave.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.leave.business.LeaveParameterRules.Proposal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Plan 19 phase 19b: what each M-20 parameter value must be (SRS-LV-14, SRS M-20 field table). */
class LeaveParameterRulesTest {

    private static Proposal p(String code, String type, BigDecimal number, String text) {
        return new Proposal(code, type, number, text, "As in the current system");
    }

    @Test
    void SRS_LV_14_aNumberUpToTheLargestTheColumnHoldsIsAcceptedAndOneStepMoreIsNot() {
        // The value column is NUMBER(18,4): 99999999999999.9999 is the largest it holds.
        BigDecimal max = new BigDecimal("99999999999999.9999");
        BigDecimal over = max.add(new BigDecimal("0.0001"));
        for (String code : java.util.List.of("ENCASH_DIVISOR", "LWP_DIVISOR")) {
            assertThat(LeaveParameterRules.defect(p(code, null, max, null), false))
                    .as(code)
                    .isEmpty();
            assertThat(LeaveParameterRules.defect(p(code, null, over, null), false))
                    .as(code)
                    .contains("VALUE");
        }
        assertThat(
                        LeaveParameterRules.defect(
                                p("SEPARATION_AGGREGATE_CEILING", null, max, null), false))
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("SEPARATION_AGGREGATE_CEILING", null, over, null), false))
                .contains("VALUE");
    }

    @Test
    void SRS_LV_14_eachParameterTakesItsOwnKindOfValue() {
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_DIVISOR", null, new BigDecimal("30"), null), false))
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_DIVISOR", null, BigDecimal.ZERO, null), false))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("ENCASH_DIVISOR", null, null, "30"), false))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("LWP_DIVISOR", null, null, "CALENDAR_DAYS"), false))
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("LWP_DIVISOR", null, new BigDecimal("30"), null), false))
                .isEmpty();
        assertThat(LeaveParameterRules.defect(p("LWP_DIVISOR", null, null, "WORKING_DAYS"), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("LWP_DIVISOR", null, BigDecimal.ONE, "CALENDAR_DAYS"), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_PAY_ELEMENTS", null, null, "101+111"), false))
                .isEmpty();
        assertThat(LeaveParameterRules.defect(p("ENCASH_PAY_CODES", null, null, "184,185"), false))
                .isEmpty();
        assertThat(LeaveParameterRules.defect(p("ENCASH_PAY_CODES", null, null, "BASIC"), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_PAY_CODES", null, BigDecimal.ONE, "184"), false))
                .contains("VALUE");
        // US-GAP-298 (SRS-LV-15, BR-LEAVE-007): the components subject to LWP are an M-07
        // attribute, no longer an M-20 parameter.
        assertThat(LeaveParameterRules.defect(p("LWP_COMPONENTS", null, null, "101"), false))
                .contains("PARAMETER");
        assertThat(
                        LeaveParameterRules.defect(
                                p("SEPARATION_AGGREGATE_CEILING", null, BigDecimal.ZERO, null),
                                false))
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("INSERVICE_PER_YEAR_LIMIT", null, new BigDecimal("-1"), null),
                                false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("INSERVICE_PER_YEAR_LIMIT", null, null, null), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p(
                                        "INSERVICE_PER_OCCASION_LIMIT",
                                        null,
                                        new BigDecimal("1E15"),
                                        null),
                                false))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("UNKNOWN", null, BigDecimal.ONE, null), false))
                .contains("PARAMETER");
        assertThat(LeaveParameterRules.defect(p(null, null, BigDecimal.ONE, null), false))
                .contains("PARAMETER");
    }

    @Test
    void SRS_LV_27_thePayEffectIsHeldPerLeaveTypeAsADdlWageEffect() {
        assertThat(LeaveParameterRules.defect(p("PAY_EFFECT", "LWP", null, "FULL_DEDUCTION"), true))
                .isEmpty();
        assertThat(LeaveParameterRules.defect(p("PAY_EFFECT", null, null, "FULL_DEDUCTION"), true))
                .contains("LEAVE_TYPE_REQUIRED");
        assertThat(LeaveParameterRules.defect(p("PAY_EFFECT", "LWP", null, "HELD_UNDEFINED"), true))
                .as("never configured as a value")
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("PAY_EFFECT", "XX", null, "HALF_PAY"), false))
                .contains("LEAVE_TYPE");
    }

    @Test
    void SRS_LV_17_onlyTheLimitsMayBeSetForOneLeaveType() {
        assertThat(
                        LeaveParameterRules.defect(
                                p("INSERVICE_PER_OCCASION_LIMIT", "EL", BigDecimal.TEN, null),
                                true))
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_DIVISOR", "EL", BigDecimal.TEN, null), true))
                .contains("LEAVE_TYPE_NOT_ALLOWED");
        assertThat(
                        LeaveParameterRules.defect(
                                new Proposal("ENCASH_DIVISOR", null, BigDecimal.TEN, null, " "),
                                false))
                .contains("REASON");
        assertThat(
                        LeaveParameterRules.defect(
                                new Proposal("ENCASH_DIVISOR", null, BigDecimal.TEN, null, null),
                                false))
                .contains("REASON");
    }

    @Test
    void thePayCodesOfAValueAreReadInOrder() {
        assertThat(LeaveParameterRules.payCodes(" 101+111 ")).containsExactly(101, 111);
        assertThat(LeaveParameterRules.payCodes("184,185")).containsExactly(184, 185);
    }

    /** SRS M-20 field table: each kind takes exactly its form of value, within the column. */
    @Test
    void SRS_LV_14_eachKindRefusesTheOtherFormOfValueAndAValueBeyondTheColumn() {
        BigDecimal tooBig = new BigDecimal("100000000000000");
        assertThat(LeaveParameterRules.defect(p("LWP_DIVISOR", " ", BigDecimal.TEN, null), false))
                .as("a blank leave type is none")
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_DIVISOR", null, BigDecimal.TEN, " "), false))
                .as("a blank text is none")
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_DIVISOR", null, BigDecimal.TEN, "30"), false))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("ENCASH_DIVISOR", null, tooBig, null), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_DIVISOR", null, BigDecimal.ZERO, null), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("INSERVICE_PER_YEAR_LIMIT", null, BigDecimal.TEN, "x"), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("INSERVICE_PER_YEAR_LIMIT", null, null, null), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("INSERVICE_PER_YEAR_LIMIT", null, tooBig, null), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("INSERVICE_PER_YEAR_LIMIT", null, BigDecimal.ZERO, null), false))
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                p("LWP_DIVISOR", null, BigDecimal.TEN, "CALENDAR_DAYS"), false))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("LWP_DIVISOR", null, null, "OTHER"), false))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("LWP_DIVISOR", null, null, null), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_PAY_ELEMENTS", null, BigDecimal.ONE, "101"), false))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("ENCASH_PAY_ELEMENTS", null, null, null), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("ENCASH_PAY_ELEMENTS", null, null, "101;111"), false))
                .contains("VALUE");
        assertThat(
                        LeaveParameterRules.defect(
                                p("PAY_EFFECT", "LWP", BigDecimal.ONE, "NO_EFFECT"), true))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("PAY_EFFECT", "LWP", null, null), true))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("PAY_EFFECT", "LWP", null, "HELD_UNDEFINED"), true))
                .contains("VALUE");
        assertThat(LeaveParameterRules.defect(p("PAY_EFFECT", "LWP", null, "NO_EFFECT"), true))
                .isEmpty();
    }

    /** R-ASCII: the M-20 reason is a text area: line breaks kept within 500, controls refused. */
    @Test
    void R_ASCII_theReasonOfATextAreaKeepsItsLineBreaks() {
        assertThat(
                        LeaveParameterRules.defect(
                                new Proposal(
                                        "ENCASH_DIVISOR",
                                        null,
                                        BigDecimal.TEN,
                                        null,
                                        "Line\r\nbreak"),
                                false))
                .isEmpty();
        assertThat(
                        LeaveParameterRules.defect(
                                new Proposal(
                                        "ENCASH_DIVISOR",
                                        null,
                                        BigDecimal.TEN,
                                        null,
                                        "x".repeat(501)),
                                false))
                .contains("REASON");
        assertThat(
                        LeaveParameterRules.defect(
                                new Proposal(
                                        "ENCASH_DIVISOR", null, BigDecimal.TEN, null, "a\u0007"),
                                false))
                .contains("REASON");
    }
}
