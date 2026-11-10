package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.CafeteriaElection;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The arrear of a cafeteria selection received after the freeze (SRS-11-16, SRS-G-33, BR-CYC-010;
 * DDL {@code cafeteria_election.arrear_event_id}, status ARREAR_RAISED). Each locked month the
 * member was paid in, from the selection's start date, is set against what the versions in force
 * make due: the difference of each cafeteria pay code goes to I-08 as a known event ({@link
 * RetroEvents#known}; the Maker submits it and the Checker approves it like any other).
 *
 * <p>The earnings recompute of the retrospective engine cannot find it: it compares only the
 * pay-linked groups (D13-7), and the cafeteria pay codes are not stated to be in them. What counts
 * as already paid for the month: the run's lines, the CAFETERIA payments of I-23 once approved
 * (SRS-G-33: "count cafeteria allowances already paid through Supplementary for that period, so
 * they are not paid again") and the earlier arrears of this source, so a second revision of the
 * same months settles only what is left. A month with no difference raises nothing, so a selection
 * the run already paid stays APPLIED.
 */
@Component
public class CafeteriaArrears {

    /** The arrear event type and DDL {@code arrear_event.source_module} of a claim-module event. */
    static final String SOURCE = "REIMBURSEMENT";

    private final JdbcTemplate jdbc;
    private final RetroEvents retro;
    private final AllowanceRepositories.Elections elections;
    private final AuditService audit;

    public CafeteriaArrears(
            JdbcTemplate jdbc,
            RetroEvents retro,
            AllowanceRepositories.Elections elections,
            AuditService audit) {
        this.jdbc = jdbc;
        this.retro = retro;
        this.elections = elections;
        this.audit = audit;
    }

    /** One selected component of one version, as the month's amount is worked out from it. */
    private record Version(
            LocalDate from, LocalDate to, String status, int payCode, BigDecimal monthly) {

        boolean live() {
            return !CafeteriaElection.Status.CANCELLED.name().equals(status)
                    && !CafeteriaElection.Status.RECEIVED.name().equals(status);
        }
    }

    /** What is already paid for a month; a payment with no pay code counts in total only. */
    private record Paid(Map<Integer, BigDecimal> byCode, BigDecimal unattributed) {}

    /**
     * Raises the arrear of the election, in the caller's transaction, when a locked month differs.
     *
     * @param approvalId the I-21 approval, or null when a cycle lock found it
     * @return the arrear event id, or empty when nothing is owed either way
     */
    public Optional<Long> settle(CafeteriaElection election, String by, Long approvalId) {
        long employee = election.getEmployeeId();
        List<String> months = lockedMonths(employee, election.getEffectiveFrom());
        if (months.isEmpty()) {
            return Optional.empty();
        }
        List<Version> versions = versions(employee);
        Set<Integer> codes = new HashSet<>();
        versions.forEach(v -> codes.add(v.payCode()));
        List<RetroEvents.Line> lines = new ArrayList<>();
        for (String month : months) {
            Paid paid = paid(employee, month, codes);
            ClaimRules.cafeteriaDifferential(
                            due(versions, month), paid.byCode(), paid.unattributed())
                    .forEach(
                            (code, amount) -> lines.add(new RetroEvents.Line(month, code, amount)));
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        String last = months.getLast();
        long event =
                retro.known(
                        new RetroEvents.Known(
                                employee,
                                SOURCE,
                                SOURCE,
                                CafeteriaEffects.ENTITY,
                                election.getId() + "-" + last,
                                "Cafeteria selection from "
                                        + election.getEffectiveFrom()
                                        + " received after the freeze",
                                by,
                                lines));
        String before = election.getStatus();
        election.arrearRaised(event);
        elections.save(election);
        audit.record(
                by,
                AuditService.Entry.change(
                                CafeteriaEffects.ENTITY,
                                String.valueOf(election.getId()),
                                "election_status",
                                before,
                                election.getStatus(),
                                "Arrear event "
                                        + event
                                        + " raised for the locked months to "
                                        + last)
                        .approval(approvalId)
                        .module(CafeteriaQueryService.SCREEN));
        return Optional.of(event);
    }

    /** The months the member's ACTUAL result is locked, from the selection's first month. */
    private List<String> lockedMonths(long employee, LocalDate from) {
        return jdbc.queryForList(
                "SELECT r.payroll_yymm FROM payroll_result r JOIN payroll_cycle_instance c ON"
                        + " c.cycle_instance_id = r.cycle_instance_id WHERE r.employee_id = ? AND"
                        + " r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT' AND"
                        + " c.cycle_status = 'LOCKED' AND r.payroll_yymm >= ? ORDER BY"
                        + " r.payroll_yymm",
                String.class,
                employee,
                yymm(YearMonth.from(from)));
    }

    private List<Version> versions(long employee) {
        List<Version> out = new ArrayList<>();
        jdbc.query(
                "SELECT e.effective_from, e.effective_to, e.election_status, p.legacy_pay_code,"
                        + " l.elected_amount FROM cafeteria_election e JOIN"
                        + " cafeteria_election_line l ON l.election_id = e.election_id JOIN"
                        + " pay_component p ON p.component_id = l.component_id WHERE"
                        + " e.employee_id = ? ORDER BY e.version_no",
                rs -> {
                    Date to = rs.getDate(2);
                    out.add(
                            new Version(
                                    rs.getDate(1).toLocalDate(),
                                    to == null ? null : to.toLocalDate(),
                                    rs.getString(3),
                                    rs.getInt(4),
                                    rs.getBigDecimal(5)));
                },
                employee);
        return out;
    }

    /** What the versions in force make due in the month, exactly as the run pays it. */
    private static Map<Integer, BigDecimal> due(List<Version> versions, String month) {
        YearMonth ym =
                YearMonth.of(
                        Integer.parseInt(month.substring(0, 4)),
                        Integer.parseInt(month.substring(4)));
        Map<Integer, BigDecimal> due = new HashMap<>();
        for (Version v : versions) {
            if (v.live()) {
                BigDecimal amount = ClaimRules.proRata(v.monthly(), v.from(), v.to(), ym);
                if (amount.signum() > 0) {
                    due.merge(v.payCode(), amount, BigDecimal::add);
                }
            }
        }
        return due;
    }

    private Paid paid(long employee, String month, Set<Integer> codes) {
        Map<Integer, BigDecimal> byCode = new HashMap<>();
        jdbc.query(
                "SELECT c.legacy_pay_code, rc.amount FROM payroll_result r JOIN"
                        + " payroll_result_component rc ON rc.payroll_result_id ="
                        + " r.payroll_result_id JOIN pay_component c ON c.component_id ="
                        + " rc.component_id WHERE r.employee_id = ? AND r.payroll_yymm = ? AND"
                        + " r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT'",
                rs -> {
                    if (codes.contains(rs.getInt(1))) {
                        byCode.merge(rs.getInt(1), rs.getBigDecimal(2), BigDecimal::add);
                    }
                },
                employee,
                month);
        jdbc.query(
                "SELECT c.legacy_pay_code, d.arrear_amount FROM arrear_detail d JOIN arrear_event"
                        + " e ON e.arrear_event_id = d.arrear_event_id JOIN pay_component c ON"
                        + " c.component_id = d.component_id WHERE d.employee_id = ? AND"
                        + " d.differential_yymm = ? AND e.source_entity = ? AND e.event_status NOT"
                        + " IN ('REVERSED', 'REJECTED') AND d.arrear_status <> 'REVERSED'",
                rs -> {
                    byCode.merge(rs.getInt(1), rs.getBigDecimal(2), BigDecimal::add);
                },
                employee,
                month,
                CafeteriaEffects.ENTITY);
        BigDecimal[] unattributed = {BigDecimal.ZERO};
        jdbc.query(
                "SELECT c.legacy_pay_code, s.amount FROM supplementary_payment s LEFT JOIN"
                        + " pay_component c ON c.component_id = s.component_id WHERE"
                        + " s.employee_id = ? AND s.for_period_yymm = ? AND s.payment_type ="
                        + " 'CAFETERIA' AND s.payment_status IN ('APPROVED', 'RELEASED')",
                rs -> {
                    int code = rs.getInt(1);
                    if (rs.wasNull()) {
                        unattributed[0] = unattributed[0].add(rs.getBigDecimal(2));
                    } else {
                        byCode.merge(code, rs.getBigDecimal(2), BigDecimal::add);
                    }
                },
                employee,
                month);
        return new Paid(byCode, unattributed[0]);
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }
}
