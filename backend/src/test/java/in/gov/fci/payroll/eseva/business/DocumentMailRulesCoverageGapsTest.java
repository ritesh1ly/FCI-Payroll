package in.gov.fci.payroll.eseva.business;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SRS l.3763 (the registered e-mail address) and MAIL-1 (the PDF password rule) at their edges: a
 * domain label of 63 characters is accepted and one of 64 or none is not; letters of either case
 * and digits are the only plain characters, every neighbour of their ranges is refused; a rule with
 * a control or non-ASCII character, a closing brace before its opening one, a nested brace, an
 * empty argument or a date pattern over 20 characters is not usable.
 */
class DocumentMailRulesCoverageGapsTest {

    @Test
    void SRS_17_12_aDomainLabelIsOneTo63Characters() {
        String label63 = "a".repeat(63);
        assertThat(DocumentMailRules.validAddress("asha@" + label63 + ".in")).isTrue();
        assertThat(DocumentMailRules.validAddress("asha@" + label63 + "b.in")).isFalse();
        assertThat(DocumentMailRules.validAddress("asha@.fci.in")).isFalse();
        assertThat(DocumentMailRules.validAddress("asha@fci.in.")).isFalse();
    }

    @Test
    void SRS_17_12_onlyLettersOfEitherCaseAndDigitsArePlainCharacters() {
        assertThat(DocumentMailRules.validAddress("AZaz09@FCI09.Gov.in")).isTrue();
        // The characters just outside 0-9, A-Z and a-z.
        for (String c : new String[] {"/", ":", "@x", "[", "`", "{"}) {
            assertThat(DocumentMailRules.validAddress("as" + c + "ha@fci.in"))
                    .as("local part with %s", c)
                    .isFalse();
            assertThat(DocumentMailRules.validAddress("asha@f" + c + "ci.in"))
                    .as("domain with %s", c)
                    .isFalse();
        }
    }

    @Test
    void MAIL_1_aRuleWithAControlOrNonAsciiCharacterIsNotUsable() {
        assertThat(DocumentMailRules.validRule("FCI{pan}")).isTrue();
        assertThat(DocumentMailRules.validRule("FCI\t{pan}")).isFalse();
        assertThat(DocumentMailRules.validRule("FCI\u007F{pan}")).isFalse();
        assertThat(DocumentMailRules.validRule("FCI~{pan}")).isTrue();
        assertThat(DocumentMailRules.validRule("FCI {pan}")).isTrue();
    }

    @Test
    void MAIL_1_misplacedBracesAndEmptyArgumentsAreNotUsable() {
        assertThat(DocumentMailRules.validRule("}{pan}")).isFalse();
        assertThat(DocumentMailRules.validRule("x}y{pan}")).isFalse();
        assertThat(DocumentMailRules.validRule("{p{an}")).isFalse();
        assertThat(DocumentMailRules.validRule("{pan:}")).isFalse();
        assertThat(DocumentMailRules.validRule("{pan:4}")).isTrue();
    }

    @Test
    void MAIL_1_aDatePatternIsAtMost20Characters() {
        String twenty = "dd-MM-yyyy-dd-MM-yyy";
        assertThat(twenty).hasSize(20);
        assertThat(DocumentMailRules.validRule("{dob:" + twenty + "}")).isTrue();
        assertThat(DocumentMailRules.validRule("{dob:" + twenty + "y}")).isFalse();
    }
}
