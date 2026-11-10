package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.increment.business.IncrementRules;
import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * I-05 individual cases worked out in Payroll (plan 17 phases 17d and 17e; SRS-13-3, SRS-13-9 …
 * 13-18, SRS-13-20, SRS-13-24, SRS-13-27; D17-1). Establishment / HR raises the case with its order
 * (number, date, issuing authority; SRS-13-18) and the Payroll Checker approves - an increment-date
 * or Basic correction with the OTP INCREMENT_DATE_BASIC_CORRECTION (route {@value #CORRECTION});
 * nothing changes before the approval, and a rejection changes nothing.
 *
 * <ul>
 *   <li>Fixation on promotion (SRS-13-3, FRS l.6410): the next stage above the present Basic in the
 *       member's pay scale as HRMS holds it (HRMS-owned, I-21) - the new scale once the promotion's
 *       pay-scale change is applied; OPEN-QUESTION (US-GAP-285, CLR-103): where the new scale comes
 *       from when the fixation is raised before HRMS sends it (IN-2);
 *   <li>individual pay revision and an additional increment: the Basic the order gives, within the
 *       scale and on a stage when stages are configured;
 *   <li>stagnation increment: a member at the scale maximum for the qualifying period, below the
 *       frequency cap and without an increment-stop order; the quantum is the scale's stagnation
 *       amount (M-03), the qualifying months and the cap rule values (STAGNATION_QUALIFYING_MONTHS,
 *       STAGNATION_FREQUENCY_CAP) - unseeded, the case is refused as not configured; the test that
 *       fired is recorded (SRS-13-19);
 *   <li>Basic correction: the corrected Basic from the date (an arrear or recovery for past
 *       months); increment-date correction: the next due date revised and, when the last increment
 *       was given on the wrong date, re-applied from the corrected one;
 *   <li>reversal of a wrong entry (SRS-13-24, US-GAP-50): the member's latest Basic entry is undone
 *       from its own date - the Basic before it applies again, the arrear or recovery follows and
 *       the entry stays in the history marked as corrected; an entry received from HRMS is
 *       corrected through HRMS (I-21, SRS-G-45), and the opening Basic of a migrated record has no
 *       earlier Basic to restore;
 *   <li>refixation on an Audit or Accounts observation (SRS-13-27, US-GAP-54): a past Basic refixed
 *       with the observation's source and reference kept on the case and on the correction record;
 *       the differential follows as for a Basic correction (BR-INCR-005).
 * </ul>
 *
 * <p>A reversal and an observation refixation amend a past Basic, so they take the correction route
 * with the Checker's OTP INCREMENT_DATE_BASIC_CORRECTION (SRS-13-24, SRS-G-21).
 */
@Service
@Loggable
public class PayFixationService {

    public static final String SCREEN = "I-05";
    public static final String FIXATION = "I05_FIXATION";
    public static final String CORRECTION = "I05_CORRECTION";
    static final String ENTITY = "pay_fixation_case";

    public static final Set<String> TYPES =
            Set.of(
                    "PROMOTION_FIXATION",
                    "INDIVIDUAL_REVISION",
                    "ADDITIONAL_INCREMENT",
                    "STAGNATION_INCREMENT",
                    "BASIC_CORRECTION",
                    "DATE_CORRECTION",
                    "REVERSE_ENTRY",
                    "OBSERVATION_REFIXATION");

    /** The case types that amend a past Basic or due date: approved with OTP (SRS-13-24). */
    static final Set<String> CORRECTIONS =
            Set.of(
                    "BASIC_CORRECTION",
                    "DATE_CORRECTION",
                    "REVERSE_ENTRY",
                    "OBSERVATION_REFIXATION");

    /** SRS-13-27: the observations a past Basic is refixed on. */
    public static final Set<String> OBSERVATION_SOURCES = Set.of("AUDIT", "ACCOUNTS");

    /** The IST business day (the JVM and the database run on UTC). */
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    /** A case as raised. */
    public record Case(
            String eis,
            String caseType,
            LocalDate effectiveDate,
            BigDecimal basic,
            LocalDate correctedDueDate,
            String orderNumber,
            LocalDate orderDate,
            String issuingAuthority,
            String reason,
            Long orderAttachmentId,
            String orderProvisionalKey,
            String observationSource,
            String observationReference) {

        /** A case with its order copy, not an observation refixation. */
        public Case(
                String eis,
                String caseType,
                LocalDate effectiveDate,
                BigDecimal basic,
                LocalDate correctedDueDate,
                String orderNumber,
                LocalDate orderDate,
                String issuingAuthority,
                String reason,
                Long orderAttachmentId,
                String orderProvisionalKey) {
            this(
                    eis,
                    caseType,
                    effectiveDate,
                    basic,
                    correctedDueDate,
                    orderNumber,
                    orderDate,
                    issuingAuthority,
                    reason,
                    orderAttachmentId,
                    orderProvisionalKey,
                    null,
                    null);
        }

        /** A case without its order copy (the preview: the copy is checked at submission). */
        public Case(
                String eis,
                String caseType,
                LocalDate effectiveDate,
                BigDecimal basic,
                LocalDate correctedDueDate,
                String orderNumber,
                LocalDate orderDate,
                String issuingAuthority,
                String reason) {
            this(
                    eis,
                    caseType,
                    effectiveDate,
                    basic,
                    correctedDueDate,
                    orderNumber,
                    orderDate,
                    issuingAuthority,
                    reason,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** An order copy stored before its case (SRS-G-31): its id and provisional key. */
    public record StoredOrder(long attachmentId, String provisionalKey) {}

    /** The order copy's particulars and file (SRS-G-30). */
    public record OrderCopy(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** The proposal shown before submission and carried to the approval. */
    public record Proposal(
            long employeeId,
            String eis,
            String caseType,
            LocalDate effectiveDate,
            BigDecimal oldBasic,
            BigDecimal revisedBasic,
            LocalDate oldDueDate,
            LocalDate correctedDueDate,
            String test,
            // SRS-13-24 (US-GAP-50): the Basic history entry a reversal undoes, else null.
            Long reversedEntry) {

        /** A proposal that reverses no entry. */
        public Proposal(
                long employeeId,
                String eis,
                String caseType,
                LocalDate effectiveDate,
                BigDecimal oldBasic,
                BigDecimal revisedBasic,
                LocalDate oldDueDate,
                LocalDate correctedDueDate,
                String test) {
            this(
                    employeeId,
                    eis,
                    caseType,
                    effectiveDate,
                    oldBasic,
                    revisedBasic,
                    oldDueDate,
                    correctedDueDate,
                    test,
                    null);
        }
    }

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final Scales scales;
    private final Stagnation stagnation;
    private final BasicPayService basic;
    private final IncrementRunService increments;
    private final ObjectMapper json;
    private final DocumentStore documents;
    private final AuditService audit;

    public PayFixationService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MakerChecker approvals,
            Scales scales,
            Stagnation stagnation,
            BasicPayService basic,
            IncrementRunService increments,
            ObjectMapper json,
            DocumentStore documents,
            AuditService audit) {
        this.documents = documents;
        this.audit = audit;
        this.named = named;
        this.policy = policy;
        this.approvals = approvals;
        this.scales = scales;
        this.stagnation = stagnation;
        this.basic = basic;
        this.increments = increments;
        this.json = json;
    }

    /** The proposal of a case (the Basic history panel shows it before save, SRS-13-26). */
    public Proposal preview(AccessContext context, Case c) {
        policy.require(context, SCREEN, "SUBMIT");
        return propose(context, c);
    }

    /** SRS-13-18 / 13-20: Establishment / HR submits the case for the Payroll Checker. */
    @Transactional
    public SubmittedApproval submit(AccessContext context, Case c) {
        policy.require(context, SCREEN, "SUBMIT");
        Proposal p = propose(context, c);
        boolean correction = CORRECTIONS.contains(c.caseType());
        // SRS-13-18 (US-GAP-10): the order before submission, with the order control of SRS-G-30.
        DocumentStore.AttachmentView copy = orderCopy(context, p.eis(), c);
        String key = UUID.randomUUID().toString();
        documents.adopt(context.loginId(), ENTITY, c.orderProvisionalKey(), copy.id(), key);
        Map<String, Object> payload = new HashMap<>();
        payload.put("employeeId", p.employeeId());
        payload.put("caseType", p.caseType());
        payload.put("effective", p.effectiveDate().toString());
        payload.put("old", p.oldBasic() == null ? "" : p.oldBasic().toPlainString());
        payload.put("revised", p.revisedBasic() == null ? "" : p.revisedBasic().toPlainString());
        payload.put("oldDue", p.oldDueDate() == null ? "" : p.oldDueDate().toString());
        payload.put(
                "correctedDue",
                p.correctedDueDate() == null ? "" : p.correctedDueDate().toString());
        payload.put("order", c.orderNumber().strip());
        payload.put("orderDate", c.orderDate().toString());
        payload.put("authority", c.issuingAuthority().strip());
        payload.put("reason", c.reason().strip());
        payload.put("test", p.test() == null ? "" : p.test());
        payload.put("reversed", p.reversedEntry() == null ? "" : p.reversedEntry().toString());
        payload.put(
                "observationSource",
                c.observationSource() == null ? "" : c.observationSource().strip());
        payload.put(
                "observationReference",
                c.observationReference() == null ? "" : c.observationReference().strip());
        Long location =
                named.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = :id",
                        new MapSqlParameterSource("id", p.employeeId()),
                        Long.class);
        return approvals.submit(
                context,
                new Submission(
                                correction ? CORRECTION : FIXATION,
                                SCREEN,
                                "SUBMIT",
                                ENTITY,
                                key,
                                ActionType.UPDATE,
                                location,
                                json.writeValueAsString(payload),
                                p.effectiveDate(),
                                c.reason().strip())
                        .withImpact(
                                p.revisedBasic() == null || p.oldBasic() == null
                                        ? BigDecimal.ZERO
                                        : p.revisedBasic().subtract(p.oldBasic())),
                (OtpProof) null);
    }

    /**
     * SRS-13-18 / SRS-G-31: the Maker stores the increment, promotion or pay-fixation order first;
     * the case then names it (shown to the Payroll Checker on the Approvals Inbox).
     */
    @Transactional
    public StoredOrder storeOrder(AccessContext context, String eis, OrderCopy copy) {
        policy.require(context, SCREEN, "SUBMIT");
        member(context, eis);
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView stored =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                provisional,
                                copy.documentType(),
                                copy.reference(),
                                copy.orderDate(),
                                copy.issuingAuthority(),
                                copy.fileName(),
                                copy.content(),
                                true));
        return new StoredOrder(stored.id(), provisional);
    }

    /** The copy this user stored for the case; none is an audited refusal (SRS-20-27). */
    private DocumentStore.AttachmentView orderCopy(AccessContext context, String eis, Case c) {
        DocumentStore.AttachmentView copy =
                c.orderAttachmentId() == null
                                || c.orderProvisionalKey() == null
                                || !c.orderProvisionalKey().startsWith(DocumentStore.PROVISIONAL)
                        ? null
                        : documents.active(ENTITY, c.orderProvisionalKey()).stream()
                                .filter(
                                        a ->
                                                a.id().equals(c.orderAttachmentId())
                                                        && context.loginId().equals(a.uploadedBy()))
                                .findFirst()
                                .orElse(null);
        if (copy == null) {
            audit.submissionBlocked(context.loginId(), ENTITY, eis, SCREEN, "order copy");
            throw entry("the order copy (SRS-13-18)");
        }
        return copy;
    }

    /** The Checker's approval: the case takes effect; a rejection or return changes nothing. */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY) || !d.approved()) {
            return;
        }
        JsonNode p = json.readTree(d.payload());
        long employee = p.path("employeeId").asLong();
        String type = p.path("caseType").asString();
        LocalDate effective = LocalDate.parse(p.path("effective").asString());
        BigDecimal old = amount(p.path("old").asString());
        BigDecimal revised = amount(p.path("revised").asString());
        String order =
                p.path("order").asString()
                        + " of "
                        + p.path("orderDate").asString()
                        + ", "
                        + p.path("authority").asString();
        String reason = p.path("reason").asString();
        String by = d.checkerLoginId();
        switch (type) {
            case "PROMOTION_FIXATION", "INDIVIDUAL_REVISION" -> {
                BasicPayService.Applied applied =
                        basic.apply(
                                change(
                                        employee,
                                        revised,
                                        effective,
                                        "PROMOTION_FIXATION".equals(type)
                                                ? "PROMOTION"
                                                : "FIXATION",
                                        reason,
                                        order,
                                        d.approvalId(),
                                        by,
                                        "pay_fixation_event",
                                        d.entityKey()));
                named.update(
                        "INSERT INTO pay_fixation_event (employee_id, event_basis, old_basic,"
                                + " revised_basic, fixation_date, order_reference,"
                                + " basic_history_id, is_historical_only, approval_id,"
                                + " arrear_event_id) VALUES (:employee, :basis, :old, :revised,"
                                + " :date, :order, :history, 'N', :approval, :arrear)",
                        new MapSqlParameterSource("employee", employee)
                                .addValue(
                                        "basis",
                                        "PROMOTION_FIXATION".equals(type)
                                                ? "PROMOTION"
                                                : "INDIVIDUAL_REVISION")
                                .addValue("old", old)
                                .addValue("revised", revised)
                                .addValue("date", Date.valueOf(effective))
                                .addValue("order", cut(order, 100))
                                .addValue("history", applied.historyId())
                                .addValue("approval", d.approvalId())
                                .addValue("arrear", applied.arrearEventId(), Types.NUMERIC));
            }
            case "ADDITIONAL_INCREMENT", "STAGNATION_INCREMENT" ->
                    increments.record(
                            employee,
                            effective,
                            IncrementRules.duePeriod(effective),
                            "ADDITIONAL_INCREMENT".equals(type) ? "ADDITIONAL" : "STAGNATION",
                            old,
                            revised,
                            d.approvalId(),
                            by,
                            "ADDITIONAL_INCREMENT".equals(type)
                                    ? "INCREMENT"
                                    : "STAGNATION_INCREMENT");
            case "BASIC_CORRECTION" -> {
                BasicPayService.Applied applied =
                        basic.apply(
                                change(
                                        employee,
                                        revised,
                                        effective,
                                        "CORRECTION",
                                        reason,
                                        order,
                                        d.approvalId(),
                                        by,
                                        "increment_correction",
                                        d.entityKey()));
                correction(p, employee, "BASIC_PAY", applied, null, d);
            }
            case "DATE_CORRECTION" -> dateCorrection(p, employee, d);
            case "REVERSE_ENTRY" -> reversal(p, employee, effective, revised, order, reason, d);
            case "OBSERVATION_REFIXATION" -> {
                // SRS-13-27: the refixed Basic from its date, with the observation it answers.
                String observation = observation(p);
                BasicPayService.Applied applied =
                        basic.apply(
                                change(
                                        employee,
                                        revised,
                                        effective,
                                        "CORRECTION",
                                        observation + ": " + reason,
                                        order,
                                        d.approvalId(),
                                        by,
                                        "increment_correction",
                                        d.entityKey()));
                correction(p, employee, "BASIC_PAY", applied, null, d, observation + ": " + reason);
            }
            default -> throw new IllegalStateException("Unknown case type " + type);
        }
    }

    /** SRS-13-27: "Audit observation REF" / "Accounts observation REF". */
    private static String observation(JsonNode p) {
        String source = p.path("observationSource").asString();
        return ("AUDIT".equals(source) ? "Audit" : "Accounts")
                + " observation "
                + p.path("observationReference").asString();
    }

    /**
     * SRS-13-24 (US-GAP-50): the wrong entry is undone from its own date - the Basic before it is
     * written as a correction on the same date (a later sequence), so the entry stays in the
     * history and is shown as corrected. The entry must still be the member's latest: an entry
     * written after the submission refuses the approval, and the Maker raises the reversal again.
     */
    private void reversal(
            JsonNode p,
            long employee,
            LocalDate effective,
            BigDecimal restored,
            String order,
            String reason,
            ApprovalDecided d) {
        long reversed = Long.parseLong(p.path("reversed").asString());
        Optional<Map<String, Object>> latest = latestEntry(employee);
        if (latest.isEmpty() || ((Number) latest.get().get("HISTORY_ID")).longValue() != reversed) {
            throw entry("the entry to reverse is no longer the member's latest Basic entry");
        }
        BasicPayService.Applied applied =
                basic.apply(
                        change(
                                employee,
                                restored,
                                effective,
                                "CORRECTION",
                                "Reversal of a wrong entry: " + reason,
                                order,
                                d.approvalId(),
                                d.checkerLoginId(),
                                "increment_correction",
                                d.entityKey()));
        correction(p, employee, "REVERSE_ENTRY", applied, null, d);
    }

    /** The member's latest Basic history entry (the one a reversal undoes). */
    private Optional<Map<String, Object>> latestEntry(long employee) {
        return named
                .queryForList(
                        "SELECT history_id, basic_amount, old_basic_amount, effective_from,"
                                + " event_type, source_code FROM employee_basic_history WHERE"
                                + " employee_id = :employee ORDER BY effective_from DESC,"
                                + " event_sequence DESC FETCH FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource("employee", employee))
                .stream()
                .findFirst();
    }

    /**
     * SRS-13-24: the next due date revised; the last increment re-applied from the corrected date.
     */
    private void dateCorrection(JsonNode p, long employee, ApprovalDecided d) {
        LocalDate oldDue =
                p.path("oldDue").asString().isEmpty()
                        ? null
                        : LocalDate.parse(p.path("oldDue").asString());
        LocalDate corrected = LocalDate.parse(p.path("correctedDue").asString());
        BasicPayService.Applied applied = null;
        // US-GAP-16: oldDue is the member's current next due date, which the last annual increment
        // set as its own next_due_date (IncrementRunService); its increment_due_date is a year
        // back.
        List<Map<String, Object>> last =
                named.queryForList(
                        "SELECT increment_id, revised_basic FROM pay_increment_cycle WHERE"
                                + " employee_id = :employee AND increment_type = 'ANNUAL' AND"
                                + " next_due_date = :due ORDER BY increment_due_date DESC",
                        new MapSqlParameterSource("employee", employee)
                                .addValue(
                                        "due",
                                        oldDue == null ? null : Date.valueOf(oldDue),
                                        Types.DATE));
        if (!last.isEmpty()) {
            applied =
                    basic.apply(
                            change(
                                    employee,
                                    (BigDecimal) last.getFirst().get("REVISED_BASIC"),
                                    corrected,
                                    "CORRECTION",
                                    "Increment re-applied from the corrected due date " + corrected,
                                    p.path("order").asString(),
                                    d.approvalId(),
                                    d.checkerLoginId(),
                                    "increment_correction",
                                    d.entityKey()));
        }
        // The next due date follows the corrected one (a year on when the increment was given).
        LocalDate next = last.isEmpty() ? corrected : IncrementRules.nextDue(corrected);
        int changed =
                named.update(
                        "UPDATE employee_increment_due SET next_due_date = :next, source_code ="
                                + " 'ENT', updated_by = :by, updated_at = CURRENT_TIMESTAMP WHERE"
                                + " employee_id = :employee",
                        new MapSqlParameterSource("next", Date.valueOf(next))
                                .addValue("by", d.checkerLoginId())
                                .addValue("employee", employee));
        if (changed == 0) {
            named.update(
                    "INSERT INTO employee_increment_due (employee_id, next_due_date, source_code,"
                            + " updated_by, updated_at) VALUES (:employee, :next, 'ENT', :by,"
                            + " CURRENT_TIMESTAMP)",
                    new MapSqlParameterSource("next", Date.valueOf(next))
                            .addValue("by", d.checkerLoginId())
                            .addValue("employee", employee));
        }
        correction(
                p,
                employee,
                "INCREMENT_DATE",
                applied,
                last.isEmpty() ? null : ((Number) last.getFirst().get("INCREMENT_ID")).longValue(),
                d);
    }

    /**
     * The increment_correction record of an approved correction (with its OTP challenge).
     *
     * @param increment the increment a date correction re-dates (its history row is the old entry
     *     shown as corrected, SRS-13-24), or null
     */
    private void correction(
            JsonNode p,
            long employee,
            String type,
            BasicPayService.Applied applied,
            Long increment,
            ApprovalDecided d) {
        correction(p, employee, type, applied, increment, d, p.path("reason").asString());
    }

    /** The correction record with the reason it keeps (an observation refixation names it). */
    private void correction(
            JsonNode p,
            long employee,
            String type,
            BasicPayService.Applied applied,
            Long increment,
            ApprovalDecided d,
            String reason) {
        named.update(
                "INSERT INTO increment_correction (employee_id, increment_id, basic_history_id,"
                        + " correction_type, correction_origin, old_due_date, corrected_due_date,"
                        + " old_basic, corrected_basic, correction_reason, order_number,"
                        + " order_date, issuing_authority, arrear_event_id, correction_status,"
                        + " approval_id, otp_challenge_id) VALUES (:employee, :increment, :history,"
                        + " :type, 'PAYROLL', :oldDue, :due, :old, :basic, :reason, :order,"
                        + " :orderDate, :authority, :arrear, 'APPROVED', :approval, :otp)",
                new MapSqlParameterSource("employee", employee)
                        .addValue("increment", increment, Types.NUMERIC)
                        .addValue(
                                "history",
                                applied == null ? null : applied.historyId(),
                                Types.NUMERIC)
                        .addValue("type", type)
                        .addValue("oldDue", date(p.path("oldDue").asString()), Types.DATE)
                        .addValue("due", date(p.path("correctedDue").asString()), Types.DATE)
                        .addValue("old", amount(p.path("old").asString()), Types.NUMERIC)
                        .addValue("basic", amount(p.path("revised").asString()), Types.NUMERIC)
                        .addValue("reason", cut(reason, 1000))
                        .addValue("order", cut(p.path("order").asString(), 100))
                        .addValue("orderDate", date(p.path("orderDate").asString()), Types.DATE)
                        .addValue("authority", cut(p.path("authority").asString(), 150))
                        .addValue(
                                "arrear",
                                applied == null ? null : applied.arrearEventId(),
                                Types.NUMERIC)
                        .addValue("approval", d.approvalId())
                        .addValue("otp", d.otpChallengeId(), Types.NUMERIC));
    }

    // ---------------------------------------------------------------- proposal

    private Proposal propose(AccessContext context, Case c) {
        if (c.caseType() == null || !TYPES.contains(c.caseType())) {
            throw entry("the case type");
        }
        if (c.effectiveDate() == null) {
            throw entry("the effective date");
        }
        if (blank(c.orderNumber()) || c.orderDate() == null || blank(c.issuingAuthority())) {
            throw entry("the order number, date and issuing authority (SRS-13-18)");
        }
        if (blank(c.reason())) {
            throw entry("the reason");
        }
        Map<String, Object> m = member(context, c.eis());
        long employee = ((Number) m.get("EMPLOYEE_ID")).longValue();
        BigDecimal old = basic.basicOn(employee, c.effectiveDate());
        if (old == null) {
            old = (BigDecimal) m.get("CURRENT_BASIC");
        }
        Optional<Scales.Scale> scale = scales.ofMember(employee, c.effectiveDate());
        LocalDate oldDue =
                m.get("NEXT_DUE_DATE") == null ? null : Dates.local(m.get("NEXT_DUE_DATE"));
        String eis = (String) m.get("EMP_NUM");
        return switch (c.caseType()) {
            case "PROMOTION_FIXATION" -> {
                Scales.Scale s = scale.orElseThrow(() -> flagged(eis, Flag.NO_SCALE));
                if (s.stages().isEmpty()) {
                    throw flagged(eis, Flag.STAGES_NOT_CONFIGURED);
                }
                BigDecimal next =
                        IncrementRules.nextStageAbove(old, s.stages())
                                .orElseThrow(() -> flagged(eis, Flag.ABOVE_MAXIMUM));
                yield new Proposal(
                        employee,
                        eis,
                        c.caseType(),
                        c.effectiveDate(),
                        old,
                        next,
                        oldDue,
                        null,
                        null);
            }
            case "INDIVIDUAL_REVISION",
                    "ADDITIONAL_INCREMENT",
                    "BASIC_CORRECTION",
                    "OBSERVATION_REFIXATION" -> {
                if ("OBSERVATION_REFIXATION".equals(c.caseType())) {
                    observationDefect(c);
                }
                if (c.basic() == null || c.basic().signum() <= 0) {
                    throw entry("the revised Basic");
                }
                Scales.Scale s = scale.orElseThrow(() -> flagged(eis, Flag.NO_SCALE));
                IncrementRules.scale(c.basic(), s.max(), false)
                        .ifPresent(
                                f -> {
                                    throw flagged(eis, f);
                                });
                if (!s.stages().isEmpty()) {
                    IncrementRules.stage(c.basic(), s.stages())
                            .ifPresent(
                                    f -> {
                                        throw flagged(eis, f);
                                    });
                }
                yield new Proposal(
                        employee,
                        eis,
                        c.caseType(),
                        c.effectiveDate(),
                        old,
                        c.basic(),
                        oldDue,
                        null,
                        null);
            }
            case "STAGNATION_INCREMENT" -> stagnation(employee, eis, c, old, scale, oldDue);
            case "REVERSE_ENTRY" -> reversalProposal(employee, eis, c, oldDue);
            default -> {
                if (c.correctedDueDate() == null) {
                    throw entry("the corrected increment date");
                }
                yield new Proposal(
                        employee,
                        eis,
                        c.caseType(),
                        c.effectiveDate(),
                        old,
                        old,
                        oldDue,
                        c.correctedDueDate(),
                        null);
            }
        };
    }

    /**
     * SRS-13-27 (US-GAP-54): the observation's source (Audit or Accounts) and its reference, and a
     * past Basic (an effective date not after today).
     */
    private static void observationDefect(Case c) {
        if (c.observationSource() == null
                || !OBSERVATION_SOURCES.contains(c.observationSource().strip())) {
            throw entry("the observation source (Audit or Accounts, SRS-13-27)");
        }
        if (blank(c.observationReference()) || c.observationReference().strip().length() > 100) {
            throw entry("the observation reference (at most 100 characters, SRS-13-27)");
        }
        if (c.effectiveDate().isAfter(LocalDate.now(IST))) {
            throw entry("a refixation on an observation refixes a past Basic (SRS-13-27)");
        }
    }

    /**
     * SRS-13-24 (US-GAP-50): the reversal of the member's latest Basic entry, named by its date:
     * the Basic it replaced applies again from that date.
     */
    private Proposal reversalProposal(long employee, String eis, Case c, LocalDate oldDue) {
        Map<String, Object> e =
                latestEntry(employee)
                        .orElseThrow(() -> entry(eis + " has no Basic entry to reverse"));
        LocalDate from = Dates.local(e.get("EFFECTIVE_FROM"));
        if (!from.equals(c.effectiveDate())) {
            throw entry("the entry to reverse is the latest Basic entry, effective " + from);
        }
        if ("HRMS".equals(e.get("SOURCE_CODE"))) {
            throw entry("an entry received from HRMS is corrected through HRMS (I-21, SRS-G-45)");
        }
        if ("MIGRATION_OPENING".equals(e.get("EVENT_TYPE")) || e.get("OLD_BASIC_AMOUNT") == null) {
            throw entry("the entry has no earlier Basic to restore");
        }
        return new Proposal(
                employee,
                eis,
                c.caseType(),
                from,
                (BigDecimal) e.get("BASIC_AMOUNT"),
                (BigDecimal) e.get("OLD_BASIC_AMOUNT"),
                oldDue,
                null,
                null,
                ((Number) e.get("HISTORY_ID")).longValue());
    }

    /**
     * SRS-13-11 … 13-13: the eligibility test (shared with the I-05 run), recorded with the case.
     */
    private Proposal stagnation(
            long employee,
            String eis,
            Case c,
            BigDecimal old,
            Optional<Scales.Scale> scale,
            LocalDate oldDue) {
        Scales.Scale s = scale.orElseThrow(() -> flagged(eis, Flag.NO_SCALE));
        Stagnation.Outcome o = stagnation.evaluate(employee, old, s, c.effectiveDate());
        switch (o.verdict()) {
            case NOT_CONFIGURED -> throw notConfigured(o.missing());
            case NOT_AT_MAXIMUM ->
                    throw entry(eis + " is not at the scale maximum " + s.max().toPlainString());
            case NOT_QUALIFIED ->
                    throw entry(
                            eis
                                    + " has not been at the scale maximum for "
                                    + o.months().toPlainString()
                                    + " months");
            case BARRED -> throw flagged(eis, Flag.BARRED);
            case AT_CAP ->
                    throw entry(
                            eis + " has reached the frequency cap of " + o.cap().toPlainString());
            case ELIGIBLE -> {
                // the proposal below
            }
        }
        return new Proposal(
                employee,
                eis,
                c.caseType(),
                c.effectiveDate(),
                old,
                o.revised(),
                oldDue,
                null,
                o.test());
    }

    private Map<String, Object> member(AccessContext context, String eis) {
        if (blank(eis)) {
            throw entry("the member (EIS)");
        }
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT e.employee_id, e.emp_num, e.current_basic, d.next_due_date FROM"
                            + " employee e LEFT JOIN employee_increment_due d ON d.employee_id ="
                            + " e.employee_id WHERE e.emp_num = :eis AND e.record_status = 'ACTIVE'"
                            + " AND e.emp_lbr_flag = 'N' AND (:all = 'Y' OR e.location_id IN"
                            + " (:locations))",
                        new MapSqlParameterSource("eis", eis.strip())
                                .addValue("all", policy.isAtHeadquarters(context) ? "Y" : "N")
                                .addValue("locations", OfficeScope.ids(context)));
        if (rows.size() != 1) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private static BasicPayService.Change change(
            long employee,
            BigDecimal basic,
            LocalDate effective,
            String eventType,
            String reason,
            String order,
            Long approval,
            String by,
            String entity,
            String key) {
        return new BasicPayService.Change(
                employee,
                basic,
                effective,
                eventType,
                "ENT",
                reason,
                cut(order, 100),
                null,
                null,
                approval,
                by,
                "INCREMENT",
                entity,
                key);
    }

    private static BigDecimal amount(String v) {
        return v == null || v.isEmpty() ? null : new BigDecimal(v);
    }

    private static Date date(String v) {
        return v == null || v.isEmpty() ? null : Date.valueOf(LocalDate.parse(v));
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private static FciException entry(String what) {
        return new FciException(IncrementErrorCode.ENTRY, new Object[] {what}, List.of(), null);
    }

    private static FciException flagged(String eis, Flag flag) {
        return new FciException(
                IncrementErrorCode.FLAGGED, new Object[] {eis, flag.name()}, List.of(), null);
    }

    private static FciException notConfigured(String what) {
        return new FciException(
                IncrementErrorCode.NOT_CONFIGURED, new Object[] {what}, List.of(), null);
    }
}
