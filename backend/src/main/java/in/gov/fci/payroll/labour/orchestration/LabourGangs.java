package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.labour.business.AttendanceRules;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The handling gang a depot input belongs to and the state of its month batches (I-01, I-02;
 * D20-8): one read shared by the attendance and work-slip services.
 */
@Component
class LabourGangs {

    /** A handling gang (or, for attendance and work-slips, the depot's Ancillary Pool). */
    record Gang(
            long id,
            String code,
            String name,
            long depotId,
            String depot,
            String payrollType,
            String status,
            LocalDate from,
            LocalDate to,
            String type) {

        /** A handling gang. */
        Gang(
                long id,
                String code,
                String name,
                long depotId,
                String depot,
                String payrollType,
                String status,
                LocalDate from,
                LocalDate to) {
            this(id, code, name, depotId, depot, payrollType, status, from, to, "HANDLING");
        }

        /** SRS-2-46: the depot's Ancillary Pool (no bag fields, no strength). */
        boolean pool() {
            return "ANCILLARY_POOL".equals(type);
        }

        /** The gang works on the day: active and within its dates. */
        boolean activeOn(LocalDate day) {
            return "ACTIVE".equals(status)
                    && !day.isBefore(from)
                    && (to == null || !day.isAfter(to));
        }
    }

    /**
     * A month batch: its latest approval item, state and, when returned, the Checker's reasons
     * (SRS-2-29 "send a returned batch back to the Labour Dealing Hand with reasons").
     */
    record Batch(Long approvalId, BatchState state, String returnReason) {

        Batch(Long approvalId, BatchState state) {
            this(approvalId, state, null);
        }
    }

    private final JdbcTemplate jdbc;

    LabourGangs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The handling gang, or 404. */
    Gang gang(long gangId) {
        return find(gangId, false);
    }

    /**
     * A gang whose people are marked and booked (I-01, I-02): a handling gang or the depot's
     * Ancillary Pool (SRS-2-46 "Record attendance and overtime for each person in the pool"), or
     * 404.
     */
    Gang workGang(long gangId) {
        return find(gangId, true);
    }

    private Gang find(long gangId, boolean poolToo) {
        return jdbc
                .query(
                        "SELECT g.gang_id, g.gang_code, g.gang_name, g.depot_location_id,"
                            + " l.location_code, l.location_name, g.payroll_type, g.gang_status,"
                            + " g.effective_from, g.effective_to, g.gang_type FROM labour_gang g"
                            + " JOIN org_location l ON l.location_id = g.depot_location_id WHERE"
                            + " g.gang_id = ? AND (g.gang_type = 'HANDLING' OR (? = 'Y' AND"
                            + " g.gang_type = 'ANCILLARY_POOL'))",
                        (rs, i) -> {
                            java.sql.Date to = rs.getDate("effective_to");
                            return new Gang(
                                    rs.getLong("gang_id"),
                                    rs.getString("gang_code"),
                                    rs.getString("gang_name"),
                                    rs.getLong("depot_location_id"),
                                    rs.getString("location_code")
                                            + " - "
                                            + rs.getString("location_name"),
                                    rs.getString("payroll_type"),
                                    rs.getString("gang_status"),
                                    rs.getDate("effective_from").toLocalDate(),
                                    to == null ? null : to.toLocalDate(),
                                    rs.getString("gang_type"));
                        },
                        gangId,
                        poolToo ? "Y" : "N")
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** The gang's month batch of an approval entity, from its latest approval item. */
    Batch batch(String entity, long gangId, YearMonth month) {
        Map<String, Object> latest =
                jdbc
                        .queryForList(
                                "SELECT approval_id, status, return_reason FROM approval_queue"
                                        + " WHERE entity_name ="
                                        + " ? AND entity_key = ? ORDER BY approval_id DESC",
                                entity,
                                AttendanceRules.batchKey(gangId, month))
                        .stream()
                        .findFirst()
                        .orElse(null);
        return latest == null
                ? new Batch(null, BatchState.OPEN)
                : new Batch(
                        ((Number) latest.get("APPROVAL_ID")).longValue(),
                        BatchState.of((String) latest.get("STATUS")),
                        (String) latest.get("RETURN_REASON"));
    }
}
