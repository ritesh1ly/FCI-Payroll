package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.employee.business.model.EmployeeTransfer;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * SRS-G-53 (FRS §6.2; NEW-G1-07): "On transfer, move the member's full payroll ... history with the
 * record. The present unit sees the full history; the previous unit keeps read-only access to the
 * period served there." A member whose present posting is outside the session's data scope is
 * readable (VIEW only) by a unit in scope that an applied M-11 transfer moved the member out of: an
 * approved transfer, or one on record without an approval (legacy history). A pending, rejected or
 * withdrawn transfer moved nobody and gives no access. Every change stays with the present unit:
 * this grants no action other than VIEW (OD-68).
 */
@Component
public class PreviousUnitAccess {

    private final EventRepositories.Transfers transfers;
    private final ApprovalStatuses statuses;

    public PreviousUnitAccess(EventRepositories.Transfers transfers, ApprovalStatuses statuses) {
        this.transfers = transfers;
        this.statuses = statuses;
    }

    /**
     * The date of the latest applied transfer out of an office in the session's scope (the end of
     * the period served there), or empty when the present posting is in scope (ordinary access) or
     * the member never served in scope.
     */
    public Optional<LocalDate> transferredOut(
            AccessContext context, Long employeeId, Long presentLocation) {
        if (context.dataScope().isEmpty()
                || (presentLocation != null && context.dataScope().contains(presentLocation))) {
            return Optional.empty();
        }
        List<EmployeeTransfer> out =
                transfers.findByEmployeeIdOrderByTransferDateDesc(employeeId).stream()
                        .filter(t -> t.getFromLocationId() != null)
                        .filter(t -> context.dataScope().contains(t.getFromLocationId()))
                        .toList();
        if (out.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, ApprovalStatus> status =
                statuses.of(
                        out.stream()
                                .map(EmployeeTransfer::getApprovalId)
                                .filter(Objects::nonNull)
                                .toList());
        return out.stream()
                .filter(
                        t ->
                                t.getApprovalId() == null
                                        || status.get(t.getApprovalId()) == ApprovalStatus.APPROVED)
                .map(EmployeeTransfer::getTransferDate)
                .max(Comparator.naturalOrder());
    }
}
