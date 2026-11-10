package in.gov.fci.payroll.tax.business;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of an I-18 entry (SRS-7-11, SRS-7-20, SRS-7-25; BR-TAX-007, BR-TAX-015): external
 * savings name a section of the master, other income names its head; amounts in rupees and paise;
 * the transport rebate is one row per month of the financial year.
 */
public final class SavingsRules {

    /** {@code amount NUMBER(14,2)}. */
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("999999999999.99");

    /** {@code monthly_amount NUMBER(12,2)}. */
    private static final BigDecimal MONTHLY_MAX = new BigDecimal("9999999999.99");

    private static final Pattern YYMM = Pattern.compile("^[0-9]{6}$");

    private SavingsRules() {}

    /**
     * External savings name a section in force ({@code sectionInForce}); other income names its
     * head (printable ASCII, up to 60) and no section.
     */
    public static void requireKind(
            Type type, String sectionCode, boolean sectionInForce, String incomeHead) {
        boolean ok =
                switch (type) {
                    case EXTERNAL_SAVINGS -> sectionCode != null && sectionInForce;
                    case OTHER_INCOME ->
                            sectionCode == null
                                    && incomeHead != null
                                    && !incomeHead.isBlank()
                                    && incomeHead.length() <= 60
                                    && StandardCharsets.US_ASCII.newEncoder().canEncode(incomeHead);
                };
        if (!ok) {
            throw new FciException(TaxErrorCode.ENTRY_KIND);
        }
    }

    /** SRS-7-25: the amount above zero, the TDS from other sources from zero. */
    public static void requireAmounts(BigDecimal amount, BigDecimal tdsOtherSource) {
        if (!money(amount, AMOUNT_MAX)
                || amount.signum() <= 0
                || !money(tdsOtherSource, AMOUNT_MAX)
                || tdsOtherSource.signum() < 0) {
            throw new FciException(TaxErrorCode.ENTRY_AMOUNT);
        }
    }

    /** Q-08-5: an entry is for the current financial year. */
    public static void requireYear(String finYear, String current) {
        if (!current.equals(finYear)) {
            throw new FciException(
                    TaxErrorCode.ENTRY_YEAR, new Object[] {current}, List.of(), null);
        }
    }

    /**
     * The months of a financial year {@code 2026-27} as YYYYMM: April 2026 to March 2027.
     *
     * @return the twelve months, April first
     */
    public static List<String> monthsOf(String finYear) {
        int start = Integer.parseInt(finYear.substring(0, 4));
        java.util.ArrayList<String> out = new java.util.ArrayList<>(12);
        for (int m = 4; m <= 15; m++) {
            int year = m > 12 ? start + 1 : start;
            int month = m > 12 ? m - 12 : m;
            out.add(String.format("%04d%02d", year, month));
        }
        return out;
    }

    /**
     * The transport rebate rows of a year: at least one, each a month of the year once, not one
     * already approved, with an amount above zero.
     */
    public static void requireRebate(
            String finYear, Map<String, BigDecimal> months, Collection<String> approved) {
        Set<String> year = new HashSet<>(monthsOf(finYear));
        boolean ok = months != null && !months.isEmpty();
        if (ok) {
            for (Map.Entry<String, BigDecimal> m : months.entrySet()) {
                ok &=
                        m.getKey() != null
                                && YYMM.matcher(m.getKey()).matches()
                                && year.contains(m.getKey())
                                && !approved.contains(m.getKey())
                                && money(m.getValue(), MONTHLY_MAX)
                                && m.getValue().signum() > 0;
            }
        }
        if (!ok) {
            throw new FciException(
                    TaxErrorCode.REBATE_MONTHS, new Object[] {finYear}, List.of(), null);
        }
    }

    private static boolean money(BigDecimal v, BigDecimal max) {
        return v != null && v.scale() <= 2 && v.compareTo(max) <= 0;
    }
}
