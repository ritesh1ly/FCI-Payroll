package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.leave.business.LeaveParameterRules;
import in.gov.fci.payroll.leave.business.LeaveParameterRules.Kind;
import in.gov.fci.payroll.leave.business.LeaveParameterRules.Proposal;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-20 Leave Encashment Parameters (plan 19 phase 19b; SRS-LV-14, SRS M-20 use case; D19-2, D19-3,
 * D19-10): the Administrator (the Rule Administrator at Headquarters) proposes an effective-dated
 * version of a parameter, held unconfirmed with its approval (route {@value #ACTION}); it is read
 * only once the Payroll Checker approves it ({@link LeaveParameterEffect}), and a returned or
 * rejected version is never used. A new version starts after every approved one of the same
 * parameter and leave type, on a date no other version holds (no overlap), and only one version
 * waits at a time. Pay codes must be active component-catalogue entries (M-07).
 */
@Service
@Loggable
public class LeaveParameterService {

    public static final String SCREEN = "M-20";
    public static final String ACTION = "M20_CREATE";
    static final String ENTITY = "leave_pay_parameter";

    /** One version as M-20 shows it (Status: Pending Approval / Active / Superseded / Returned). */
    public record Version(
            long id,
            String code,
            String leaveType,
            BigDecimal number,
            String text,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            Long approvalId,
            String circularNumber) {}

    /**
     * A proposal as the screen sends it; {@code circularId} is the M-25 circular "where an order
     * applies" (SRS M-20 use case), optional.
     */
    public record NewVersion(
            String code,
            String leaveType,
            BigDecimal number,
            String text,
            LocalDate effectiveFrom,
            String reason,
            Long circularId) {

        /** A proposal no order applies to. */
        public NewVersion(
                String code,
                String leaveType,
                BigDecimal number,
                String text,
                LocalDate effectiveFrom,
                String reason) {
            this(code, leaveType, number, text, effectiveFrom, reason, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Circulars circulars;

    public LeaveParameterService(
            JdbcTemplate jdbc,
            Authorization policy,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Circulars circulars) {
        this.circulars = circulars;
        this.jdbc = jdbc;
        this.policy = policy;
        this.approvals = approvals;
        this.statuses = statuses;
    }

    /** SRS-LV-14: every version of every parameter, newest first per parameter, one page. */
    @Transactional(readOnly = true)
    public Paged<Version> versions(AccessContext context, String code, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        String filter = code == null || code.isBlank() ? null : code.strip();
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM leave_pay_parameter WHERE (? IS NULL OR"
                                + " parameter_code = ?)",
                        Long.class,
                        filter,
                        filter);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT p.leave_pay_parameter_id, p.parameter_code, p.leave_type_code,"
                            + " p.numeric_value, p.text_value, p.version_no, p.effective_from,"
                            + " p.effective_to, p.is_confirmed, p.approval_id, (SELECT"
                            + " MAX(c.circular_number) FROM circular_order_link l JOIN"
                            + " circular_order c ON c.circular_order_id = l.circular_order_id WHERE"
                            + " l.linked_entity = 'leave_pay_parameter' AND l.linked_key ="
                            + " TO_CHAR(p.leave_pay_parameter_id) AND l.link_status = 'ACTIVE') AS"
                            + " circular_number FROM leave_pay_parameter p WHERE (? IS NULL OR"
                            + " p.parameter_code = ?) ORDER BY p.parameter_code, p.leave_type_code"
                            + " NULLS FIRST, p.effective_from DESC, p.leave_pay_parameter_id DESC"
                            + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                        filter,
                        filter,
                        page.page() * page.size(),
                        page.size());
        Map<Long, ApprovalStatus> status =
                statuses.of(
                        rows.stream()
                                .map(r -> (Number) r.get("APPROVAL_ID"))
                                .filter(Objects::nonNull)
                                .map(Number::longValue)
                                .toList());
        List<Version> items = rows.stream().map(r -> version(r, status)).toList();
        return new Paged<>(items, page.page(), page.size(), RowCounts.of(total));
    }

    /**
     * SRS-LV-14: proposes a version (Headquarters only), held until the Payroll Checker decides.
     */
    @Transactional
    public Version propose(AccessContext context, NewVersion v) {
        policy.require(context, SCREEN, "CREATE");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(LeaveErrorCode.HQ_ONLY);
        }
        String type =
                v.leaveType() == null || v.leaveType().isBlank() ? null : v.leaveType().strip();
        String text = v.text() == null || v.text().isBlank() ? null : v.text().strip();
        Proposal p = new Proposal(v.code(), type, v.number(), text, v.reason());
        boolean knownType =
                type != null
                        && count(
                                        "SELECT COUNT(*) FROM leave_type_reference WHERE"
                                                + " leave_type_code = ?",
                                        type)
                                > 0;
        LeaveParameterRules.defect(p, knownType)
                .ifPresent(
                        d -> {
                            throw parameter(d);
                        });
        if (v.effectiveFrom() == null) {
            throw parameter("EFFECTIVE_FROM");
        }
        // SRS M-20: "where an order applies, the linked circular from M-25" (Active only,
        // SRS-G-38).
        if (v.circularId() != null) {
            circulars.requireActive(v.circularId());
        }
        if (LeaveParameterRules.PARAMETERS.get(p.code()) == Kind.PAY_CODES) {
            requireActivePayCodes(text, v.effectiveFrom());
        }
        List<Map<String, Object>> existing =
                jdbc.queryForList(
                        "SELECT effective_from, approval_id, version_no FROM leave_pay_parameter"
                                + " WHERE parameter_code = ? AND ((? IS NULL AND leave_type_code IS"
                                + " NULL) OR leave_type_code = ?)",
                        p.code(),
                        type,
                        type);
        Map<Long, ApprovalStatus> status =
                statuses.of(
                        existing.stream()
                                .map(r -> (Number) r.get("APPROVAL_ID"))
                                .filter(Objects::nonNull)
                                .map(Number::longValue)
                                .toList());
        requireNonePending(p.code(), existing, status);
        requireFreeDate(p.code(), v.effectiveFrom(), existing, status);
        int versionNo =
                existing.stream()
                                .mapToInt(r -> ((Number) r.get("VERSION_NO")).intValue())
                                .max()
                                .orElse(0)
                        + 1;
        String label = p.code() + (type == null ? "" : " / " + type);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                label + "|" + v.effectiveFrom(),
                                ActionType.CREATE,
                                context.locationId(),
                                v.circularId() == null
                                        ? null
                                        : "{\"circularId\":" + v.circularId() + "}",
                                v.effectiveFrom(),
                                v.reason().strip()),
                        (OtpProof) null);
        long id = insert(p, versionNo, v.effectiveFrom(), item.id());
        return new Version(
                id,
                p.code(),
                type,
                p.number(),
                text,
                versionNo,
                v.effectiveFrom(),
                null,
                ApprovalStatus.PENDING_APPROVAL.name(),
                item.id(),
                null);
    }

    // ---------------------------------------------------------------- steps

    private void requireActivePayCodes(String text, LocalDate day) {
        for (Integer code : LeaveParameterRules.payCodes(text)) {
            long active =
                    count(
                            "SELECT COUNT(*) FROM pay_component WHERE legacy_pay_code = ? AND"
                                    + " effective_from <= ? AND (effective_to IS NULL OR"
                                    + " effective_to >= ?)",
                            code,
                            Date.valueOf(day),
                            Date.valueOf(day));
            if (active == 0) {
                throw parameter("PAY_CODE " + code + " is not an active M-07 component");
            }
        }
    }

    private static void requireNonePending(
            String code, List<Map<String, Object>> existing, Map<Long, ApprovalStatus> status) {
        for (Map<String, Object> r : existing) {
            Long approval = id(r.get("APPROVAL_ID"));
            if (approval != null && status.get(approval) == ApprovalStatus.PENDING_APPROVAL) {
                throw new FciException(
                        LeaveErrorCode.PARAMETER_PENDING,
                        new Object[] {code, approval},
                        List.of(),
                        null);
            }
        }
    }

    /** No overlap: after every approved version, and on a date no other version holds. */
    private static void requireFreeDate(
            String code,
            LocalDate from,
            List<Map<String, Object>> existing,
            Map<Long, ApprovalStatus> status) {
        LocalDate latestApproved = null;
        boolean taken = false;
        for (Map<String, Object> r : existing) {
            LocalDate start = toLocalDate(r.get("EFFECTIVE_FROM"));
            if (from.equals(start)) {
                taken = true;
            }
            Long approval = id(r.get("APPROVAL_ID"));
            if (approval != null
                    && status.get(approval) == ApprovalStatus.APPROVED
                    && (latestApproved == null || start.isAfter(latestApproved))) {
                latestApproved = start;
            }
        }
        if (taken || (latestApproved != null && !from.isAfter(latestApproved))) {
            throw new FciException(
                    LeaveErrorCode.PARAMETER_DATE,
                    new Object[] {code, latestApproved == null ? "-" : latestApproved.toString()},
                    List.of(),
                    null);
        }
    }

    private long insert(Proposal p, int versionNo, LocalDate from, long approvalId) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO leave_pay_parameter (parameter_code,"
                                            + " parameter_name, leave_type_code, numeric_value,"
                                            + " text_value, version_no, effective_from,"
                                            + " is_confirmed, approval_id) VALUES (?, ?, ?, ?, ?,"
                                            + " ?, ?, 'N', ?)",
                                    new String[] {"leave_pay_parameter_id"});
                    ps.setString(1, p.code());
                    ps.setString(2, p.code().replace('_', ' '));
                    ps.setString(3, p.leaveType());
                    ps.setObject(4, p.number(), Types.NUMERIC);
                    ps.setString(5, p.text());
                    ps.setInt(6, versionNo);
                    ps.setDate(7, Date.valueOf(from));
                    ps.setLong(8, approvalId);
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    private static Version version(Map<String, Object> r, Map<Long, ApprovalStatus> status) {
        Long approval = id(r.get("APPROVAL_ID"));
        ApprovalStatus s = approval == null ? null : status.get(approval);
        LocalDate to = toLocalDate(r.get("EFFECTIVE_TO"));
        String shown;
        if ("Y".equals(r.get("IS_CONFIRMED"))) {
            shown = to == null ? "ACTIVE" : "SUPERSEDED";
        } else {
            shown = s == null ? "DRAFT" : s.name();
        }
        return new Version(
                ((Number) r.get("LEAVE_PAY_PARAMETER_ID")).longValue(),
                (String) r.get("PARAMETER_CODE"),
                (String) r.get("LEAVE_TYPE_CODE"),
                (BigDecimal) r.get("NUMERIC_VALUE"),
                (String) r.get("TEXT_VALUE"),
                ((Number) r.get("VERSION_NO")).intValue(),
                toLocalDate(r.get("EFFECTIVE_FROM")),
                to,
                shown,
                approval,
                (String) r.get("CIRCULAR_NUMBER"));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return RowCounts.of(n);
    }

    private static Long id(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /** A DATE column reads as java.sql.Date or Timestamp by driver: both are dates here. */
    static LocalDate toLocalDate(Object value) {
        return value == null ? null : new Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static FciException parameter(String defect) {
        return new FciException(LeaveErrorCode.PARAMETER, new Object[] {defect}, List.of(), null);
    }
}
