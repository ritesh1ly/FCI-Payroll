package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.cpf.business.VpfRules.Status;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * The VPF subscription change (plan 14 phase 14c; BR-ESEVA-006; D14-4): the amount is a
 * non-negative NUMBER(12,2) different from the one in force; it applies from this month, or the
 * next once this month is locked; the ledger year runs April to March; the Maker verifies only a
 * request not already with the Checker.
 */
class VpfRulesTest {

    @Test
    void BR_ESEVA_006_theAmountIsANewNonNegativeTwoDecimalAmount() {
        assertThat(VpfRules.defect(null, null)).contains("AMOUNT");
        assertThat(VpfRules.defect(new BigDecimal("-1"), null)).contains("AMOUNT");
        assertThat(VpfRules.defect(new BigDecimal("1.005"), null)).contains("AMOUNT");
        assertThat(VpfRules.defect(new BigDecimal("10000000000"), null)).contains("AMOUNT");
        assertThat(VpfRules.defect(new BigDecimal("9999999999.99"), null)).isEmpty();
        assertThat(VpfRules.defect(new BigDecimal("1.50"), null)).isEmpty();
        assertThat(VpfRules.defect(BigDecimal.ZERO, null)).contains("UNCHANGED");
        assertThat(VpfRules.defect(new BigDecimal("2500.00"), new BigDecimal("2500")))
                .contains("UNCHANGED");
        assertThat(VpfRules.defect(BigDecimal.ZERO, new BigDecimal("2500"))).isEmpty();
    }

    @Test
    void D14_4_theChangeAppliesFromTheNextOpenMonth() {
        YearMonth march = YearMonth.of(2027, 3);
        assertThat(VpfRules.effectiveMonth(march, false)).isEqualTo(march);
        assertThat(VpfRules.effectiveMonth(march, true)).isEqualTo(YearMonth.of(2027, 4));
        assertThat(VpfRules.finYear(march)).isEqualTo("2026-27");
        assertThat(VpfRules.finYear(YearMonth.of(2027, 4))).isEqualTo("2027-28");
        assertThat(VpfRules.finYear(YearMonth.of(2099, 12))).isEqualTo("2099-00");
        assertThat(VpfRules.yymm(YearMonth.of(2026, 7))).isEqualTo("202607");
    }

    @Test
    void BR_ESEVA_006_theMakerVerifiesARequestNotWithTheChecker() {
        assertThat(VpfRules.verifiable(Status.SUBMITTED, false)).isTrue();
        assertThat(VpfRules.verifiable(Status.SUBMITTED, true)).isFalse();
        for (Status s : Status.values()) {
            if (s != Status.SUBMITTED) {
                assertThat(VpfRules.verifiable(s, false)).as(s.name()).isFalse();
            }
        }
    }
}
