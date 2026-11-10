package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.cpf.business.InterestRunRules;
import org.junit.jupiter.api.Test;

/**
 * C-12 counts and report in the database (SRS-6-45; deep profile 2026-10-27): the CPF-code groups
 * that are not calculated follow InterestRunRules, and a member's name is built as the master
 * builds it (blank parts left out, no member: no name).
 */
class InterestLedgersTest {

    @Test
    void SRS_6_45_theGroupsNotCalculatedAreThoseTheRulesGiveAReasonFor() {
        java.util.List<Integer> groups = InterestLedgers.notCalculatedGroups();
        assertThat(groups.contains(0))
                .isEqualTo(InterestRunRules.notCalculatedReason(false) != null);
        assertThat(groups.contains(1))
                .isEqualTo(InterestRunRules.notCalculatedReason(true) != null);
    }

    @Test
    void SRS_6_45_aMembersNameJoinsItsPartsAndAMissingMemberHasNone() {
        assertThat(InterestLedgers.name("Asha", null, "Rao")).isEqualTo("Asha Rao");
        assertThat(InterestLedgers.name("Asha", " ", "Rao")).isEqualTo("Asha Rao");
        assertThat(InterestLedgers.name("Asha", "K", "Rao")).isEqualTo("Asha K Rao");
        assertThat(InterestLedgers.name(null, null, null)).isNull();
    }
}
