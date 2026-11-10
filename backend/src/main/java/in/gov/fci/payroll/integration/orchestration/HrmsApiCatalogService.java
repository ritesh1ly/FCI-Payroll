package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.business.HrmsContracts;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only HRMS API view of X-03 (owner 2026-11-05; SRS-INT-6, SRS-INT-16, SRS-INT-17,
 * SRS-INT-32; screen X-03 VIEW): per API of the Postman collection its class, direction, whether
 * its URL setting is set, the path (never a host, a query string, a credential, a key or a token),
 * the retry / back-off / time limit of its interface and the bulkhead limits from the settings, the
 * last call with its outcome and the received / accepted / rejected / refused counts over a window.
 * Nothing here writes: URLs are configuration, not screen-edited.
 */
@Service
public class HrmsApiCatalogService {

    static final String SCREEN = "X-03";

    /** Counts are over the last 7 days unless the caller asks for another window (1 to 90). */
    public static final int DEFAULT_DAYS = 7;

    /** The statuses a last outcome can be filtered by. */
    public static final Set<String> STATUSES = Set.of("OK", "PARTIAL", "FAILED", "NONE");

    /**
     * One API of the collection.
     *
     * @param selectionClass REQUIRED (class A) or RULE_PENDING (class B)
     * @param direction INBOUND (Payroll publishes it) or OUTBOUND (Payroll calls it)
     * @param path the path part of the URL setting, or null when not configured
     * @param received counts over the window for the APIs that have an interface of their own
     *     (request-* and token); null for status, cancel and report APIs
     * @param refused refusals recorded for the API's interface in the window (authentication
     *     refused, bulkhead full, not configured)
     * @param lastStatus OK, PARTIAL, FAILED or NONE (no call in the window)
     */
    public record Row(
            String code,
            String method,
            String selectionClass,
            String direction,
            boolean configured,
            String path,
            String interfaceCode,
            Integer retryCount,
            Integer backoffSeconds,
            String backoffStrategy,
            Integer timeoutSeconds,
            Integer bulkheadMaxConcurrentCalls,
            Integer bulkheadMaxWaitMillis,
            Instant lastCallAt,
            String lastStatus,
            Long received,
            Long accepted,
            Long rejected,
            Long refused,
            boolean specSignedOff,
            int days) {}

    private record Activity(
            Instant lastCallAt,
            String lastStatus,
            long received,
            long accepted,
            long rejected,
            long refused,
            boolean specSignedOff) {}

    private final IntegrationStore store;
    private final HrmsApiProperties properties;
    private final InterfaceSettings settings;
    private final Authorization policy;
    private final Clock clock;

