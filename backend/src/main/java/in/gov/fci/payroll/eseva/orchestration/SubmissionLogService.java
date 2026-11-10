package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.eseva.business.EsevaRules;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents.Status;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents.StatusChanged;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents.Submitted;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Self-Service Submission Log (plan 14 phase 14a; SRS-17-3, SRS-17-18, BR-ESEVA-006; DDL {@code
 * ess_submission}). Every eSeva submission is recorded in the transaction of the owning module's
 * change (its {@code Submitted} event) and follows it: the owning module's status changes and the
 * decision of the approval it was routed to. The rows are derived from the owning modules' audited
 * changes, so they carry no audit of their own. Screen E-02 lists them for payroll staff of the
 * member's offices (every office at Headquarters); a member never sees the log.
 */
@Service
@Loggable
public class SubmissionLogService {

    /** Screen of the staff log and support preview (D14-1). */
    public static final String SCREEN = "E-02";

    /** E-02 filters; each may be null. */
    public record Filter(String eis, String type, String status) {}

    /** One row of the log. */
    public record Row(
            long id,
            String eis,
            String member,
            String office,
            String type,
            String reference,
            String value,
            Instant submittedAt,
            String status,
            Long approvalId) {}

    private static final String FROM =
            " FROM ess_submission s JOIN employee e ON e.employee_id = s.employee_id LEFT JOIN"
                    + " org_location l ON l.location_id = e.location_id";

    private static final String WHERE =
            " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND (:eis IS NULL OR e.emp_num ="
                    + " :eis) AND (:type IS NULL OR s.submission_type = :type) AND (:status IS NULL"
                    + " OR s.submission_status = :status)";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MemberNotifications notifications;
    private final Clock clock;

    public SubmissionLogService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MemberNotifications notifications,
            Clock clock) {
        this.jdbc = jdbc;
        this.named = named;
        this.policy = policy;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** SRS-17-3: the submission, in the owning module's transaction. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(Submitted s) {
        if (s.entityId() == null) {
            return;
        }
        Status status = s.approvalId() == null ? Status.SUBMITTED : Status.ROUTED;
        String value = EsevaRules.value(s.value());
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO ess_submission (employee_id, submission_type,"
                                        + " reference_entity_name, reference_entity_id,"
                                        + " it_declaration_id, vpf_change_request_id,"
                                        + " hrms_update_request_id, submitted_value, submitted_at,"
                                        + " submission_status, routed_to_approval_id) VALUES (?, ?,"
                                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    new String[] {"submission_id"});
                    ps.setLong(1, s.employeeId());
                    ps.setString(2, s.type().name());
                    ps.setString(3, s.entityName());
                    ps.setLong(4, s.entityId());
                    ps.setObject(5, s.declarationId(), Types.BIGINT);
                    ps.setObject(6, s.vpfChangeRequestId(), Types.BIGINT);
                    ps.setObject(7, s.hrmsUpdateRequestId(), Types.BIGINT);
                    ps.setString(8, value);
                    ps.setTimestamp(9, Timestamp.from(clock.instant()));
                    ps.setString(10, status.name());
                    ps.setObject(11, s.approvalId(), Types.BIGINT);
                    return ps;
                },
                key);
        Number id = key.getKey();
        if (id == null) {
            throw new IllegalStateException("The log row just written has no id");
        }
        // SRS-17-12: the member is told at every status, from the first one.
        notifications.statusChanged(
                s.employeeId(), id.longValue(), s.type().name(), status.name(), value);
    }

    /** The owning module's record moved on; rows already final stay as they are. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(StatusChanged c) {
        if (c.entityId() == null) {
            return;
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT submission_id, submission_status, employee_id, submission_type,"
                                + " submitted_value FROM ess_submission WHERE"
                                + " reference_entity_name = ? AND reference_entity_id = ?",
                        c.entityName(),
                        c.entityId());
        if (c.approvalId() != null) {
            for (Map<String, Object> r : rows) {
                if (!EsevaRules.isFinal(Status.valueOf((String) r.get("SUBMISSION_STATUS")))) {
                    jdbc.update(
                            "UPDATE ess_submission SET routed_to_approval_id = ? WHERE"
                                    + " submission_id = ?",
                            c.approvalId(),
                            r.get("SUBMISSION_ID"));
                }
            }
        }
        move(rows, c.status());
    }

    /** BR-ESEVA-006: the decision of the approval a submission was routed to. */
    @EventListener
    public void on(ApprovalDecided decision) {
        Status to =
                decision.approved()
                        ? Status.ACTIONED
                        : decision.outcome() == ApprovalStatus.REJECTED ? Status.REJECTED : null;
        if (to == null) {
            return; // returned: still with the owning module
        }
        move(
                jdbc.queryForList(
                        "SELECT submission_id, submission_status, employee_id, submission_type,"
                                + " submitted_value FROM ess_submission WHERE"
                                + " routed_to_approval_id = ?",
                        decision.approvalId()),
                to);
    }

    private void move(List<Map<String, Object>> rows, Status to) {
        for (Map<String, Object> r : rows) {
            Status from = Status.valueOf((String) r.get("SUBMISSION_STATUS"));
            if (EsevaRules.moves(from, to)) {
                jdbc.update(
                        "UPDATE ess_submission SET submission_status = ? WHERE submission_id = ?",
                        to.name(),
                        r.get("SUBMISSION_ID"));
                notifications.statusChanged(
                        ((Number) r.get("EMPLOYEE_ID")).longValue(),
                        ((Number) r.get("SUBMISSION_ID")).longValue(),
                        (String) r.get("SUBMISSION_TYPE"),
                        to.name(),
                        (String) r.get("SUBMITTED_VALUE"));
            }
        }
    }

    /** E-02: the log of the session's offices, newest first, paged (SRS-17-18). */
    @Transactional(readOnly = true)
    public Paged<Row> page(AccessContext context, Filter filter, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue("eis", blank(filter.eis()), Types.VARCHAR)
                        .addValue("type", blank(filter.type()), Types.VARCHAR)
                        .addValue("status", blank(filter.status()), Types.VARCHAR);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Row> rows =
                named.query(
                        "SELECT s.submission_id, e.emp_num, e.first_name, e.last_name,"
                                + " l.location_code, l.location_name, s.submission_type,"
                                + " s.reference_entity_name, s.reference_entity_id,"
                                + " s.submitted_value, s.submitted_at, s.submission_status,"
                                + " s.routed_to_approval_id"
                                + FROM
                                + WHERE
                                + " ORDER BY s.submission_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            String last = rs.getString("last_name");
                            String code = rs.getString("location_code");
                            long approval = rs.getLong("routed_to_approval_id");
                            return new Row(
                                    rs.getLong("submission_id"),
                                    rs.getString("emp_num"),
                                    last == null || last.isBlank()
                                            ? rs.getString("first_name")
                                            : rs.getString("first_name") + " " + last,
                                    code == null
                                            ? null
                                            : code + " - " + rs.getString("location_name"),
                                    rs.getString("submission_type"),
                                    rs.getString("reference_entity_name")
                                            + " #"
                                            + rs.getLong("reference_entity_id"),
                                    rs.getString("submitted_value"),
                                    rs.getTimestamp("submitted_at").toInstant(),
                                    rs.getString("submission_status"),
                                    approval == 0 ? null : approval);
                        });
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
