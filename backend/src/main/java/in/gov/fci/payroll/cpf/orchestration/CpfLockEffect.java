package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.cpf.business.EmployerShare;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.model.EmployeePfDetail.PaymentType;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.StatutoryPayCodes;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * When a staff cycle locks, the month's CPF of each member it computed is booked to the member's
 * ledger (plan 07 phase 07i part 2; SRS-6-1, SRS-6-7, SRS-6-57, BR-CPF-017, BR-CPF-018; DDL {@code
 * employee_pf_detail}): one SALARY row with the CPF wage base, the employee contribution and VPF as
 * deducted, and the employer contribution with its EPS / higher-pension split at the M-18 rates of
 * the month ({@link EmployerShare}, the rules the computation checked). Posted through {@link
 * CpfLedgerService#post}, so a negative part, a closed ledger or a member without a CPF code
 * refuses the lock rather than book a wrong ledger. In the lock's transaction.
 */
@Component
public class CpfLockEffect {

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;
    private final RuleEngine rules;
    private final CpfLedgerService ledger;
    private final SystemParameters parameters;

    public CpfLockEffect(
            JdbcTemplate jdbc,
            RuleEngine rules,
            CpfLedgerService ledger,
            SystemParameters parameters) {
        this.parameters = parameters;
        this.jdbc = jdbc;
        this.rules = rules;
        this.ledger = ledger;
    }

    /** One member's CPF lines of the cycle. */
    private static final class Month {
        BigDecimal base;
        BigDecimal employee = BigDecimal.ZERO;
        BigDecimal vpf = BigDecimal.ZERO;
        long locationId;
        LocalDate birthDate;
    }

    @EventListener
    public void on(CycleLocked locked) {
        LocalDate first = YearMonth.parse(locked.payrollYymm(), YYMM).atDay(1);
        Optional<Integer> cpf = StatutoryPayCodes.of(jdbc, "CPF", first);
        if (cpf.isEmpty()) {
            return; // no CPF pay code: the computation deducted no CPF this month
        }
        int vpf = StatutoryPayCodes.of(jdbc, "VPF", first).orElse(cpf.get());
        Map<Long, Month> members = new LinkedHashMap<>();
        jdbc.query(
                "SELECT c.employee_id, c.legacy_pay_code, c.amount, c.basis_amount, e.location_id,"
                        + " e.birth_date FROM payroll_result r JOIN payroll_result_component c ON"
                        + " c.payroll_result_id = r.payroll_result_id AND c.period_date ="
                        + " r.period_date JOIN employee e ON e.employee_id = c.employee_id WHERE"
                        + " r.cycle_instance_id = ? AND r.period_date = ? AND r.run_type ="
                        + " 'ACTUAL' AND r.result_status = 'CURRENT' AND c.legacy_pay_code IN (?,"
                        + " ?) ORDER BY c.employee_id",
                rs -> {
                    Month m = members.computeIfAbsent(rs.getLong(1), id -> new Month());
                    BigDecimal amount = rs.getBigDecimal(3).abs();
                    if (rs.getInt(2) == cpf.get()) {
                        m.employee = amount;
                        m.base = rs.getBigDecimal(4);
                    } else {
                        m.vpf = amount;
                    }
                    m.locationId = rs.getLong(5);
                    java.sql.Date birth = rs.getDate(6);
                    m.birthDate = birth == null ? null : birth.toLocalDate();
                },
                locked.cycleInstanceId(),
                Date.valueOf(first),
                cpf.get(),
                vpf);
        if (members.isEmpty()) {
            return;
        }
        RuleSet set = rules.snapshot(first);
        // SRS-1-76, SRS-1-59 (US-GAP-6): EPS stops from the month the member reaches 58 (A-07).
        int epsAge =
                (int)
                        parameters
                                .configured(SystemParameters.Code.EPS_CUTOFF_AGE)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "EPS_CUTOFF_AGE not configured (A-07)"));
        YearMonth month = YearMonth.from(first);
        String reason = "Cycle " + locked.payrollYymm() + " locked: CPF of the month";
        members.forEach(
                (employeeId, m) -> {
                    if (m.base == null) {
                        return; // VPF without CPF cannot be computed (the VPF maximum refuses it)
                    }
                    EmployerShare computed =
                            CpfContributionDeduction.share(
                                    set, new RuleEngine.Context("STAFF", m.locationId), m.base);
                    EmployerShare share =
                            m.birthDate != null
                                            && EmployerShare.epsStopped(m.birthDate, epsAge, month)
                                    ? computed.withoutPension()
                                    : computed;
                    ledger.post(
                            new CpfLedgerService.Posting(
                                    employeeId,
                                    first,
                                    PaymentType.SALARY,
                                    new LedgerBalances(m.employee, share.employer(), m.vpf),
                                    m.base,
                                    share.eps(),
                                    share.pohw(),
                                    share.rounding(),
                                    locked.lockedBy(),
                                    reason));
                });
    }
}
