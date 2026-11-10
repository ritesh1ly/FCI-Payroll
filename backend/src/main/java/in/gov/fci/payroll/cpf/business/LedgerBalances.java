package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A CPF / PF balance per part: employee, employer and VPF, kept separately (FRS V0.5 §13
 * EMPLOYEE_PART, EMPLOYER_PART, VPF_PART). SRS-6-57 / BR-CPF-025: no transaction (contribution,
 * adjustment, withdrawal, recovery or correction) may make any part negative; {@link #apply} is the
 * one place that checks it, for every posting path.
 */
public record LedgerBalances(BigDecimal employee, BigDecimal employer, BigDecimal vpf) {

    /** The parts of a CPF balance, in the order the ledger names them. */
    public enum Part {
        EMPLOYEE,
        EMPLOYER,
        VPF
    }

    public static final LedgerBalances ZERO =
            new LedgerBalances(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public LedgerBalances {
        Objects.requireNonNull(employee, "employee");
        Objects.requireNonNull(employer, "employer");
        Objects.requireNonNull(vpf, "vpf");
    }

    /** One part's amount. */
    public BigDecimal part(Part part) {
        return switch (part) {
            case EMPLOYEE -> employee;
            case EMPLOYER -> employer;
            case VPF -> vpf;
        };
    }

    /** The three parts together (the member's balance). */
    public BigDecimal total() {
        return employee.add(employer).add(vpf);
    }

    /**
     * SRS-6-57: these balances moved by {@code change} (signed: a withdrawal or recovery is
     * negative); refused, naming the first part that would go below zero, when any would.
     */
    public LedgerBalances apply(LedgerBalances change) {
        LedgerBalances after =
                new LedgerBalances(
                        employee.add(change.employee),
                        employer.add(change.employer),
                        vpf.add(change.vpf));
        for (Part p : Part.values()) {
            if (after.part(p).signum() < 0) {
                throw new FciException(
                        CpfErrorCode.NEGATIVE_BALANCE, new Object[] {p.name()}, List.of(), null);
            }
        }
        return after;
    }

    /** The same amounts with the sign reversed (a debit of these balances). */
    public LedgerBalances negate() {
        return new LedgerBalances(employee.negate(), employer.negate(), vpf.negate());
    }
}
