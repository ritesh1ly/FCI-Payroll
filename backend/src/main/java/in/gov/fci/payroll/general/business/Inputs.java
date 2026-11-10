package in.gov.fci.payroll.general.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.regex.Pattern;

/** Input rules shared by the Module 01 transactions (payroll months, free text). */
public final class Inputs {

    /** A payroll month as the DDL keeps it ({@code *_yymm}: YYYYMM, 2000-2099). */
    private static final Pattern YYMM = Pattern.compile("^20\\d{2}(0[1-9]|1[0-2])$");

    private Inputs() {}

    /**
     * The month of a YYYYMM value.
     *
     * @throws FciException {@code invalid} when the value is not a payroll month
     */
    public static YearMonth month(String yymm, ErrorCode invalid) {
        if (yymm == null || !YYMM.matcher(yymm).matches()) {
            throw new FciException(invalid);
        }
        return YearMonth.of(
                Integer.parseInt(yymm.substring(0, 4)), Integer.parseInt(yymm.substring(4)));
    }

    /** The YYYYMM value of a month. */
    public static String yymm(YearMonth month) {
        return String.format("%04d%02d", month.getYear(), month.getMonthValue());
    }

    /**
     * Required free text, stripped: printable ASCII only (English letters, digits and punctuation,
     * as for every Module 01 text field; VARCHAR2 lengths are counted in bytes), at most {@code
     * max}.
     *
     * @throws FciException {@code invalid} when blank, too long or not printable ASCII
     */
    public static String text(String value, int max, ErrorCode invalid) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()
                || v.length() > max
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(v)
                || v.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new FciException(invalid);
        }
        return v;
    }

    /**
     * Required multi-line free text (X-06 query, response, resolution): as {@link #text} but line
     * breaks and tabs are kept.
     */
    public static String multilineText(String value, int max, ErrorCode invalid) {
        String v = value == null ? "" : value.strip();
        boolean printable =
                StandardCharsets.US_ASCII.newEncoder().canEncode(v)
                        && v.chars().allMatch(Inputs::printableOrLineBreak);
        if (v.isEmpty() || v.length() > max || !printable) {
            throw new FciException(invalid);
        }
        return v;
    }

    private static boolean printableOrLineBreak(int c) {
        return (c >= 0x20 && c != 0x7F) || c == 0x0A || c == 0x0D || c == 0x09; // LF, CR, TAB
    }

    /** At most the first {@code max} characters (a system value kept to its column). */
    public static String cut(String value, int max) {
        return value.substring(0, Math.min(max, value.length()));
    }

    /** At most the last {@code max} characters (a file name keeps its extension). */
    public static String cutEnd(String value, int max) {
        return value.substring(Math.max(0, value.length() - max));
    }

    /** Optional free text: null when blank, else as {@link #text}. */
    public static String optionalText(String value, int max, ErrorCode invalid) {
        return value == null || value.isBlank() ? null : text(value, max, invalid);
    }
}
