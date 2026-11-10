package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.time.LocalDate;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The last approval of an I-06 order (plan 12 phase 12b; SRS-14-1, SRS-14-10, SRS-14-11, SRS-14-14,
 * SRS-14-15): the order is written through {@link PenaltyWriter} (the Penalty ID given when the
 * Maker recorded it, US-GAP-229; schedule, audit; the order copy stays on the request, reached
 * through the order's approval) and - when it breaches an M-14 floor in force - the {@code
 * net_pay_floor_override} with the approval that allowed it. A returned or rejected request writes
 * nothing: it stays in its approval item with the reason (PN-17).
 */
@Component
public class PenaltyOrderEffect {

    static final String ORDER_ENTITY = PenaltyWriter.ORDER_ENTITY;

    /** SRS l.2995: the reason of an instalment the Maker deferred to F&amp;F at entry. */
    static final String DEFERRED_AT_ENTRY =
            "Deferred to Full & Final Settlement: the recovery would breach the net-pay floor"
                    + " (SRS-14-10)";

    private final PenaltyWriter writer;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PenaltyRepositories.Orders orders;
    private final PenaltyRepositories.Lines lines;

    public PenaltyOrderEffect(
            PenaltyWriter writer,
            JdbcTemplate jdbc,
            ObjectMapper json,
            PenaltyRepositories.Orders orders,
            PenaltyRepositories.Lines lines) {
        this.writer = writer;
        this.jdbc = jdbc;
        this.json = json;
        this.orders = orders;
        this.lines = lines;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(PenaltyOrderService.ENTITY) || !decision.approved()) {
            return;
        }
        JsonNode p;
        try {
            p = json.readTree(decision.payload());
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Unreadable penalty order " + decision.approvalId(), ex);
        }
        long employeeId = p.path("employeeId").asLong();
        String start = p.path("startYymm").asString();
        PenaltyOrder order =
                writer.create(
                        new PenaltyWriter.Approved(
                                employeeId,
                                p.path("nature").asString(),
                                p.path("orderReference").asString(),
                                LocalDate.parse(p.path("effectiveDate").asString()),
                                // A labour order that recovers nothing carries no terms (SRS-14-8).
                                p.hasNonNull("amount") ? p.path("amount").decimalValue() : null,
                                p.hasNonNull("amount") ? p.path("instalments").asInt() : null,
                                p.hasNonNull("amount") ? start : null,
                                p.hasNonNull("periodTo")
                                        ? LocalDate.parse(p.path("periodTo").asString())
                                        : null,
                                p.path("backdated").asBoolean(),
                                null,
                                null,
                                // US-GAP-229: the Penalty ID given when the Maker recorded the
                                // order (an
                                // item submitted before V076_1 carries none and is numbered now).
                                p.hasNonNull("penaltyRef")
                                        ? p.path("penaltyRef").asString()
                                        : null),
                        decision.checkerLoginId(),
                        decision.approvalId(),
                        decision.decidedAt());
        if (p.path("deferToFnf").asBoolean()) {
            // SRS l.2995: deferred to F&F - nothing is recovered in service; the whole balance
            // stays on the order for the settlement's penalty clearance (PenaltyClearance).
            for (PenaltyRecoveryLine l : lines.findByOrderIdOrderByPayrollYymm(order.getId())) {
                l.deferred(DEFERRED_AT_ENTRY);
                lines.save(l);
            }
            order.status(PenaltyOrder.Status.PAUSED_FNF);
            orders.save(order);
            return;
        }
        JsonNode floor = p.path("floor");
        if (p.path("belowFloor").asBoolean() && !floor.isNull() && !floor.isMissingNode()) {
            jdbc.update(
                    "INSERT INTO net_pay_floor_override (employee_id, payroll_yymm,"
                        + " computed_net_pay, floor_amount, approval_id) VALUES (?, ?, ?, ?, ?)",
                    employeeId,
                    start,
                    p.path("netAfter").decimalValue(),
                    floor.decimalValue(),
                    decision.approvalId());
        }
    }
}
