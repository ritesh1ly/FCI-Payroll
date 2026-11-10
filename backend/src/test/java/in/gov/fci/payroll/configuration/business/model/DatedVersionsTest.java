package in.gov.fci.payroll.configuration.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Dated master versions under maker-checker (R-RULE, R-MC): M-04 / M-22 rate sets and A-07 rule
 * versions are in force only once approved and only inside their window, both ends inclusive.
 */
class DatedVersionsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);

    // ---------------------------------------------------------------- rate_set

    @Test
    void M_04_aProposedRateSetIsPendingAndNeverInForce() {
        RateSet set = RateSet.propose("HRA", "House rent", "STAFF", 3, FROM, 8L, 77L);
        assertThat(set.isPending()).isTrue();
        assertThat(set.isApproved()).isFalse();
        assertThat(set.isInForceOn(FROM)).isFalse();
        assertThat(set.getRateSetCode()).isEqualTo("HRA");
        assertThat(set.getRateSetName()).isEqualTo("House rent");
        assertThat(set.getPayrollType()).isEqualTo("STAFF");
        assertThat(set.getVersionNo()).isEqualTo(3);
        assertThat(set.getEffectiveFrom()).isEqualTo(FROM);
        assertThat(set.getCircularOrderId()).isEqualTo(8L);
        assertThat(set.getApprovalId()).isEqualTo(77L);
        assertThat(set.getApprovalState()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void M_04_anApprovedRateSetIsInForceFromItsFirstToItsLastDay() {
        RateSet set = RateSet.propose("HRA", "House rent", "STAFF", 1, FROM, null, 1L);
        set.approve();
        assertThat(set.isApproved()).isTrue();
        assertThat(set.isPending()).isFalse();
        assertThat(set.isInForceOn(FROM.minusDays(1))).isFalse();
        assertThat(set.isInForceOn(FROM)).isTrue();
        assertThat(set.isInForceOn(FROM.plusYears(30))).as("open-ended").isTrue();
        set.endBefore(FROM.plusMonths(6));
        assertThat(set.getEffectiveTo()).isEqualTo(FROM.plusMonths(6).minusDays(1));
        assertThat(set.isInForceOn(FROM.plusMonths(6).minusDays(1))).isTrue();
        assertThat(set.isInForceOn(FROM.plusMonths(6))).isFalse();
        assertThatThrownBy(set::approve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void M_04_aRejectedRateSetStaysRejectedAndAnApprovedOneCannotBeRejected() {
        RateSet rejected = RateSet.propose("CCA", "City", "STAFF", 1, FROM, null, 1L);
        rejected.reject();
        assertThat(rejected.getApprovalState()).isEqualTo("REJECTED");
        assertThat(rejected.isInForceOn(FROM)).isFalse();
        assertThatThrownBy(rejected::approve).isInstanceOf(IllegalStateException.class);

        RateSet approved = RateSet.propose("CCA", "City", "STAFF", 2, FROM, null, 2L);
        approved.approve();
        approved.reject();
        assertThat(approved.isApproved()).as("reject ignored once approved").isTrue();
    }

    // ---------------------------------------------------------------- rule_version (A-07)

    private static RuleVersion pending() {
        return RuleVersion.propose(
                5L, 2, FROM, new BigDecimal("15"), "sysadmin", 9L, Instant.EPOCH);
    }

    @Test
    void A_07_aProposedRuleVersionHoldsItsValueAndIsNotInForce() {
        RuleVersion v = pending();
        assertThat(v.getState()).isEqualTo(RuleVersion.State.PENDING_APPROVAL);
        assertThat(v.getRuleId()).isEqualTo(5L);
        assertThat(v.getVersionNo()).isEqualTo(2);
        assertThat(v.getScalarValue()).isEqualByComparingTo("15");
        assertThat(v.getApprovalId()).isEqualTo(9L);
        assertThat(v.getCreatedBy()).isEqualTo("sysadmin");
        assertThat(v.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(v.isInForceOn(FROM)).isFalse();
    }

    @Test
    void A_07_theProposerIsCutTo50Characters() {
        String fifty = "a".repeat(50);
        assertThat(
                        RuleVersion.propose(1L, 1, FROM, null, fifty, null, Instant.EPOCH)
                                .getCreatedBy())
                .isEqualTo(fifty);
        assertThat(
                        RuleVersion.propose(1L, 1, FROM, null, fifty + "b", null, Instant.EPOCH)
                                .getCreatedBy())
                .isEqualTo(fifty);
    }

    @Test
    void A_07_aRuleAndAStartDateAreRequired() {
        assertThatThrownBy(() -> RuleVersion.propose(null, 1, FROM, null, "x", null, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RuleVersion.propose(1L, 1, null, null, "x", null, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void A_07_approvalStartsTheVersionNoEarlierThanTheApprovalDay() {
        RuleVersion early = pending();
        early.approve(FROM.minusDays(10));
        assertThat(early.getEffectiveFrom())
                .as("approved before its date: keeps it")
                .isEqualTo(FROM);
        assertThat(early.getState()).isEqualTo(RuleVersion.State.APPROVED);

        RuleVersion late = pending();
        late.approve(FROM.plusDays(3));
        assertThat(late.getEffectiveFrom())
                .as("approved after its date: starts then")
                .isEqualTo(FROM.plusDays(3));
        assertThat(late.isInForceOn(FROM.plusDays(2))).isFalse();
        assertThat(late.isInForceOn(FROM.plusDays(3))).isTrue();

        RuleVersion same = pending();
        same.approve(FROM);
        assertThat(same.getEffectiveFrom()).isEqualTo(FROM);
        assertThatThrownBy(() -> same.approve(FROM)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void A_07_aSuccessorEndsTheVersionTheDayBeforeButNeverBeforeItStarts() {
        RuleVersion v = pending();
        v.approve(FROM);
        v.endBefore(FROM.plusDays(1));
        assertThat(v.getEffectiveTo()).isEqualTo(FROM);
        assertThat(v.isInForceOn(FROM)).isTrue();
        assertThat(v.isInForceOn(FROM.plusDays(1))).isFalse();
        RuleVersion other = pending();
        assertThatThrownBy(() -> other.endBefore(FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void A_07_onlyAPendingVersionIsRejected() {
        RuleVersion v = pending();
        v.reject();
        assertThat(v.getState()).isEqualTo(RuleVersion.State.REJECTED);
        assertThatThrownBy(() -> v.approve(FROM)).isInstanceOf(IllegalStateException.class);
        RuleVersion approved = pending();
        approved.approve(FROM);
        approved.reject();
        assertThat(approved.getState()).isEqualTo(RuleVersion.State.APPROVED);
    }
}
