package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code cpf_interest_freeze}: the year-end CPF interest freeze of a zone's financial year (target
 * DDL v4.0 Section 10; SRS-6-46, FRS V0.5 §13.3): written on the approval of the role set in the
 * Approval Workflow Master, with the number of runs that preceded it; never removed ({@code
 * trg_cpf_freeze_irreversible}). Not one of the 18 OTP actions.
 */
@Entity
@Table(name = "cpf_interest_freeze")
public class CpfInterestFreeze {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "freeze_id")
    private Long freezeId;

    @Column(name = "fin_year", nullable = false, length = 9)
    private String finYear;

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "frozen_by", nullable = false, length = 50)
    private String frozenBy;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt;

    @Column(name = "preceding_run_count", nullable = false)
    private Integer precedingRunCount;

    @Column(name = "final_run_id")
    private Long finalRunId;

    @Column(name = "approval_id")
    private Long approvalId;

    protected CpfInterestFreeze() {}

    /** The freeze as the approving role decided it. */
    public static CpfInterestFreeze approved(
            String finYear,
            Long zoneId,
            String frozenBy,
            Instant frozenAt,
            int precedingRunCount,
            Long finalRunId,
            Long approvalId) {
        CpfInterestFreeze f = new CpfInterestFreeze();
        f.finYear = finYear;
        f.zoneId = zoneId;
        f.frozenBy = frozenBy;
        f.frozenAt = frozenAt;
        f.precedingRunCount = precedingRunCount;
        f.finalRunId = finalRunId;
        f.approvalId = approvalId;
        return f;
    }

    public Long getFreezeId() {
        return freezeId;
    }

    public String getFinYear() {
        return finYear;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public String getFrozenBy() {
        return frozenBy;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public Integer getPrecedingRunCount() {
        return precedingRunCount;
    }

    public Long getFinalRunId() {
        return finalRunId;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
