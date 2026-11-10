package in.gov.fci.payroll.eseva.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.jobs.api.JobEnded;
import in.gov.fci.payroll.loans.api.LoanRequestReturned;
import in.gov.fci.payroll.payroll.api.CycleReturned;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.workflow.api.ApproverDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notices to payroll staff (owner 2026-10-28, open items 05d-notify and
 * 16-ageing-escalation; SRS-15-30, SRS-1-68). A returned cycle is told to the Payroll Makers of its
 * unit with the reason; an I-21 HRMS change task pending longer than A-07 I21_AGEING_DAYS is told
 * once to the Payroll Maker and Checker of its paying unit (housekeeping on the batch instance;
 * nothing while the setting is unseeded). Each user reads their own notices; the escalation to the
 * next level waits for FCI to name it (PENDING 16-ageing-escalation). A loan sanction request the
 * Payroll Checker returned is told to the Payroll Makers of the member's unit with the reason
 * (SRS-12-7, US-GAP-198, OD-56).
 */
@Component
@Loggable
public class StaffNotices implements Housekeeping {

    static final String CYCLE_RETURNED = "CYCLE_RETURNED";

    /** DR-18: prefix of the job notices (JOB_COMPLETED, JOB_FAILED, JOB_STOPPED). */
    static final String JOB = "JOB_";

    static final String I21_OVERDUE = "I21_OVERDUE";

    static final String LOAN_RETURNED = "LOAN_REQUEST_RETURNED";

    /** One notice as the user sees it. */
    public record Notice(long id, String type, String message, boolean read, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final ApproverDirectory directory;
    private final SystemParameters parameters;
    private final Clock clock;

    public StaffNotices(
            JdbcTemplate jdbc,
            ApproverDirectory directory,
            SystemParameters parameters,
            Clock clock) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.parameters = parameters;
        this.clock = clock;
    }

    /** SRS-15-30: the Payroll Makers of the unit learn of the return and its reason. */
    @EventListener
    @Transactional
    public void on(CycleReturned event) {
        Long unit =
                jdbc.queryForObject(
                        "SELECT MAX(org_unit_id) FROM payroll_cycle_instance WHERE"
                                + " cycle_instance_id = ?",
                        Long.class,
                        event.cycleInstanceId());
        if (unit == null) {
            return;
        }
        String message =
                "Payroll of "
                        + event.unitCode()
                        + " for "
                        + event.period()
                        + " returned by "
                        + event.returnedBy()
                        + ": "
                        + (event.reason() == null ? "" : event.reason().strip());
        notify(
                directory.holders("PAYROLL_MAKER", unit),
                CYCLE_RETURNED,
                "cycle_return",
                String.valueOf(event.returnId()),
                message);
    }

    /**
     * SRS-12-7 (US-GAP-198): the Payroll Makers of the unit learn that a loan sanction request was
     * returned, and why; it waits in their queue for correction and resubmission.
     */
    @EventListener
    @Transactional
    public void on(LoanRequestReturned event) {
        notify(
                directory.holders("PAYROLL_MAKER", event.unitId()),
                LOAN_RETURNED,
                "approval_queue",
                String.valueOf(event.approvalId()),
                "Loan sanction request "
                        + event.loanType()
                        + " for "
                        + event.eis()
                        + " returned by "
                        + event.returnedBy()
                        + ": "
                        + (event.reason() == null ? "" : event.reason().strip()));
    }

    /**
     * SRS-NF-2 / SRS-NF-5 (deep review DR-18): the user who asked for a background run, report or
     * simulation learns that it ended, how it went and, for a failure, why; C-13 has the detail.
     * Written in the transaction that records the job's outcome.
     */
    @EventListener
    @Transactional
    public void on(JobEnded event) {
        String what =
                "Job "
                        + event.jobRequestId()
                        + " ("
                        + event.jobType()
                        + (event.payPeriod() == null ? "" : ", " + event.payPeriod())
                        + ")";
        String message =
                switch (event.status()) {
                    case "COMPLETED" ->
                            what
                                    + " completed: read "
                                    + event.read()
                                    + ", written "
                                    + event.written()
                                    + ", skipped "
                                    + event.skipped()
                                    + ".";
                    case "FAILED" ->
                            what
                                    + " failed"
                                    + (event.error() == null ? "" : ": " + event.error().strip())
                                    + ". It can be restarted on C-13.";
                    default -> what + " " + event.status().toLowerCase(java.util.Locale.ROOT) + ".";
                };
        notify(
                List.of(event.requestedBy()),
                JOB + event.status(),
                "batch_job_request",
                String.valueOf(event.jobRequestId()),
                message);
    }

