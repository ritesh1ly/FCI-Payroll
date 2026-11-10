package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.penalty.api.PenaltyPayEffects;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.business.PenaltyRules;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.business.model.PenaltyRevoke;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Penalty Revoke and cancellation (plan 12 phase 12e; SRS-14-6, SRS-14-14, SRS-14-16, SRS-14-17,
 * SRS-14-20, BR-PEN2-006, BR-PEN2-008; D12-9, D12-10), both under maker-checker against the Penalty
 * ID, with the revoke or cancellation order attached:
 *
 * <ul>
 *   <li>a revoke waives the order in full or in part, by amount (the months still due end earlier)
 *       or by period (route I06_REVOKE); a cancellation ends the order as though never imposed
 *       (route I06_CANCEL, SRS-G-37);
 *   <li>what was already recovered within the waiver is due back as a reversing arrear: raised on
 *       I-08 through the one retrospective mechanism (module 13, phase 13g, D13-14) with each
 *       month's refund under the nature's recovery head, for the Payroll Maker and Checker
 *       (SRS-21-10); a recovery head not configured refuses the decision (R-RULE);
 *   <li>revoking or cancelling an increment stop restores the increment from its original due date
 *       with the arrear ({@link PenaltyPayEffects}, in the decision's transaction; SRS-14-17,
 *       SRS-21-10); what cannot be restored by itself goes to X-05. A separate Basic-reduction
 *       order is never touched by it, and that order ends on its own full revoke;
 *   <li>a revoke is open for an order applied from HRMS as well: SRS-14-16 says "for all payrolls"
 *       and SRS-14-17 is about employees, whose orders only come from HRMS. Cancellation stays with
 *       the orders keyed on I-06.
 * </ul>
 */
@Service
@Loggable
public class PenaltyChangeService {

    static final String REVOKE = "penalty_revoke";
    static final String CANCEL = "penalty_cancel";
    static final String RECOVERY_HEADS = "PENALTY_RECOVERY";

    /** X-05: an increment or Basic a waiver could not settle by itself. */
    static final String INCREMENT_PENDING = "PENALTY_INCREMENT_RESTORE";

    static final String BASIC_REDUCTION = "BASIC_REDUCTION";
    static final String INCREMENT_STOP = "INCREMENT_STOP";
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");
    private static final Set<PenaltyOrder.Status> OPEN =
            EnumSet.of(
                    PenaltyOrder.Status.ACTIVE,
                    PenaltyOrder.Status.PARTLY_REVOKED,
                    PenaltyOrder.Status.PAUSED_FNF);

    /** What the Maker enters from the revoke order (amount, or period, or neither for FULL). */
    public record Revoke(
            PenaltyRevoke.Type type,
            BigDecimal amount,
            String fromYymm,
            String toYymm,
            String orderReference,
            LocalDate orderDate,
            String reason) {}

    /** The source of an order applied from an HRMS penalty through I-21. */
    static final String HRMS = "HRMS";

    private final PenaltyRepositories.Orders orders;
    private final PenaltyRepositories.Lines lines;
    private final PenaltyRepositories.Revokes revokes;
    private final PenaltyQueryService queries;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final ExceptionWorklist worklist;
    private final AuditService audit;
    private final RetroEvents retro;
    private final PenaltyPayEffects pay;
    private final ReferenceData references;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public PenaltyChangeService(
            PenaltyRepositories.Orders orders,
            PenaltyRepositories.Lines lines,
            PenaltyRepositories.Revokes revokes,
            PenaltyQueryService queries,
            DocumentStore documents,
            MakerChecker approvals,
            ExceptionWorklist worklist,
            AuditService audit,
            RetroEvents retro,
            PenaltyPayEffects pay,
            ReferenceData references,
            ObjectMapper json,
            Clock clock) {
        this.orders = orders;
        this.lines = lines;
        this.revokes = revokes;
        this.queries = queries;
        this.documents = documents;
        this.approvals = approvals;
        this.worklist = worklist;
        this.audit = audit;
        this.retro = retro;
        this.pay = pay;
        this.references = references;
        this.json = json;
        this.clock = clock;
    }

