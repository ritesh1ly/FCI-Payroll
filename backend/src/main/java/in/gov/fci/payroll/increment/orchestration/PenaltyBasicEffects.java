package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.increment.business.IncrementRules;
import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
import in.gov.fci.payroll.penalty.api.IncrementStops;
import in.gov.fci.payroll.penalty.api.PenaltyPayEffects;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The Basic effects of a penalty order (SRS-14-8, SRS-14-13, SRS-14-17, SRS-13-26, SRS-21-10;
 * BR-EMPR-021, D12-10), in the transaction of the approval:
 *
 * <ul>
 *   <li><b>A waived increment stop.</b> A stopped member's next due date never moved (the run flags
 *       the member BARRED and writes nothing), so it is the original due date. Each due date from
 *       it that falls in the waived period and is not after this month is given as the run would
 *       give it, on the Basic of that date, through {@link IncrementRunService}: the Basic event,
 *       the arrear for past months and the next due date a year on. A later due date stays for the
 *       run. A labourer's increment waits for the labour rules (SRS-2-54, OQ-9).
 *   <li><b>A reduced Basic.</b> The event "reduction under a penalty order"; a past date raises the
 *       recovery through the arrear engine. It ends on its own revoke and on nothing else.
 *   <li><b>Basic events after the restored date</b> stand as entered: what they should become is a
 *       fixation, so the case goes to X-05 for I-05. Nothing here guesses a new Basic.
 * </ul>
 *
 * <p>Pay effect on C-02: the Basic these events write is read by the next computation; nothing is
 * recomputed here. Deterministic: same order, same dates, same result.
 */
@Component
public class PenaltyBasicEffects implements PenaltyPayEffects {

    static final String REDUCTION = "PENALTY_REDUCTION";
    static final String RESTORATION = "PENALTY_RESTORATION";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NamedParameterJdbcTemplate named;
    private final IncrementRunService run;
    private final BasicPayService basic;
    private final IncrementStops stops;
    private final Scales scales;
    private final Clock clock;

    public PenaltyBasicEffects(
            NamedParameterJdbcTemplate named,
            IncrementRunService run,
            BasicPayService basic,
            IncrementStops stops,
            Scales scales,
            Clock clock) {
        this.named = named;
        this.run = run;
        this.basic = basic;
        this.stops = stops;
        this.scales = scales;
        this.clock = clock;
    }

