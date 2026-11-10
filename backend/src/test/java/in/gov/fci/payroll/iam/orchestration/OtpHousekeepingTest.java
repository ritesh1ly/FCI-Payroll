package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-48 / R-OTP: the batch housekeeping expires an issued OTP whose validity has run out (also
 * one expiring at this very instant) and leaves an issued OTP still valid.
 */
class OtpHousekeepingTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

    @Test
    void SRS_G_48_onlyOtpsPastTheirValidityAreExpired() {
        IdentityRepositories.Otps otps = mock(IdentityRepositories.Otps.class);
        OtpChallenge lapsed = mock(OtpChallenge.class);
        when(lapsed.expiresAt()).thenReturn(NOW.minusSeconds(60));
        OtpChallenge exactly = mock(OtpChallenge.class);
        when(exactly.expiresAt()).thenReturn(NOW);
        OtpChallenge valid = mock(OtpChallenge.class);
        when(valid.expiresAt()).thenReturn(NOW.plusSeconds(60));
        when(otps.findByOutcomeAndIssuedAtBefore(OtpChallenge.Outcome.ISSUED, NOW))
                .thenReturn(List.of(lapsed, exactly, valid));
        OtpHousekeeping housekeeping = new OtpHousekeeping(otps);

        assertThat(housekeeping.name()).isEqualTo("expiredOtps");
        assertThat(housekeeping.run(NOW)).isEqualTo(2);
        verify(lapsed).expire(NOW);
        verify(exactly).expire(NOW);
        verify(valid, never()).expire(NOW);
        verify(otps).save(lapsed);
        verify(otps, never()).save(valid);
    }
}
