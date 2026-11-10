package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BR-APPR-007: an approver of a level is someone other than the maker, on an ACTIVE account, who
 * holds the level's role through a group covering the office; a level without a role or office has
 * none. The holders of a role at an office are listed the same way, the maker included.
 */
class ScopedApproverDirectoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private static final long OFFICE = 40L;

    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final ScopeResolver scopes = mock(ScopeResolver.class);
    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final ScopedApproverDirectory directory =
            new ScopedApproverDirectory(assignments, users, scopes, contexts);

    ScopedApproverDirectoryTest() {
        when(contexts.today()).thenReturn(TODAY);
        // 1: gone; 2: deactivated; 3: the maker; 4: checker elsewhere; 5: auditor here; 6: checker.
        when(assignments.findUserIdsWithOpenAssignments())
                .thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L));
        when(users.findById(1L)).thenReturn(Optional.empty());
        user(2L, "gone@x", AppUser.Status.DEACTIVATED);
        user(3L, "maker@x", AppUser.Status.ACTIVE);
        user(4L, "far@x", AppUser.Status.ACTIVE);
        user(5L, "auditor@x", AppUser.Status.ACTIVE);
        user(6L, "checker@x", AppUser.Status.ACTIVE);
        groups(3L, group("PAYROLL_CHECKER", OFFICE));
        groups(4L, group("PAYROLL_CHECKER", 99L));
        groups(5L, group("AUDITOR", OFFICE));
        groups(6L, group("AUDITOR", 99L), group("PAYROLL_CHECKER", OFFICE));
    }

    private void user(long id, String login, AppUser.Status status) {
        AppUser u = mock(AppUser.class);
        when(u.getLoginId()).thenReturn(login);
        when(u.getStatus()).thenReturn(status);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    private static ActiveGroup group(String role, long office) {
        ActiveGroup g = mock(ActiveGroup.class);
        when(g.roleCode()).thenReturn(role);
        when(g.coverage()).thenReturn(Set.of(office));
        return g;
    }

    private void groups(long userId, ActiveGroup... held) {
        held(userId, List.of(held));
    }

    private final java.util.Map<Long, List<ActiveGroup>> current = new java.util.HashMap<>();

    private void held(long userId, List<ActiveGroup> groups) {
        current.put(userId, groups);
        when(scopes.activeGroupsOf(List.of(1L, 2L, 3L, 4L, 5L, 6L), TODAY)).thenReturn(current);
    }

    @Test
    void BR_APPR_007_anApproverIsAnotherActiveHolderOfTheRoleAtTheOffice() {
        assertThat(directory.hasApprover("PAYROLL_CHECKER", OFFICE, "maker@x")).isTrue();
        assertThat(directory.hasApprover("DDO", OFFICE, "maker@x")).isFalse();
        // When the checker raised the item, the first holder (skipped above as the maker) approves.
        assertThat(directory.hasApprover("PAYROLL_CHECKER", OFFICE, "checker@x")).isTrue();
        verify(scopes, org.mockito.Mockito.atLeastOnce())
                .activeGroupsOf(List.of(1L, 2L, 3L, 4L, 5L, 6L), TODAY);
    }

    @Test
    void BR_APPR_007_aLevelWithoutARoleOrAnOfficeHasNoApproverAndNoHolder() {
        assertThat(directory.hasApprover(null, OFFICE, "maker@x")).isFalse();
        assertThat(directory.hasApprover("PAYROLL_CHECKER", null, "maker@x")).isFalse();
        assertThat(directory.holders(null, OFFICE)).isEmpty();
        assertThat(directory.holders("PAYROLL_CHECKER", null)).isEmpty();
        verifyNoInteractions(assignments);
    }

    /**
     * BR-APPR-007 with A-08 (Wave A review): the snapshot answers every level as {@link
     * ScopedApproverDirectory#hasApprover} does, reading each account and its groups once however
     * many levels it answers (no read per item of the unstaffed list).
     */
    @Test
    void BR_APPR_007_theStaffingSnapshotAnswersAsHasApproverReadingEachAccountOnce() {
        ScopedApproverDirectory.Staffing staffing = directory.staffing();
        for (int i = 0; i < 50; i++) {
            assertThat(staffing.hasApprover("PAYROLL_CHECKER", OFFICE, "maker@x")).isTrue();
            assertThat(staffing.hasApprover("PAYROLL_CHECKER", OFFICE, "checker@x")).isTrue();
            assertThat(staffing.hasApprover("DDO", OFFICE, "maker@x")).isFalse();
            assertThat(staffing.hasApprover("AUDITOR", 99L, "checker@x")).isFalse();
            assertThat(staffing.hasApprover("AUDITOR", OFFICE, "maker@x")).isTrue();
            assertThat(staffing.hasApprover("PAYROLL_CHECKER", 77L, "maker@x")).isFalse();
            assertThat(staffing.hasApprover(null, OFFICE, "maker@x")).isFalse();
            assertThat(staffing.hasApprover("PAYROLL_CHECKER", null, "maker@x")).isFalse();
        }
        verify(assignments, org.mockito.Mockito.times(1)).findUserIdsWithOpenAssignments();
        verify(users, org.mockito.Mockito.times(1)).findById(6L);
        verify(scopes, org.mockito.Mockito.times(1))
                .activeGroupsOf(List.of(1L, 2L, 3L, 4L, 5L, 6L), TODAY);
        verify(scopes, org.mockito.Mockito.never()).activeGroups(any(), any());
    }

    @Test
    void BR_APPR_007_aHolderWhoseAccountIsGoneOrInactiveIsNoHolderAndNoApprover() {
        groups(1L, group("PAYROLL_CHECKER", 77L));
        groups(2L, group("PAYROLL_CHECKER", 77L));
        assertThat(directory.holders("PAYROLL_CHECKER", 77L)).isEmpty();
        assertThat(directory.hasApprover("PAYROLL_CHECKER", 77L, "maker@x")).isFalse();
        assertThat(directory.staffing().hasApprover("PAYROLL_CHECKER", 77L, "maker@x")).isFalse();
    }

    /** The cycle return waited on this: every account read once in one batch, not one by one. */
    @Test
    void SRS_15_30_theHoldersComeFromOneBatchReadOfTheAccounts() {
        directory.holders("PAYROLL_CHECKER", OFFICE);
        verify(scopes, org.mockito.Mockito.never()).activeGroups(any(), any());
        verify(users, org.mockito.Mockito.never()).findById(4L);
    }

    @Test
    void BR_APPR_007_theHoldersAreTheActiveAccountsHoldingTheRoleAtTheOffice() {
        assertThat(directory.holders("PAYROLL_CHECKER", OFFICE)).containsExactly(3L, 6L);
        assertThat(directory.holders("AUDITOR", 99L)).containsExactly(6L);
    }
}
