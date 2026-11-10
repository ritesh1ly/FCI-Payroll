package in.gov.fci.payroll.settlement.orchestration;

import in.gov.fci.payroll.arrear.api.ArrearSettlement;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.loans.api.LoanSettlement;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.ReleaseSuspensions;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalDeciding;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.settlement.business.SettlementErrorCode;
import in.gov.fci.payroll.settlement.business.SettlementRules;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import in.gov.fci.payroll.settlement.orchestration.SettlementWork.Case;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approvals of a settlement (plan 11 phases 11d / 11e; SRS-10-6, 10-14, 10-19, 10-22, 10-25,
 * 10-28, 10-29, 10-30; D11-4, D11-5, D11-6).
 *
 * <ul>
 *   <li>The Maker submits the computed settlement; the Payroll Checker approves it with OTP (route
 *       O05_COMPUTE, FNF_APPROVAL): stage Approved. A pending HQ approval of a gratuity recovery or
 *       a pending ex-gratia entry holds the submission. An outstanding clearance holds only the
 *       approval (SRS-10-14, US-GAP-218): the Checker's approval is refused while any clearance is
 *       outstanding and not excluded, and while the settlement is no longer the computed one
 *       submitted. A return or rejection undoes the computation (SRS-10-30): the case goes back to
 *       the Maker to recompute (stage Clearances raised, or received once all are settled), the
 *       computed values kept in the audit.
 *   <li>Finance initiates the release and the Payroll Checker approves it with OTP (O05_RELEASE,
 *       DISBURSEMENT_RELEASE): stage Posted, then Closed. A net recovery needs its further approval
 *       first (O05_NET_RECOVERY) and the credit memo of module 23 (DDL chk_fnf_credit_memo).
 *   <li>Ex-gratia at the letter's amount with the letter attached, approved by a different user
 *       (O05_EX_GRATIA); an outstanding clearance excluded by the Payroll Checker, with a named
 *       reason, against the settlement approval they are deciding (SRS-10-14, US-GAP-219; no route
 *       of its own); a recovery against gratuity with reason, order and HQ approval
 *       (O05_GRATUITY_HQ).
 * </ul>
 */
@Service
@Loggable
public class SettlementApprovals {

    static final String EX_GRATIA = "fs_ex_gratia_entry";
    static final String CLEARANCE = "settlement_clearance";
    static final String RECOVERY = "fs_recovery_detail";
    static final String RELEASE = "final_settlement_release";
    static final String NET_RECOVERY = "final_settlement_net_recovery";

    /** A reason entered in a text area (exclusion, recovery source): line breaks kept (R-ASCII). */
    private static final Pattern TEXT = Pattern.compile("^[\\x20-\\x7E\\t\\r\\n]{1,500}$");

    private static final Pattern REF = Pattern.compile("^[\\x20-\\x7E]{1,60}$");
    private static final String SUBMITTED_NET = "\"netPayable\":\"";

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final SettlementWork work;
    private final DocumentStore documents;
    private final AuditService audit;
    private final LoanSettlement loans;
    private final ReleaseSuspensions suspensions;
    private final OrgDirectory org;
    private final ArrearSettlement arrears;

