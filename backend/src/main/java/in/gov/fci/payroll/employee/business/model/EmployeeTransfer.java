package in.gov.fci.payroll.employee.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code employee_transfer}: M-11 staff transfer (target DDL v4.0 S03): never changes the CPF
 * Member ID or parent zone; the staff number may be reissued at the destination.
 */
@Entity
@Table(name = "employee_transfer")
public class EmployeeTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id")
    private Long transferId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "from_location_id")
    private Long fromLocationId;

    @Column(name = "to_location_id", nullable = false)
    private Long toLocationId;

    @Column(name = "from_zone_id")
    private Long fromZoneId;

    @Column(name = "to_zone_id")
    private Long toZoneId;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    @Column(name = "relieving_date")
    private LocalDate relievingDate;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(name = "joining_time", length = 5)
    private String joiningTime;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "lpc_reference", length = 100)
    private String lpcReference;

    @Column(name = "order_reference", length = 100)
    private String orderReference;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "split_wage_flag", nullable = false, length = 1)
    private Boolean splitWageFlag;

    @Column(name = "from_unit_days")
    private Integer fromUnitDays;

    @Column(name = "to_unit_days")
    private Integer toUnitDays;

    @Column(name = "hrms_request_id", length = 50)
    private String hrmsRequestId;

    @Column(name = "hrms_change_task_id")
    private Long hrmsChangeTaskId;

    @Column(name = "approval_id")
    private Long approvalId;

    protected EmployeeTransfer() {}

    /** A transfer recorded in Payroll; applied to the employee when approved. */
    public static EmployeeTransfer record(
            Long employeeId,
            Long fromLocationId,
            Long toLocationId,
            Long fromZoneId,
            Long toZoneId,
            LocalDate transferDate,
            LocalDate relievingDate,
            LocalDate joiningDate,
            String joiningTime,
            String reason,
            String lpcReference,
            String orderReference,
            boolean splitWage,
            Integer fromUnitDays,
            Integer toUnitDays) {
        EmployeeTransfer t = new EmployeeTransfer();
        t.employeeId = employeeId;
        t.fromLocationId = fromLocationId;
        t.toLocationId = toLocationId;
        t.fromZoneId = fromZoneId;
        t.toZoneId = toZoneId;
        t.transferDate = transferDate;
        t.relievingDate = relievingDate;
        t.joiningDate = joiningDate;
        t.joiningTime = joiningTime;
        t.reason = reason;
        t.lpcReference = lpcReference;
        t.orderReference = orderReference;
        t.splitWageFlag = splitWage;
        t.fromUnitDays = fromUnitDays;
        t.toUnitDays = toUnitDays;
        return t;
    }

    public void attachApproval(Long approvalId) {
        this.approvalId = approvalId;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getFromLocationId() {
        return fromLocationId;
    }

    public Long getToLocationId() {
        return toLocationId;
    }

    public Long getFromZoneId() {
        return fromZoneId;
    }

    public Long getToZoneId() {
        return toZoneId;
    }

    public LocalDate getTransferDate() {
        return transferDate;
    }

    public LocalDate getRelievingDate() {
        return relievingDate;
    }

    public LocalDate getJoiningDate() {
        return joiningDate;
    }

    public String getJoiningTime() {
        return joiningTime;
    }

    public String getReason() {
        return reason;
    }

    public String getLpcReference() {
        return lpcReference;
    }

    public String getOrderReference() {
        return orderReference;
    }

    public boolean isSplitWageFlag() {
        return Boolean.TRUE.equals(splitWageFlag);
    }

    public Integer getFromUnitDays() {
        return fromUnitDays;
    }

    public Integer getToUnitDays() {
        return toUnitDays;
    }

    public String getHrmsRequestId() {
        return hrmsRequestId;
    }

    public Long getHrmsChangeTaskId() {
        return hrmsChangeTaskId;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
