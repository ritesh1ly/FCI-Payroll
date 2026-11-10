package in.gov.fci.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.iam.business.PasswordPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DomainPolicyTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "Short1!, TOO_SHORT",
        "alllowercaseletters, LOW_COMPLEXITY",
        "Paaaaassword#2026, REPEATED_CHARACTERS",
        "Ritesh.K#Strong2026, CONTAINS_IDENTITY",
        "Password@1234, COMMON_PASSWORD"
    })
    void passwordPolicyRejects(String candidate, PasswordPolicy.Violation expected) {
        PasswordPolicy policy = new PasswordPolicy(Set.of("password@1234"));
        assertThat(policy.check(candidate, "ritesh.k", "FCI001", "rk@fci.gov.in"))
                .contains(expected);
    }

    @Test
    void passwordPolicyAcceptsStrongPasswordsInAnyScript() {
        PasswordPolicy policy = new PasswordPolicy(Set.of());
        assertThat(policy.check("Godown!Ledger#2027", "ritesh.k", "FCI001", "rk@fci.gov.in"))
                .isEmpty();
        assertThat(policy.check("भंडार#Ledger2027", "ritesh.k", "FCI001", "rk@fci.gov.in"))
                .isEmpty();
        assertThat(policy.check("x".repeat(130) + "A1!", null, null, null))
                .contains(PasswordPolicy.Violation.TOO_LONG);
    }
}