    /** SRS-14-16: a revoke of the order, with its order copy, for the Checker. */
    @Transactional
    public SubmittedApproval revoke(
            AccessContext context, long orderId, Revoke r, PenaltyOrderService.Copy copy) {
        PenaltyOrder order = open(context, orderId, true);
        if (r == null || r.type() == null) {
            throw invalid("choose a full or a partial revoke");
        }
        text(r.orderReference(), 100, "the revoke order reference");
        text(r.reason(), 1000, "the reason", true);
        if (r.orderDate() == null) {
            throw invalid("the revoke order date is required");
        }
        boolean byAmount = r.amount() != null;
        boolean byPeriod = r.fromYymm() != null || r.toYymm() != null;
        LocalDate from = null;
        LocalDate to = null;
        BigDecimal waived = r.amount();
        if (r.type() == PenaltyRevoke.Type.FULL) {
            if (byAmount || byPeriod) {
                throw invalid("a full revoke waives the whole order: no amount or period");
            }
            // chk_penalty_revoke_scope: the whole order, by its amount or (no amount) its period.
            waived = order.getTotalRecoveryAmount();
            if (waived == null) {
                from = order.getPeriodFrom();
                to = order.getPeriodTo();
            }
        } else if (byAmount == byPeriod) {
            throw invalid("a partial revoke waives an amount or a period, one of them");
        } else if (byAmount) {
            if (r.amount().signum() <= 0
                    || r.amount().stripTrailingZeros().scale() > 2
                    || order.getTotalRecoveryAmount() == null
                    || r.amount().compareTo(order.getTotalRecoveryAmount()) > 0) {
                throw invalid("the amount is above zero and within the order's amount");
            }
        } else {
            YearMonth f =
                    PenaltyRules.month(r.fromYymm())
                            .orElseThrow(() -> invalid("the period is two months YYYYMM"));
            YearMonth t =
                    PenaltyRules.month(r.toYymm())
                            .orElseThrow(() -> invalid("the period is two months YYYYMM"));
            if (t.isBefore(f)) {
                throw invalid("the period ends before it starts");
            }
            from = f.atDay(1);
            to = t.atEndOfMonth();
            if (outside(order, from, to)) {
                // US-GAP-234 (SRS-14-16, I-06 Penalty Revoke "Amount or Period"): the waived period
                // is a period of the penalty, as the waived amount is within the order's amount.
                throw invalid("the period is outside the order's period");
            }
        }
        requireCopy(context, REVOKE, String.valueOf(orderId), copy);
        PenaltyRevoke saved =
                revokes.save(
                        PenaltyRevoke.submitted(
                                order.getId(),
                                r.type(),
                                r.orderReference().strip(),
                                r.orderDate(),
                                r.reason().strip(),
                                waived,
                                from,
                                to,
                                clock.instant()));
        attach(context, REVOKE, String.valueOf(saved.getId()), copy);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("penaltyRef", order.getPenaltyRef());
        payload.put("type", r.type().name());
        payload.put("amount", r.amount());
        payload.put("from", r.fromYymm());
        payload.put("to", r.toYymm());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        PenaltyOrderService.SCREEN,
                                        "REVOKE",
                                        REVOKE,
                                        String.valueOf(saved.getId()),
                                        ActionType.UPDATE,
                                        context.locationId(),
                                        json.writeValueAsString(payload),
                                        r.orderDate(),
                                        r.type().name()
                                                + " revoke of "
                                                + order.getPenaltyRef()
                                                + ": "
                                                + r.reason().strip())
                                .withImpact(
                                        r.amount() == null
                                                ? order.getTotalRecoveryAmount()
                                                : r.amount()),
                        (OtpProof) null);
        saved.approval(item.id());
        revokes.save(saved);
        return item;
    }

    /** SRS-14-20, SRS-G-37: the cancellation of the order, with its order copy, for the Checker. */
    @Transactional
    public SubmittedApproval cancel(
            AccessContext context, long orderId, String reason, PenaltyOrderService.Copy copy) {
        PenaltyOrder order = open(context, orderId, false);
        text(reason, 1000, "the reason", true);
        requireCopy(context, CANCEL, String.valueOf(orderId), copy);
        String key = order.getId() + "|" + UUID.randomUUID().toString().substring(0, 8);
        attach(context, CANCEL, key, copy);
        return approvals.submit(
                context,
                new Submission(
                                PenaltyOrderService.SCREEN,
                                "CANCEL",
                                CANCEL,
                                key,
                                ActionType.UPDATE,
                                context.locationId(),
                                json.writeValueAsString(
                                        Map.of(
                                                "orderId",
                                                order.getId(),
                                                "penaltyRef",
                                                order.getPenaltyRef())),
                                LocalDate.now(clock),
                                "Cancellation of " + order.getPenaltyRef() + ": " + reason.strip())
                        .withImpact(order.getTotalRecoveryAmount()),
                (OtpProof) null);
    }

    // ---------------------------------------------------------------- the decisions

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(REVOKE)) {
            revoked(decision);
        } else if (decision.concerns(CANCEL) && decision.approved()) {
            long orderId = Long.parseLong(decision.entityKey().split("\\|")[0]);
            PenaltyOrder order = orders.findById(orderId).orElseThrow();
            waive(
                    order,
                    PenaltyRules.waiveAmount(months(order), null),
                    null,
                    null,
                    decision,
                    new Source(PenaltyWriter.ORDER_ENTITY, order.getPenaltyRef() + "/CANCEL"),
                    "cancellation");
            order.status(PenaltyOrder.Status.CANCELLED);
            orders.save(order);
            if (INCREMENT_STOP.equals(order.getPenaltyNature())) {
                // SRS-21-10 (l.3234): a cancellation restores a stopped increment as a waiver does.
                orders.flush();
                restore(order, order.getPeriodFrom(), order.getPeriodTo(), "cancelled", decision);
            }
            record(order, decision, "cancelled: " + decision.reason());
        }
    }

    private void revoked(ApprovalDecided decision) {
        PenaltyRevoke r = revokes.findById(Long.parseLong(decision.entityKey())).orElseThrow();
        if (!decision.approved()) {
            r.decided(
                    decision.outcome() == ApprovalStatus.RETURNED
                            ? PenaltyRevoke.Status.RETURNED
                            : PenaltyRevoke.Status.CANCELLED);
            revokes.save(r);
            return;
        }
        PenaltyOrder order = orders.findById(r.getOrderId()).orElseThrow();
        List<PenaltyRules.Month> months = months(order);
        String from = r.getWaivedPeriodFrom() == null ? null : yymm(r.getWaivedPeriodFrom());
        String to = r.getWaivedPeriodTo() == null ? null : yymm(r.getWaivedPeriodTo());
        PenaltyRules.Waiver w =
                r.type() == PenaltyRevoke.Type.FULL
                        ? PenaltyRules.waiveAmount(months, null)
                        : r.getWaivedPeriodFrom() != null
                                ? PenaltyRules.waivePeriod(months, from, to)
                                : PenaltyRules.waiveAmount(months, r.getWaivedAmount());
        boolean period = r.type() != PenaltyRevoke.Type.FULL && from != null;
        waive(
                order,
                w,
                period ? from : null,
                period ? to : null,
                decision,
                new Source(REVOKE, String.valueOf(r.getId())),
                "revoke " + r.getRevokeOrderRef());
        order.status(
                r.type() == PenaltyRevoke.Type.FULL
                        ? PenaltyOrder.Status.REVOKED
                        : PenaltyOrder.Status.PARTLY_REVOKED);
        orders.save(order);
        r.decided(PenaltyRevoke.Status.APPROVED);
        revokes.save(r);
        payEffect(order, r, decision);
        record(order, decision, r.type().name() + " revoke " + r.getRevokeOrderRef());
    }

    /**
     * SRS-14-17, BR-EMPR-021: what the revoke does to the Basic. The stop and the waiver must be in
     * the database first: the increment module reads them through SQL in this transaction.
     */
    private void payEffect(PenaltyOrder order, PenaltyRevoke r, ApprovalDecided decision) {
        String nature = order.getPenaltyNature();
        if (INCREMENT_STOP.equals(nature)) {
            orders.flush();
            boolean full = r.type() == PenaltyRevoke.Type.FULL;
            PenaltyPayEffects.Restored done =
                    restore(
                            order,
                            full ? order.getPeriodFrom() : r.getWaivedPeriodFrom(),
                            full ? order.getPeriodTo() : r.getWaivedPeriodTo(),
                            r.getRevokeOrderRef(),
                            decision);
            if (done.incrementId() != null) {
                r.restored(done.incrementId());
                revokes.save(r);
            }
        } else if (BASIC_REDUCTION.equals(nature)) {
            orders.flush();
            String open =
                    r.type() == PenaltyRevoke.Type.FULL
                            ? pay.restoreBasic(
                                    waived(
                                            order,
                                            order.getPeriodFrom(),
                                            order.getPeriodTo(),
                                            r.getRevokeOrderRef(),
                                            decision),
                                    order.getOrderReference())
                            // OPEN-QUESTION (PENDING I06-BASIC-PART): what a partial waiver of a
                            // Basic reduction does to the Basic is not stated.
                            : "A partial waiver of a Basic reduction: refix on I-05.";
            if (open != null) {
                pending(order, decision, INCREMENT_PENDING, order.getPenaltyRef() + ": " + open);
            }
        }
    }

    private PenaltyPayEffects.Restored restore(
            PenaltyOrder order,
            LocalDate from,
            LocalDate to,
            String reference,
            ApprovalDecided decision) {
        PenaltyPayEffects.Restored done =
                pay.restoreIncrement(waived(order, from, to, reference, decision));
        if (done.unresolved() != null) {
            pending(
                    order,
                    decision,
                    INCREMENT_PENDING,
                    order.getPenaltyRef() + ": " + done.unresolved());
        }
        return done;
    }

    private static PenaltyPayEffects.Waived waived(
            PenaltyOrder order,
            LocalDate from,
            LocalDate to,
            String reference,
            ApprovalDecided decision) {
        return new PenaltyPayEffects.Waived(
                order.getEmployeeId(),
                order.getPenaltyRef(),
                reference,
                from,
                to,
                decision.approvalId(),
                decision.checkerLoginId());
    }

    /** The record a reversing arrear belongs to (one open arrear event per record). */
    private record Source(String entity, String key) {}

    @SuppressWarnings("java:S107") // the waiver, its window, the decision and its source
    private void waive(
            PenaltyOrder order,
            PenaltyRules.Waiver w,
            String from,
            String to,
            ApprovalDecided decision,
            Source source,
            String what) {
        List<PenaltyRecoveryLine> schedule = lines.findByOrderIdOrderByPayrollYymm(order.getId());
        List<PenaltyRules.Month> before = months(order);
        for (int i : w.waive()) {
            PenaltyRecoveryLine l = schedule.get(i);
            l.waived();
            lines.save(l);
        }
        w.reduce()
                .ifPresent(
                        red -> {
                            PenaltyRecoveryLine l = schedule.get(red.index());
                            l.reduced(red.scheduled());
                            lines.save(l);
                        });
        if (w.reversal().signum() > 0) {
            // SRS-14-16 / SRS-21-10 (D13-14): the refund of what was recovered, month by month,
            // through the one retrospective mechanism; the Checker approves it on I-08.
            int head = recoveryHead(order.getPenaltyNature());
            List<RetroEvents.Line> refund =
                    PenaltyRules.refunds(before, w.reversal(), from, to).stream()
                            .map(f -> new RetroEvents.Line(f.payrollYymm(), head, f.amount()))
                            .toList();
            retro.known(
                    new RetroEvents.Known(
                            order.getEmployeeId(),
                            "PENALTY",
                            "PENALTY",
                            source.entity(),
                            source.key(),
                            "Reversing arrear of " + order.getPenaltyRef() + " (" + what + ")",
                            decision.checkerLoginId(),
                            refund));
        }
    }

    /** The nature's recovery head in force today (M-16 PENALTY_RECOVERY); never a default. */
    private int recoveryHead(String nature) {
        return references
                .list(RECOVERY_HEADS)
                .flatMap(
                        l ->
                                l.values().stream()
                                        .filter(
                                                v ->
                                                        v.active()
                                                                && v.code().equals(nature)
                                                                && v.numeric() != null)
                                        .findFirst())
                .map(v -> v.numeric().intValueExact())
                .orElseThrow(
                        () ->
                                new FciException(
                                        PenaltyErrorCode.NOT_CONFIGURED,
                                        new Object[] {
                                            "The recovery head of "
                                                    + nature
                                                    + " (M-16 "
                                                    + RECOVERY_HEADS
                                                    + ")"
                                        },
                                        List.of(),
                                        null));
    }

    private void pending(PenaltyOrder order, ApprovalDecided decision, String code, String detail) {
        worklist.raise(
                decision.checkerLoginId(),
                new ExceptionWorklist.NewEntry(
                        null,
                        order.getEmployeeId(),
                        PenaltyOrderService.SCREEN,
                        code,
                        "WARNING",
                        null,
                        null,
                        PenaltyWriter.ORDER_ENTITY,
                        order.getPenaltyRef(),
                        "ENT",
                        PenaltyOrderService.SCREEN,
                        detail));
    }

    private void record(PenaltyOrder order, ApprovalDecided decision, String what) {
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                PenaltyWriter.ORDER_ENTITY,
                                order.getPenaltyRef(),
                                "order_status",
                                null,
                                order.status().name(),
                                order.getOrderReference() + ": " + what)
                        .approval(decision.approvalId())
                        .module(PenaltyOrderService.SCREEN));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The order in the user's scope (404 outside it) and still open to a change. An order applied
     * from an HRMS penalty through I-21 stays HRMS's to cancel (R-SRC, SRS-14-1), but it is waived
     * here: Penalty Revoke is the Payroll Maker's, for all payrolls (SRS-14-16, SRS-14-17).
     */
    private PenaltyOrder open(AccessContext context, long orderId, boolean hrmsAllowed) {
        queries.detail(context, orderId);
        PenaltyOrder order = orders.findById(orderId).orElseThrow();
        if (!hrmsAllowed && HRMS.equals(order.getPenaltySource())) {
            throw new FciException(
                    PenaltyErrorCode.HRMS_ONLY,
                    new Object[] {"penalty order's cancellation"},
                    List.of(),
                    null);
        }
        if (!OPEN.contains(order.status())) {
            throw invalid("the order is " + order.status().name() + " and cannot change");
        }
        return order;
    }

    private List<PenaltyRules.Month> months(PenaltyOrder order) {
        return lines.findByOrderIdOrderByPayrollYymm(order.getId()).stream()
                .map(
                        l ->
                                new PenaltyRules.Month(
                                        l.getPayrollYymm(),
                                        l.getScheduledAmount(),
                                        l.getRecoveredAmount(),
                                        l.status() == PenaltyRecoveryLine.Status.PENDING
                                                || l.status()
                                                        == PenaltyRecoveryLine.Status.DEFERRED))
                .toList();
    }

    private void attach(
            AccessContext context, String entity, String key, PenaltyOrderService.Copy copy) {
        documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        entity,
                        key,
                        copy.documentType(),
                        copy.reference(),
                        copy.orderDate(),
                        copy.issuingAuthority(),
                        copy.fileName(),
                        copy.content(),
                        true));
    }

    /** The order copy is mandatory; a refusal is audited (SRS-20-27). */
    private void requireCopy(
            AccessContext context, String entity, String key, PenaltyOrderService.Copy copy) {
        if (copy == null || copy.content() == null || copy.content().length == 0) {
            audit.submissionBlocked(
                    context.loginId(), entity, key, PenaltyOrderService.SCREEN, "order copy");
            throw new FciException(PenaltyErrorCode.ORDER_COPY);
        }
    }

    private static void text(String v, int max, String what) {
        text(v, max, what, false);
    }

    /** A required text; {@code multiLine}: entered in a text area, line breaks kept (R-ASCII). */
    private static void text(String v, int max, String what, boolean multiLine) {
        String s = v == null ? "" : v.strip();
        boolean printable =
                multiLine ? InputPatterns.isPrintableText(s) : PRINTABLE.matcher(s).matches();
        if (s.isEmpty() || s.length() > max || !printable) {
            throw invalid(what + " is required, printable, at most " + max + " characters");
        }
    }

    /**
     * US-GAP-234: true when {@code from} … {@code to} shares no month with the order's period (an
     * order with no period end, an open increment stop, runs on).
     */
    static boolean outside(PenaltyOrder order, LocalDate from, LocalDate to) {
        LocalDate start = order.getPeriodFrom();
        LocalDate end = order.getPeriodTo();
        if (start == null) {
            return false;
        }
        return to.isBefore(start.withDayOfMonth(1))
                || (end != null && from.isAfter(end.withDayOfMonth(end.lengthOfMonth())));
    }

    private static String yymm(LocalDate d) {
        return String.format("%04d%02d", d.getYear(), d.getMonthValue());
    }

    private static FciException invalid(String why) {
        return new FciException(PenaltyErrorCode.ORDER, new Object[] {why}, List.of(), null);
    }
}
