package in.gov.fci.payroll.configuration.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What the M-04 Dearness Allowance table reads beside the rate set: the base stated with a proposal
 * (kept in its approval payload, DDL approval_queue.payload) and the arrear event a DA revision
 * raised (DDL arrear_event, arrear_detail, event type DA_REVISION; the configuration module holds
 * no arrear model, so a plain read of the two tables).
 */
@Component
public class RateVersionFacts {

    private static final Pattern COMPUTED_ON = Pattern.compile("\"computedOn\":\"([^\"]*)\"");

    /** The arrear event of a DA revision: its reference and the total of its lines. */
    public record ArrearImpact(String reference, BigDecimal amount) {}

    private final JdbcTemplate jdbc;

    public RateVersionFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stated base by approval id; an item without one is absent. */
    public Map<Long, String> computedOn(Collection<Long> approvalIds) {
        Map<Long, String> result = new HashMap<>();
        if (approvalIds.isEmpty()) {
            return result;
        }
        jdbc.query(
                "SELECT approval_id, payload FROM approval_queue WHERE approval_id IN ("
                        + marks(approvalIds.size())
                        + ")",
                rs -> {
                    String payload = rs.getString("payload");
                    Matcher m = COMPUTED_ON.matcher(payload == null ? "" : payload);
                    if (m.find()) {
                        result.put(rs.getLong("approval_id"), m.group(1));
                    }
                },
                approvalIds.toArray());
        return result;
    }

    /** The DA_REVISION event starting on each of {@code days}, with the total of its lines. */
    public Map<LocalDate, ArrearImpact> dearnessArrears(List<LocalDate> days) {
        Map<LocalDate, ArrearImpact> result = new HashMap<>();
        if (days.isEmpty()) {
            return result;
        }
        jdbc.query(
                "SELECT e.effective_from, MAX(e.event_reference) event_reference,"
                        + " COALESCE(SUM(d.arrear_amount), 0) total"
                        + " FROM arrear_event e"
                        + " JOIN arrear_event_type t ON t.event_type_id = e.event_type_id"
                        + " LEFT JOIN arrear_detail d ON d.arrear_event_id = e.arrear_event_id"
                        + " WHERE t.event_code = 'DA_REVISION' AND e.event_status <> 'REJECTED'"
                        + " AND e.effective_from IN ("
                        + marks(days.size())
                        + ") GROUP BY e.effective_from",
                rs -> {
                    result.put(
                            rs.getDate("effective_from").toLocalDate(),
                            new ArrearImpact(
                                    rs.getString("event_reference"), rs.getBigDecimal("total")));
                },
                days.stream().map(Date::valueOf).toArray());
        return result;
    }

    private static String marks(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
