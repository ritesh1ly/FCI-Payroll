package in.gov.fci.payroll.payroll.api;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The pay code a statutory deduction is recovered under (plan 16 phase 16e-2; SRS-1-6): the M-16
 * list STATUTORY_DEDUCTION, {@code numeric_attribute} of the value in force on the day. No source
 * names the legacy codes, so each is configuration and an unset code is "not configured" (D16-24).
 */
public final class StatutoryPayCodes {

    private StatutoryPayCodes() {}

    /** The pay code of {@code deduction} (CPF, VPF, TDS, PT) in force on {@code day}, if set. */
    public static Optional<Integer> of(JdbcTemplate jdbc, String deduction, LocalDate day) {
        List<BigDecimal> codes =
                jdbc.queryForList(
                        "SELECT v.numeric_attribute FROM reference_value v JOIN reference_list l ON"
                                + " l.list_id = v.list_id WHERE l.list_code = 'STATUTORY_DEDUCTION'"
                                + " AND v.value_code = ? AND v.numeric_attribute IS NOT NULL AND"
                                + " v.effective_from <= ? AND (v.effective_to IS NULL OR"
                                + " v.effective_to >= ?) ORDER BY v.effective_from DESC",
                        BigDecimal.class,
                        deduction,
                        Date.valueOf(day),
                        Date.valueOf(day));
        return codes.isEmpty() ? Optional.empty() : Optional.of(codes.get(0).intValueExact());
    }
}
