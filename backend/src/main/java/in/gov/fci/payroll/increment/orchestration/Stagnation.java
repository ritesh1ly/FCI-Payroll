package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.penalty.api.IncrementStops;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The stagnation-increment test (SRS-13-10 … 13-14; BR-INCR-STAG-001, 002, 004 … 007), one for the
 * I-05 run and the individual case: the scale's stagnation amount (M-03) and the rule values
 * STAGNATION_QUALIFYING_MONTHS and STAGNATION_FREQUENCY_CAP configured (unseeded: not configured,
 * never a guess); the Basic at the scale maximum for the qualifying months on the day; no
 * increment-stop order in force; fewer stagnation increments given than the cap. The quantum is the
 * scale's amount, never keyed per member.
 */
@Component
public class Stagnation {

    /** Why a member is, or is not, offered a stagnation increment. */
    public enum Verdict {
        ELIGIBLE,
        NOT_CONFIGURED,
        NOT_AT_MAXIMUM,
        NOT_QUALIFIED,
        BARRED,
        AT_CAP
    }

    /**
     * The outcome: the revised Basic and the test that fired when eligible; otherwise what is
     * missing (not configured), the qualifying months or the cap.
     */
    public record Outcome(
            Verdict verdict,
            BigDecimal revised,
            String test,
            String missing,
            BigDecimal months,
            BigDecimal cap) {

        public boolean eligible() {
            return verdict == Verdict.ELIGIBLE;
        }

        static Outcome not(Verdict verdict, String missing, BigDecimal months, BigDecimal cap) {
            return new Outcome(verdict, null, null, missing, months, cap);
        }
    }

    private final NamedParameterJdbcTemplate named;
    private final RuleEngine rules;
    private final IncrementStops stops;

    public Stagnation(NamedParameterJdbcTemplate named, RuleEngine rules, IncrementStops stops) {
        this.named = named;
        this.rules = rules;
        this.stops = stops;
    }

    /** The test for a member on a day (the due date in the run, the effective date of a case). */
    public Outcome evaluate(long employee, BigDecimal old, Scales.Scale s, LocalDate day) {
        if (s.stagnationAmount() == null) {
            return Outcome.not(
                    Verdict.NOT_CONFIGURED,
                    "The stagnation increment amount of scale " + s.bandCode() + " (M-03)",
                    null,
                    null);
        }
        RuleEngine.Context ctx = new RuleEngine.Context("STAFF", null);
        Optional<BigDecimal> months = scalar("STAGNATION_QUALIFYING_MONTHS", day, ctx);
        if (months.isEmpty()) {
            return Outcome.not(
                    Verdict.NOT_CONFIGURED,
                    "The rule value STAGNATION_QUALIFYING_MONTHS",
                    null,
                    null);
        }
        Optional<BigDecimal> cap = scalar("STAGNATION_FREQUENCY_CAP", day, ctx);
        if (cap.isEmpty()) {
            return Outcome.not(
                    Verdict.NOT_CONFIGURED, "The rule value STAGNATION_FREQUENCY_CAP", null, null);
        }
        if (old.compareTo(s.max()) < 0) {
            return Outcome.not(Verdict.NOT_AT_MAXIMUM, null, months.get(), cap.get());
        }
        LocalDate since = atMaximumSince(employee, s.max());
        if (since == null || since.plusMonths(months.get().longValue()).isAfter(day)) {
            return Outcome.not(Verdict.NOT_QUALIFIED, null, months.get(), cap.get());
        }
        if (!stops.stopping(employee, day).isEmpty()) {
            return Outcome.not(Verdict.BARRED, null, months.get(), cap.get());
        }
        Long given =
                named.queryForObject(
                        "SELECT COUNT(*) FROM pay_increment_cycle WHERE employee_id = :employee AND"
                                + " increment_type = 'STAGNATION'",
                        new MapSqlParameterSource("employee", employee),
                        Long.class);
        long count = RowCounts.of(given);
        if (count >= cap.get().longValue()) {
            return Outcome.not(Verdict.AT_CAP, null, months.get(), cap.get());
        }
        String test =
                "At maximum "
                        + s.max().toPlainString()
                        + " since "
                        + since
                        + " (qualifying "
                        + months.get().toPlainString()
                        + " months); "
                        + count
                        + " of "
                        + cap.get().toPlainString()
                        + " given; no increment-stop order on "
                        + day;
        return new Outcome(
                Verdict.ELIGIBLE,
                old.add(s.stagnationAmount()),
                test,
                null,
                months.get(),
                cap.get());
    }

    /** The first day of the member's current unbroken stretch at or above the maximum. */
    private LocalDate atMaximumSince(long employee, BigDecimal max) {
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT basic_amount, effective_from FROM employee_basic_history WHERE"
                                + " employee_id = :employee ORDER BY effective_from DESC,"
                                + " event_sequence DESC",
                        new MapSqlParameterSource("employee", employee));
        LocalDate since = null;
        for (Map<String, Object> r : rows) {
            if (((BigDecimal) r.get("BASIC_AMOUNT")).compareTo(max) < 0) {
                break;
            }
            since = Dates.local(r.get("EFFECTIVE_FROM"));
        }
        return since;
    }

    /** A rule value; empty when it is not configured (R-RULE: never a default). */
    private Optional<BigDecimal> scalar(String key, LocalDate day, RuleEngine.Context ctx) {
        try {
            return rules.resolve(key, day, ctx).map(RuleEngine.Resolved::scalar);
        } catch (FciException ex) {
            return Optional.empty();
        }
    }
}
