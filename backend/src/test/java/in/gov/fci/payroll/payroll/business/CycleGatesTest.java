package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.business.CycleGates.Gate;
import in.gov.fci.payroll.payroll.business.CycleGates.Statutory;
import in.gov.fci.payroll.payroll.business.CycleGates.Total;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-15-11, SRS-15-14 … 18 (BR-CYC-005): each pre-lock gate from its source, never met by an empty
 * source, and the lock held by every unmet hard gate and every unmet, unaccepted soft gate.
 */
class CycleGatesTest {

    private static Total total(String source, long members, String gross, String ded, String net) {
        return new Total(
                source, members, new BigDecimal(gross), new BigDecimal(ded), new BigDecimal(net));
    }

    @Test
    void SRS_15_15_g0IsMetOnlyByACompleteRunWithEveryUnitCommittedAndResultsReconciled() {
        assertThat(CycleGates.g0(null, null, 0, 0, null, null).met()).isFalse();
        assertThat(CycleGates.g0(7L, "COMPLETE", 3, 3, 120L, 120L).met()).isTrue();
        assertThat(CycleGates.g0(7L, "COMPLETE", 3, 2, 120L, 120L).met()).isFalse();
        assertThat(CycleGates.g0(7L, "COMPLETE", 3, 3, 119L, 120L).met()).isFalse();
        assertThat(CycleGates.g0(7L, "PARTIAL", 3, 3, 120L, 120L).met()).isFalse();
        assertThat(CycleGates.g0(7L, "COMPLETE", 0, 0, 0L, 0L).met()).isFalse();
        assertThat(CycleGates.g0(7L, "COMPLETE", 1, 1, null, 1L).met()).isFalse();
        assertThat(CycleGates.g0(7L, "COMPLETE", 3, 3, 120L, 120L).detail())
                .contains("Run 7", "3 of 3", "120 results for 120 eligible");
    }

    @Test
    void SRS_15_18_g1NeedsEveryEngineComplete() {
        assertThat(CycleGates.g1(Map.of()).met()).isFalse();
        assertThat(CycleGates.g1(Map.of("STAFF", "COMPLETE")).met()).isTrue();
        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put("STAFF", "COMPLETE");
        mixed.put("DPS_LABOUR", "FAILED");
        Gate g = CycleGates.g1(mixed);
        assertThat(g.met()).isFalse();
        assertThat(g.detail()).isEqualTo("Not complete: DPS_LABOUR FAILED");
        assertThat(g.overridable()).isFalse();
    }

    @Test
    void SRS_15_16_g2IsAcceptableOnlyWithoutBlockingExceptionsOrFailedBlockChecks() {
        assertThat(CycleGates.g2(0, 0, 0).met()).isTrue();
        // OPEN-QUESTION CLR-124 (BMAD TR-006): SRS l.1521 lets non-blocking exceptions remain with
        // G2 met, while its override and SRS-15-16 accept an unmet G2 with only non-blocking ones.
        // As built (unmet, accepted by the Checker) until FCI answers.
        assertThat(CycleGates.g2(0, 2, 0).met()).isFalse();
        assertThat(CycleGates.g2(1, 0, 0).met()).isFalse();
        assertThat(CycleGates.g2(0, 0, 1).met()).isFalse();
        assertThat(CycleGates.g2Acceptable(0, 0)).isTrue();
        assertThat(CycleGates.g2Acceptable(1, 0)).isFalse();
        assertThat(CycleGates.g2Acceptable(0, 1)).isFalse();
        assertThat(CycleGates.g2(0, 0, 0).overridable()).isTrue();
    }

    /**
     * SRS l.1524 / SRS-15-16 (BMAD TR-010): G5 is accepted only for named members legitimately
     * excluded - never with nobody named, never over a statutory process not complete (no member's
     * exclusion), never when more members stay uncomputed than are named; boundary: exactly as many
     * named as uncomputed.
     */
    @Test
    void SRS_15_16_g5IsAcceptedOnlyForNamedMembersLegitimatelyExcluded() {
        List<CycleGates.Statutory> complete =
                List.of(
                        new CycleGates.Statutory("CPF_PF", "COMPLETE", 2L),
                        new CycleGates.Statutory("INCOME_TAX", "COMPLETE", 1L),
                        new CycleGates.Statutory("PROFESSIONAL_TAX", "COMPLETE", null),
                        new CycleGates.Statutory("NPS_DEDUCTION", "COMPLETE", 0L));
        assertThat(CycleGates.g5Exclusion(complete, 0)).contains("NO_MEMBER");
        assertThat(CycleGates.g5Exclusion(complete, -1)).contains("NO_MEMBER");
        assertThat(CycleGates.g5Exclusion(complete, 1)).contains("UNNAMED_MEMBERS:2");
        assertThat(CycleGates.g5Exclusion(complete, 2)).isEmpty();
        assertThat(CycleGates.g5Exclusion(complete, 3)).isEmpty();
        assertThat(CycleGates.g5Exclusion(complete.subList(1, 4), 5))
                .contains("PROCESS_NOT_COMPLETE:CPF_PF");
        List<CycleGates.Statutory> running =
                List.of(
                        new CycleGates.Statutory("CPF_PF", "COMPLETE", 0L),
                        new CycleGates.Statutory("INCOME_TAX", "RUNNING", 0L),
                        new CycleGates.Statutory("PROFESSIONAL_TAX", "COMPLETE", 0L),
                        new CycleGates.Statutory("NPS_DEDUCTION", "COMPLETE", 0L));
        assertThat(CycleGates.g5Exclusion(running, 1)).contains("PROCESS_NOT_COMPLETE:INCOME_TAX");
        assertThat(CycleGates.g5Exclusion(List.of(), 1)).contains("PROCESS_NOT_COMPLETE:CPF_PF");
    }

