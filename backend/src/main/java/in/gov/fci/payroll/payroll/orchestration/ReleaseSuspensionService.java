package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Emergency stop of payment release on O-02 (plan 05 phase 05h; SRS-15-35, FRS BR-SEC-007 (SRS
 * label BR-SEC-006, CLR-43); D-15, D-16): Finance at Headquarters suspends release of bank files
 * and payment postings for all units or a named unit, for all channels or one, with a reason, at
 * once; the suspension blocks every release ({@code payroll.api.ReleaseSuspensions}) until its
 * resumption, requested by Finance at Headquarters, is approved by the Payroll Checker at
 * Headquarters (route O02_RESUME, no OTP); both are audited. Nothing is deleted: a resumed
 * suspension stays on record.
 */
@Service
@Loggable
public class ReleaseSuspensionService {

    /** Approval entity of a resumption (key: the suspension id). */
    public static final String ENTITY = "disb_release_suspension";

    private static final String SCREEN = "O-02";

    /** One suspension as O-02 lists it. */
    public record SuspensionView(
            long id,
            String scope,
            String unitCode,
            String channel,
            String suspendedBy,
            Instant suspendedAt,
            String reason,
            String resumedBy,
            Instant resumedAt,
            String resumeReason,
            Long pendingApprovalId) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final OrgDirectory org;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final ApprovalStatuses approvalStatuses;
    private final AuditService audit;
    private final Clock clock;
    private final ObjectMapper json;

