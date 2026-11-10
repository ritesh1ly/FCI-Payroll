package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.iam.business.PasswordPolicy.Violation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Password policy boundaries (ADR-0008; NIST SP 800-63B style): length 12-128 code points, at least
 * all four character classes (SRS-SEC-13), no run longer than three, no identity fragment of three
 * or more characters, no common password.
 */
class PasswordPolicyBoundaryTest {

    private final PasswordPolicy policy = new PasswordPolicy(Set.of("godownledger#1"));

    private List<Violation> check(String password) {
        return policy.check(password, "ritesh.k", "FCI001", "rk@fci.gov.in");
    }

    @Test
    void lengthIsTwelveTo128CodePoints() {
        assertThat(check("Abcdefghij1!")).as("12").doesNotContain(Violation.TOO_SHORT);
        assertThat(check("Abcdefghi1!")).as("11").contains(Violation.TOO_SHORT);
        String base = "Ab1!";
        String l128 = (base.repeat(32));
        assertThat(l128).hasSize(128);
        assertThat(check(l128)).doesNotContain(Violation.TOO_LONG);
        assertThat(check(l128 + "x")).contains(Violation.TOO_LONG);
        // A supplementary character counts once.
        String emoji = "😀";
        assertThat(check("Abcdefghi1!" + emoji)).doesNotContain(Violation.TOO_SHORT);
    }

    @Test
    void aStricterConfiguredMinimumApplies() {
        assertThat(policy.check("Abcdefghij1!", null, null, null, 14))
                .contains(Violation.TOO_SHORT);
        assertThat(policy.check("Abcdefghijkl1!", null, null, null, 14))
                .doesNotContain(Violation.TOO_SHORT);
        // A lower configured minimum never weakens the floor of 12.
        assertThat(policy.check("Abcdefgh1!", null, null, null, 4)).contains(Violation.TOO_SHORT);
    }

    @Test
    void SRS_SEC_13_allFourCharacterClassesAreNeeded() {
        assertThat(check("abcdefghijkl")).contains(Violation.LOW_COMPLEXITY);
        assertThat(check("ABCDEFGHIJKL")).contains(Violation.LOW_COMPLEXITY);
        assertThat(check("abcdefGHIJKL")).as("two classes").contains(Violation.LOW_COMPLEXITY);
        assertThat(check("123456789012")).contains(Violation.LOW_COMPLEXITY);
        assertThat(check("!@#$%^&*()_+")).contains(Violation.LOW_COMPLEXITY);
        assertThat(check("abcdefGHIJK1"))
                .as("upper, lower, digit")
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(check("abcdefGHIJK!"))
                .as("upper, lower, symbol")
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(check("abcdefghij1!"))
                .as("lower, digit, symbol")
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(check("ABCDEFGHIJ1!"))
                .as("upper, digit, symbol")
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(check("abcdeFGHIJ1!")).as("all four").doesNotContain(Violation.LOW_COMPLEXITY);
        // Spaces are not symbols.
        assertThat(check("abcdef ghij 1")).contains(Violation.LOW_COMPLEXITY);
        // Upper, lower and digit with a space: still three classes, the space is no fourth.
        assertThat(check("abcdeFGHIJ 1"))
                .as("upper, lower, digit, space")
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(check("abcdeFGHIJ 1!"))
                .as("all four and a space")
                .doesNotContain(Violation.LOW_COMPLEXITY);
    }

    @Test
    void noCharacterMayRepeatMoreThanThreeTimesInARow() {
        assertThat(check("Abcaaa1!xyzw")).doesNotContain(Violation.REPEATED_CHARACTERS);
        assertThat(check("Abcaaaa1!xyz")).contains(Violation.REPEATED_CHARACTERS);
        assertThat(check("aaaaBcd1!xyz"))
                .as("run at the start")
                .contains(Violation.REPEATED_CHARACTERS);
        assertThat(check("Bcd1!xyz1111"))
                .as("run at the end")
                .contains(Violation.REPEATED_CHARACTERS);
    }

