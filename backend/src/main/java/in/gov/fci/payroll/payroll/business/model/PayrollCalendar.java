package in.gov.fci.payroll.payroll.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * {@code payroll_calendar}: M-10 payroll calendar (target DDL v4.0 S08): one period per payroll
 * month with its processing window.
 */
@Entity
@Table(name = "payroll_calendar")
public class PayrollCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "calendar_id")
    private Long calendarId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "period_start_date", nullable = false)
    private LocalDate periodStartDate;

    @Column(name = "period_end_date", nullable = false)
    private LocalDate periodEndDate;

    @Column(name = "input_cutoff_date")
    private LocalDate inputCutoffDate;

    @Column(name = "compute_by_date")
    private LocalDate computeByDate;

    @Column(name = "verify_by_date")
    private LocalDate verifyByDate;

    @Column(name = "lock_target_date")
    private LocalDate lockTargetDate;

    @Column(name = "disbursement_date")
    private LocalDate disbursementDate;

    @Column(name = "supplementary_from")
    private LocalDate supplementaryFrom;

    @Column(name = "supplementary_to")
    private LocalDate supplementaryTo;

    @Column(name = "mdp_window_from")
    private LocalDate mdpWindowFrom;

    @Column(name = "mdp_window_to")
    private LocalDate mdpWindowTo;

    @Column(name = "approval_id")
    private Long approvalId;

    protected PayrollCalendar() {}

    public Long getCalendarId() {
        return calendarId;
    }

    public String getFinYear() {
        return finYear;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public LocalDate getPeriodStartDate() {
        return periodStartDate;
    }

    public LocalDate getPeriodEndDate() {
        return periodEndDate;
    }

    public LocalDate getInputCutoffDate() {
        return inputCutoffDate;
    }

    public LocalDate getComputeByDate() {
        return computeByDate;
    }

    public LocalDate getVerifyByDate() {
        return verifyByDate;
    }

    public LocalDate getLockTargetDate() {
        return lockTargetDate;
    }

    public LocalDate getDisbursementDate() {
        return disbursementDate;
    }

    public LocalDate getSupplementaryFrom() {
        return supplementaryFrom;
    }

    public LocalDate getSupplementaryTo() {
        return supplementaryTo;
    }

    public LocalDate getMdpWindowFrom() {
        return mdpWindowFrom;
    }

    public LocalDate getMdpWindowTo() {
        return mdpWindowTo;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
