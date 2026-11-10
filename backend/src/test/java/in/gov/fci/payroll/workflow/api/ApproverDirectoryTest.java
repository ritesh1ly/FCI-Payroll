package in.gov.fci.payroll.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BR-APPR-007: whether anyone other than the maker can decide a level. A directory that only
 * answers {@code hasApprover} has no holders to notify (SRS-15-30) and a staffing snapshot that
 * asks it level by level (the A-08 unstaffed list).
 */
class ApproverDirectoryTest {

    private final List<String> asked = new ArrayList<>();

    /** Staffed only for PAYROLL_CHECKER at office 40, and never by the maker herself. */
    private final ApproverDirectory directory =
            (roleCode, locationId, makerLogin) -> {
                asked.add(roleCode + "@" + locationId + "/" + makerLogin);
                return "PAYROLL_CHECKER".equals(roleCode)
                        && Long.valueOf(40L).equals(locationId)
                        && !"maker.delhi".equals(makerLogin);
            };

    @Test
    void BR_APPR_007_aDirectoryWithoutHolderLookupNotifiesNobody() {
        assertThat(directory.holders("PAYROLL_CHECKER", 40L)).isEmpty();
        assertThat(directory.holders(null, null)).isEmpty();
        assertThat(directory.holders("PAYROLL_CHECKER", 40L))
                .as("an immutable empty list, never null")
                .isNotNull();
    }

    @Test
    void BR_APPR_007_theDefaultStaffingAsksTheDirectoryForEveryLevelAtTheTimeItIsAsked() {
        ApproverDirectory.Staffing staffing = directory.staffing();
        assertThat(asked).as("nothing is read until a level is asked").isEmpty();

        assertThat(staffing.hasApprover("PAYROLL_CHECKER", 40L, "maker.pune")).isTrue();
        assertThat(staffing.hasApprover("PAYROLL_CHECKER", 40L, "maker.delhi"))
                .as("the maker never approves her own item")
                .isFalse();
        assertThat(staffing.hasApprover("PAYROLL_CHECKER", 41L, "maker.pune"))
                .as("another office")
                .isFalse();
        assertThat(staffing.hasApprover("PAYROLL_MAKER", 40L, "maker.pune"))
                .as("another role")
                .isFalse();
        assertThat(staffing.hasApprover("PAYROLL_CHECKER", null, null))
                .as("no office, no maker")
                .isFalse();
        assertThat(asked).hasSize(5);
    }
}
