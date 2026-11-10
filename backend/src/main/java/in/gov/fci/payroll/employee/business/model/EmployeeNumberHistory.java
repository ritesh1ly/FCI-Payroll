package in.gov.fci.payroll.employee.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * {@code employee_number_history}: Every staff number an employee held (target DDL v4.0 S03): the
 * old number is end-dated, never deleted.
 */
@Entity
@Table(name = "employee_number_history")
public class EmployeeNumberHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "emp_num_hist_id")
    private Long empNumHistId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "old_emp_num", nullable = false, length = 20)
    private String oldEmpNum;

    @Column(name = "new_emp_num", nullable = false, length = 20)
    private String newEmpNum;

    @Column(name = "from_location_id")
    private Long fromLocationId;

    @Column(name = "to_location_id")
    private Long toLocationId;

    @Column(name = "change_date", nullable = false)
    private LocalDate changeDate;

    @Column(name = "change_event", nullable = false, length = 30)
    private String changeEvent;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "hrms_change_task_id")
    private Long hrmsChangeTaskId;

    @Column(name = "approval_id")
    private Long approvalId;

    protected EmployeeNumberHistory() {}

    public static EmployeeNumberHistory of(
            Long employeeId,
            String oldNumber,
            String newNumber,
            Long fromLocationId,
            Long toLocationId,
            LocalDate changeDate,
            String changeEvent,
            String reason,
            Long approvalId) {
        EmployeeNumberHistory h = new EmployeeNumberHistory();
        h.employeeId = employeeId;
        h.oldEmpNum = oldNumber;
        h.newEmpNum = newNumber;
        h.fromLocationId = fromLocationId;
        h.toLocationId = toLocationId;
        h.changeDate = changeDate;
        h.changeEvent = changeEvent;
        h.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
        h.approvalId = approvalId;
        return h;
    }

    public Long getEmpNumHistId() {
        return empNumHistId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getOldEmpNum() {
        return oldEmpNum;
    }

    public String getNewEmpNum() {
        return newEmpNum;
    }

    public Long getFromLocationId() {
        return fromLocationId;
    }

    public Long getToLocationId() {
        return toLocationId;
    }

    public LocalDate getChangeDate() {
        return changeDate;
    }

    public String getChangeEvent() {
        return changeEvent;
    }

    public String getReason() {
        return reason;
    }

    public Long getHrmsChangeTaskId() {
        return hrmsChangeTaskId;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
