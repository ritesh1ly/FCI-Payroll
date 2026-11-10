package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The rules of a CPF advance, part-final or final request through the three-tier chain (SRS-6-2,
 * SRS-6-4, SRS-6-5, SRS-6-14, SRS-6-24, SRS-6-26, SRS-6-36 to 39, SRS-6-55; BR-CPF-001 to 011, 029
 * to 032, 039; DDL {@code pf_sanction}, {@code cpf_sanction_tier}). Amounts are worked at two
 * decimals (R-01); a computed ceiling is rounded down to the paisa, so a sanction never exceeds the
 * fraction the rule states.
 */
public final class SanctionRules {

    /** {@code pf_sanction.workflow_stage} (DDL CHECK). */
    public enum Stage {
        UNIT_VERIFICATION,
        ZONAL_HQ_VALIDATION,
        COMPETENT_AUTHORITY,
        SANCTIONED,
        RETURNED,
        REJECTED,
        PAYMENT_BLOCKED,
        PAID,
        CANCELLED;

        /** Still in the chain or sanctioned and not paid (BR-CPF-030, built reading of Q-07-4). */
        public boolean active() {
            return this != RETURNED && this != REJECTED && this != PAID && this != CANCELLED;
        }

        /** The member may still withdraw it (before the sanction). */
        public boolean withdrawable() {
            return this == UNIT_VERIFICATION
                    || this == ZONAL_HQ_VALIDATION
                    || this == COMPETENT_AUTHORITY
                    || this == RETURNED;
        }
    }

    /** A checklist entry: the document type and whether it is mandatory. */
    public record ChecklistItem(String documentType, boolean mandatory) {}

    /** The amount a sanction goes ahead at, and whether the request was capped (SRS-6-26). */
    public record Capped(BigDecimal sanctioned, boolean wasCapped) {}

    private SanctionRules() {}