    @Test
    void noIdentityFragmentOfThreeOrMoreCharacters() {
        assertThat(check("Xx#Ritesh.k2027")).contains(Violation.CONTAINS_IDENTITY);
        assertThat(check("Xx#fci0012027Q"))
                .as("employee id, any case")
                .contains(Violation.CONTAINS_IDENTITY);
        assertThat(check("Godown#RK2027q"))
                .as("2-character local part ignored")
                .doesNotContain(Violation.CONTAINS_IDENTITY);
        assertThat(policy.check("Godown#abc2027", "abc", null, null))
                .as("exactly 3")
                .contains(Violation.CONTAINS_IDENTITY);
        assertThat(policy.check("Godown#ab2027Q", "ab", null, null))
                .doesNotContain(Violation.CONTAINS_IDENTITY);
    }

    @Test
    void theEmailLocalPartCountsAsIdentity() {
        assertThat(policy.check("Godown#kavita99", null, null, "kavita@fci.gov.in"))
                .contains(Violation.CONTAINS_IDENTITY);
        assertThat(policy.check("Godown#kav99Qx", null, null, "kavita@fci.gov.in"))
                .doesNotContain(Violation.CONTAINS_IDENTITY);
        // No @: the whole value; @ first: the whole value too.
        assertThat(policy.check("Godown#kavita99", null, null, "kavita"))
                .contains(Violation.CONTAINS_IDENTITY);
        assertThat(policy.check("Godown#@kav99Qx", null, null, "@kav"))
                .contains(Violation.CONTAINS_IDENTITY);
        assertThat(policy.check("Godown#Ledger27", null, null, null))
                .doesNotContain(Violation.CONTAINS_IDENTITY);
    }

    @Test
    void aCommonPasswordIsRefusedWhateverItsCase() {
        assertThat(check("GodownLedger#1")).contains(Violation.COMMON_PASSWORD);
        assertThat(check("GodownLedger#2")).doesNotContain(Violation.COMMON_PASSWORD);
    }

    @Test
    void passwordsAreCompatibilityNormalised() {
        assertThat(PasswordPolicy.normalise("ＡＢＣ１")).isEqualTo("ABC1");
        assertThat(check("Ｇodown#Ledger27")).isEmpty();
    }

    // NEW-G1-08 (US-01-16 AC4, US-02-16 AC6): the character mix is the A-07 setting
    // PASSWORD_CHARACTER_CLASSES; at the boundary a password with exactly the required kinds
    // passes and one kind fewer fails; a value outside 1-4 (never accepted by A-07) means all four.
    @Test
    void SRS_G_49_theCharacterMixIsASetting() {
        assertThat(policy.check("abcdefGHIJK1", null, null, null, 12, 3))
                .as("three kinds, three required")
                .doesNotContain(Violation.LOW_COMPLEXITY);
        assertThat(policy.check("abcdefGHIJKL", null, null, null, 12, 3))
                .as("two kinds, three required")
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(policy.check("abcdefghijkl", null, null, null, 12, 1))
                .as("one kind, one required")
                .doesNotContain(Violation.LOW_COMPLEXITY);
        assertThat(policy.check("abcdefGHIJK1", null, null, null, 12, 4))
                .contains(Violation.LOW_COMPLEXITY);
        assertThat(policy.check("abcdefGHIJ1!", null, null, null, 12, 4))
                .doesNotContain(Violation.LOW_COMPLEXITY);
        for (int broken : new int[] {0, -1, 5}) {
            assertThat(policy.check("abcdefGHIJK1", null, null, null, 12, broken))
                    .as("value %d reads as all four", broken)
                    .contains(Violation.LOW_COMPLEXITY);
        }
        assertThat(PasswordPolicy.requiredClasses(1)).isEqualTo(1);
        assertThat(PasswordPolicy.requiredClasses(4)).isEqualTo(4);
        assertThat(PasswordPolicy.requiredClasses(0)).isEqualTo(4);
        assertThat(PasswordPolicy.requiredClasses(5)).isEqualTo(4);
    }
}
