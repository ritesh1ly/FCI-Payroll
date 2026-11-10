package in.gov.fci.payroll.cpf.business;

import java.util.Arrays;
import java.util.Optional;

/**
 * The CPF sanction limits whose basis the SRS states (SRS-6-2, SRS-6-24; BR-CPF-001 to 008): the
 * rule key holding the multiplier and what it multiplies. The multipliers are rule versions on M-19
 * (seeded from SRS-6-2, changeable through M-08); a purpose whose limit the SRS does not state
 * (illness, construction, home-loan repayment, special circumstances: "carried forward from the
 * current process", SRS-6-39, Q-074) carries no limit key and answers "not configured" at tier 2.
 */
public enum WithdrawalLimit {
    MARRIAGE("CPF.LIMIT.MGW.MARRIAGE", Basis.MGW_MULTIPLE),
    RELIGIOUS("CPF.LIMIT.MGW.RELIGIOUS", Basis.MGW_MULTIPLE),
    HIGHER_EDUCATION("CPF.LIMIT.MGW.HIGHER_EDUCATION", Basis.MGW_MULTIPLE),
    HOUSE("CPF.LIMIT.MGW.HOUSE", Basis.MGW_MULTIPLE),
    PLOT("CPF.LIMIT.MGW.PLOT", Basis.MGW_MULTIPLE),
    /** SRS-6-24: least of 12 x pay and (employee + VPF contribution), never a flat MGW multiple. */
    RENOVATION("CPF.LIMIT.PAY.RENOVATION", Basis.PAY_MULTIPLE_OR_OWN_SHARE),
    /** SRS-6-2: 0.9 x (employee + employer + VPF). */
    BEFORE_RETIREMENT("CPF.LIMIT.BALANCE.BEFORE_RETIREMENT", Basis.BALANCE_FRACTION);

    /** What a limit's multiplier multiplies. */
    public enum Basis {
        /** The member's Minimum Guaranteed Wage, set per payroll type (SRS-6-23; OQ-3). */
        MGW_MULTIPLE,
        /** The whole balance: employee + employer + VPF. */
        BALANCE_FRACTION,
        /** Least of (multiplier x pay) and (employee + VPF). */
        PAY_MULTIPLE_OR_OWN_SHARE
    }

    /** SRS-6-2: every advance is also capped at this fraction of (employee + VPF). */
    public static final String ADVANCE_CAP = "CPF.LIMIT.BALANCE.ADVANCE_CAP";

    private final String ruleKey;
    private final Basis basis;

    WithdrawalLimit(String ruleKey, Basis basis) {
        this.ruleKey = ruleKey;
        this.basis = basis;
    }

    public String ruleKey() {
        return ruleKey;
    }

    public Basis basis() {
        return basis;
    }

    /** The limit held under {@code ruleKey}, if it is one of these. */
    public static Optional<WithdrawalLimit> ofRuleKey(String ruleKey) {
        return Arrays.stream(values()).filter(l -> l.ruleKey.equals(ruleKey)).findFirst();
    }
}
