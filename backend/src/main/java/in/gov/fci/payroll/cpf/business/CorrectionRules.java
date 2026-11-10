package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.cpf.business.LedgerBalances.Part;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules of an I-22 PF / CPF opening-balance correction (SRS-6-43, SRS-6-44, BR-CPF-025): at
 * least one part changes; no part becomes negative; each changed part is its own adjustment row
 * beside the old amount (never an overwrite). Pure: the service checks a request with them at
 * submission and again before the correction is applied.
 */
public final class CorrectionRules {

    /** One part the correction changes, from its current opening amount to the corrected one. */
    public record Change(Part part, BigDecimal oldAmount, BigDecimal newAmount) {

        /** The signed difference the ledger moves by. */
        public BigDecimal difference() {
            return newAmount.subtract(oldAmount);
        }
    }

    private CorrectionRules() {}

    /**
     * SRS-6-43 / SRS-6-44: the parts that change, from the current opening balance to the corrected
     * amounts (null = the part is not corrected; an amount equal to the current one does not count
     * as a change). Refused when no part changes, or a corrected amount is negative.
     */
    public static List<Change> changes(
            LedgerBalances current, BigDecimal employee, BigDecimal employer, BigDecimal vpf) {
        List<Change> out = new ArrayList<>();
        BigDecimal[] corrected = {employee, employer, vpf};
        for (Part part : Part.values()) {
            BigDecimal value = corrected[part.ordinal()];
            if (value == null) {
                continue;
            }
            if (value.signum() < 0) {
                throw new FciException(
                        CpfErrorCode.NEGATIVE_BALANCE, new Object[] {part.name()}, List.of(), null);
            }
            if (value.compareTo(current.part(part)) != 0) {
                out.add(new Change(part, current.part(part), value));
            }
        }
        if (out.isEmpty()) {
            throw new FciException(CpfErrorCode.NO_CHANGE);
        }
        return List.copyOf(out);
    }

    /** The ledger change of a correction: the differences, per part. */
    public static LedgerBalances difference(List<Change> changes) {
        BigDecimal[] d = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Change c : changes) {
            d[c.part().ordinal()] = c.difference();
        }
        return new LedgerBalances(d[0], d[1], d[2]);
    }

    /**
     * SRS-6-44: a correction that lowers a part may change a withdrawal sanctioned on the old
     * balance (its limit rested on it); it is blocked and routed to the CPF Division for review. A
     * correction that only raises parts cannot reduce what a sanction rested on.
     */
    public static boolean blockedBySanction(boolean sanctionedSinceAsAt, List<Change> changes) {
        return sanctionedSinceAsAt && changes.stream().anyMatch(c -> c.difference().signum() < 0);
    }

    /** The request number, e.g. OB-2026-000031 (the year it was raised, a running number). */
    public static String reference(int year, long number) {
        return String.format("OB-%d-%06d", year, number);
    }
}
