package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M-03 pay scales as X-04 / History-button records (SRS-20-29, SRS-20-3; SRS M-03, SRS-G-38), key =
 * the band code. A scale's bounds are kept as effective-dated versions: each approved version (or
 * one loaded without an approval item) is a version with its stages. The proposals were audited
 * under the version id, their approvals under "{@code CODE from DATE}" (PayScaleService). A
 * national record: Headquarters keeps the scales.
 */
@Component
public class PayScaleHistory implements RecordHistorySource {

    private final NamedParameterJdbcTemplate named;

    public PayScaleHistory(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public String entity() {
        return PayScaleService.ENTITY;
    }

    @Override
    public Optional<HistoryRecord> find(String key) {
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT v.band_version_id, v.version_no, v.min_basic, v.max_basic,"
                                + " v.stagnation_increment_amt, v.effective_from, v.effective_to,"
                                + " v.approval_id, q.status FROM pay_scale_band b LEFT JOIN"
                                + " pay_scale_band_version v ON v.band_id = b.band_id LEFT JOIN"
                                + " approval_queue q ON q.approval_id = v.approval_id WHERE"
                                + " b.band_code = :code ORDER BY v.version_no",
                        Map.of("code", key));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<String> auditKeys = new ArrayList<>();
        List<Version> versions = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object id = r.get("BAND_VERSION_ID");
            if (id == null) {
                continue;
            }
            LocalDate from = Dates.local(r.get("EFFECTIVE_FROM"));
            auditKeys.add(id.toString());
            auditKeys.add(key + " from " + from);
            Object approval = r.get("APPROVAL_ID");
            if (approval != null && !"APPROVED".equals(r.get("STATUS"))) {
                continue;
            }
            versions.add(
                    new Version(
                            ((Number) r.get("VERSION_NO")).intValue(),
                            from,
                            Dates.local(r.get("EFFECTIVE_TO")),
                            approval == null ? null : ((Number) approval).longValue(),
                            image(r, ((Number) id).longValue())));
        }
        return Optional.of(
                HistoryRecord.ofVersions(
                        PayScaleService.ENTITY,
                        key,
                        PayScaleService.SCREEN,
                        null,
                        auditKeys,
                        versions));
    }

    /** What M-03 enters for a version: the bounds, the stagnation amount and the stages. */
    private Map<String, String> image(Map<String, Object> r, long versionId) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("min_basic", plain(r.get("MIN_BASIC")));
        image.put("max_basic", plain(r.get("MAX_BASIC")));
        image.put("stagnation_increment_amt", plain(r.get("STAGNATION_INCREMENT_AMT")));
        image.put(
                "stages",
                named
                        .queryForList(
                                "SELECT stage_basic FROM pay_scale_band_stage WHERE"
                                        + " band_version_id = :version ORDER BY stage_basic",
                                Map.of("version", versionId),
                                BigDecimal.class)
                        .stream()
                        .map(BigDecimal::toPlainString)
                        .collect(Collectors.joining(",")));
        return image;
    }

    private static String plain(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).toPlainString();
    }
}
