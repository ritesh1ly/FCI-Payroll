package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The leave masters as X-04 / History-button records (SRS-20-29, SRS-20-3): an M-13 leave type
 * received from HRMS, one row updated in place with each field's change audited (SRS-LV-13), and an
 * M-20 encashment parameter, kept as effective-dated versions (SRS-LV-14). National records: both
 * apply corporation-wide.
 */
@Configuration(proxyBeanMethods = false)
public class LeaveHistory {

    static final String TYPE_ENTITY = "leave_type_reference";
    static final String TYPE_SCREEN = "M-13";

    /** The parameter and its leave type in an M-20 key ({@code CODE} or {@code CODE / TYPE}). */
    private static final String TYPE_SEPARATOR = " / ";

    /** M-13 leave type, key = its code: as-of is rebuilt from its field-level audit rows. */
    @Bean
    RecordHistorySource leaveTypeHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                TYPE_ENTITY,
                code ->
                        named
                                .queryForList(
                                        "SELECT leave_type_name, is_encashable,"
                                                + " supports_half_day FROM leave_type_reference"
                                                + " WHERE leave_type_code = :code",
                                        Map.of("code", code))
                                .stream()
                                .findFirst()
                                .map(
                                        t -> {
                                            Map<String, String> current = new LinkedHashMap<>();
                                            current.put(
                                                    "leave_type_name",
                                                    text(t.get("LEAVE_TYPE_NAME")));
                                            current.put(
                                                    "is_encashable", text(t.get("IS_ENCASHABLE")));
                                            current.put(
                                                    "supports_half_day",
                                                    text(t.get("SUPPORTS_HALF_DAY")));
                                            return HistoryRecord.ofCurrent(
                                                    TYPE_ENTITY, code, TYPE_SCREEN, null, current);
                                        }));
    }

    /**
     * M-20 parameter of a leave type (or of all types), key = {@code CODE} or {@code CODE / TYPE}:
     * its confirmed (approved) versions. Each proposal and its approval were audited under {@code
     * key|effective-from} (LeaveParameterService, LeaveParameterEffect).
     */
    @Bean
    RecordHistorySource leaveParameterHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                LeaveParameterService.ENTITY,
                key -> {
                    int at = key.indexOf(TYPE_SEPARATOR);
                    String code = at < 0 ? key : key.substring(0, at);
                    String type = at < 0 ? null : key.substring(at + TYPE_SEPARATOR.length());
                    List<Map<String, Object>> rows =
                            named.queryForList(
                                    "SELECT version_no, numeric_value, text_value,"
                                            + " effective_from, effective_to, is_confirmed,"
                                            + " approval_id FROM leave_pay_parameter WHERE"
                                            + " parameter_code = :code AND ((:type IS NULL AND"
                                            + " leave_type_code IS NULL) OR leave_type_code ="
                                            + " :type) ORDER BY version_no",
                                    new MapSqlParameterSource("code", code)
                                            .addValue("type", type, Types.VARCHAR));
                    if (rows.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    LeaveParameterService.ENTITY,
                                    key,
                                    LeaveParameterService.SCREEN,
                                    null,
                                    rows.stream()
                                            .map(
                                                    r ->
                                                            key
                                                                    + "|"
                                                                    + LeaveParameterService
                                                                            .toLocalDate(
                                                                                    r.get(
                                                                                            "EFFECTIVE_FROM")))
                                            .toList(),
                                    rows.stream()
                                            .filter(r -> "Y".equals(r.get("IS_CONFIRMED")))
                                            .map(LeaveHistory::version)
                                            .toList()));
                });
    }

    private static Version version(Map<String, Object> r) {
        Map<String, String> image = new LinkedHashMap<>();
        Object number = r.get("NUMERIC_VALUE");
        image.put(
                "numeric_value",
                number == null ? null : new BigDecimal(number.toString()).toPlainString());
        image.put("text_value", text(r.get("TEXT_VALUE")));
        Object approval = r.get("APPROVAL_ID");
        return new Version(
                ((Number) r.get("VERSION_NO")).intValue(),
                LeaveParameterService.toLocalDate(r.get("EFFECTIVE_FROM")),
                LeaveParameterService.toLocalDate(r.get("EFFECTIVE_TO")),
                approval == null ? null : ((Number) approval).longValue(),
                image);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
