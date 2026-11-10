package in.gov.fci.payroll.tax.business;

import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The rules of the statutory certificates and returns (SRS-7-5, SRS-7-7, SRS-7-8, SRS-7-19,
 * SRS-7-21, SRS-7-31; BR-TAX-010 … 012): the quarters of a financial year, no output for an
 * unlocked period, reconciliation to the posted payroll and the remitted challans before release,
 * and the lines of the challan file (Q-08-3: the {@code tax_challan} columns as CSV).
 */
public final class StatutoryRules {

    private static final Pattern CODE = Pattern.compile("^[0-9A-Za-z]{1,30}$");
    private static final Pattern QUARTER = Pattern.compile("^Q[1-4]$");

    private StatutoryRules() {}

    /** One parsed challan of the file. */
    public record ChallanLine(
            String cin,
            String bsr,
            LocalDate date,
            BigDecimal amount,
            String reference,
            String period) {}

    /** The months (YYYYMM) of a quarter of the financial year: Q1 April-June … Q4 January-March. */
    public static List<String> quarterMonths(String finYear, String quarter) {
        if (quarter == null || !QUARTER.matcher(quarter).matches()) {
            throw new FciException(
                    TaxErrorCode.DECLARATION_STATE,
                    new Object[] {"quarter " + quarter},
                    List.of(),
                    null);
        }
        int q = quarter.charAt(1) - '0';
        List<String> year = SavingsRules.monthsOf(finYear);
        return year.subList((q - 1) * 3, q * 3);
    }

    /** SRS-7-8: every month locked, or the first unlocked one is named. */
    public static void requireLocked(List<String> months, Predicate<String> locked) {
        for (String m : months) {
            if (!locked.test(m)) {
                throw new FciException(
                        TaxErrorCode.PERIOD_NOT_LOCKED, new Object[] {m}, List.of(), null);
            }
        }
    }

    /**
     * SRS-7-8, BR-TAX-011, BR-TAX-012: the differences that block release, empty when the output's
     * total agrees with the posted TDS and the remitted challans within the tolerance.
     */
    public static List<String> differences(
            BigDecimal outputTotal,
            Optional<BigDecimal> postedTds,
            BigDecimal challanTotal,
            BigDecimal tolerance) {
        List<String> out = new ArrayList<>();
        if (postedTds.isEmpty()) {
            out.add("the posted payroll is not available (posting interface not yet connected)");
        } else if (outputTotal.subtract(postedTds.get()).abs().compareTo(tolerance) > 0) {
            out.add(
                    "TDS "
                            + outputTotal.toPlainString()
                            + " against posted "
                            + postedTds.get().toPlainString());
        }
        if (outputTotal.subtract(challanTotal).abs().compareTo(tolerance) > 0) {
            out.add(
                    "TDS "
                            + outputTotal.toPlainString()
                            + " against challans "
                            + challanTotal.toPlainString());
        }
        return out;
    }

    /**
     * One line of the challan file: {@code cin,bsr,date (YYYY-MM-DD),amount,reference,period}. The
     * date lies in the financial year; the period is a quarter or a month of the year.
     */
    public static ChallanLine parseChallan(int lineNo, String line, String finYear) {
        String[] f = line.split(",", -1);
        if (f.length != 6) {
            throw challan(lineNo, "six columns: cin, bsr, date, amount, reference, period");
        }
        String cin = f[0].strip();
        String bsr = f[1].strip();
        if (!CODE.matcher(cin).matches() || !CODE.matcher(bsr).matches() || bsr.length() > 20) {
            throw challan(lineNo, "the CIN and BSR code are letters and digits");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(f[2].strip());
        } catch (DateTimeParseException e) {
            throw challan(lineNo, "the date as YYYY-MM-DD");
        }
        List<String> months = SavingsRules.monthsOf(finYear);
        String yymm = String.format("%04d%02d", date.getYear(), date.getMonthValue());
        if (!months.contains(yymm)) {
            throw challan(lineNo, "a date in " + finYear);
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(f[3].strip());
        } catch (NumberFormatException e) {
            throw challan(lineNo, "the amount in rupees and paise");
        }
        if (amount.signum() <= 0
                || amount.scale() > 2
                || amount.precision() - amount.scale() > 16) {
            throw challan(lineNo, "an amount above zero with at most two decimals");
        }
        String reference = f[4].strip();
        if (reference.length() > 50
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(reference)) {
            throw challan(lineNo, "a payment reference of up to 50 printable characters");
        }
        String period = f[5].strip();
        if (!QUARTER.matcher(period).matches() && !months.contains(period)) {
            throw challan(lineNo, "the period as Q1-Q4 or a month (YYYYMM) of " + finYear);
        }
        return new ChallanLine(
                cin, bsr, date, amount, reference.isEmpty() ? null : reference, period);
    }

    /** The quarter (Q1-Q4) a month (YYYYMM) of the financial year belongs to. */
    public static String quarterOf(String finYear, String yymm) {
        int i = SavingsRules.monthsOf(finYear).indexOf(yymm);
        return i < 0 ? null : "Q" + (i / 3 + 1);
    }

    private static FciException challan(int lineNo, String why) {
        return new FciException(
                TaxErrorCode.CHALLAN_FILE, new Object[] {lineNo, why}, List.of(), null);
    }
}
