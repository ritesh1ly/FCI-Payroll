package in.gov.fci.payroll.configuration.business;

/**
 * The four payroll types a master can be scoped to, with the codes of target DDL v4.0 ({@code
 * circular_order_scope.payroll_type}): Employee / Staff (IDA, CDA, FND), Departmental, DPS and NWNP
 * labour (SRS-16-13, SRS-G-38). Used for {@code net_pay_floor_rule.payroll_type} too, whose column
 * has no CHECK (plan 04 D-4).
 */
public enum PayrollType {
    STAFF,
    DEPT_LABOUR,
    DPS_LABOUR,
    NWNP_LABOUR;

    /** The type of {@code code}, or null when it names none. */
    public static PayrollType of(String code) {
        for (PayrollType t : values()) {
            if (t.name().equals(code)) {
                return t;
            }
        }
        return null;
    }
}