    @Test
    void SRS_15_18_g3IsMetWhenNothingIsPending() {
        assertThat(CycleGates.g3(0).met()).isTrue();
        assertThat(CycleGates.g3(2).met()).isFalse();
    }

    @Test
    void SRS_15_18_g4NeedsTheThreeTotalsPresentAndAgreeingForEveryPayrollType() {
        assertThat(CycleGates.g4(Map.of()).met()).isFalse();
        List<Total> agree =
                List.of(
                        total("PAYROLL_RESULT", 2, "100.00", "20", "80"),
                        total("SALARY_CONTROL_REGISTER", 2, "100", "20.00", "80"),
                        total("DISBURSEMENT_REGISTER", 2, "100", "20", "80.0"));
        assertThat(CycleGates.g4(Map.of("STAFF", agree)).met()).isTrue();
        Gate onlyResult =
                CycleGates.g4(
                        Map.of("STAFF", List.of(total("PAYROLL_RESULT", 2, "100", "20", "80"))));
        assertThat(onlyResult.met()).isFalse();
        assertThat(onlyResult.detail())
                .isEqualTo("STAFF: no SALARY_CONTROL_REGISTER, DISBURSEMENT_REGISTER");
        for (Total differs :
                List.of(
                        // OPEN-QUESTION CLR-126 (BMAD TR-012): SRS l.1523 names the three money
                        // totals only; the member count is compared as built until FCI answers.
                        total("DISBURSEMENT_REGISTER", 3, "100", "20", "80"),
                        total("DISBURSEMENT_REGISTER", 2, "101", "20", "80"),
                        total("DISBURSEMENT_REGISTER", 2, "100", "21", "80"),
                        total("DISBURSEMENT_REGISTER", 2, "100", "20", "79"))) {
            Gate g =
                    CycleGates.g4(
                            Map.of(
                                    "STAFF",
                                    List.of(
                                            total("PAYROLL_RESULT", 2, "100", "20", "80"),
                                            total("SALARY_CONTROL_REGISTER", 2, "100", "20", "80"),
                                            differs)));
            assertThat(g.met()).isFalse();
            assertThat(g.detail()).isEqualTo("STAFF: totals differ");
        }
    }

    @Test
    void SRS_15_18_g5NeedsCpfTaxPtAndNpsCompleteForEveryone() {
        List<Statutory> all =
                List.of(
                        new Statutory("CPF_PF", "COMPLETE", 0L),
                        new Statutory("INCOME_TAX", "COMPLETE", null),
                        new Statutory("PROFESSIONAL_TAX", "COMPLETE", 0L),
                        new Statutory("NPS_DEDUCTION", "COMPLETE", 0L),
                        new Statutory("LWF", "FAILED", 3L));
        assertThat(CycleGates.g5(all).met()).isTrue();
        assertThat(CycleGates.g5(List.of()).detail())
                .isEqualTo("Not complete: CPF_PF, INCOME_TAX, PROFESSIONAL_TAX, NPS_DEDUCTION");
        assertThat(
                        CycleGates.g5(
                                        List.of(
                                                new Statutory("CPF_PF", "COMPLETE", 2L),
                                                new Statutory("INCOME_TAX", "RUNNING", 0L),
                                                new Statutory("PROFESSIONAL_TAX", "COMPLETE", 0L),
                                                new Statutory("NPS_DEDUCTION", "COMPLETE", 0L)))
                                .detail())
                .isEqualTo("Not complete: CPF_PF, INCOME_TAX");
    }

    @Test
    void SRS_15_13_g6NeedsEveryQuarantinedMemberReviewedAndAConfirmedFloor() {
        assertThat(CycleGates.g6(0, true).met()).isTrue();
        assertThat(CycleGates.g6(1, true).met()).isFalse();
        Gate noFloor = CycleGates.g6(0, false);
        assertThat(noFloor.met()).isFalse();
        assertThat(noFloor.detail()).contains("no confirmed net-pay floor");
    }

    @Test
    void SRS_15_14_theLockIsHeldByUnmetHardGatesAndUnacceptedSoftGates() {
        List<Gate> gates =
                List.of(
                        new Gate("G0", true, ""),
                        new Gate("G1", false, ""),
                        new Gate("G2", false, ""),
                        new Gate("G3", true, ""),
                        new Gate("G4", false, ""),
                        new Gate("G5", false, ""),
                        new Gate("G6", true, ""));
        assertThat(CycleGates.blocking(gates, Set.of())).containsExactly("G1", "G2", "G4", "G5");
        // An acceptance of a hard gate never counts.
        assertThat(CycleGates.blocking(gates, Set.of("G1", "G2", "G4", "G5")))
                .containsExactly("G1", "G4");
        assertThat(CycleGates.HARD).containsExactlyInAnyOrder("G0", "G1", "G3", "G4");
    }
}
