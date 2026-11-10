package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-19-21, wireframe M-08 (M08-04): the domains that have a master screen of their own, shown on
 * the umbrella catalogue read-only: what each holds in force today and since when, and whether a
 * change waits. Never a rule version: the values stay on M-14, M-15 and M-20, so the row's Edit is
 * drawn disabled and names that screen.
 */
@Service
@Loggable
public class RuleReadThroughService {

    /** One read-only catalogue row. {@code from} is null while nothing is in force. */
    public record ReadThrough(
            String key,
            String type,
            String screen,
            long records,
            LocalDate from,
            boolean pending) {}

    private static final String SCREEN = "M-08";
    private static final String LEAVE_PARAMETERS = "leave_pay_parameter";

    private final JdbcTemplate jdbc;
    private final SystemParameters settings;
    private final ApprovalStatuses statuses;
    private final Authorization policy;

    public RuleReadThroughService(
            JdbcTemplate jdbc,
            SystemParameters settings,
            ApprovalStatuses statuses,
            Authorization policy) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.statuses = statuses;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public List<ReadThrough> rows(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = settings.today();
        return List.of(
                dated(
                        "LEAVE.LWP_DIVISOR / ENCASH_DIVISOR / ENCASH_LIMITS / SEP_CEILING",
                        "PARAMETER",
                        "M-20",
                        LEAVE_PARAMETERS,
                        today),
                dated(
                        "ALLOW.<CLAIMTYPE>.ENTITLEMENT / CEILING",
                        "CEILING",
                        "M-15",
                        CeilingNormService.ENTITY,
                        today),
                dated("PENALTY.MINNET.FLOOR", "FLOOR", "M-14", NetPayFloorService.ENTITY, today),
                exclusions(today));
    }

    /** A dated master: {@code table} is one of this class's constants, never input. */
    private ReadThrough dated(
            String key, String type, String screen, String table, LocalDate today) {
        return held(
                key,
                type,
                screen,
                table,
                "SELECT COUNT(*), MIN(effective_from) FROM "
                        + table
                        + " WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to >="
                        + " ?)",
                today);
    }

    /** SRS-19-21: the supplementary-exclusion codes are the flagged catalogue entries (M-14). */
    private ReadThrough exclusions(LocalDate today) {
        return held(
                "SUPP.EXCL.CODES",
                "ELIGIBILITY",
                "M-14",
                NetPayFloorService.EXCLUSIONS,
                "SELECT COUNT(*), MIN(effective_from) FROM pay_component WHERE supp_excluded_flag ="
                        + " 'Y' AND effective_from <= ? AND (effective_to IS NULL OR effective_to"
                        + " >= ?)",
                today);
    }

    private ReadThrough held(
            String key, String type, String screen, String entity, String sql, LocalDate today) {
        Date day = Date.valueOf(today);
        return jdbc.queryForObject(
                sql,
                (rs, i) -> {
                    Date from = rs.getDate(2);
                    return new ReadThrough(
                            key,
                            type,
                            screen,
                            rs.getLong(1),
                            from == null ? null : from.toLocalDate(),
                            !statuses.pending(entity).isEmpty());
                },
                day,
                day);
    }
}
