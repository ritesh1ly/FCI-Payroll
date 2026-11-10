package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.business.CycleCorrectionRules.ReturnScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SRS-15-30 / BR-CYC-016 and SRS-15-31 / BR-CYC-017, SRS-15-10 (plan 05 phase 05d): which cycle
 * states may be returned or un-frozen, what each return scope needs, and when a return can no
 * longer be withdrawn.
 */
class CycleCorrectionRulesTest {

    @ParameterizedTest
    @CsvSource({
        "INITIATED,false,false",
        "FROZEN,false,true",
        "COMPUTED,true,true",
        "VERIFIED,true,true",
        "LOCKED,false,false"
    })
    void SRS_15_10_onlyAComputedOrVerifiedCycleIsReturnedAndNothingIsUnfrozenAfterLock(
            String status, boolean returnable, boolean unfreezable) {
        assertThat(CycleCorrectionRules.returnable(status)).isEqualTo(returnable);
        assertThat(CycleCorrectionRules.unfreezable(status)).isEqualTo(unfreezable);
    }

    @Test
    void SRS_15_30_nullStatusIsNeitherReturnableNorUnfreezable() {
        assertThat(CycleCorrectionRules.returnable(null)).isFalse();
        assertThat(CycleCorrectionRules.unfreezable(null)).isFalse();
    }

    @Test
    void SRS_15_30_eachScopeNamesWhatItNeedsAndNothingElse() {
        List<String> two = List.of("100001", "100002");
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.INSTANCE, null, null)).isEmpty();
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.INSTANCE, " ", List.of()))
                .isEmpty();
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.INSTANCE, "DP-WB01", null))
                .contains("DETAIL_NOT_ALLOWED");
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.INSTANCE, null, two))
                .contains("DETAIL_NOT_ALLOWED");

        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.ORG_UNIT, "DP-WB01", null))
                .isEmpty();
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.ORG_UNIT, null, null))
                .contains("ORG_UNIT_REQUIRED");
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.ORG_UNIT, "  ", null))
                .contains("ORG_UNIT_REQUIRED");
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.ORG_UNIT, "DP-WB01", two))
                .contains("DETAIL_NOT_ALLOWED");

        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.MEMBERS, null, two)).isEmpty();
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.MEMBERS, null, null))
                .contains("MEMBERS_REQUIRED");
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.MEMBERS, null, List.of()))
                .contains("MEMBERS_REQUIRED");
        assertThat(CycleCorrectionRules.scopeDefect(ReturnScope.MEMBERS, "DP-WB01", two))
                .contains("DETAIL_NOT_ALLOWED");
        assertThat(
                        CycleCorrectionRules.scopeDefect(
                                ReturnScope.MEMBERS, null, List.of("100001", "100001")))
                .contains("MEMBER_TWICE");
    }

    @Test
    void SRS_15_30_aReturnIsWithdrawnOnlyWhileOpenAndNothingWasDoneOnIt() {
        assertThat(CycleCorrectionRules.withdrawalDefect("OPEN", "FROZEN", false, false)).isEmpty();
        assertThat(CycleCorrectionRules.withdrawalDefect("WITHDRAWN", "FROZEN", false, false))
                .contains("WITHDRAWN");
        assertThat(CycleCorrectionRules.withdrawalDefect("RECOMPUTED", "COMPUTED", true, false))
                .contains("RECOMPUTED");
        // The un-freeze is named first: it cancelled what the withdrawal would restore.
        assertThat(CycleCorrectionRules.withdrawalDefect("OPEN", "INITIATED", true, true))
                .contains("UNFROZEN");
        assertThat(CycleCorrectionRules.withdrawalDefect("OPEN", "FROZEN", true, false))
                .contains("COMPUTATION_STARTED");
        assertThat(CycleCorrectionRules.withdrawalDefect("OPEN", "COMPUTED", false, false))
                .contains("COMPUTED");
    }
}
