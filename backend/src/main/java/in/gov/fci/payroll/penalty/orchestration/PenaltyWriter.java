package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.penalty.business.PenaltyRules;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes an approved order (plan 12 phases 12b / 12c; SRS-14-1, SRS-14-14, SRS-14-15): the order
 * ACTIVE with its Penalty ID, for a recovering nature its schedule PENDING month by month, audited
 * with the Penalty ID and the order reference. The one path for an order entered on I-06 and one
 * received from HRMS through I-21.
 *
 * <p>SRS-14-15, use case "Capture and Recover a Penalty Order" (US-GAP-229): the Penalty ID is
 * assigned when the order is recorded - on I-06 at the Maker's submission ({@link #nextPenaltyId}),
 * carried in the approval item to this writer; an order applied from an HRMS penalty is recorded in
 * Payroll when its I-21 task is approved and is numbered then. The number is the sequence {@code
 * penalty_ref_seq} (V076_1), never the order's identity.
 */
@Component
public class PenaltyWriter {

    static final String ORDER_ENTITY = "penalty_order";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * An approved order.
     *
     * @param amount the recovery amount, null for a nature that recovers nothing
     * @param startYymm the first month of recovery, null for a nature that recovers nothing
     * @param periodTo the end of an increment stop, null when open or derived from the schedule
     * @param penaltyRef the Penalty ID assigned when the order was recorded, null to number it now
     */
    public record Approved(
            long employeeId,
            String nature,
            String orderReference,
            LocalDate effectiveDate,
            BigDecimal amount,
            Integer instalments,
            String startYymm,
            LocalDate periodTo,
            boolean backdated,
            Long hrmsTaskId,
            String hrmsRequestId,
            String penaltyRef) {}

    private final PenaltyRepositories.Orders orders;
    private final PenaltyRepositories.Lines lines;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public PenaltyWriter(
            PenaltyRepositories.Orders orders,
            PenaltyRepositories.Lines lines,
            AuditService audit,
            JdbcTemplate jdbc) {
        this.orders = orders;
        this.lines = lines;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * SRS-14-15, BR-PEN2-007 (US-GAP-229): the next system-generated Penalty ID, of the year of
     * {@code day} (India's date), from the sequence {@code penalty_ref_seq}.
     */
    public static String nextPenaltyId(JdbcTemplate jdbc, LocalDate day) {
        Long next = jdbc.queryForObject("SELECT penalty_ref_seq.NEXTVAL FROM dual", Long.class);
        if (next == null) {
            throw new IllegalStateException("penalty_ref_seq returned no value");
        }
        return PenaltyRules.penaltyId(day.getYear(), next);
    }

    /** Writes the order and its schedule; returns it with its Penalty ID. */
    public PenaltyOrder create(Approved a, String checker, long approvalId, Instant decidedAt) {
        boolean recovers = a.amount() != null && a.instalments() != null && a.startYymm() != null;
        LocalDate from =
                recovers
                        ? PenaltyRules.month(a.startYymm()).orElseThrow().atDay(1)
                        : a.effectiveDate();
        String ref =
                a.penaltyRef() != null
                        ? a.penaltyRef()
                        : nextPenaltyId(jdbc, LocalDate.ofInstant(decidedAt, IST));
        PenaltyOrder order =
                orders.save(
                        PenaltyOrder.approved(
                                ref,
                                a.employeeId(),
                                a.orderReference(),
                                a.nature(),
                                a.amount(),
                                a.instalments(),
                                from,
                                a.periodTo(),
                                a.effectiveDate(),
                                a.backdated(),
                                a.hrmsTaskId(),
                                a.hrmsRequestId(),
                                checker,
                                approvalId));
        if (recovers) {
            String last = a.startYymm();
            for (PenaltyRules.Line line :
                    PenaltyRules.schedule(a.amount(), a.instalments(), a.startYymm())) {
                lines.save(
                        PenaltyRecoveryLine.pending(
                                order.getId(), line.payrollYymm(), line.amount()));
                last = line.payrollYymm();
            }
            order.period(PenaltyRules.month(last).orElseThrow().atEndOfMonth());
        }
        orders.save(order);
        audit.record(
                checker,
                AuditService.Entry.change(
                                ORDER_ENTITY,
                                ref,
                                "order_status",
                                null,
                                PenaltyOrder.Status.ACTIVE.name(),
                                a.nature()
                                        + " order "
                                        + a.orderReference()
                                        + (recovers
                                                ? " imposed: "
                                                        + a.amount().toPlainString()
                                                        + " in "
                                                        + a.instalments()
                                                        + " instalments from "
                                                        + a.startYymm()
                                                : " imposed from " + a.effectiveDate())
                                        + (a.hrmsTaskId() == null ? "" : " (HRMS, I-21)"))
                        .approval(approvalId)
                        .module(PenaltyOrderService.SCREEN));
        return order;
    }
}