    /** SRS I-04 / E-01 field table: more than zero, at most two decimals. */
    public static void requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new FciException(CpfErrorCode.REQUEST_AMOUNT);
        }
    }

    /** SRS I-04 field table "Supporting Documents": File (PDF / JPG / PNG, up to 5 files). */
    public static final int MAX_DOCUMENTS = 5;

    /** SRS I-04 field table: at most {@link #MAX_DOCUMENTS} supporting documents per request. */
    public static void requireDocumentCount(int attached) {
        if (attached > MAX_DOCUMENTS) {
            throw new FciException(
                    CpfErrorCode.DOCUMENT_COUNT, new Object[] {MAX_DOCUMENTS}, List.of(), null);
        }
    }

    /** BR-CPF-039: the purposes that need them carry the property's address and description. */
    public static void requireProperty(boolean required, String address, String description) {
        if (required && (blank(address) || blank(description))) {
            throw new FciException(CpfErrorCode.PROPERTY_DETAILS);
        }
    }

    /**
     * SRS-6-14: the mandatory checklist documents not attached, in checklist order; submission is
     * blocked naming them.
     */
    public static List<String> missingDocuments(
            Collection<ChecklistItem> checklist, Collection<String> attachedTypes) {
        return checklist.stream()
                .filter(ChecklistItem::mandatory)
                .map(ChecklistItem::documentType)
                .filter(type -> !attachedTypes.contains(type))
                .toList();
    }

    /**
     * SRS-6-5 / BR-CPF-009: at least the configured years of CPF membership on the request date.
     */
    public static void requireMembership(
            LocalDate epsJoin, LocalDate requestDate, BigDecimal minYears) {
        if (epsJoin == null) {
            throw dataMissing("eps_join_date");
        }
        if (Period.between(epsJoin, requestDate).getYears() < whole(minYears)) {
            throw new FciException(
                    CpfErrorCode.MEMBERSHIP_YEARS, new Object[] {whole(minYears)}, List.of(), null);
        }
    }

    /**
     * SRS-6-5 / BR-CPF-010: fewer part-final withdrawals in service than the configured maximum.
     */
    public static void requireCount(long partFinalSoFar, BigDecimal maxCount) {
        if (partFinalSoFar >= whole(maxCount)) {
            throw new FciException(
                    CpfErrorCode.WITHDRAWAL_COUNT, new Object[] {whole(maxCount)}, List.of(), null);
        }
    }

    /**
     * SRS-6-5 / BR-CPF-011: the 90 % withdrawal only within the configured years before retirement.
     */
    public static void requireRetirementWindow(
            LocalDate retirement, LocalDate requestDate, BigDecimal years) {
        if (retirement == null) {
            throw dataMissing("retirement_date");
        }
        if (requestDate.isAfter(retirement)
                || requestDate.isBefore(retirement.minusYears(whole(years)))) {
            throw new FciException(
                    CpfErrorCode.RETIREMENT_WINDOW, new Object[] {whole(years)}, List.of(), null);
        }
    }

    /** SRS-6-37 / BR-CPF-030: no new advance while another is active. */
    public static void requireNoActiveAdvance(boolean anotherActive) {
        if (anotherActive) {
            throw new FciException(CpfErrorCode.ACTIVE_ADVANCE);
        }
    }

    /**
     * SRS-6-2 / SRS-6-24: the ceiling of a limit from its multiplier and basis, or empty when an
     * input it needs is not configured or not available (the MGW basis, OQ-3; the pay of SRS-6-24).
     *
     * @param balance the member's current balance (employee, employer, VPF)
     */
    public static Optional<BigDecimal> ceiling(
            WithdrawalLimit limit,
            BigDecimal multiplier,
            BigDecimal mgw,
            LedgerBalances balance,
            BigDecimal pay) {
        if (multiplier == null) {
            return Optional.empty();
        }
        return switch (limit.basis()) {
            case MGW_MULTIPLE -> Optional.ofNullable(mgw).map(m -> paise(m.multiply(multiplier)));
            case BALANCE_FRACTION -> Optional.of(paise(total(balance).multiply(multiplier)));
            case PAY_MULTIPLE_OR_OWN_SHARE ->
                    Optional.ofNullable(pay)
                            .map(p -> paise(p.multiply(multiplier)).min(ownShare(balance)));
        };
    }

    /** SRS-6-2: an advance is also capped at the configured fraction of (employee + VPF). */
    public static BigDecimal advanceCap(BigDecimal fraction, LedgerBalances balance) {
        return paise(ownShare(balance).multiply(fraction));
    }

    /** SRS-6-26: above the ceiling the request is capped at it, never rejected. */
    public static Capped cap(BigDecimal requested, BigDecimal ceiling) {
        return requested.compareTo(ceiling) > 0
                ? new Capped(ceiling, true)
                : new Capped(requested, false);
    }

    /**
     * SRS-6-38 / BR-CPF-031: month + year + zone + running sequence, in that order, joined by the
     * configured separator, the sequence padded to the configured width (Q-07-1).
     */
    public static String sanctionNumber(
            LocalDate sanctionDate, String zoneCode, long sequence, String separator, int width) {
        String seq = String.valueOf(sequence);
        if (seq.length() < width) {
            seq = "0".repeat(width - seq.length()) + seq;
        }
        return String.join(
                separator,
                String.format("%02d", sanctionDate.getMonthValue()),
                String.valueOf(sanctionDate.getYear()),
                zoneCode,
                seq);
    }

    /** SRS-6-4 step 4: the payment is blocked when the balance cannot carry the sanction. */
    public static boolean paymentBlocked(LedgerBalances balance, BigDecimal sanctioned) {
        return total(balance).compareTo(sanctioned) < 0;
    }

    static BigDecimal total(LedgerBalances b) {
        return b.employee().add(b.employer()).add(b.vpf());
    }

    private static BigDecimal ownShare(LedgerBalances b) {
        return b.employee().add(b.vpf());
    }

    private static BigDecimal paise(BigDecimal v) {
        return v.setScale(2, RoundingMode.DOWN);
    }

    private static int whole(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).intValueExact();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static FciException dataMissing(String field) {
        return new FciException(
                CpfErrorCode.ELIGIBILITY_DATA, new Object[] {field}, List.of(), null);
    }
}