    public ReleaseSuspensionService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            OrgDirectory org,
            Authorization policy,
            MakerChecker approvals,
            ApprovalStatuses approvalStatuses,
            AuditService audit,
            Clock clock,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = named;
        this.org = org;
        this.policy = policy;
        this.approvals = approvals;
        this.approvalStatuses = approvalStatuses;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    /**
     * The suspensions the session's offices are concerned by, open ones first, then newest first:
     * every one at Headquarters; elsewhere the ALL_UNITS suspensions and those of the session's own
     * units (R-RBAC data scope; security review 2026-10-31: every unit's suspensions, with who
     * suspended them and why, were listed to any O-02 viewer).
     */
    @Transactional(readOnly = true)
    public Paged<SuspensionView> list(AccessContext context, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource params =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue("offset", (long) spec.page() * spec.size())
                        .addValue("size", spec.size());
        String scope =
                " WHERE (s.scope_type = 'ALL_UNITS' OR (:all = 'Y' OR s.org_unit_id IN"
                        + " (:locations)))";
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*) FROM disb_release_suspension s" + scope,
                        params,
                        Long.class);
        List<SuspensionView> rows =
                named.query(
                        "SELECT s.suspension_id, s.scope_type, o.location_code, s.release_channel,"
                                + " s.suspended_by, s.suspended_at, s.suspension_reason,"
                                + " s.resumed_by, s.resumed_at, s.resume_reason FROM"
                                + " disb_release_suspension s LEFT JOIN org_location o ON"
                                + " o.location_id = s.org_unit_id"
                                + scope
                                + " ORDER BY CASE WHEN s.resumed_at IS NULL THEN 0 ELSE 1 END,"
                                + " s.suspension_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS"
                                + " ONLY",
                        params,
                        (rs, i) ->
                                new SuspensionView(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        instant(rs.getTimestamp(6)),
                                        rs.getString(7),
                                        rs.getString(8),
                                        instant(rs.getTimestamp(9)),
                                        rs.getString(10),
                                        null));
        return new Paged<>(withPending(rows), spec.page(), spec.size(), CycleSupport.orZero(total));
    }

    /**
     * SRS-15-35: suspends release at once.
     *
     * @param scope ALL_UNITS or UNIT
     * @param unitCode the payroll cycle unit (UNIT), else null
     * @param channel ALL, BANK_FILE, EBS_AP_POSTING or APPS_REMITTANCE
     * @return the suspension
     */
    @Transactional
    public long suspend(
            AccessContext context, String scope, String unitCode, String channel, String reason) {
        requireHq(context, "SUSPEND");
        Long unitId = scopeUnit(scope, unitCode);
        String me = login(context);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO disb_release_suspension (scope_type, org_unit_id,"
                                            + " release_channel, suspended_by, suspended_at,"
                                            + " suspension_reason) VALUES (?, ?, ?, ?, ?, ?)",
                                    new String[] {"suspension_id"});
                    ps.setString(1, scope);
                    if (unitId == null) {
                        ps.setNull(2, Types.BIGINT);
                    } else {
                        ps.setLong(2, unitId);
                    }
                    ps.setString(3, channel);
                    ps.setString(4, me);
                    ps.setTimestamp(5, Timestamp.from(clock.instant()));
                    ps.setString(6, reason);
                    return ps;
                },
                key);
        long id = GeneratedKeys.id(key);
        audit.record(
                me,
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "suspension",
                                null,
                                scope + (unitCode == null ? "" : " " + unitCode) + " " + channel,
                                reason)
                        .module(SCREEN));
        return id;
    }

    /** SRS-15-35: asks for the resumption of an open suspension (decided on W-01). */
    @Transactional
    public long requestResume(AccessContext context, long id, String reason) {
        requireHq(context, "RESUME");
        Map<String, Object> s =
                jdbc
                        .queryForList(
                                "SELECT resumed_at FROM disb_release_suspension WHERE"
                                        + " suspension_id = ?",
                                id)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (s.get("RESUMED_AT") != null) {
            throw state("RESUMED");
        }
        if (pending(id) != null) {
            throw state("PENDING");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suspensionId", id);
        payload.put("reason", reason);
        long approval =
                approvals
                        .submit(
                                context,
                                new Submission(
                                        SCREEN,
                                        "RESUME",
                                        ENTITY,
                                        String.valueOf(id),
                                        ActionType.UPDATE,
                                        context.locationId(),
                                        json.writeValueAsString(payload),
                                        java.time.LocalDate.now(clock),
                                        reason),
                                null)
                        .id();
        audit.record(
                login(context),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "resumption",
                                null,
                                "PENDING_APPROVAL",
                                reason)
                        .approval(approval)
                        .module(SCREEN));
        return approval;
    }

    /** The waiting resumption of each suspension of the page (one query for the page). */
    private List<SuspensionView> withPending(List<SuspensionView> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, Long> latest = new LinkedHashMap<>();
        named.query(
                "SELECT entity_key, MAX(approval_id) FROM approval_queue WHERE entity_name ="
                        + " :entity AND entity_key IN (:keys) GROUP BY entity_key",
                new MapSqlParameterSource()
                        .addValue("entity", ENTITY)
                        .addValue("keys", rows.stream().map(r -> String.valueOf(r.id())).toList()),
                rs -> {
                    latest.put(rs.getString(1), rs.getLong(2));
                });
        Map<Long, ApprovalStatus> statuses = approvalStatuses.of(List.copyOf(latest.values()));
        return rows.stream()
                .map(
                        r -> {
                            Long item = latest.get(String.valueOf(r.id()));
                            ApprovalStatus s = item == null ? null : statuses.get(item);
                            boolean waiting = s != null && s.name().startsWith("PENDING");
                            return new SuspensionView(
                                    r.id(),
                                    r.scope(),
                                    r.unitCode(),
                                    r.channel(),
                                    r.suspendedBy(),
                                    r.suspendedAt(),
                                    r.reason(),
                                    r.resumedBy(),
                                    r.resumedAt(),
                                    r.resumeReason(),
                                    waiting ? item : null);
                        })
                .toList();
    }

    private void requireHq(AccessContext context, String action) {
        policy.requireAt(context, SCREEN, action, context.locationId());
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
    }

    /** ALL_UNITS names no unit; UNIT names a payroll cycle unit. */
    private Long scopeUnit(String scope, String unitCode) {
        boolean named = unitCode != null && !unitCode.isBlank();
        if ("ALL_UNITS".equals(scope)) {
            if (named) {
                throw scopeError("UNIT_NOT_ALLOWED");
            }
            return null;
        }
        if (!named) {
            throw scopeError("UNIT_REQUIRED");
        }
        OrgLocation unit =
                org.byCode(unitCode.strip())
                        .filter(
                                l ->
                                        org.cycleUnitOf(l.getId())
                                                .map(u -> u.getId().equals(l.getId()))
                                                .orElse(false))
                        .orElseThrow(() -> scopeError("NOT_A_CYCLE_UNIT"));
        return unit.getId();
    }

    private Long pending(long id) {
        return CycleSupport.pending(jdbc, approvalStatuses, ENTITY, String.valueOf(id));
    }

    private static FciException state(String what) {
        return new FciException(
                ApiErrorCode.SUSPENSION_STATE, new Object[] {what}, List.of(), null);
    }

    private static FciException scopeError(String what) {
        return new FciException(
                ApiErrorCode.SUSPENSION_SCOPE, new Object[] {what}, List.of(), null);
    }

    private static Instant instant(Timestamp t) {
        return CycleSupport.instant(t);
    }

    private static String login(AccessContext context) {
        return CycleSupport.login(context);
    }
}
