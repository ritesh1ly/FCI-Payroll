package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** ADR-0008 / SRS-SEC-13: a password encoder that returns no hash is refused, never stored. */
class PasswordHasherBranchGapsTest {

    @Test
    void SRS_SEC_13_anEncoderWithoutAHashIsRefused() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn(null);
        assertThatThrownBy(() -> new PasswordHasher(encoder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no hash");
    }
}
