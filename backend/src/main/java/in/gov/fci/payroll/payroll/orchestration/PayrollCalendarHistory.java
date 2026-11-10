package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * M-10 Payroll Calendar records for X-04 and the M-10 History button (SRS-20-29, SRS-20-3;
 * SRS-15-33, SRS-15-1): a period, key = its {@code payroll_yymm} (e.g. {@code 202604}), and a step
 * of the Engine Run Order, key = its step code. Both are one row changed in place: as-of is rebuilt
 * from the field-level audit rows {@link PayrollCalendarEffect} writes on approval. The proposals
 * were audited under the financial year and under {@value PayrollCalendarService#RUN_ORDER_KEY}.
 * National records: one calendar and one run order for the corporation.
 */
@Configuration(proxyBeanMethods = false)
public class PayrollCalendarHistory {

    static final String SCREEN = "M-10";

    @Bean
    RecordHistorySource calendarPeriodHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                PayrollCalendarService.CALENDAR_ENTITY,
                yymm ->
                        named
                                .queryForList(
                                        "SELECT * FROM payroll_calendar WHERE payroll_yymm ="
                                                + " :yymm",
                                        Map.of("yymm", yymm))
                                .stream()
                                .findFirst()
                                .map(
                                        row -> {
                                            Map<String, String> current = new LinkedHashMap<>();
                                            for (String[] c : PayrollCalendarEffect.COLUMNS) {
                                                Object value =
                                                        row.get(c[0].toUpperCase(Locale.ROOT));
                                                current.put(
                                                        c[0],
                                                        Objects.toString(
                                                                PayrollCalendarService.date(value),
                                                                null));
                                            }
                                            return new HistoryRecord(
                                                    PayrollCalendarService.CALENDAR_ENTITY,
                                                    yymm,
                                                    SCREEN,
                                                    null,
                                                    List.of(
                                                            yymm,
                                                            String.valueOf(row.get("FIN_YEAR"))),
                                                    false,
                                                    List.of(),
                                                    current,
                                                    null);
                                        }));
    }

    @Bean
    RecordHistorySource runOrderHistory(NamedParameterJdbcTemplate named) {
        return RecordHistorySource.of(
                PayrollCalendarService.RUN_ORDER_ENTITY,
                code ->
                        named
                                .queryForList(
                                        "SELECT sequence_no, step_name, depends_on_step,"
                                                + " owner_role_code, payroll_type, is_active FROM"
                                                + " cycle_run_order WHERE engine_step_code = :code",
                                        Map.of("code", code))
                                .stream()
                                .findFirst()
                                .map(
                                        row -> {
                                            Map<String, String> current = new LinkedHashMap<>();
                                            current.put(
                                                    "sequence_no", text(row.get("SEQUENCE_NO")));
                                            current.put("step_name", text(row.get("STEP_NAME")));
                                            current.put(
                                                    "depends_on_step",
                                                    text(row.get("DEPENDS_ON_STEP")));
                                            current.put(
                                                    "owner_role_code",
                                                    text(row.get("OWNER_ROLE_CODE")));
                                            current.put(
                                                    "payroll_type", text(row.get("PAYROLL_TYPE")));
                                            current.put("is_active", text(row.get("IS_ACTIVE")));
                                            return new HistoryRecord(
                                                    PayrollCalendarService.RUN_ORDER_ENTITY,
                                                    code,
                                                    SCREEN,
                                                    null,
                                                    List.of(
                                                            code,
                                                            PayrollCalendarService.RUN_ORDER_KEY),
                                                    false,
                                                    List.of(),
                                                    current,
                                                    null);
                                        }));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
