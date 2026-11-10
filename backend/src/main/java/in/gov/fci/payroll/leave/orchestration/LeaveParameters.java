package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.leave.business.LeaveParameterRules;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The M-20 leave pay parameters in force on a day (plan 19 phase 19b; SRS-LV-14, BR-LEAVE-007 …
 * 009, BR-FNF-009; D19-2): only an approved version ({@code is_confirmed = 'Y'}) is read, the one
 * of the leave type before the one for every type; a closed period reads the version then in force.
 * Nothing is seeded (DDL: "a seeded guess is worse than an empty value"): an absent parameter is
 * empty here and the caller refuses with "not configured" (R-RULE).
 */
@Component
public class LeaveParameters {

    /** The pay effect of a leave type (D19-2), a DDL {@code wage_effect_code} value. */
    public static final String PAY_EFFECT = "PAY_EFFECT";

    public static final String LWP_DIVISOR = "LWP_DIVISOR";
    public static final String ENCASH_DIVISOR = "ENCASH_DIVISOR";
    public static final String ENCASH_PAY_ELEMENTS = "ENCASH_PAY_ELEMENTS";
    public static final String INSERVICE_PER_OCCASION_LIMIT = "INSERVICE_PER_OCCASION_LIMIT";
    public static final String INSERVICE_PER_YEAR_LIMIT = "INSERVICE_PER_YEAR_LIMIT";
    public static final String SEPARATION_AGGREGATE_CEILING = "SEPARATION_AGGREGATE_CEILING";

    public static final String ENCASH_PAY_CODES = "ENCASH_PAY_CODES";

    private final JdbcTemplate jdbc;

    public LeaveParameters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The text value of the parameter for the leave type (or every type) on the day. */
    @Transactional(readOnly = true)
    public Optional<String> text(String code, String leaveType, LocalDate day) {
        return row(code, leaveType, day).map(r -> (String) r.get("TEXT_VALUE"));
    }

    /** The numeric value of the parameter for the leave type (or every type) on the day. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> number(String code, String leaveType, LocalDate day) {
        return row(code, leaveType, day).map(r -> (BigDecimal) r.get("NUMERIC_VALUE"));
    }

    /** D19-2: the type's pay effect on the day, when one of the DDL values is configured. */
    @Transactional(readOnly = true)
    public Optional<String> payEffect(String leaveType, LocalDate day) {
        return text(PAY_EFFECT, leaveType, day)
                .map(String::strip)
                .filter(LeaveParameterRules.PAY_EFFECTS::contains);
    }

    private Optional<Map<String, Object>> row(String code, String leaveType, LocalDate day) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT leave_type_code, numeric_value, text_value FROM leave_pay_parameter"
                                + " WHERE parameter_code = ? AND is_confirmed = 'Y' AND"
                                + " effective_from <= ? AND (effective_to IS NULL OR effective_to"
                                + " >= ?) AND (leave_type_code = ? OR leave_type_code IS NULL)"
                                + " ORDER BY CASE WHEN leave_type_code IS NULL THEN 1 ELSE 0 END,"
                                + " effective_from DESC",
                        code,
                        Date.valueOf(day),
                        Date.valueOf(day),
                        leaveType);
        return rows.stream().findFirst();
    }
}
