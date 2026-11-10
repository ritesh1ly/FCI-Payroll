package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.platform.security.SecureTokens;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The configuration of the flag-driven staff allowances on a day (plan 16 phase 16d; SRS-1-4:
 * "apply CCA, NPA, transport, washing, conveyance, lunch subsidy, PLI and other configured
 * allowances only as set by the employee's Payroll allowance flags on M-01 … Hold all rates and
 * applicability as dated rules"). Read from the masters that hold them, approved versions in force
 * on the day only:
 *
 * <ul>
 *   <li>the pay code of each flag: reference list ELIGIBILITY_FLAG ({@code numeric_attribute},
 *       M-16);
 *   <li>NPA: M-23 {@code other_parameter} NPA_PCT (percent) and NPA_CEILING;
 *   <li>Transport: M-22 rate sets TRANSPORT_IDA / TRANSPORT_CDA, corporation-wide lines by city
 *       class (a line for no city class applies to every class).
 * </ul>
 *
 * Nothing is defaulted (R-RULE): an absent value is empty here and the engine refuses the member.
 * The {@link Allowances#hash()} enters the run's rule-version-set hash, so a change of any of them
 * stops a resume (BR-CYC-011 … 017).
 *
 * <p>OPEN-QUESTION (PENDING 16-allowance-lines): a Transport line scoped to a designation,
 * category, Basic slab or one office is not used yet (the engine has no designation input); a rate
 * set for one location is ignored.
 */
@Component
public class AllowanceConfiguration {

    /** One Transport line: monthly amount for a pay pattern and city class (null = every class). */
    public record TransportRate(String payPattern, String cityClass, BigDecimal amount) {}

    /** The allowance configuration in force on a day. */
    public record Allowances(
            Map<String, Integer> flagPayCodes,
            BigDecimal npaPercent,
            BigDecimal npaCeiling,
            List<TransportRate> transport,
            String hash) {

        public Allowances {
            flagPayCodes = flagPayCodes == null ? Map.of() : Map.copyOf(flagPayCodes);
            transport = transport == null ? List.of() : List.copyOf(transport);
        }

        /** Nothing configured (a snapshot built without the masters, e.g. in a unit test). */
        public static Allowances none() {
            return new Allowances(Map.of(), null, null, List.of(), "");
        }

        /** The legacy pay code an allowance flag is paid under, if set. */
        public Optional<Integer> payCode(String flagCode) {
            return Optional.ofNullable(flagPayCodes.get(flagCode));
        }

        /**
         * The Transport monthly amount for a pay pattern (FND follows CDA) and city class: the line
         * of that class, else the line for every class; empty when none, or when two lines answer.
         */
        public Optional<BigDecimal> transport(String payPattern, String cityClass) {
            String pattern = "FND".equals(payPattern) ? "CDA" : payPattern;
            List<TransportRate> exact =
                    transport.stream()
                            .filter(r -> r.payPattern().equals(pattern))
                            .filter(r -> r.cityClass() != null && r.cityClass().equals(cityClass))
                            .toList();
            List<TransportRate> any =
                    exact.isEmpty()
                            ? transport.stream()
                                    .filter(r -> r.payPattern().equals(pattern))
                                    .filter(r -> r.cityClass() == null)
                                    .toList()
                            : exact;
            return any.size() == 1 ? Optional.of(any.get(0).amount()) : Optional.empty();
        }
    }

    /** In force on the day ({@code ?, ?}), one constant per table alias (no SQL is built). */
    private static final String IN_FORCE_V =
            " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >= ?)";

    private static final String IN_FORCE_P =
            " p.effective_from <= ? AND (p.effective_to IS NULL OR p.effective_to >= ?)";

    private static final String IN_FORCE_S =
            " s.effective_from <= ? AND (s.effective_to IS NULL OR s.effective_to >= ?)";

    private static final String IN_FORCE_S2 =
            " s2.effective_from <= ? AND (s2.effective_to IS NULL OR s2.effective_to >= ?)";

    private final JdbcTemplate jdbc;

    public AllowanceConfiguration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Allowances on(LocalDate day) {
        Date d = Date.valueOf(day);
        Map<String, Integer> codes = new TreeMap<>();
        jdbc.query(
                "SELECT v.value_code, v.numeric_attribute FROM reference_value v JOIN"
                        + " reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                        + " 'ELIGIBILITY_FLAG' AND v.numeric_attribute IS NOT NULL AND"
                        + IN_FORCE_V,
                rs -> {
                    codes.put(rs.getString(1), rs.getBigDecimal(2).intValueExact());
                },
                d,
                d);
        Map<String, Object[]> npa = new TreeMap<>();
        jdbc.query(
                "SELECT p.parameter_code, p.parameter_id, p.percent_value,"
                    + " COALESCE(p.ceiling_value, p.numeric_value) FROM other_parameter p JOIN"
                    + " approval_queue q ON q.approval_id = p.approval_id AND q.status = 'APPROVED'"
                    + " WHERE p.parameter_code IN ('NPA_PCT', 'NPA_CEILING') AND"
                        + IN_FORCE_P
                        + " ORDER BY p.parameter_code, p.version_no DESC",
                rs -> {
                    npa.putIfAbsent(
                            rs.getString(1),
                            new Object[] {rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4)});
                },
                d,
                d);
        Map<String, Long> transportSets = new TreeMap<>();
        List<TransportRate> transport = new java.util.ArrayList<>();
        jdbc.query(
                "SELECT s.rate_set_code, s.rate_set_id, l.city_class, l.rate_value FROM rate_set s"
                        + " JOIN rate_set_line l ON l.rate_set_id = s.rate_set_id WHERE"
                        + " s.rate_set_code IN ('TRANSPORT_IDA', 'TRANSPORT_CDA') AND"
                        + " s.approval_state = 'APPROVED' AND s.location_id IS NULL AND"
                        + IN_FORCE_S
                        + " AND s.version_no = (SELECT MAX(s2.version_no) FROM rate_set s2 WHERE"
                        + " s2.rate_set_code = s.rate_set_code AND s2.approval_state = 'APPROVED'"
                        + " AND s2.location_id IS NULL AND"
                        + IN_FORCE_S2
                        + ") AND l.designation_id IS NULL AND l.category_code IS NULL AND"
                        + " l.basic_slab_from IS NULL AND l.basic_slab_to IS NULL ORDER BY"
                        + " s.rate_set_code, l.city_class",
                rs -> {
                    String code = rs.getString(1);
                    transportSets.put(code, rs.getLong(2));
                    transport.add(
                            new TransportRate(
                                    code.substring("TRANSPORT_".length()),
                                    rs.getString(3),
                                    rs.getBigDecimal(4)));
                },
                d,
                d,
                d,
                d);
        Object[] pct = npa.get("NPA_PCT");
        Object[] ceiling = npa.get("NPA_CEILING");
        String versions =
                codes
                        + "|npa="
                        + (pct == null ? "-" : pct[0])
                        + ","
                        + (ceiling == null ? "-" : ceiling[0])
                        + "|transport="
                        + transportSets;
        return new Allowances(
                codes,
                pct == null ? null : (BigDecimal) pct[1],
                ceiling == null ? null : (BigDecimal) ceiling[2],
                transport,
                SecureTokens.sha256Hex(versions));
    }
}