    public SettlementApprovals(
            JdbcTemplate jdbc,
            Authorization policy,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            SettlementWork work,
            DocumentStore documents,
            AuditService audit,
            LoanSettlement loans,
            ReleaseSuspensions suspensions,
            OrgDirectory org,
            ArrearSettlement arrears) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.approvals = approvals;
        this.statuses = statuses;
        this.work = work;
        this.documents = documents;
        this.audit = audit;
        this.loans = loans;
        this.suspensions = suspensions;
        this.org = org;
        this.arrears = arrears;
    }

    /** SRS-10-19: the Maker submits the computed settlement to the Payroll Checker (OTP). */
    @Transactional
    public SubmittedApproval submit(AccessContext context, long caseId) {
        Case c = work.load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "COMPUTE", c.employeeId(), c.locationId());
        if (!"COMPUTED".equals(c.status()) || c.stage() != Stage.COMPUTED) {
            throw SettlementWork.stage(c, Stage.COMPUTED);
        }
        // SRS-10-14 (US-GAP-218): an outstanding clearance holds the approval, not the submission.
        requireDecided(
                c,
                "SELECT hq_approval_id FROM fs_recovery_detail WHERE settlement_id = ? AND"
                        + " recovery_source = 'GRATUITY'",
                "Headquarters approval of the recovery against gratuity");
        requireDecided(
                c,
                "SELECT approval_id FROM fs_ex_gratia_entry WHERE settlement_id = ? AND"
                        + " entry_status = 'SUBMITTED'",
                "decision on the ex-gratia entry");
        BigDecimal net = netPayable(c);
        return approvals.submit(
                context,
                new Submission(
                                "O05_COMPUTE",
                                SettlementCaseOpener.SCREEN,
                                "COMPUTE",
                                SettlementWork.ENTITY,
                                c.reference(),
                                ActionType.UPDATE,
                                c.locationId(),
                                submittedPayload(net),
                                c.separationDate(),
                                "Full & final settlement "
                                        + c.reference()
                                        + ": net payable "
                                        + SettlementWork.plain(net))
                        .withImpact(net),
                (OtpProof) null);
    }

    /**
     * SRS-10-19 / 10-6: Finance initiates the release of an approved settlement (OTP at approval).
     * SRS-15-35: refused while an emergency stop covers the case's unit.
     */
    @Transactional
    public SubmittedApproval release(AccessContext context, long caseId) {
        Case c = work.load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "RELEASE", c.employeeId(), c.locationId());
        if (!"APPROVED".equals(c.status())) {
            throw SettlementWork.stage(c, Stage.APPROVED);
        }
        requireReleaseOpen(c.locationId());
        requireArrearsAsComputed(c);
        BigDecimal net = netPayable(c);
        if (SettlementRules.isNetRecovery(net)) {
            Long further =
                    jdbc.queryForObject(
                            "SELECT net_recovery_approval_id FROM final_settlement WHERE"
                                    + " settlement_id = ?",
                            Long.class,
                            c.settlementId());
            if (further == null) {
                // SRS-10-6: the release of a net recovery first raises its further approval.
                return submitNetRecovery(context, c, net);
            }
            throw new FciException(
                    SettlementErrorCode.APPROVAL_PENDING,
                    new Object[] {"credit memo to APPS (interface not yet connected)"},
                    List.of(),
                    null);
        }
        return approvals.submit(
                context,
                new Submission(
                                "O05_RELEASE",
                                SettlementCaseOpener.SCREEN,
                                "RELEASE",
                                RELEASE,
                                c.reference(),
                                ActionType.UPDATE,
                                c.locationId(),
                                null,
                                c.separationDate(),
                                "Release of the settlement "
                                        + c.reference()
                                        + ": "
                                        + SettlementWork.plain(net))
                        .withImpact(net),
                (OtpProof) null);
    }

    /**
     * SRS-10-25: an ex-gratia entry at its letter's amount, the letter attached; sent for approval.
     */
    @Transactional
    public SubmittedApproval exGratia(
            AccessContext context,
            long caseId,
            String letterNo,
            LocalDate letterDate,
            BigDecimal amount,
            String issuingAuthority,
            String fileName,
            byte[] letter) {
        Case c = work.load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "COMPUTE", c.employeeId(), c.locationId());
        if (letterNo == null
                || !REF.matcher(letterNo.strip()).matches()
                || letterDate == null
                || amount == null
                || amount.signum() <= 0) {
            throw SettlementWork.entry(
                    "letter number, letter date and an amount above zero are required");
        }
        if (letter == null || letter.length == 0) {
            throw SettlementWork.entry("the sanction letter must be attached (BR-FNF-015)");
        }
        if (!List.of("INITIATED", "COMPUTED").contains(c.status())) {
            throw SettlementWork.stage(c, Stage.CLEARANCES_RECEIVED);
        }
        String key = c.reference() + "|" + letterNo.strip();
        DocumentStore.AttachmentView doc =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                EX_GRATIA,
                                key,
                                "SANCTION_ORDER",
                                letterNo.strip(),
                                letterDate,
                                issuingAuthority,
                                fileName,
                                letter,
                                true));
        jdbc.update(
                "INSERT INTO fs_ex_gratia_entry (settlement_id, employee_id, sanction_letter_no,"
                    + " sanction_letter_date, ex_gratia_amount, letter_attachment_id, entry_status,"
                    + " entered_by) VALUES (?, ?, ?, ?, ?, ?, 'ENTERED', ?)",
                c.settlementId(),
                c.employeeId(),
                letterNo.strip(),
                Date.valueOf(letterDate),
                amount,
                doc.id(),
                context.loginId());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        "O05_EX_GRATIA",
                                        SettlementCaseOpener.SCREEN,
                                        "COMPUTE",
                                        EX_GRATIA,
                                        key,
                                        ActionType.CREATE,
                                        c.locationId(),
                                        null,
                                        letterDate,
                                        "Ex-gratia "
                                                + SettlementWork.plain(amount)
                                                + " by letter "
                                                + letterNo.strip())
                                .withImpact(amount),
                        (OtpProof) null);
        jdbc.update(
                "UPDATE fs_ex_gratia_entry SET entry_status = 'SUBMITTED', approval_id = ? WHERE"
                        + " settlement_id = ? AND sanction_letter_no = ?",
                item.id(),
                c.settlementId(),
                letterNo.strip());
        return item;
    }

    /**
     * SRS-10-14 (US-GAP-219; UC-06 E1; O-05 Clearance Checklist): the Payroll Checker records a
     * named, reasoned exclusion of an outstanding clearance while deciding the settlement submitted
     * to them. The exclusion is held against that settlement approval (route O05_COMPUTE; DDL
     * {@code chk_settlement_clr_exclusion}), is audited with it, and is never recorded by the Maker
     * who submitted it (R-MC). Refused before the submission, and for a clearance that is not
     * outstanding. The exclusion accompanies that approval (SRS-10-14 l.2871): a return or a
     * rejection of it puts the clearance back to outstanding ({@link #restoreExclusions}).
     */
    @Transactional
    public void exclude(AccessContext context, long caseId, String clearanceType, String reason) {
        Case c = work.load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "APPROVE", c.employeeId(), c.locationId());
        if (reason == null || !TEXT.matcher(reason.strip()).matches()) {
            throw SettlementWork.entry("a named reason is required");
        }
        String status =
                jdbc
                        .queryForList(
                                "SELECT clearance_status FROM settlement_clearance WHERE"
                                        + " settlement_case_id = ? AND clearance_type = ?",
                                String.class,
                                caseId,
                                clearanceType)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> SettlementWork.entry("no clearance " + clearanceType));
        if (!List.of("PENDING", "RAISED").contains(status)) {
            throw SettlementWork.entry("the clearance " + clearanceType + " is not outstanding");
        }
        List<ApprovalStatuses.Pending> submitted =
                statuses.pending(SettlementWork.ENTITY, c.reference());
        if (!"COMPUTED".equals(c.status()) || submitted.isEmpty()) {
            throw SettlementWork.stage(c, Stage.COMPUTED);
        }
        ApprovalStatuses.Pending item = submitted.getFirst();
        if (context.loginId().equalsIgnoreCase(item.submittedBy())) {
            throw new FciException(ApiErrorCode.MAKER_CHECKER);
        }
        long approvalId = item.approvalId();
        jdbc.update(
                "UPDATE settlement_clearance SET clearance_status = 'EXCLUDED', remark = ?,"
                        + " exclusion_approval_id = ? WHERE settlement_case_id = ? AND"
                        + " clearance_type = ? AND clearance_status IN ('PENDING', 'RAISED')",
                "Excluded: " + reason.strip(),
                approvalId,
                caseId,
                clearanceType);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                CLEARANCE,
                                c.reference() + "|" + clearanceType,
                                "clearance_status",
                                status,
                                "EXCLUDED",
                                "Excluded by the Checker: " + reason.strip())
                        .approval(approvalId)
                        .module(SettlementCaseOpener.SCREEN));
    }

    /**
     * SRS-10-15 / 10-22 (deep review DR-3): a recovery is taken from gratuity only as far as the
     * case's gratuity goes. While the gratuity is not computed (it waits for the qualifying
     * service, PENDING 11-service-history) nothing is recovered from it; the recoveries already
     * against it and this one together never exceed it.
     */
    private void requireGratuityCovers(long settlementId, long recoveryId) {
        BigDecimal gratuity =
                jdbc.queryForObject(
                        "SELECT gratuity_amount FROM final_settlement WHERE settlement_id = ?",
                        BigDecimal.class,
                        settlementId);
        if (gratuity == null || gratuity.signum() <= 0) {
            throw SettlementWork.entry(
                    "the gratuity is not computed yet: recover from the payroll dues (SRS-10-15)");
        }
        BigDecimal taken =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(recovery_amount), 0) FROM fs_recovery_detail WHERE"
                                + " settlement_id = ? AND (recovery_source = 'GRATUITY' OR"
                                + " recovery_detail_id = ?)",
                        BigDecimal.class,
                        settlementId,
                        recoveryId);
        if (taken != null && taken.compareTo(gratuity) > 0) {
            throw SettlementWork.entry(
                    "the recoveries against gratuity would exceed the gratuity of " + gratuity);
        }
    }

    /**
     * SRS-10-21 / 10-22: the source of one recovery. Payroll dues at once; gratuity with a reason,
     * the order reference, the order copy attached and Headquarters approval.
     */
    @Transactional
    public void source(
            AccessContext context,
            long caseId,
            long recoveryId,
            String source,
            String reason,
            String orderRef,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] order) {
        Case c = work.load(caseId);
        policy.requireEmployeeRecord(
                context, SettlementCaseOpener.SCREEN, "COMPUTE", c.employeeId(), c.locationId());
        if (!List.of("INITIATED", "COMPUTED").contains(c.status())) {
            throw SettlementWork.stage(c, Stage.COMPUTED);
        }
        Integer found =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fs_recovery_detail WHERE recovery_detail_id = ? AND"
                                + " settlement_id = ?",
                        Integer.class,
                        recoveryId,
                        c.settlementId());
        if (found == null || found == 0) {
            throw new FciException(in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND);
        }
        if ("PAYROLL_DUES".equals(source)) {
            jdbc.update(
                    "UPDATE fs_recovery_detail SET recovery_source = 'PAYROLL_DUES',"
                            + " gratuity_reason = NULL, gratuity_order_ref = NULL,"
                            + " gratuity_order_attachment_id = NULL, hq_approval_id = NULL WHERE"
                            + " recovery_detail_id = ?",
                    recoveryId);
            restate(context, c);
            return;
        }
        if (!"GRATUITY".equals(source)) {
            throw SettlementWork.entry(
                    "the source is payroll dues or gratuity; never the CPF final payment"
                            + " (BR-FNF-011)");
        }
        if (reason == null
                || !TEXT.matcher(reason.strip()).matches()
                || orderRef == null
                || !REF.matcher(orderRef.strip()).matches()
                || order == null
                || order.length == 0) {
            throw SettlementWork.entry(
                    "a recovery against gratuity needs its reason, the order reference and the"
                            + " order copy");
        }
        requireGratuityCovers(c.settlementId(), recoveryId);
        String key = c.reference() + "|" + recoveryId;
        DocumentStore.AttachmentView doc =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                RECOVERY,
                                key,
                                "SANCTION_ORDER",
                                orderRef.strip(),
                                orderDate,
                                issuingAuthority,
                                fileName,
                                order,
                                false));
        SubmittedApproval hq =
                approvals.submit(
                        context,
                        new Submission(
                                "O05_GRATUITY_HQ",
                                SettlementCaseOpener.SCREEN,
                                "COMPUTE",
                                RECOVERY,
                                key,
                                ActionType.UPDATE,
                                c.locationId(),
                                null,
                                c.separationDate(),
                                "Recover against gratuity: " + reason.strip()),
                        (OtpProof) null);
        jdbc.update(
                "UPDATE fs_recovery_detail SET recovery_source = 'GRATUITY', gratuity_reason = ?,"
                    + " gratuity_order_ref = ?, gratuity_order_attachment_id = ?, hq_approval_id ="
                    + " ? WHERE recovery_detail_id = ?",
                reason.strip(),
                orderRef.strip(),
                doc.id(),
                hq.id(),
                recoveryId);
        restate(context, c);
    }

    /**
     * SRS-10-24 (BMAD TR-029): once the paying office confirms a recovery's source, the recovery
     * reduces the payroll net payable or the gratuity, as confirmed - the computed settlement's
     * recoveries, the part recovered from gratuity and the payroll net payable (SRS-10-13) are
     * restated at once, so the statement still reconciles to the payroll net payable plus gratuity
     * and the Checker approves the figure the source choice gives. Audited (R-AUD).
     */
    private void restate(AccessContext context, Case c) {
        Map<String, Object> f =
                jdbc.queryForMap(
                        "SELECT pending_salary_wage, leave_encashment_amount, total_arrears,"
                                + " ex_gratia_amount, net_payable, settlement_status FROM"
                                + " final_settlement WHERE settlement_id = ?",
                        c.settlementId());
        if (!"COMPUTED".equals(f.get("SETTLEMENT_STATUS"))) {
            return;
        }
        BigDecimal fromPayroll = recovered(c.settlementId(), "PAYROLL_DUES");
        BigDecimal fromGratuity = recovered(c.settlementId(), "GRATUITY");
        BigDecimal net =
                SettlementRules.netPayable(
                        (BigDecimal) f.get("PENDING_SALARY_WAGE"),
                        (BigDecimal) f.get("LEAVE_ENCASHMENT_AMOUNT"),
                        (BigDecimal) f.get("TOTAL_ARREARS"),
                        (BigDecimal) f.get("EX_GRATIA_AMOUNT"),
                        fromPayroll);
        jdbc.update(
                "UPDATE final_settlement SET total_recoveries = ?, recovered_from_gratuity = ?,"
                        + " net_payable = ? WHERE settlement_id = ?",
                fromPayroll.add(fromGratuity),
                fromGratuity,
                net,
                c.settlementId());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                SettlementWork.ENTITY,
                                c.reference(),
                                "net_payable",
                                SettlementWork.plain((BigDecimal) f.get("NET_PAYABLE")),
                                SettlementWork.plain(net),
                                "Recovery source confirmed (SRS-10-24)")
                        .module(SettlementCaseOpener.SCREEN));
    }

    private BigDecimal recovered(long settlementId, String source) {
        BigDecimal sum =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(recovery_amount), 0) FROM fs_recovery_detail WHERE"
                                + " settlement_id = ? AND recovery_source = ?",
                        BigDecimal.class,
                        settlementId,
                        source);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // ---------------------------------------------------------------- decisions

    /**
     * SRS-10-14 / SRS-10-30 (Wave A review): the settlement's approval preconditions are checked
     * before the Checker's OTP is asked or verified, so a refused approval spends no code. The
     * decision's own listener checks them again in the same transaction.
     */
    @EventListener
    public void on(ApprovalDeciding d) {
        if (d.concerns(SettlementWork.ENTITY)) {
            Map<String, Object> c = caseOf(d.entityKey());
            requireApprovable(
                    ((Number) c.get("SETTLEMENT_CASE_ID")).longValue(),
                    d.approvalId(),
                    d.payload());
        }
    }

    @EventListener
    public void on(ApprovalDecided d) {
        if (d.concerns(SettlementWork.ENTITY)) {
            settlementDecided(d);
        } else if (d.concerns(RELEASE)) {
            releaseDecided(d);
        } else if (d.concerns(EX_GRATIA)) {
            jdbc.update(
                    "UPDATE fs_ex_gratia_entry SET entry_status = ?, checked_by = ?, checked_at = ?"
                            + " WHERE approval_id = ?",
                    d.approved() ? "APPROVED" : "RETURNED",
                    d.checkerLoginId(),
                    Timestamp.from(d.decidedAt()),
                    d.approvalId());
            undoIfComputed(d);
        } else if (d.concerns(CLEARANCE) && d.approved()) {
            // An exclusion proposed on the earlier O05_EXCLUDE route and still pending when the
            // Checker came to record exclusions directly (US-GAP-219) takes effect on its approval.
            String[] parts = d.entityKey().split("\\|", 2);
            jdbc.update(
                    "UPDATE settlement_clearance SET clearance_status = 'EXCLUDED', remark = ?,"
                        + " exclusion_approval_id = ? WHERE settlement_case_id = (SELECT"
                        + " settlement_case_id FROM settlement_case WHERE case_reference = ?) AND"
                        + " clearance_type = ?",
                    "Excluded: " + d.payload(),
                    d.approvalId(),
                    parts[0],
                    parts[1]);
            jdbc.queryForList(
                            "SELECT settlement_case_id FROM settlement_case WHERE case_reference ="
                                    + " ?",
                            Long.class,
                            parts[0])
                    .forEach(this::advanceIfSettled);
        } else if (d.concerns(NET_RECOVERY) && d.approved()) {
            jdbc.update(
                    "UPDATE final_settlement SET is_net_recovery = 'Y', net_recovery_approval_id ="
                            + " ? WHERE settlement_case_id = (SELECT settlement_case_id FROM"
                            + " settlement_case WHERE case_reference = ?)",
                    d.approvalId(),
                    d.entityKey());
        }
    }

    private void settlementDecided(ApprovalDecided d) {
        Map<String, Object> c = caseOf(d.entityKey());
        long caseId = ((Number) c.get("SETTLEMENT_CASE_ID")).longValue();
        if (d.approved()) {
            requireApprovable(caseId, d.approvalId(), d.payload());
            jdbc.update(
                    "UPDATE final_settlement SET settlement_status = 'APPROVED', approval_id = ?,"
                            + " otp_challenge_id = ? WHERE settlement_case_id = ?",
                    d.approvalId(),
                    d.otpChallengeId(),
                    caseId);
            move(caseId, Stage.APPROVED);
        } else {
            // SRS-10-14: the Checker's exclusions accompanied this approval and fall with it.
            restoreExclusions(caseId, d);
            // SRS-10-30: the return undoes the computation; the values computed stay in the audit.
            jdbc.update(
                    "UPDATE final_settlement SET settlement_status = 'INITIATED' WHERE"
                            + " settlement_case_id = ?",
                    caseId);
            jdbc.update(
                    "UPDATE settlement_case SET case_stage = ? WHERE settlement_case_id = ?",
                    Stage.CLEARANCES_RAISED.number(),
                    caseId);
            advanceIfSettled(caseId);
        }
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                SettlementWork.ENTITY,
                                d.entityKey(),
                                "settlement_status",
                                "COMPUTED",
                                d.approved() ? "APPROVED" : "INITIATED",
                                d.approved()
                                        ? "Settlement approved"
                                        : "Returned to the Maker (undo): " + d.reason())
                        .approval(d.approvalId())
                        .module(SettlementCaseOpener.SCREEN));
    }

    private void releaseDecided(ApprovalDecided d) {
        if (!d.approved()) {
            return;
        }
        Map<String, Object> c = caseOf(d.entityKey());
        long caseId = ((Number) c.get("SETTLEMENT_CASE_ID")).longValue();
        // SRS-15-35: a stop placed while the release waited for the Checker refuses the approval
        // too; the refusal rolls the decision back, so the Checker decides again after resumption.
        Case released = work.load(caseId);
        requireReleaseOpen(released.locationId());
        requireArrearsAsComputed(released);
        jdbc.update(
                "UPDATE final_settlement SET settlement_status = 'PAID', release_otp_challenge_id ="
                        + " ?, released_at = ? WHERE settlement_case_id = ?",
                d.otpChallengeId(),
                Timestamp.from(d.decidedAt()),
                caseId);
        move(caseId, Stage.POSTED);
        // SRS-10-29: on posting the case closes and its records are kept read-only (the statement,
        // the part-year Form 16 and the gratuity advice: PENDING 11-outputs, module 23 postings).
        jdbc.update(
                "UPDATE settlement_case SET case_stage = ?, closed_at = ? WHERE settlement_case_id"
                        + " = ?",
                Stage.CLOSED.number(),
                Timestamp.from(d.decidedAt()),
                caseId);
        // SRS-8-16: the arrears paid here leave APPROVED, so no cycle's lock pays them again.
        arrears.paid(released.employeeId(), d.checkerLoginId(), d.approvalId());
        // SRS-10-7, UC-06 postcondition (l.2917): every loan or advance the settlement itemised is
        // closed on the loan ledger as recovered at settlement, in the posting's transaction.
        loans.settle(
                jdbc.query(
                        "SELECT r.source_key, r.recovery_amount FROM fs_recovery_detail r JOIN"
                                + " final_settlement s ON s.settlement_id = r.settlement_id WHERE"
                                + " s.settlement_case_id = ? AND r.recovery_type IN ('LOAN',"
                                + " 'ADVANCE') ORDER BY r.recovery_detail_id",
                        (rs, i) ->
                                new LoanSettlement.Recovered(
                                        Long.parseLong(rs.getString(1).split(" ", 2)[0]),
                                        rs.getBigDecimal(2)),
                        caseId),
                LocalDate.ofInstant(d.decidedAt(), java.time.ZoneId.of("Asia/Kolkata")),
                d.entityKey(),
                d.checkerLoginId(),
                d.approvalId());
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                SettlementWork.ENTITY,
                                d.entityKey(),
                                "settlement_status",
                                "APPROVED",
                                "PAID",
                                "Released and closed")
                        .approval(d.approvalId())
                        .module(SettlementCaseOpener.SCREEN));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * SRS-10-28 (l.2884), never settled on partial data: an arrear approved after the computation
     * is not in the statement, and release would mark it paid unpaid. Refused until the case is
     * computed again. OPEN-QUESTION: FCI to confirm this hold (PENDING O05-ARREAR-PAID).
     */
    private void requireArrearsAsComputed(Case c) {
        BigDecimal open = arrears.approvedTotal(c.employeeId());
        if (open.signum() == 0) {
            return;
        }
        BigDecimal computed =
                jdbc.queryForObject(
                        "SELECT total_arrears FROM final_settlement WHERE settlement_id = ?",
                        BigDecimal.class,
                        c.settlementId());
        if (computed == null || computed.compareTo(open) != 0) {
            throw SettlementWork.source("an arrear of the member changed after the computation");
        }
    }

    private SubmittedApproval submitNetRecovery(AccessContext context, Case c, BigDecimal net) {
        return approvals.submit(
                context,
                new Submission(
                                "O05_NET_RECOVERY",
                                SettlementCaseOpener.SCREEN,
                                "RELEASE",
                                NET_RECOVERY,
                                c.reference(),
                                ActionType.UPDATE,
                                c.locationId(),
                                null,
                                c.separationDate(),
                                "Net recovery of "
                                        + SettlementWork.plain(net.negate())
                                        + " from the member")
                        .withImpact(net.negate()),
                (OtpProof) null);
    }

    /**
     * SRS-10-14 (US-GAP-218): the Checker's approval moves the case to Approved only when no
     * clearance is outstanding (each received, not applicable or excluded by the Checker), and only
     * for the settlement as computed and submitted: one sent back for recomputation after the
     * submission (dues itemised later, SRS-10-3; a decided ex-gratia entry) is returned, never
     * approved. The net payable submitted travels in the item's payload: a settlement whose net is
     * no longer the one submitted (recomputed after dues were itemised; Wave A review) is refused
     * too, so an approval never stands for a settlement the Checker did not see. Thrown inside the
     * decision's transaction, so the decision is not recorded.
     */
    private void requireApprovable(long caseId, Long approvalId, String payload) {
        List<Map<String, Object>> settlement =
                jdbc.queryForList(
                        "SELECT s.settlement_status, c.case_stage, s.net_payable FROM"
                                + " final_settlement s JOIN settlement_case c ON"
                                + " c.settlement_case_id = s.settlement_case_id WHERE"
                                + " s.settlement_case_id = ?",
                        caseId);
        Map<String, Object> s = settlement.isEmpty() ? Map.of() : settlement.getFirst();
        if (!"COMPUTED".equals(s.get("SETTLEMENT_STATUS"))) {
            Object stage = s.get("CASE_STAGE");
            throw new FciException(
                    SettlementErrorCode.STAGE,
                    new Object[] {
                        stage == null ? "-" : Stage.of(((Number) stage).intValue()).name(),
                        Stage.COMPUTED.name()
                    },
                    List.of(),
                    null);
        }
        BigDecimal submitted = submittedNet(payload);
        BigDecimal now = (BigDecimal) s.get("NET_PAYABLE");
        if (submitted != null && (now == null || submitted.compareTo(now) != 0)) {
            throw new FciException(
                    SettlementErrorCode.CHANGED,
                    new Object[] {SettlementWork.plain(submitted), SettlementWork.plain(now)},
                    List.of(),
                    null);
        }
        requireClearancesSettled(caseId);
    }

    /**
     * SRS-10-14 (l.2871, Wave A review): the Checker's exclusions were recorded against this
     * approval; returned or rejected, each goes back to outstanding (Raised once it was raised,
     * else Pending), its exclusion remark and approval cleared and the change audited with the
     * decision. The audit keeps the exclusion's reason. Wave C review: one answered while it was
     * excluded comes back Received, with the answer's remark ({@code answer_remark}).
     */
    private void restoreExclusions(long caseId, ApprovalDecided d) {
        List<Map<String, Object>> excluded =
                jdbc.queryForList(
                        "SELECT clearance_type, raised_at, received_at, answer_remark FROM"
                                + " settlement_clearance WHERE settlement_case_id = ? AND"
                                + " exclusion_approval_id = ? AND clearance_status = 'EXCLUDED'"
                                + " ORDER BY clearance_type",
                        caseId,
                        d.approvalId());
        for (Map<String, Object> r : excluded) {
            String type = (String) r.get("CLEARANCE_TYPE");
            String back;
            if (r.get("RECEIVED_AT") != null) {
                back = "RECEIVED";
            } else {
                back = r.get("RAISED_AT") == null ? "PENDING" : "RAISED";
            }
            jdbc.update(
                    "UPDATE settlement_clearance SET clearance_status = ?, remark = ?,"
                            + " answer_remark = NULL, exclusion_approval_id = NULL WHERE"
                            + " settlement_case_id = ? AND clearance_type = ? AND"
                            + " exclusion_approval_id = ? AND clearance_status = 'EXCLUDED'",
                    back,
                    "RECEIVED".equals(back) ? r.get("ANSWER_REMARK") : null,
                    caseId,
                    type,
                    d.approvalId());
            audit.record(
                    d.checkerLoginId(),
                    AuditService.Entry.change(
                                    CLEARANCE,
                                    d.entityKey() + "|" + type,
                                    "clearance_status",
                                    "EXCLUDED",
                                    back,
                                    "Exclusion undone: the settlement was "
                                            + d.outcome().name().toLowerCase(java.util.Locale.ROOT))
                            .approval(d.approvalId())
                            .module(SettlementCaseOpener.SCREEN));
        }
    }

    /** The payload of a settlement submission: the net payable the Checker is asked to approve. */
    static String submittedPayload(BigDecimal net) {
        return "{\"netPayable\":\"" + SettlementWork.plain(net) + "\"}";
    }

    /**
     * The net payable of a submission's payload, or null (an item submitted before it was kept).
     */
    static BigDecimal submittedNet(String payload) {
        if (payload == null) {
            return null;
        }
        // A plain scan, no regular expression (SpotBugs REDOS): the value between the quotes.
        int at = payload.indexOf(SUBMITTED_NET);
        if (at < 0) {
            return null;
        }
        int from = at + SUBMITTED_NET.length();
        int to = payload.indexOf('"', from);
        if (to < 0) {
            return null;
        }
        try {
            return new BigDecimal(payload.substring(from, to));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void requireClearancesSettled(long caseId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT clearance_type, clearance_status FROM settlement_clearance WHERE"
                                + " settlement_case_id = ? ORDER BY clearance_type",
                        caseId);
        for (Map<String, Object> r : rows) {
            if (!SettlementRules.clearancesSettled(List.of((String) r.get("CLEARANCE_STATUS")))) {
                throw new FciException(
                        SettlementErrorCode.CLEARANCE,
                        new Object[] {r.get("CLEARANCE_TYPE")},
                        List.of(),
                        null);
            }
        }
    }

    private void requireDecided(Case c, String sql, String label) {
        List<Long> ids =
                SettlementWork.nonNull(jdbc.queryForList(sql, Long.class, c.settlementId()));
        Map<Long, ApprovalStatus> status = statuses.of(ids);
        for (Long id : ids) {
            if (status.get(id) != ApprovalStatus.APPROVED) {
                throw new FciException(
                        SettlementErrorCode.APPROVAL_PENDING,
                        new Object[] {label},
                        List.of(),
                        null);
            }
        }
    }

    /** A decision on a part of a computed settlement sends it back for recomputation. */
    private void undoIfComputed(ApprovalDecided d) {
        String reference = d.entityKey().split("\\|", 2)[0];
        jdbc.update(
                "UPDATE final_settlement SET settlement_status = 'INITIATED' WHERE"
                        + " settlement_status = 'COMPUTED' AND settlement_case_id = (SELECT"
                        + " settlement_case_id FROM settlement_case WHERE case_reference = ?)",
                reference);
        jdbc.update(
                "UPDATE settlement_case SET case_stage = ? WHERE case_reference = ? AND case_stage"
                        + " = ?",
                Stage.CLEARANCES_RAISED.number(),
                reference,
                Stage.COMPUTED.number());
        jdbc.queryForList(
                        "SELECT settlement_case_id FROM settlement_case WHERE case_reference = ?",
                        Long.class,
                        reference)
                .forEach(this::advanceIfSettled);
    }

    private void advanceIfSettled(long caseId) {
        List<String> statuses =
                jdbc.queryForList(
                        "SELECT clearance_status FROM settlement_clearance WHERE settlement_case_id"
                                + " = ?",
                        String.class,
                        caseId);
        if (SettlementRules.clearancesSettled(statuses)) {
            jdbc.update(
                    "UPDATE settlement_case SET case_stage = ? WHERE settlement_case_id = ? AND"
                            + " case_stage = ?",
                    Stage.CLEARANCES_RECEIVED.number(),
                    caseId,
                    Stage.CLEARANCES_RAISED.number());
        }
    }

    private void move(long caseId, Stage to) {
        jdbc.update(
                "UPDATE settlement_case SET case_stage = ? WHERE settlement_case_id = ? AND"
                        + " case_stage < ?",
                to.number(),
                caseId,
                to.number());
    }

    /**
     * SRS-15-35: a suspension names a payroll cycle unit, and a member at a depot or a hosted
     * office is paid by the unit above it, so the check is on that unit. A member with no unit is
     * still stopped by a suspension of all units.
     */
    private void requireReleaseOpen(Long locationId) {
        long office = locationId == null ? 0L : locationId;
        long unit =
                locationId == null
                        ? office
                        : org.cycleUnitOf(locationId).map(OrgLocation::getId).orElse(office);
        suspensions.requireNoPaymentSuspended(unit);
    }

    /** The computed net payable (zero before any computation). */
    private BigDecimal netPayable(Case c) {
        BigDecimal net =
                jdbc.queryForObject(
                        "SELECT net_payable FROM final_settlement WHERE settlement_id = ?",
                        BigDecimal.class,
                        c.settlementId());
        return net == null ? BigDecimal.ZERO : net;
    }

    private Map<String, Object> caseOf(String reference) {
        return jdbc.queryForMap(
                "SELECT settlement_case_id FROM settlement_case WHERE case_reference = ?",
                reference);
    }
}
