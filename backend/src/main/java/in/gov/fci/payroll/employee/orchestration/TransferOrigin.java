package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.employee.api.OriginWorkSlips;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The origin check of an M-11 transfer (UC M-11 main flow, SRS l.1321: "The system checks ... that
 * no work-slip or computation exists there after it"; on approval "the system rechecks the origin",
 * l.1323). A computation after the transfer date is an ACTUAL payroll result of the member for a
 * period that starts on or after that date (a simulation is never a computation, BR-CYC-007; a
 * SUPERSEDED result belongs to a returned or un-frozen run and no longer stands); a work-slip after
 * it is a slip the member works on, dated on or after it and not cancelled or rejected. A transfer
 * dated the 1st is so refused while that month is computed at the origin (Wave A review; OD-51: the
 * safest reading of "after", for FCI to confirm). Until the transfer is approved every such record
 * is at the origin, so the member's records are read without naming the unit. Both are read through
 * their owners' ports ({@code payroll::api}, {@link OriginWorkSlips}), never from their tables.
 *
 * <p>The other half of the precondition, attendance and payroll complete up to the transfer date,
 * is not built here (PENDING B-12).
 */
@Component
class TransferOrigin {

    private final PayrollRecords payroll;
    private final OriginWorkSlips workSlips;

    TransferOrigin(PayrollRecords payroll, OriginWorkSlips workSlips) {
        this.payroll = payroll;
        this.workSlips = workSlips;
    }

    /**
     * The earliest computation period or work-slip date of the member on or after {@code
     * transferDate}, if any.
     */
    Optional<LocalDate> laterRecord(long employeeId, LocalDate transferDate) {
        return earliest(
                payroll.firstActualPeriodFrom(employeeId, transferDate).orElse(null),
                workSlips.firstFrom(employeeId, transferDate).orElse(null));
    }

    /**
     * Refuses the transfer while the origin holds a computation or work-slip after its date.
     *
     * @throws FciException EMP-409-TRANSFER-ORIGIN naming the earliest such date
     */
    void requireClear(long employeeId, LocalDate transferDate) {
        laterRecord(employeeId, transferDate)
                .ifPresent(
                        d -> {
                            throw new FciException(
                                    ApiErrorCode.M11_ORIGIN_LATER,
                                    new Object[] {d.toString(), transferDate.toString()},
                                    List.of(),
                                    null);
                        });
    }

    static Optional<LocalDate> earliest(LocalDate a, LocalDate b) {
        if (a == null) {
            return Optional.ofNullable(b);
        }
        return Optional.of(b == null || a.isBefore(b) ? a : b);
    }
}
