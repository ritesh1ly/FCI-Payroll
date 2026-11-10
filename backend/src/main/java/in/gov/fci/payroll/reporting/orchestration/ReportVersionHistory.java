package in.gov.fci.payroll.reporting.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M-12 report definitions as X-04 / History-button records (SRS-20-29, SRS-20-3; SRS-18-9,
 * SRS-18-10, SRS-G-12), key = the report key: its approved versions, oldest first, each with the
 * layout format, period lock, store mode and blocked flag M-12 enters. The proposals and decisions
 * were audited under the version id (ReportVersionService). A national record: the catalogue is
 * kept at Headquarters.
 */
@Component
public class ReportVersionHistory implements RecordHistorySource {

    private final NamedParameterJdbcTemplate named;

    public ReportVersionHistory(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public String entity() {
        return ReportVersionService.ENTITY;
    }

    @Override
    public Optional<HistoryRecord> find(String key) {
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT v.report_version_id, v.version_no, v.effective_from,"
                                + " v.effective_to, v.layout_format, v.period_lock_required,"
                                + " v.output_store_mode, v.approval_state, v.is_blocked,"
                                + " v.blocked_reason, v.approval_id FROM report_definition d LEFT"
                                + " JOIN report_definition_version v ON v.report_def_id ="
                                + " d.report_def_id WHERE d.report_key = :key ORDER BY"
                                + " v.version_no",
                        Map.of("key", key));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> versions =
                rows.stream().filter(r -> r.get("REPORT_VERSION_ID") != null).toList();
        return Optional.of(
                HistoryRecord.ofVersions(
                        ReportVersionService.ENTITY,
                        key,
                        ReportVersionService.SCREEN,
                        null,
                        versions.stream().map(r -> r.get("REPORT_VERSION_ID").toString()).toList(),
                        versions.stream()
                                .filter(r -> "APPROVED".equals(r.get("APPROVAL_STATE")))
                                .map(
                                        r ->
                                                new Version(
                                                        ((Number) r.get("VERSION_NO")).intValue(),
                                                        date(r.get("EFFECTIVE_FROM")),
                                                        date(r.get("EFFECTIVE_TO")),
                                                        r.get("APPROVAL_ID") == null
                                                                ? null
                                                                : ((Number) r.get("APPROVAL_ID"))
                                                                        .longValue(),
                                                        image(r)))
                                .toList()));
    }

    private static Map<String, String> image(Map<String, Object> r) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("layout_format", text(r.get("LAYOUT_FORMAT")));
        image.put("period_lock_required", text(r.get("PERIOD_LOCK_REQUIRED")));
        image.put("output_store_mode", text(r.get("OUTPUT_STORE_MODE")));
        image.put("is_blocked", text(r.get("IS_BLOCKED")));
        image.put("blocked_reason", text(r.get("BLOCKED_REASON")));
        return image;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** A DATE column as JDBC hands it over (H2 a Timestamp, Oracle a Date). */
    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }
}