    public HrmsApiCatalogService(
            IntegrationStore store,
            HrmsApiProperties properties,
            InterfaceSettings settings,
            Authorization policy,
            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.settings = settings;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * The APIs, filtered, newest activity first is not needed: the order is the collection's.
     *
     * @param selectionClass REQUIRED or RULE_PENDING, or null
     * @param direction INBOUND or OUTBOUND, or null
     * @param configured true / false, or null
     * @param status OK, PARTIAL, FAILED or NONE, or null
     * @param days the counting window, null for the default
     */
    @Transactional(readOnly = true)
    public Paged<Row> list(
            AccessContext context,
            String selectionClass,
            String direction,
            Boolean configured,
            String status,
            Integer days,
            PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        int window = days == null ? DEFAULT_DAYS : Math.max(1, Math.min(90, days));
        Instant since = clock.instant().minusSeconds(window * 86_400L);
        Map<String, Activity> activity = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (HrmsApi api : HrmsApi.values()) {
            Row row = row(api, since, window, activity);
            if (selectionClass != null && !selectionClass.equals(row.selectionClass())) {
                continue;
            }
            if (direction != null && !direction.equals(row.direction())) {
                continue;
            }
            if (configured != null && configured != row.configured()) {
                continue;
            }
            if (status != null && !status.equals(row.lastStatus())) {
                continue;
            }
            rows.add(row);
        }
        int from = (int) Math.min((long) spec.page() * spec.size(), rows.size());
        int to = Math.min(from + spec.size(), rows.size());
        return new Paged<>(
                List.copyOf(rows.subList(from, to)), spec.page(), spec.size(), rows.size());
    }

    private Row row(HrmsApi api, Instant since, int days, Map<String, Activity> cache) {
        Optional<HrmsApiProperties.ApiUrl> entry =
                Optional.ofNullable(properties.api()).map(m -> m.get(api.code()));
        boolean outbound = entry.isPresent() && !entry.get().inbound();
        boolean configured =
                outbound ? properties.outboundUrl(api).isPresent() : properties.published(api);
        String path =
                configured
                        ? pathOnly(entry.map(HrmsApiProperties.ApiUrl::path).orElse(null))
                        : null;
        String interfaceCode = interfaceCodeOf(api);
        Optional<Settings> config = settings.inForce(interfaceCode);
        Optional<HrmsApiProperties.Limit> limit = properties.limit(api);
        Activity a = cache.computeIfAbsent(interfaceCode, c -> activity(c, since));
        boolean own = HrmsContracts.request(api).isPresent() || api == HrmsApi.TOKEN;
        return new Row(
                api.code(),
                api.method(),
                api.selection().name(),
                outbound ? "OUTBOUND" : "INBOUND",
                configured,
                path,
                interfaceCode,
                config.map(Settings::retryCount).orElse(null),
                config.map(Settings::backoffSeconds).orElse(null),
                config.map(Settings::backoffStrategy).orElse(null),
                config.map(Settings::timeoutSeconds).orElse(null),
                limit.map(HrmsApiProperties.Limit::maxConcurrentCalls).orElse(null),
                limit.map(HrmsApiProperties.Limit::maxWaitMillis).orElse(null),
                a.lastCallAt(),
                a.lastStatus(),
                own ? a.received() : null,
                own ? a.accepted() : null,
                own ? a.rejected() : null,
                own ? a.refused() : null,
                a.specSignedOff(),
                days);
    }

    /** The registry interface an API's calls are recorded under. */
    static String interfaceCodeOf(HrmsApi api) {
        if (api == HrmsApi.TOKEN) {
            return HrmsRefusals.AUTH_INTERFACE;
        }
        return HrmsClient.interfaceOf(api);
    }

    /** Only the path of a URL setting: no scheme, host, user info, query or fragment. */
    static String pathOnly(String setting) {
        if (setting == null || setting.isBlank()) {
            return null;
        }
        String s = setting.strip();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                s = URI.create(s).getPath();
            } catch (IllegalArgumentException ex) {
                return null;
            }
            if (s == null || s.isBlank()) {
                return "/";
            }
        }
        int cut = s.indexOf('?');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        cut = s.indexOf('#');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        return "/" + s.replaceAll("^/+", "");
    }

    private Activity activity(String interfaceCode, Instant since) {
        Timestamp from = Timestamp.from(since);
        List<Map<String, Object>> reg =
                store.jdbc()
                        .queryForList(
                                "SELECT interface_id, spec_signed_off FROM interface_registry WHERE"
                                        + " interface_code = ?",
                                interfaceCode);
        if (reg.isEmpty()) {
            return new Activity(null, "NONE", 0, 0, 0, 0, false);
        }
        long id = ((Number) reg.getFirst().get("INTERFACE_ID")).longValue();
        boolean signed = "Y".equals(reg.getFirst().get("SPEC_SIGNED_OFF"));
        List<Map<String, Object>> sums =
                store.jdbc()
                        .queryForList(
                                "SELECT COALESCE(SUM(received_count), 0) r,"
                                    + " COALESCE(SUM(accepted_count), 0) a,"
                                    + " COALESCE(SUM(rejected_count), 0) j FROM interface_run WHERE"
                                    + " interface_id = ? AND started_at >= ?",
                                id,
                                from);
        List<Map<String, Object>> last =
                store.jdbc()
                        .queryForList(
                                "SELECT started_at, run_status FROM interface_run WHERE"
                                    + " interface_id = ? AND started_at >= ? ORDER BY started_at"
                                    + " DESC, run_id DESC FETCH FIRST 1 ROWS ONLY",
                                id,
                                from);
        Long refused =
                store.jdbc()
                        .queryForObject(
                                "SELECT COUNT(*) FROM interface_exception WHERE interface_id = ?"
                                        + " AND raised_at >= ? AND staging_id IS NULL AND"
                                        + " LOWER(rejection_reason) LIKE '%refused%'",
                                Long.class, id, from);
        Map<String, Object> s = sums.getFirst();
        return new Activity(
                last.isEmpty() ? null : ((Timestamp) last.getFirst().get("STARTED_AT")).toInstant(),
                last.isEmpty() ? "NONE" : (String) last.getFirst().get("RUN_STATUS"),
                ((Number) s.get("R")).longValue(),
                ((Number) s.get("A")).longValue(),
                ((Number) s.get("J")).longValue(),
                refused == null ? 0 : refused,
                signed);
    }
}
