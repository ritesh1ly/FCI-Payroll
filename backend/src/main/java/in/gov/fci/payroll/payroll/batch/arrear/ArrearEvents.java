package in.gov.fci.payroll.payroll.batch.arrear;

import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.YearMonth;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The arrear event of a rate arrear run (DA_ARREAR, HRA_ARREAR; target DDL v4.0 S13): RAISED when
 * the job starts, COMPUTED when it completes; it posts into the next open cycle ({@code
 * posted_to_yymm}: the current period, never a closed one), once approved on I-08. SRS-8-15 (plan
 * 13 phase 13c): a run that overlaps an open event of the same type and unit is refused.
 */
@Component
@Profile("batch")
public class ArrearEvents {

    private final JdbcTemplate jdbc;
    private final CycleRuns cycles;
    private final Clock clock;

    public ArrearEvents(JdbcTemplate jdbc, CycleRuns cycles, Clock clock) {
        this.jdbc = jdbc;
        this.cycles = cycles;
        this.clock = clock;
    }

    @Transactional
    public long raise(
            long requestId, long locationId, YearMonth from, YearMonth to, YearMonth nextOpen) {
        return raise(requestId, locationId, from, to, nextOpen, "DA_REVISION", "DA");
    }

    /**
     * @param eventCode the arrear event type (DA_REVISION, HRA_REVISION)
     * @param label the revised component, for the event's reference
     * @throws IllegalStateException SRS-8-15: an open event of the type and unit overlaps the range
     */
    @Transactional
    public long raise(
            long requestId,
            long locationId,
            YearMonth from,
            YearMonth to,
            YearMonth nextOpen,
            String eventCode,
            String label) {
        OrgLocation unit = cycles.cycleUnit(locationId);
        // SRS-8-15 (US-GAP-252): two runs of the unit raised at the same moment are serialised on
        // the unit's row lock, so the second sees the first's event and is refused - the overlap
        // check and the insert are never interleaved.
        jdbc.queryForList(
                "SELECT location_id FROM org_location WHERE location_id = ? FOR UPDATE",
                unit.getId());
        java.util.List<Long> open =
                jdbc.queryForList(
                        "SELECT e.arrear_event_id FROM arrear_event e JOIN arrear_event_type t ON"
                                + " t.event_type_id = e.event_type_id WHERE t.event_code = ? AND"
                                + " e.scope_location_id = ? AND e.event_status IN ('RAISED',"
                                + " 'COMPUTED', 'APPROVED') AND e.scope_from_yymm <= ? AND"
                                + " e.scope_to_yymm >= ?",
                        Long.class,
                        eventCode,
                        unit.getId(),
                        CycleKeys.yymm(to),
                        CycleKeys.yymm(from));
        if (!open.isEmpty()) {
            throw new IllegalStateException(
                    "SRS-8-15: the range overlaps the open "
                            + label
                            + " arrear event "
                            + open.getFirst()
                            + " of this unit; settle or reverse it first");
        }
        String by =
                jdbc.queryForObject(
                        "SELECT requested_by_login FROM batch_job_request WHERE job_request_id = ?",
                        String.class,
                        requestId);
        Long type =
                jdbc.queryForObject(
                        "SELECT event_type_id FROM arrear_event_type WHERE event_code = ?",
                        Long.class,
                        eventCode);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO arrear_event (event_type_id, event_reference,"
                                        + " effective_from, effective_to, scope_payroll_type,"
                                        + " scope_location_id, scope_from_yymm, scope_to_yymm,"
                                        + " source_module, source_entity, source_key, event_status,"
                                        + " posted_to_yymm, raised_by, raised_at) VALUES (?, ?, ?,"
                                        + " ?, 'STAFF', ?, ?, ?, 'RATE_CHANGE',"
                                        + " 'batch_job_request', ?, 'RAISED', ?, ?, ?)",
                                    new String[] {"arrear_event_id"});
                    ps.setLong(1, type);
                    ps.setString(
                            2,
                            label + " arrear " + from + " to " + to + " (" + unit.getCode() + ")");
                    ps.setDate(3, Date.valueOf(from.atDay(1)));
                    ps.setDate(4, Date.valueOf(to.atEndOfMonth()));
                    ps.setLong(5, unit.getId());
                    ps.setString(6, CycleKeys.yymm(from));
                    ps.setString(7, CycleKeys.yymm(to));
                    ps.setString(8, String.valueOf(requestId));
                    ps.setString(9, CycleKeys.yymm(nextOpen));
                    ps.setString(10, by == null ? "system" : CycleKeys.truncate(by));
                    ps.setTimestamp(11, Timestamp.from(clock.instant()));
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    @Transactional
    public void computed(long eventId) {
        jdbc.update(
                "UPDATE arrear_event SET event_status = 'COMPUTED' WHERE arrear_event_id = ? AND"
                        + " event_status = 'RAISED'",
                eventId);
    }
}
