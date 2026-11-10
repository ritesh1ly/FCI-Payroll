package in.gov.fci.payroll.payroll.api;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.payroll.batch.computation.MemberReader;
import in.gov.fci.payroll.payroll.business.PayDataException;
import in.gov.fci.payroll.payroll.business.PayRules;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The salary of one member for one month computed outside the regular cycle (I-20, SRS-G-10: "The
 * system computes the pending salary outside the regular month-end cycle"), with exactly the inputs
 * and rules of the regular computation: the member as {@code MemberReader} reads him, the rates in
 * force on the first day of the month, and {@link PayRules}. The rules are PROVISIONAL like the
 * regular run's (Basic, DA, HRA, CCA; no deductions yet - module 16).
 */
@Component
public class PendingSalary {

    /**
     * The computed salary.
     *
     * @param computed false when nothing is payable (salary stopped / held, or no basic pay);
     *     {@code skipReason} then says why
     * @param lines pay code and amount of each component
     */
    public record Result(
            boolean computed,
            String skipReason,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            List<Line> lines,
            String rateVersionHash) {

        /**
         * SRS-1-41: the month is a Salary Stop, which suppresses its computation and payment; a
         * Salary Withheld or a member with no Basic pay is something else (F&amp;F, SRS-10-28).
         */
        public boolean salaryStopped() {
            return !computed && "STOP_PAYMENT".equals(skipReason);
        }
    }

    public record Line(int payCode, BigDecimal amount) {}

    /**
     * The member's data or the rates are not complete: the amount cannot be computed (never zero).
     */
    public static class NotComputable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotComputable(String reason) {
            super(reason);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PayConfiguration configuration;
    private final StaffDeductionRunner deductions;

    public PendingSalary(NamedParameterJdbcTemplate jdbc, PayConfiguration configuration) {
        this(jdbc, configuration, null);
    }

    /** With the month's deductions, netted as the run nets them (plan 16 phase 16e-2). */
    @org.springframework.beans.factory.annotation.Autowired
    public PendingSalary(
            NamedParameterJdbcTemplate jdbc,
            PayConfiguration configuration,
            StaffDeductionRunner deductions) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.deductions = deductions;
    }

    /**
     * Computes the month's salary of the member.
     *
     * @throws NotComputable when the member is unknown or a rate / pay code / member field is
     *     missing
     */
    @Transactional(readOnly = true)
    public Result compute(Long employeeId, YearMonth month) {
        MemberReader.MemberRow row =
                MemberReader.member(jdbc, employeeId, month.atDay(1))
                        .orElseThrow(
                                () ->
                                        new NotComputable(
                                                "Employee "
                                                        + employeeId
                                                        + " is not on the Payroll master"));
        PayConfiguration.Snapshot rates = configuration.staffConfiguration(month.atDay(1));
        PayRules.Outcome o;
        try {
            o = PayRules.compute(row.member(), rates);
            if (deductions != null) {
                o =
                        deductions
                                .open(month)
                                .apply(
                                        o,
                                        row.employeeId(),
                                        row.locationId(),
                                        row.payPattern(),
                                        rates);
            }
        } catch (PayDataException ex) {
            throw new NotComputable(ex.getMessage());
        }
        // An off-cycle payment (I-20) of a negative net is not a payment: refused, as before
        // SRS-1-8 moved the negative net of the cycle to the quarantine (BMAD TR-037).
        if (o.net() != null && o.net().signum() < 0) {
            throw new NotComputable(
                    "Net pay below zero after deductions: gross "
                            + o.gross()
                            + ", deductions "
                            + o.deductions());
        }
        List<Line> lines = o.lines().stream().map(l -> new Line(l.payCode(), l.amount())).toList();
        // SRS-1-41: a withheld or held salary is not paid off-cycle either: the hold is its reason.
        return new Result(
                PayRules.COMPUTED.equals(o.status()) && o.paymentHold() == null,
                o.paymentHold() != null ? o.paymentHold() : o.skipReason(),
                o.gross(),
                o.deductions(),
                o.net(),
                lines,
                rates.versionSetHash());
    }
}
