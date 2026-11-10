package in.gov.fci.payroll.cpf.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A withdrawal purpose's validity (SRS-6-5), when a claim is decided (SRS-6-4, BR-CPF-039) and what
 * each sanction tier records (SRS-6-4).
 */
class CpfClaimStatesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Instant AT = Instant.parse("2026-10-23T05:00:00Z");

    @Test
    void SRS_6_5_aPurposeIsInForceFromItsStartUpToTheDayBeforeItsSuccessor() {
        CpfWithdrawalPurpose p =
                CpfWithdrawalPurpose.approved(
                        "HOUSE", "House", "NR", "CPF.HOUSE", 1, false, true, DAY, true);
        assertThat(p.inForceOn(DAY.minusDays(1))).isFalse();
        assertThat(p.inForceOn(DAY)).isTrue();
        assertThat(p.inForceOn(DAY.plusYears(5))).isTrue();
        p.endBefore(DAY.plusDays(10));
        assertThat(p.inForceOn(DAY.plusDays(9))).isTrue();
        assertThat(p.inForceOn(DAY.plusDays(10))).isFalse();
    }

    @Test
    void SRS_6_4_onlyAFinalOutcomeRecordsWhenTheClaimWasDecided() {
        for (CpfClaim.Status s : CpfClaim.Status.values()) {
            CpfClaim c = CpfClaim.eseva(1L, "NR", 2L, null, new BigDecimal("1000"), null, null, AT);
            c.status(s, AT.plusSeconds(60));
            assertThat(c.status()).isEqualTo(s);
            boolean last =
                    s == CpfClaim.Status.APPROVED
                            || s == CpfClaim.Status.REJECTED
                            || s == CpfClaim.Status.WITHDRAWN;
            assertThat(c.getDecidedAt()).as(s.name()).isEqualTo(last ? AT.plusSeconds(60) : null);
        }
    }

    @Test
    void SRS_6_4_eachTierRecordsWhatItChecked() {
        CpfSanctionTier unit = CpfSanctionTier.acted(1L, 1, 1, "VERIFY", "admin", 3L, AT, null);
        assertThat(unit.verified(true, false)).isSameAs(unit);
        assertThat(ReflectionTestUtils.getField(unit, "uanKycVerified")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(unit, "documentsVerified")).isEqualTo(false);
        CpfSanctionTier zone = CpfSanctionTier.acted(1L, 1, 2, "VALIDATE", "zone", 3L, AT, null);
        assertThat(zone.validated(new BigDecimal("50000"), new BigDecimal("40000"), 9L))
                .isSameAs(zone);
        assertThat(zone.getCeilingAmount()).isEqualByComparingTo("50000");
        assertThat(zone.getCappedAmount()).isEqualByComparingTo("40000");
        CpfSanctionTier authority = CpfSanctionTier.acted(1L, 1, 3, "APPROVE", "ca", 3L, AT, null);
        assertThat(authority.decided(9L, 12L)).isSameAs(authority);
        assertThat(authority.getOtpChallengeId()).isEqualTo(12L);
        assertThat(authority.getTierCode()).isEqualTo("COMPETENT_AUTHORITY");
    }
}
