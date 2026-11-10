package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * NEW-G1-07 (US-01-26 AC2): SRS-G-53 "the previous unit keeps read-only access to the period served
 * there" - only a unit an applied transfer moved the member out of, never the present unit's path
 * nor an office the member never served; a pending or rejected transfer moved nobody.
 */
class PreviousUnitAccessTest {

    private static final long DELHI = 4L;
    private static final long MUMBAI = 8L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    private final EventRepositories.Transfers transfers = mock(EventRepositories.Transfers.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final PreviousUnitAccess access = new PreviousUnitAccess(transfers, statuses);

    private static AccessContext scoped(Set<Long> scope) {
        return new AccessContext(
                1L, "u", "U", "s", "P", DELHI, null, List.of(), Map.of(), Set.of(), Set.of(),
                scope);
    }

    private static EmployeeTransfer transfer(Long from, LocalDate day, Long approvalId) {
        EmployeeTransfer t =
                EmployeeTransfer.record(
                        7L, from, MUMBAI, null, null, day, day, day, null, "r", null, "O", false,
                        null, null);
        if (approvalId != null) {
            t.attachApproval(approvalId);
        }
        return t;
    }

    @Test
    void SRS_G_53_theUnitAnAppliedTransferMovedTheMemberOutOfReadsIt() {
        when(transfers.findByEmployeeIdOrderByTransferDateDesc(7L))
                .thenReturn(
                        List.of(
                                transfer(DELHI, DAY.plusDays(20), 92L),
                                transfer(DELHI, DAY, 91L),
                                transfer(DELHI, DAY.minusYears(3), null),
                                transfer(null, DAY.minusYears(5), null)));
        when(statuses.of(anyCollection()))
                .thenReturn(Map.of(91L, ApprovalStatus.APPROVED, 92L, ApprovalStatus.REJECTED));
        assertThat(access.transferredOut(scoped(Set.of(DELHI)), 7L, MUMBAI))
                .as("the latest applied transfer: the rejected later one moved nobody")
                .contains(DAY);
    }

    @Test
    void SRS_G_53_legacyHistoryWithoutAnApprovalCounts() {
        when(transfers.findByEmployeeIdOrderByTransferDateDesc(7L))
                .thenReturn(List.of(transfer(DELHI, DAY, null)));
        when(statuses.of(anyCollection())).thenReturn(Map.of());
        assertThat(access.transferredOut(scoped(Set.of(DELHI)), 7L, MUMBAI)).contains(DAY);
    }

    @Test
    void SRS_G_53_noPreviousUnitAccessWhereTheSourceGivesNone() {
        // The present posting in scope: ordinary access, nothing read.
        assertThat(access.transferredOut(scoped(Set.of(DELHI, MUMBAI)), 7L, MUMBAI)).isEmpty();
        // No scope at all (an eSeva member).
        assertThat(access.transferredOut(scoped(Set.of()), 7L, MUMBAI)).isEmpty();
        verify(transfers, never()).findByEmployeeIdOrderByTransferDateDesc(7L);
        // An office the member never served.
        when(transfers.findByEmployeeIdOrderByTransferDateDesc(7L))
                .thenReturn(List.of(transfer(DELHI, DAY, null)));
        assertThat(access.transferredOut(scoped(Set.of(99L)), 7L, MUMBAI)).isEmpty();
        // Only a pending transfer out of the office: it moved nobody.
        when(transfers.findByEmployeeIdOrderByTransferDateDesc(7L))
                .thenReturn(List.of(transfer(DELHI, DAY, 93L)));
        when(statuses.of(anyCollection())).thenReturn(Map.of(93L, ApprovalStatus.PENDING_APPROVAL));
        assertThat(access.transferredOut(scoped(Set.of(DELHI)), 7L, MUMBAI)).isEmpty();
        // No present posting (an incomplete HRMS record) and only a pending transfer: nothing.
        assertThat(access.transferredOut(scoped(Set.of(DELHI)), 7L, null)).isEmpty();
    }
}
