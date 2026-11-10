package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.api.ArrearSettlement;
import in.gov.fci.payroll.audit.api.AuditService;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-8-16, SRS-10-13: arrears paid through a settlement. They leave APPROVED in the release's
 * transaction: {@link ArrearLockEffect} and the run's arrear lines read only APPROVED, so a cycle
 * holding a result for that member cannot pay them again.
 */
@Component
class SettledArrears implements ArrearSettlement {

    private static final String ENTITY = "arrear_detail";
    private static final String SCREEN = "O-05";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    SettledArrears(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    public BigDecimal approvedTotal(long employeeId) {
        BigDecimal total =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(arrear_amount), 0) FROM arrear_detail WHERE"
                                + " employee_id = ? AND arrear_status = 'APPROVED'",
                        BigDecimal.class,
                        employeeId);
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    public void paid(long employeeId, String actor, long approvalId) {
        int rows =
                jdbc.update(
                        "UPDATE arrear_detail SET arrear_status = 'PAID' WHERE employee_id = ? AND"
                                + " arrear_status = 'APPROVED'",
                        employeeId);
        if (rows == 0) {
            return;
        }
        jdbc.update(
                "UPDATE arrear_event SET event_status = 'POSTED' WHERE event_status = 'APPROVED'"
                        + " AND EXISTS (SELECT 1 FROM arrear_detail d WHERE d.arrear_event_id ="
                        + " arrear_event.arrear_event_id AND d.employee_id = ?) AND NOT EXISTS"
                        + " (SELECT 1 FROM arrear_detail d WHERE d.arrear_event_id ="
                        + " arrear_event.arrear_event_id AND d.arrear_status = 'APPROVED')",
                employeeId);
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(employeeId),
                                "arrear_status",
                                "APPROVED",
                                "PAID",
                                "Paid through the settlement")
                        .approval(approvalId)
                        .module(SCREEN));
    }
}
