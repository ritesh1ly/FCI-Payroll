package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payroll periods of the M-10 payroll calendar ({@code payroll_calendar}, target DDL v4.0 S08):
 * one row per payroll month with its window dates, approved under maker-checker. The current period
 * is the calendar month containing today's business date; every other approved period, back-dated
 * ones included, can be worked on.
 *
 * <p>Without an approved calendar (a deployment before M-10 is loaded) the current period is the
 * configured {@code fci.api.payroll.current-period}: the platform never guesses a calendar.
 *
 * <p>Cached (region {@value #REGION}, Caffeine with the periodic full reload of {@code
 * fci.cache.max-age}): the current period is read on most requests (the top bar, C-01, eSeva), the
 * calendar changes only when an M-10 change is approved, which bumps the region in its transaction
 * ({@link #changed}).
 */
@Component
public class PayrollPeriods {

    /** One approved calendar period. */
    public record Period(
            YearMonth period,
            String finYear,
            LocalDate start,
            LocalDate end,
            LocalDate lockTarget,
            LocalDate disbursement) {}

    private static final String APPROVED =
            "SELECT c.payroll_yymm, c.fin_year, c.period_start_date, c.period_end_date,"
                    + " c.lock_target_date, c.disbursement_date FROM payroll_calendar c LEFT JOIN"
                    + " approval_queue a ON a.approval_id = c.approval_id WHERE (c.approval_id IS"
                    + " NULL OR a.status = 'APPROVED')";

    /** The cache region of the approved calendar. */
    public static final String REGION = "PAYROLL_CALENDAR";

    private final JdbcTemplate jdbc;
    private final SystemParameters parameters;
    private final ApiProperties properties;
    private final VersionedCache<String, List<Period>> cache;

    public PayrollPeriods(
            JdbcTemplate jdbc,
            SystemParameters parameters,
            ApiProperties properties,
            CacheRegions regions) {
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.properties = properties;
        this.cache = VersionedCache.of(REGION, regions, 1);
    }

    /** Every approved period, newest first. */
    @Transactional(readOnly = true)
    public List<Period> all() {
        return cache.get(
                REGION,
                k ->
                        List.copyOf(
                                jdbc.query(
                                        APPROVED + " ORDER BY c.payroll_yymm DESC",
                                        (rs, i) -> row(rs))));
    }

    /** The approved period of {@code month}, if the calendar holds it. */
    @Transactional(readOnly = true)
    public Optional<Period> of(YearMonth month) {
        return all().stream().filter(p -> p.period().equals(month)).findFirst();
    }

    /**
     * An approved M-10 change: every instance reloads the calendar (in the caller's transaction).
     */
    public void changed(String changedBy) {
        cache.changed(changedBy);
    }

    /**
     * The current payroll period: the approved calendar period whose window contains today's
     * business date, else the configured period.
     */
    @Transactional(readOnly = true)
    public YearMonth current() {
        LocalDate today = parameters.today();
        return all().stream()
                .filter(p -> !p.start().isAfter(today) && !p.end().isBefore(today))
                .findFirst()
                .map(Period::period)
                .orElse(properties.payroll().currentPeriod());
    }

    private static Period row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String yymm = rs.getString(1);
        return new Period(
                YearMonth.of(
                        Integer.parseInt(yymm.substring(0, 4)),
                        Integer.parseInt(yymm.substring(4))),
                rs.getString(2),
                rs.getDate(3).toLocalDate(),
                rs.getDate(4).toLocalDate(),
                date(rs.getDate(5)),
                date(rs.getDate(6)));
    }

    private static LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }
}
