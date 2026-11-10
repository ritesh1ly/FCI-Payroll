package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.cpf.business.CorrectionRules.Change;
import in.gov.fci.payroll.cpf.business.LedgerBalances.Part;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SRS-6-43, SRS-6-44, BR-CPF-025: what an I-22 correction changes and when it is blocked. */
class CorrectionRulesTest {

    private static final LedgerBalances CURRENT =
            new LedgerBalances(
                    new BigDecimal("1000.00"), new BigDecimal("500.00"), BigDecimal.ZERO);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (FciException e) {
            return e.getErrorCode().code();
        }
        return null;
    }

    @Test
    void SRS_6_43_onlyThePartsThatChangeBecomeRowsBesideTheirOldAmount() {
        List<Change> changes = CorrectionRules.changes(CURRENT, d("1100"), d("500.00"), null);
        assertThat(changes).containsExactly(new Change(Part.EMPLOYEE, d("1000.00"), d("1100")));
        assertThat(changes.get(0).difference()).isEqualByComparingTo("100");
        List<Change> all = CorrectionRules.changes(CURRENT, d("900"), d("550"), d("25"));
        assertThat(all)
                .extracting(Change::part)
                .containsExactly(Part.EMPLOYEE, Part.EMPLOYER, Part.VPF);
        assertThat(CorrectionRules.difference(all))
                .isEqualTo(new LedgerBalances(d("-100.00"), d("50.00"), d("25")));
        // A part not corrected moves by nothing.
        assertThat(CorrectionRules.difference(changes))
                .isEqualTo(new LedgerBalances(d("100.00"), BigDecimal.ZERO, BigDecimal.ZERO));
        // A part may be corrected to zero.
        assertThat(CorrectionRules.changes(CURRENT, null, d("0"), null).get(0).newAmount())
                .isEqualByComparingTo("0");
    }

    @Test
    void SRS_6_44_aCorrectionThatChangesNothingOrMakesAPartNegativeIsRefused() {
        assertThat(code(() -> CorrectionRules.changes(CURRENT, null, null, null)))
                .isEqualTo("CPF-422-NO-CHANGE");
        assertThat(code(() -> CorrectionRules.changes(CURRENT, d("1000"), d("500.0"), d("0"))))
                .isEqualTo("CPF-422-NO-CHANGE");
        assertThatThrownBy(() -> CorrectionRules.changes(CURRENT, null, null, d("-0.01")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode().code()).isEqualTo("CPF-422-NEGATIVE");
                            assertThat(e.getMessageArgs()).containsExactly("VPF");
                        });
        assertThat(code(() -> CorrectionRules.changes(CURRENT, d("-1"), null, null)))
                .isEqualTo("CPF-422-NEGATIVE");
    }

    @Test
    void SRS_6_44_onlyALoweringOfAMemberWithASanctionSinceIsBlocked() {
        List<Change> lower = CorrectionRules.changes(CURRENT, d("999.99"), null, null);
        List<Change> raise = CorrectionRules.changes(CURRENT, d("1000.01"), null, null);
        List<Change> mixed = CorrectionRules.changes(CURRENT, d("1100"), d("499"), null);
        assertThat(CorrectionRules.blockedBySanction(true, lower)).isTrue();
        assertThat(CorrectionRules.blockedBySanction(true, mixed)).isTrue();
        assertThat(CorrectionRules.blockedBySanction(true, raise)).isFalse();
        assertThat(CorrectionRules.blockedBySanction(false, lower)).isFalse();
    }

    @Test
    void SRS_6_43_theRequestNumberIsTheYearAndARunningNumber() {
        assertThat(CorrectionRules.reference(2026, 31)).isEqualTo("OB-2026-000031");
        assertThat(CorrectionRules.reference(2026, 123456)).isEqualTo("OB-2026-123456");
    }

    @Test
    void SRS_6_44_anUnchangedPartNeverBlocksForASanction() {
        Part part = Part.values()[0];
        assertThat(
                        CorrectionRules.blockedBySanction(
                                true, List.of(new Change(part, BigDecimal.TEN, BigDecimal.TEN))))
                .isFalse();
        assertThat(
                        CorrectionRules.blockedBySanction(
                                true, List.of(new Change(part, BigDecimal.TEN, BigDecimal.ONE))))
                .isTrue();
    }
}
