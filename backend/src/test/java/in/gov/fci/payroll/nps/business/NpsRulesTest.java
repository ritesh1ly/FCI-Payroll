package in.gov.fci.payroll.nps.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.nps.business.NpsRules.Mismatch;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The NPS record received from HRMS (plan 18 phase 18a): SRS-9-1 / SRS-9-1a flag and amounts,
 * SRS-9-2 PRAN as a reference, SRS-9-18 separation.
 */
class NpsRulesTest {

    private static BigDecimal amount(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    // SC-18-0002, SC-18-0014: an NPS flag without its amount, or an amount without the flag, is a
    // mismatch raised for correction in HRMS.
    @ParameterizedTest
    @CsvSource(
            nullValues = "-",
            value = {
                "true, 1500, 2100, CONSISTENT",
                "true, 1500, -, CONSISTENT",
                "true, -, 2100, FLAG_WITHOUT_AMOUNT",
                "true, 0, 2100, FLAG_WITHOUT_AMOUNT",
                "false, -, -, CONSISTENT",
                "false, 0, 0, CONSISTENT",
                "false, 1500, -, AMOUNT_WITHOUT_FLAG",
                "false, -, 2100, AMOUNT_WITHOUT_FLAG"
            })
    void SRS_9_1a_aFlagWithoutAmountOrAnAmountWithoutFlagIsAMismatch(
            boolean flag, String employee, String employer, Mismatch expected) {
        assertThat(NpsRules.status(flag, amount(employee), amount(employer), null))
                .isEqualTo(expected);
    }

    @Test
    void SRS_9_1a_aMismatchCorrectedInHrmsIsResolvedAndOnlyAMismatchBlocks() {
        BigDecimal a = new BigDecimal("1500");
        assertThat(NpsRules.status(true, a, a, Mismatch.FLAG_WITHOUT_AMOUNT))
                .isEqualTo(Mismatch.RESOLVED_IN_HRMS);
        assertThat(NpsRules.status(false, null, null, Mismatch.AMOUNT_WITHOUT_FLAG))
                .isEqualTo(Mismatch.RESOLVED_IN_HRMS);
        assertThat(NpsRules.status(true, a, a, Mismatch.CONSISTENT)).isEqualTo(Mismatch.CONSISTENT);
        assertThat(NpsRules.status(true, a, a, Mismatch.RESOLVED_IN_HRMS))
                .isEqualTo(Mismatch.CONSISTENT);
        assertThat(NpsRules.status(true, null, a, Mismatch.FLAG_WITHOUT_AMOUNT))
                .isEqualTo(Mismatch.FLAG_WITHOUT_AMOUNT);
        assertThat(Mismatch.FLAG_WITHOUT_AMOUNT.blocksLock()).isTrue();
        assertThat(Mismatch.AMOUNT_WITHOUT_FLAG.blocksLock()).isTrue();
        assertThat(Mismatch.CONSISTENT.blocksLock()).isFalse();
        assertThat(Mismatch.RESOLVED_IN_HRMS.blocksLock()).isFalse();
    }

    @Test
    void SRS_9_2_theRecordIsCheckedOnlyForItsShape() {
        LocalDate oct = LocalDate.of(2026, 10, 1);
        BigDecimal a = new BigDecimal("1500.50");
        assertThat(NpsRules.defect("100041", "202610", a, a, "110012345678", oct)).isEmpty();
        assertThat(NpsRules.defect("100041", "202610", null, null, null, null)).isEmpty();
        assertThat(NpsRules.defect(null, "202610", a, a, null, null)).contains("NO_EMPLOYEE");
        assertThat(NpsRules.defect(" ", "202610", a, a, null, null)).contains("NO_EMPLOYEE");
        assertThat(NpsRules.defect("100041", "202613", a, a, null, null)).contains("PERIOD");
        assertThat(NpsRules.defect("100041", "20261", a, a, null, null)).contains("PERIOD");
        assertThat(NpsRules.defect("100041", null, a, a, null, null)).contains("PERIOD");
        assertThat(NpsRules.defect("100041", "202610", new BigDecimal("-1"), a, null, null))
                .contains("AMOUNT");
        assertThat(NpsRules.defect("100041", "202610", a, new BigDecimal("1.005"), null, null))
                .contains("AMOUNT");
        assertThat(
                        NpsRules.defect(
                                "100041", "202610", new BigDecimal("10000000000"), a, null, null))
                .contains("AMOUNT");
        assertThat(
                        NpsRules.defect(
                                "100041", "202610", new BigDecimal("9999999999.99"), a, null, null))
                .isEmpty();
        assertThat(NpsRules.defect("100041", "202610", a, a, "1100123456789", null))
                .contains("PRAN");
        assertThat(NpsRules.defect("100041", "202610", a, a, "1100-1234", null)).contains("PRAN");
        assertThat(NpsRules.defect("100041", "202610", a, a, "", null)).contains("PRAN");
        assertThat(NpsRules.defect("100041", "202610", a, a, null, LocalDate.of(2026, 10, 31)))
                .isEmpty();
        assertThat(NpsRules.defect("100041", "202610", a, a, null, LocalDate.of(2026, 11, 1)))
                .contains("EFFECTIVE_DATE");
    }

    // SRS-1-47: the NPS deduction stops from the separation received from HRMS.
    // SC-16-0046: the NPS deduction stops at the separation received from HRMS.
    @Test
    void SRS_9_18_noNpsForAMonthStartingAfterTheSeparationDate() {
        YearMonth oct = YearMonth.of(2026, 10);
        assertThat(NpsRules.separatedBefore(null, oct)).isFalse();
        assertThat(NpsRules.separatedBefore(LocalDate.of(2026, 9, 30), oct)).isTrue();
        // OPEN-QUESTION CLR-130 (BMAD TR-042; with CLR-59): a separation dated the 1st - is that
        // month deducted? As built (deducted as received) until FCI answers.
        assertThat(NpsRules.separatedBefore(LocalDate.of(2026, 10, 1), oct))
                .as("the month of separation is deducted as received")
                .isFalse();
        assertThat(NpsRules.month("202610")).contains(oct);
    }

    // US-GAP-291 (OD-55): a change of a consistent record's flag or amount has a pay effect.
    @ParameterizedTest
    @CsvSource(
            nullValues = "-",
            value = {
                "CONSISTENT, true, 1500, 2100, CONSISTENT, true, 1600, 2100, true",
                "CONSISTENT, true, 1500, 2100, CONSISTENT, true, 1500, 2200, true",
                "CONSISTENT, false, -, -, CONSISTENT, true, 1500, 2100, true",
                "RESOLVED_IN_HRMS, true, 1500, 2100, CONSISTENT, false, -, -, true",
                "CONSISTENT, true, 1500, 2100, CONSISTENT, true, 1500.00, 2100, false",
                "CONSISTENT, true, 1500, -, CONSISTENT, true, 1500, 0, false",
                "CONSISTENT, true, 1500, 2100, FLAG_WITHOUT_AMOUNT, true, -, 2100, false",
                "FLAG_WITHOUT_AMOUNT, true, -, 2100, RESOLVED_IN_HRMS, true, 1500, 2100, false",
                "AMOUNT_WITHOUT_FLAG, false, 1500, -, RESOLVED_IN_HRMS, false, -, -, false",
                "-, true, 1500, 2100, CONSISTENT, true, 1600, 2100, false"
            })
    void SRS_9_23_aChangeOfAConsistentRecordsFlagOrAmountHasAPayEffect(
            Mismatch before,
            boolean flagBefore,
            String employeeBefore,
            String employerBefore,
            Mismatch now,
            boolean flag,
            String employee,
            String employer,
            boolean expected) {
        assertThat(
                        NpsRules.payEffect(
                                before,
                                flagBefore,
                                amount(employeeBefore),
                                amount(employerBefore),
                                now,
                                flag,
                                amount(employee),
                                amount(employer)))
                .isEqualTo(expected);
    }

    // US-GAP-294 (SRS-9-1a, SRS-9-2): the flag / amount check is the only mismatch; a record
    // without a PRAN is not one (what else a blank PRAN means waits for FCI, CLR-58).
    @Test
    void SRS_9_2_aFlaggedRecordWithAnAmountAndNoPranIsConsistent() {
        BigDecimal a = new BigDecimal("1500");
        assertThat(NpsRules.status(true, a, a, null)).isEqualTo(Mismatch.CONSISTENT);
        assertThat(NpsRules.defect("870001", "202610", a, a, null, null)).isEmpty();
    }
}
