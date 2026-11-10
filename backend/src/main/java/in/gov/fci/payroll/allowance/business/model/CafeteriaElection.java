package in.gov.fci.payroll.allowance.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code cafeteria_election}: one version of a member's cafeteria allowance selection received from
 * HRMS (target DDL v4.0 Section 19; SRS-11-16; plan 10 D10-11). Never entered in Payroll; a revised
 * selection is a new version and the earlier one SUPERSEDED, never updated in place.
 */
@Entity
@Table(name = "cafeteria_election")
public class CafeteriaElection {

    /** {@code election_status} (DDL CHECK). */
    public enum Status {
        RECEIVED,
        APPLIED,
        ARREAR_RAISED,
        SUPERSEDED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "election_id")
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "source_code", nullable = false, length = 10)
    private String sourceCode;

    @Column(name = "hrms_request_id", length = 50)
    private String hrmsRequestId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "election_status", nullable = false, length = 20)
    private String status;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "arrear_event_id")
    private Long arrearEventId;

    protected CafeteriaElection() {}

    /** An approved selection from HRMS, APPLIED from its exact start date. */
    public static CafeteriaElection applied(
            long employeeId,
            String finYear,
            int versionNo,
            LocalDate effectiveFrom,
            String hrmsRequestId,
            Instant receivedAt,
            long approvalId) {
        CafeteriaElection e = new CafeteriaElection();
        e.employeeId = employeeId;
        e.finYear = finYear;
        e.versionNo = versionNo;
        e.effectiveFrom = effectiveFrom;
        e.sourceCode = "HRMS";
        e.hrmsRequestId = hrmsRequestId;
        e.receivedAt = receivedAt;
        e.status = Status.APPLIED.name();
        e.approvalId = approvalId;
        return e;
    }

    /** Replaced by a later version from {@code nextFrom}: in force until the day before. */
    public void supersededFrom(LocalDate nextFrom) {
        status = Status.SUPERSEDED.name();
        LocalDate end = nextFrom.minusDays(1);
        effectiveTo = end.isBefore(effectiveFrom) ? effectiveFrom : end;
    }

    /**
     * SRS-11-16: received after the freeze: the arrear of the locked months is raised and the
     * selection stays in force from its exact start date.
     */
    public void arrearRaised(long eventId) {
        status = Status.ARREAR_RAISED.name();
        arrearEventId = eventId;
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getHrmsRequestId() {
        return hrmsRequestId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getStatus() {
        return status;
    }

    public Long getArrearEventId() {
        return arrearEventId;
    }
}