    @Override
    public Restored restoreIncrement(Waived w) {
        Map<String, Object> m = member(w.employeeId());
        if ("Y".equals(m.get("EMP_LBR_FLAG"))) {
            return new Restored(
                    null,
                    0,
                    "A labourer's increment is not restored here: the due-date basis waits for FCI"
                            + " (SRS-2-54, OQ-9).");
        }
        if (m.get("NEXT_DUE_DATE") == null) {
            return new Restored(null, 0, "The member has no increment due date to restore from.");
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate day = Dates.local(m.get("NEXT_DUE_DATE"));
        Long first = null;
        int count = 0;
        LocalDate last = null;
        while (covered(day, w) && !day.isAfter(monthEnd)) {
            List<String> others = stops.stopping(w.employeeId(), day);
            if (!others.isEmpty()) {
                return new Restored(
                        first,
                        count,
                        "The due date " + day + " stays stopped by " + String.join(", ", others));
            }
            BigDecimal old = basic.basicOn(w.employeeId(), day);
            if (old == null) {
                old = (BigDecimal) m.get("CURRENT_BASIC");
            }
            IncrementRunService.Proposal p =
                    run.propose(w.employeeId(), old, day, (Number) m.get("LOCATION_ID"));
            String blocked =
                    p.flags().stream()
                            .filter(IncrementRunService.BLOCKING::contains)
                            .map(Enum::name)
                            .collect(Collectors.joining(", "));
            String period = IncrementRules.duePeriod(day);
            if (!blocked.isEmpty()) {
                return new Restored(first, count, "The increment due " + day + ": " + blocked);
            }
            if (run.exists(w.employeeId(), period, p.type())) {
                return new Restored(
                        first, count, "An increment of the due period " + period + " exists.");
            }
            long id =
                    run.record(
                            w.employeeId(),
                            day,
                            period,
                            p.type(),
                            old,
                            p.revised(),
                            w.approvalId(),
                            w.by(),
                            p.flags().contains(Flag.STAGNATION)
                                    ? "STAGNATION_INCREMENT"
                                    : "INCREMENT",
                            "restored on the waiver of "
                                    + w.penaltyRef()
                                    + " by order "
                                    + w.orderReference());
            first = first == null ? Long.valueOf(id) : first;
            count++;
            last = day;
            day = IncrementRules.nextDue(day);
        }
        return new Restored(first, count, last == null ? null : laterEvents(w.employeeId(), last));
    }

    @Override
    public String reductionDefect(long employeeId, LocalDate effectiveFrom, BigDecimal reduced) {
        BigDecimal old = basic.basicOn(employeeId, effectiveFrom);
        if (old == null) {
            old = (BigDecimal) member(employeeId).get("CURRENT_BASIC");
        }
        if (old == null) {
            return "the member has no Basic on " + effectiveFrom;
        }
        if (reduced.signum() <= 0 || reduced.compareTo(old) >= 0) {
            return "the reduced Basic is not below the Basic of " + old.toPlainString();
        }
        return scales.ofMember(employeeId, effectiveFrom)
                .filter(s -> !s.stages().isEmpty())
                .flatMap(s -> IncrementRules.stage(reduced, s.stages()))
                .map(f -> "the reduced Basic is not a stage of the member's scale")
                .orElse(null);
    }

    @Override
    public void reduceBasic(Reduction r) {
        basic.apply(
                new BasicPayService.Change(
                        r.employeeId(),
                        r.basic(),
                        r.effectiveFrom(),
                        REDUCTION,
                        r.sourceCode(),
                        "Reduction under penalty order " + r.penaltyRef(),
                        r.orderReference(),
                        r.hrmsRequestId(),
                        r.hrmsTaskId(),
                        r.approvalId(),
                        r.by(),
                        "PENALTY",
                        "penalty_order",
                        r.penaltyRef()));
    }

    @Override
    public String restoreBasic(Waived w, String order) {
        List<Map<String, Object>> reduced =
                named.queryForList(
                        "SELECT old_basic_amount, effective_from FROM employee_basic_history WHERE"
                                + " employee_id = :employee AND event_type = :type AND"
                                + " order_reference = :order ORDER BY effective_from DESC,"
                                + " event_sequence DESC FETCH FIRST 1 ROWS ONLY",
                        new MapSqlParameterSource("employee", w.employeeId())
                                .addValue("type", REDUCTION)
                                .addValue("order", order));
        if (reduced.isEmpty()) {
            return null;
        }
        LocalDate from = Dates.local(reduced.getFirst().get("EFFECTIVE_FROM"));
        basic.apply(
                new BasicPayService.Change(
                        w.employeeId(),
                        (BigDecimal) reduced.getFirst().get("OLD_BASIC_AMOUNT"),
                        from,
                        RESTORATION,
                        "ENT",
                        "Basic restored on the waiver of " + w.penaltyRef(),
                        w.orderReference(),
                        null,
                        null,
                        w.approvalId(),
                        w.by(),
                        "PENALTY",
                        "penalty_order",
                        w.penaltyRef()));
        return laterEvents(w.employeeId(), from);
    }

    private Map<String, Object> member(long employee) {
        return named.queryForMap(
                "SELECT e.emp_lbr_flag, e.location_id, e.current_basic, d.next_due_date FROM"
                        + " employee e LEFT JOIN employee_increment_due d ON d.employee_id ="
                        + " e.employee_id WHERE e.employee_id = :employee",
                new MapSqlParameterSource("employee", employee));
    }

    /** The X-05 text when Basic events after {@code day} stand as entered, else null. */
    private String laterEvents(long employee, LocalDate day) {
        Long later =
                named.queryForObject(
                        "SELECT COUNT(*) FROM employee_basic_history WHERE employee_id = :employee"
                                + " AND effective_from > :day",
                        new MapSqlParameterSource("employee", employee)
                                .addValue("day", Date.valueOf(day)),
                        Long.class);
        return RowCounts.of(later) > 0
                ? "Basic events after " + day + " stand as entered: refix them on I-05."
                : null;
    }

    private static boolean covered(LocalDate day, Waived w) {
        return !day.isBefore(w.from()) && (w.to() == null || !day.isAfter(w.to()));
    }
}
