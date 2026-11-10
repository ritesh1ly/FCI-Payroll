package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import in.gov.fci.payroll.payroll.business.PreLockRules.RecoveryLine;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SRS-15-39 (SRS §6.7.2 pre-lock checks, OD-152): the rules of the four source checks. */
class PreLockRulesTest {

    private static final BigDecimal PAID = new BigDecimal("45000.00");

    // ---------------------------------------------------------------- APPS_POSTING_UNIT_ACTIVE

    @Test
    void SRS_15_39_appsPostingUnitActive_passesForAnActivePosting() {
        assertThat(PreLockRules.postingActiveInApps(12L, "Y")).isTrue();
    }

    @Test
    void SRS_15_39_appsPostingUnitActive_failsForAnInactivePostingUnit() {
        assertThat(PreLockRules.postingActiveInApps(12L, "N")).isFalse();
    }

    @Test
    void SRS_15_39_appsPostingUnitActive_failsWhenThePostingWasNeverSynchronisedFromApps() {
        assertThat(PreLockRules.postingActiveInApps(12L, null)).isFalse();
    }

    @Test
    void SRS_15_39_appsPostingUnitActive_failsWithoutAPlaceOfPosting() {
        assertThat(PreLockRules.postingActiveInApps(null, "Y")).isFalse();
    }

    // ---------------------------------------------------------------- BANK_ACCOUNT_IFSC_VALID

    @Test
    void SRS_15_39_bankAccountIfscValid_passesForAWellFormedRouteToALiveBranch() {
        assertThat(PreLockRules.bankRouteValid(PAID, "123456789012", true, "Y", "SBIN0001234"))
                .isTrue();
    }

    @Test
    void SRS_15_39_bankAccountIfscValid_aMemberNotToBePaidIsNotChecked() {
        assertThat(PreLockRules.bankRouteValid(BigDecimal.ZERO, null, false, null, null)).isTrue();
        assertThat(PreLockRules.bankRouteValid(new BigDecimal("-10"), null, false, null, null))
                .isTrue();
        assertThat(PreLockRules.bankRouteValid(null, null, false, null, null)).isTrue();
    }

