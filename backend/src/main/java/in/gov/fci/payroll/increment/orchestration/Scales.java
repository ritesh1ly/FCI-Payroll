package in.gov.fci.payroll.increment.orchestration;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The pay scale of a member on a day (plan 17; M-03, SRS-13-23): the approved version of the
 * member's band in force on the day, with its bounds, stagnation amount, increment rule and stages
 * (D17-3). A version not yet approved by the Payroll Checker is not in force.
 */
@Component
public class Scales {

    /** A scale version in force. */
    public record Scale(
            long bandId,
            String bandCode,
            long versionId,
            BigDecimal min,
            BigDecimal max,
            BigDecimal stagnationAmount,
            String incrementRuleKey,
            List<BigDecimal> stages) {}

    private final NamedParameterJdbcTemplate named;

    public Scales(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** The scale of the member's band in force on the day; empty when the member has none. */
    public Optional<Scale> ofMember(long employeeId, LocalDate day) {
        List<Long> bands =
                named.queryForList(
                        "SELECT band_id FROM employee WHERE employee_id = :id AND band_id IS NOT"
                                + " NULL",
                        new MapSqlParameterSource("id", employeeId),
                        Long.class);
        return bands.isEmpty() ? Optional.empty() : of(bands.getFirst(), day);
    }

    /**
     * The version of the band in force on the day (an approved one: approval_id set, or seeded).
     */
    public Optional<Scale> of(long bandId, LocalDate day) {
        MapSqlParameterSource p =
                new MapSqlParameterSource("band", bandId).addValue("day", Date.valueOf(day));
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT v.band_version_id, v.min_basic, v.max_basic,"
                            + " v.stagnation_increment_amt, b.band_code, b.increment_rule_key FROM"
                            + " pay_scale_band_version v JOIN pay_scale_band b ON b.band_id ="
                            + " v.band_id LEFT JOIN approval_queue q ON q.approval_id ="
                            + " v.approval_id WHERE v.band_id = :band AND v.effective_from <= :day"
                            + " AND (v.effective_to IS NULL OR v.effective_to >= :day) AND"
                            + " (v.approval_id IS NULL OR q.status = 'APPROVED') ORDER BY"
                            + " v.effective_from DESC",
                        p);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> r = rows.getFirst();
        long version = ((Number) r.get("BAND_VERSION_ID")).longValue();
        List<BigDecimal> stages =
                named.queryForList(
                        "SELECT stage_basic FROM pay_scale_band_stage WHERE band_version_id = :v"
                                + " ORDER BY stage_basic",
                        new MapSqlParameterSource("v", version),
                        BigDecimal.class);
        return Optional.of(
                new Scale(
                        bandId,
                        (String) r.get("BAND_CODE"),
                        version,
                        (BigDecimal) r.get("MIN_BASIC"),
                        (BigDecimal) r.get("MAX_BASIC"),
                        (BigDecimal) r.get("STAGNATION_INCREMENT_AMT"),
                        (String) r.get("INCREMENT_RULE_KEY"),
                        stages));
    }
}
