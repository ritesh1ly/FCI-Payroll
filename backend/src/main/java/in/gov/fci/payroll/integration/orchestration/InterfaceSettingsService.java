package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The settings in force of an interface (SRS-INT-16, BR-INT-008; plan 23 D23-3): the {@code
 * interface_config} row whose window holds today. A row missing the retry count, back-off, strategy
 * or time limit - or a required breaker without its threshold - is not configured.
 *
 * <p>Asked on every interface call and many times per X-03 page, the answer is held per interface
 * and business day (HLD §3.12, region {@value #REGION}). The application never writes {@code
 * interface_config} (X-03 shows it): a migration that changes it marks the region changed, and the
 * cache's maximum age bounds any other change.
 */
@Service
public class InterfaceSettingsService implements InterfaceSettings {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The cache region of the interface settings ({@code interface_config}). */
    public static final String REGION = "INTERFACE_CONFIG";

    private record Key(String interfaceCode, LocalDate day) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final VersionedCache<Key, Optional<Settings>> cache;

    public InterfaceSettingsService(JdbcTemplate jdbc, Clock clock, CacheRegions regions) {
        this.jdbc = jdbc;
        this.clock = clock;
        // About 20 interfaces; keyed by day, so a window that opens at midnight applies at once.
        this.cache = VersionedCache.of(REGION, regions, 256);
    }

    /** Today in IST (the business date of every window). */
    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Settings> inForce(String interfaceCode) {
        return cache.get(new Key(interfaceCode, today()), k -> load(k.interfaceCode(), k.day()));
    }

    private Optional<Settings> load(String interfaceCode, LocalDate today) {
        List<Settings> rows =
                jdbc.query(
                        "SELECT c.retry_count, c.backoff_seconds, c.backoff_strategy,"
                            + " c.timeout_seconds, c.circuit_breaker_required,"
                            + " c.circuit_breaker_threshold, c.ack_timeout_seconds,"
                            + " c.counterparty_sla, c.effective_from, c.is_confirmed FROM"
                            + " interface_config c JOIN interface_registry r ON r.interface_id ="
                            + " c.interface_id WHERE r.interface_code = ? AND c.effective_from <= ?"
                            + " AND (c.effective_to IS NULL OR c.effective_to >= ?) ORDER BY"
                            + " c.effective_from DESC FETCH FIRST 1 ROWS ONLY",
                        (rs, i) -> {
                            Integer retry = rs.getObject("retry_count", Integer.class);
                            Integer backoff = rs.getObject("backoff_seconds", Integer.class);
                            Integer timeout = rs.getObject("timeout_seconds", Integer.class);
                            String strategy = rs.getString("backoff_strategy");
                            boolean breaker = "Y".equals(rs.getString("circuit_breaker_required"));
                            Integer threshold =
                                    rs.getObject("circuit_breaker_threshold", Integer.class);
                            if (retry == null
                                    || backoff == null
                                    || timeout == null
                                    || strategy == null
                                    || (breaker && threshold == null)) {
                                return null;
                            }
                            return new Settings(
                                    interfaceCode,
                                    retry,
                                    backoff,
                                    strategy,
                                    timeout,
                                    breaker,
                                    threshold,
                                    rs.getObject("ack_timeout_seconds", Integer.class),
                                    rs.getString("counterparty_sla"),
                                    rs.getDate("effective_from").toLocalDate(),
                                    "Y".equals(rs.getString("is_confirmed")));
                        },
                        interfaceCode,
                        Date.valueOf(today),
                        Date.valueOf(today));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
    }
}
