package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.iam.business.PasswordPolicy.Violation;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-SEC-13, SRS-G-49 mutation gaps: the configured number of character classes is honoured at
 * both ends of its 1 to 4 range, and a value outside it never weakens the policy.
 */
class PasswordPolicyMutationGapsTest {

    @Test
    void SRS_G_49_aSettingFromOneToFourIsTakenAsIsAnythingElseMeansAllFour() {
        assertThat(PasswordPolicy.requiredClasses(1)).isEqualTo(1);
        assertThat(PasswordPolicy.requiredClasses(2)).isEqualTo(2);
        assertThat(PasswordPolicy.requiredClasses(3)).isEqualTo(3);
        assertThat(PasswordPolicy.requiredClasses(4)).isEqualTo(4);
        assertThat(PasswordPolicy.requiredClasses(0)).isEqualTo(4);
        assertThat(PasswordPolicy.requiredClasses(5)).isEqualTo(4);
        assertThat(PasswordPolicy.requiredClasses(-1)).isEqualTo(4);
    }

    @Test
    void SRS_G_49_aSettingOfOneClassAcceptsALowerCaseOnlyPassword() {
        PasswordPolicy policy = new PasswordPolicy(Set.of());
        String lettersOnly = "quietriverstone";
        assertThat(policy.check(lettersOnly, null, null, null, 12, 1))
                .doesNotContain(Violation.LOW_COMPLEXITY);
        assertThat(policy.check(lettersOnly, null, null, null, 12, 2))
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(policy.check(lettersOnly, null, null, null, 12, 0))
                .contains(Violation.LOW_COMPLEXITY);
    }

    @Test
    void SRS_SEC_13_theEmailLocalPartOfThreeOrMoreCharactersMayNotBeUsed() {
        PasswordPolicy policy = new PasswordPolicy(Set.of());
        assertThat(policy.check("Xx-ritesh.kumar-9!Q", null, null, "ritesh.kumar@fci.gov.in"))
                .contains(Violation.CONTAINS_IDENTITY);
        // No @ at all: the whole value is the fragment.
        assertThat(policy.check("Xx-ritesh.kumar-9!Q", null, null, "ritesh.kumar"))
                .contains(Violation.CONTAINS_IDENTITY);
        // A leading @ leaves no local part: the whole text is the fragment.
        assertThat(policy.check("Xx-ritesh.kumar-9!Q", null, null, "@ritesh.kumar"))
                .doesNotContain(Violation.CONTAINS_IDENTITY);
        assertThat(policy.check("Xx-quiet-river-9!Q", null, null, null))
                .doesNotContain(Violation.CONTAINS_IDENTITY);
    }
}
