package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * {@code cpf_member_id_history}: every CPF / EPS / UAN / EPFO identifier a member has held, with
 * its zone and dates (target DDL v4.0 Section 3; SRS-6-49, SRS-6-52, SRS-6-58, BR-CPF-040).
 * Received from HRMS (labour: the Labour Master); never generated in Payroll, never deleted: a
 * replaced ID is closed from the new one's effective date.
 */
@Entity
@Table(name = "cpf_member_id_history")
public class CpfMemberIdHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id_hist_id")
    private Long memberIdHistId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "id_type", nullable = false, length = 10)
    private String idType;

    @Column(name = "identifier_value", length = 30)
    private String identifierValue;

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "category_code", length = 10)
    private String categoryCode;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "id_status", nullable = false, length = 20)
    private String idStatus;

    @Column(name = "supersede_reason", length = 200)
    private String supersedeReason;

    @Column(name = "id_source", nullable = false, length = 15)
    private String idSource;

    @Column(name = "hrms_request_id", length = 50)
    private String hrmsRequestId;

    @Column(name = "hrms_order_reference", length = 100)
    private String hrmsOrderReference;

    @Column(name = "hrms_change_task_id")
    private Long hrmsChangeTaskId;

    @Column(name = "approval_id")
    private Long approvalId;

    protected CpfMemberIdHistory() {}

    /** Whether the identifier is the member's one in use on {@code day}. */
    public boolean activeOn(LocalDate day) {
        return "ACTIVE".equals(idStatus)
                && !effectiveFrom.isAfter(day)
                && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    public Long getMemberIdHistId() {
        return memberIdHistId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getIdType() {
        return idType;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getIdStatus() {
        return idStatus;
    }

    public String getSupersedeReason() {
        return supersedeReason;
    }

    public String getIdSource() {
        return idSource;
    }

    public String getHrmsRequestId() {
        return hrmsRequestId;
    }

    public String getHrmsOrderReference() {
        return hrmsOrderReference;
    }

    public Long getHrmsChangeTaskId() {
        return hrmsChangeTaskId;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
