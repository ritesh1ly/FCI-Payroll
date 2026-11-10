package in.gov.fci.payroll.cpf.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code cpf_deputation_recovery}: one I-09 entry for a deputed member (target DDL v4.0 Section 10;
 * SRS-6-27 … 6-30, BR-CPF-021 … 023): an advance instalment remitted by the borrowing organisation
 * against the sanction it repays, a monthly contribution receipt with its three parts, or an excess
 * kept apart as a refund item. The advance balance falls only on the Checker's approval, never on
 * the Maker's save ({@code chk_cpf_dep_balance}, {@code chk_cpf_dep_instal}). A returned entry goes
 * back to the Maker on the same row (the month's key is unique), never deleted.
 */
@Entity
@Table(name = "cpf_deputation_recovery")
public class CpfDeputationRecovery {

    /** {@code entry_type} (DDL CHECK). */
    public enum EntryType {
        RECOVERY_INSTALMENT,
        CONTRIBUTION_RECEIPT,
        EXCESS_REFUND
    }

    /** {@code entry_status} (DDL CHECK). */
    public enum Status {
        MAKER_SAVED,
        PENDING_CHECKER,
        APPROVED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recovery_id")
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "borrowing_org_id", nullable = false)
    private Long borrowingOrgId;

    @Column(name = "sanction_id")
    private Long sanctionId;

    @Column(name = "entry_type", nullable = false, length = 25)
    private String entryType;

    @Column(name = "deputation_from", nullable = false)
    private LocalDate deputationFrom;

    @Column(name = "deputation_to")
    private LocalDate deputationTo;

    @Column(name = "payroll_yymm", nullable = false, length = 6)
    private String payrollYymm;

    @Column(name = "advance_outstanding")
    private BigDecimal advanceOutstanding;

    @Column(name = "instalment_remitted", nullable = false)
    private BigDecimal instalmentRemitted;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    @Column(name = "cpf_contribution_received", nullable = false)
    private BigDecimal contributionReceived;

    @Column(name = "contribution_employee", nullable = false)
    private BigDecimal contributionEmployee;

    @Column(name = "contribution_employer", nullable = false)
    private BigDecimal contributionEmployer;

    @Column(name = "contribution_vpf", nullable = false)
    private BigDecimal contributionVpf;

    @Column(name = "source_code", nullable = false, length = 10)
    private String sourceCode;

    @Column(name = "evidence_attachment_id")
    private Long evidenceAttachmentId;

    @Column(name = "entry_status", nullable = false, length = 15)
    private String entryStatus;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "credit_memo_flag", nullable = false, length = 1)
    private Boolean creditMemo;

    @Column(name = "approval_id")
    private Long approvalId;

    protected CpfDeputationRecovery() {}

    /**
     * A new entry keyed by the Manager (CPF), saved with the Maker.
     *
     * @param instalment the remitted instalment (RECOVERY_INSTALMENT) or the excess (EXCESS_REFUND)
     */
    @SuppressWarnings("java:S107") // the DDL row
    public static CpfDeputationRecovery keyed(
            Long employeeId,
            Long borrowingOrgId,
            EntryType type,
            Long sanctionId,
            LocalDate deputationFrom,
            LocalDate deputationTo,
            String payrollYymm,
            BigDecimal instalment,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf) {
        CpfDeputationRecovery r = new CpfDeputationRecovery();
        r.employeeId = employeeId;
        r.borrowingOrgId = borrowingOrgId;
        r.entryType = type.name();
        r.sanctionId = sanctionId;
        r.deputationFrom = deputationFrom;
        r.deputationTo = deputationTo;
        r.payrollYymm = payrollYymm;
        r.sourceCode = "ENT";
        r.creditMemo = false;
        r.amounts(instalment, employee, employer, vpf);
        r.entryStatus = Status.MAKER_SAVED.name();
        return r;
    }

    /** A returned entry corrected by the Maker (the month's row is reused). */
    public void corrected(
            BigDecimal instalment, BigDecimal employee, BigDecimal employer, BigDecimal vpf) {
        amounts(instalment, employee, employer, vpf);
        advanceOutstanding = null;
        balanceAfter = null;
        entryStatus = Status.MAKER_SAVED.name();
    }

    private void amounts(
            BigDecimal instalment, BigDecimal employee, BigDecimal employer, BigDecimal vpf) {
        instalmentRemitted = instalment;
        contributionEmployee = employee;
        contributionEmployer = employer;
        contributionVpf = vpf;
        contributionReceived = employee.add(employer).add(vpf);
    }

    /** The remittance evidence (SRS-6-27: mandatory). */
    public void evidence(Long attachmentId) {
        this.evidenceAttachmentId = attachmentId;
    }

    /** Sent to the AGM (CPF). */
    public void submitted(Long approval) {
        this.approvalId = approval;
        this.entryStatus = Status.PENDING_CHECKER.name();
    }

    /** SRS-6-28: approved; for an instalment, the balance before and after it. */
    public void approved(BigDecimal outstanding, BigDecimal after) {
        this.advanceOutstanding = outstanding;
        this.balanceAfter = after;
        this.entryStatus = Status.APPROVED.name();
    }

    /** Returned to the Maker (the reason stays on the approval item). */
    public void returned() {
        this.entryStatus = Status.MAKER_SAVED.name();
    }

    public void rejected() {
        this.entryStatus = Status.REJECTED.name();
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getBorrowingOrgId() {
        return borrowingOrgId;
    }

    public Long getSanctionId() {
        return sanctionId;
    }

    public EntryType type() {
        return EntryType.valueOf(entryType);
    }

    public LocalDate getDeputationFrom() {
        return deputationFrom;
    }

    public LocalDate getDeputationTo() {
        return deputationTo;
    }

    public String getPayrollYymm() {
        return payrollYymm;
    }

    public BigDecimal getAdvanceOutstanding() {
        return advanceOutstanding;
    }

    public BigDecimal getInstalmentRemitted() {
        return instalmentRemitted;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public BigDecimal getContributionEmployee() {
        return contributionEmployee;
    }

    public BigDecimal getContributionEmployer() {
        return contributionEmployer;
    }

    public BigDecimal getContributionVpf() {
        return contributionVpf;
    }

    public Long getEvidenceAttachmentId() {
        return evidenceAttachmentId;
    }

    public Status status() {
        return Status.valueOf(entryStatus);
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