    @Test
    void SRS_15_39_bankAccountIfscValid_aPaisaOfNetPayIsToBePaid() {
        assertThat(
                        PreLockRules.bankRouteValid(
                                new BigDecimal("0.01"), null, true, "Y", "SBIN0001234"))
                .isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "123456789012345678901", "12345A7890", "1234 567890"})
    void SRS_15_39_bankAccountIfscValid_failsForAMissingOrMalformedAccount(String account) {
        assertThat(PreLockRules.bankRouteValid(PAID, account, true, "Y", "SBIN0001234")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456", "12345678901234567890"})
    void SRS_15_39_bankAccountIfscValid_acceptsBothEndsOfTheAccountLength(String account) {
        assertThat(PreLockRules.bankRouteValid(PAID, account, true, "Y", "SBIN0001234")).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "SBIN1001234",
                "sbin0001234",
                "SBI00001234",
                "SBIN000123",
                "SBIN00012345",
                "SBIN000123$"
            })
    void SRS_15_39_bankAccountIfscValid_failsForANullOrMalformedIfsc(String ifsc) {
        assertThat(PreLockRules.bankRouteValid(PAID, "123456789012", true, "Y", ifsc)).isFalse();
    }

    @Test
    void SRS_15_39_bankAccountIfscValid_failsWhenTheBranchIsNotInTheBranchList() {
        assertThat(PreLockRules.bankRouteValid(PAID, "123456789012", false, null, null)).isFalse();
    }

    @Test
    void SRS_15_39_bankAccountIfscValid_failsForAClosedBranch() {
        assertThat(PreLockRules.bankRouteValid(PAID, "123456789012", true, "N", "SBIN0001234"))
                .isFalse();
        assertThat(PreLockRules.bankRouteValid(PAID, "123456789012", true, null, "SBIN0001234"))
                .isFalse();
    }

    // ---------------------------------------------------------------- PF_UAN_VALID

    @Test
    void SRS_15_39_pfUanValid_passesForACpfStaffMemberWithCodeAndUan() {
        assertThat(PreLockRules.pfUanValid(false, "CPF", null, "CPF/104882", "100000104882"))
                .isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"10000010488", "1000001048821", "10000010488A", "1000 0010488"})
    void SRS_15_39_pfUanValid_failsForAMissingOrMalformedUan(String uan) {
        assertThat(PreLockRules.pfUanValid(false, "CPF", null, "CPF/104882", uan)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void SRS_15_39_pfUanValid_failsWithoutACpfCode(String code) {
        assertThat(PreLockRules.pfUanValid(false, "CPF", null, code, "100000104882")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GPF", "NPS", "NONE"})
    void SRS_15_39_pfUanValid_staffOutsideThePfAreNotChecked(String type) {
        assertThat(PreLockRules.pfUanValid(false, type, null, null, null)).isTrue();
    }

    @Test
    void SRS_15_39_pfUanValid_failsForAStaffRecordWithNoProvidentFundType() {
        assertThat(PreLockRules.pfUanValid(false, null, null, "CPF/104882", "100000104882"))
                .isFalse();
    }

    @Test
    void SRS_15_39_pfUanValid_labourWithPfNeedCodeAndUanFromTheLabourMaster() {
        assertThat(PreLockRules.pfUanValid(true, null, "Y", "CPF/L1", "200000000001")).isTrue();
        assertThat(PreLockRules.pfUanValid(true, null, "Y", "CPF/L1", null)).isFalse();
        assertThat(PreLockRules.pfUanValid(true, "CPF", null, null, "200000000001")).isFalse();
    }

    @Test
    void SRS_15_39_pfUanValid_labourWithoutPfAreNotChecked() {
        assertThat(PreLockRules.pfUanValid(true, null, "N", null, null)).isTrue();
    }

    // ---------------------------------------------------------------- NO_DUPLICATE_RECOVERY

    @Test
    void SRS_15_39_noDuplicateRecovery_passesWhenEachHeadIsRecoveredOnce() {
        assertThat(
                        PreLockRules.membersWithDuplicateRecovery(
                                List.of(
                                        new RecoveryLine(1, 366, "202611"),
                                        new RecoveryLine(1, 396, "202611"),
                                        new RecoveryLine(2, 366, "202611"))))
                .isZero();
        assertThat(PreLockRules.membersWithDuplicateRecovery(List.of())).isZero();
    }

    @Test
    void SRS_15_39_noDuplicateRecovery_failsForTheSameHeadAndMonthTwice() {
        assertThat(
                        PreLockRules.membersWithDuplicateRecovery(
                                List.of(
                                        new RecoveryLine(1, 366, "202611"),
                                        new RecoveryLine(1, 366, "202611"),
                                        new RecoveryLine(2, 366, "202611"))))
                .isEqualTo(1);
    }

    @Test
    void SRS_15_39_noDuplicateRecovery_countsAMemberOnceWhateverTheNumberOfRepeatedHeads() {
        assertThat(
                        PreLockRules.membersWithDuplicateRecovery(
                                List.of(
                                        new RecoveryLine(1, 366, "202611"),
                                        new RecoveryLine(1, 366, "202611"),
                                        new RecoveryLine(1, 366, "202611"),
                                        new RecoveryLine(1, 396, "202611"),
                                        new RecoveryLine(1, 396, "202611"),
                                        new RecoveryLine(3, 504, "202611"),
                                        new RecoveryLine(3, 504, "202611"))))
                .isEqualTo(2);
    }

    @Test
    void SRS_15_39_noDuplicateRecovery_theSameHeadInAnotherMonthIsNoDuplicate() {
        assertThat(
                        PreLockRules.membersWithDuplicateRecovery(
                                List.of(
                                        new RecoveryLine(1, 366, "202610"),
                                        new RecoveryLine(1, 366, "202611"))))
                .isZero();
    }

    @Test
    void SRS_15_39_noDuplicateRecovery_refusesANullLine() {
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                PreLockRules.membersWithDuplicateRecovery(
                                        Arrays.asList(new RecoveryLine(1, 366, "202611"), null)));
    }
}