    @Override
    public String name() {
        return "i21AgeingAlerts";
    }

    /** SRS-1-68: tasks pending past I21_AGEING_DAYS, told once to the unit's Maker and Checker. */
    @Override
    public int run(Instant now) {
        OptionalLong days = parameters.configured(SystemParameters.Code.I21_AGEING_DAYS);
        if (days.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> overdue =
                jdbc.queryForList(
                        "SELECT t.hrms_change_task_id, t.paying_unit_id, t.received_at FROM"
                            + " hrms_change_task t WHERE t.task_status IN ('PENDING_ORDER_DETAILS',"
                            + " 'PENDING_CHECKER') AND t.paying_unit_id IS NOT NULL AND"
                            + " t.received_at < ? AND NOT EXISTS (SELECT 1 FROM notification n"
                            + " WHERE n.notification_type = ? AND n.related_entity_name ="
                            + " 'hrms_change_task' AND n.related_entity_key ="
                            + " CAST(t.hrms_change_task_id AS VARCHAR2(20)))",
                        Timestamp.from(now.minusSeconds(days.getAsLong() * 86_400)),
                        I21_OVERDUE);
        int sent = 0;
        for (Map<String, Object> t : overdue) {
            long unit = ((Number) t.get("PAYING_UNIT_ID")).longValue();
            Set<Long> users = new LinkedHashSet<>(directory.holders("PAYROLL_MAKER", unit));
            users.addAll(directory.holders("PAYROLL_CHECKER", unit));
            String id = String.valueOf(((Number) t.get("HRMS_CHANGE_TASK_ID")).longValue());
            sent +=
                    notify(
                            List.copyOf(users),
                            I21_OVERDUE,
                            "hrms_change_task",
                            id,
                            "HRMS change task "
                                    + id
                                    + " has waited more than "
                                    + days.getAsLong()
                                    + " days on I-21.");
        }
        return sent;
    }

    /** The user's own notices, newest first; paged. */
    @Transactional(readOnly = true)
    public Paged<Notice> mine(AccessContext context, PageSpec spec) {
        long me = staff(context);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notification WHERE recipient_user_id = ? AND channel"
                                + " = 'IN_APP'",
                        Long.class,
                        me);
        List<Notice> rows =
                jdbc.query(
                        "SELECT notification_id, notification_type, message, is_read_flag,"
                            + " created_at FROM notification WHERE recipient_user_id = ? AND"
                            + " channel = 'IN_APP' ORDER BY created_at DESC, notification_id DESC"
                            + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                        (rs, i) ->
                                new Notice(
                                        rs.getLong("notification_id"),
                                        rs.getString("notification_type"),
                                        rs.getString("message"),
                                        "Y".equals(rs.getString("is_read_flag")),
                                        rs.getTimestamp("created_at").toInstant()),
                        me,
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** The user reads one of their notices; another user's notice reads as unknown. */
    @Transactional
    public void read(AccessContext context, long id) {
        int updated =
                jdbc.update(
                        "UPDATE notification SET is_read_flag = 'Y' WHERE notification_id = ? AND"
                                + " recipient_user_id = ? AND channel = 'IN_APP'",
                        id,
                        staff(context));
        if (updated == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
    }

    /**
     * A staff session (an access group in use): an eSeva member reads the member notices instead.
     */
    private static long staff(AccessContext context) {
        if (context.userId() == null || context.groups().isEmpty()) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        return context.userId();
    }

    /** Writes one in-app notice per user; answers how many were written. */
    int notify(List<Long> users, String type, String entity, String key, String message) {
        Timestamp now = Timestamp.from(clock.instant());
        String text = message.length() > 2000 ? message.substring(0, 2000) : message;
        for (Long user : users) {
            jdbc.update(
                    "INSERT INTO notification (recipient_user_id, channel, notification_type,"
                        + " related_entity_name, related_entity_key, message, delivery_status,"
                        + " created_at, sent_at) VALUES (?, 'IN_APP', ?, ?, ?, ?, 'DELIVERED', ?,"
                        + " ?)",
                    user,
                    type,
                    entity,
                    key,
                    text,
                    now,
                    now);
        }
        return users.size();
    }
}
