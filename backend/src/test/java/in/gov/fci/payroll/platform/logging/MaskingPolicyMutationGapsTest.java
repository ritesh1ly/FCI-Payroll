package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.logging.MaskingProperties.PatternRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.Strategy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R-PII: query-string edge cases that a mutation run showed unasserted. */
class MaskingPolicyMutationGapsTest {

    private static MaskingPolicy wholeValueRule() {
        return new MaskingPolicy(
                new MaskingProperties(
                        List.of(), List.of(new PatternRule("^[0-9]{10}$", Strategy.FULL, null))));
    }

    @Test
    void R_PII_aQueryPairWithAnEmptyNameStillHasItsValueMasked() {
        // "=9876543210": the '=' is at index 0, so the pair has a name (empty) and a value.
        assertThat(wholeValueRule().query("=9876543210")).isEqualTo("=***");
    }

    @Test
    void R_PII_aQueryPairWithoutEqualsIsMaskedAsFreeText() {
        assertThat(wholeValueRule().query("9876543210")).isEqualTo("***");
        assertThat(wholeValueRule().query("a=1&9876543210&b=2")).isEqualTo("a=1&***&b=2");
    }
}
