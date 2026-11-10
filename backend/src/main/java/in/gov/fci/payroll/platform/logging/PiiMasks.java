package in.gov.fci.payroll.platform.logging;

/**
 * The one way a Restricted identifier is shown masked on a screen, report or export (R-PII;
 * SRS-SEC-4, BR-SEC-001, BR-SEC-002). The only display format the SRS states for PAN and Aadhaar is
 * "the last 4 digits only" (SRS l.754, l.802; SRS-1-54 and SRS-6-51 for Aadhaar); a bank account
 * keeps the last-four form it already had (no other format is stated). Every module masks through
 * these functions so one format is shown everywhere (BMAD test review TR-035 / batch B,
 * 2026-11-04).
 */
public final class PiiMasks {

    private static final String HIDDEN = "****";

    private PiiMasks() {}

    /**
     * A PAN by its last four characters behind six asterisks ({@code ******234D}); a value that is
     * not ten characters long shows nothing of itself ({@code ****}); null or blank stays null.
     */
    public static String pan(String pan) {
        if (pan == null || pan.isBlank()) {
            return null;
        }
        String v = pan.strip();
        return v.length() == 10 ? "*".repeat(6) + v.substring(6) : HIDDEN;
    }

    /**
     * An Aadhaar or bank account number by its last four digits, the rest as asterisks; four
     * characters or fewer show nothing of themselves; null or blank stays null.
     */
    public static String lastFour(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        String v = number.strip();
        return v.length() <= 4 ? HIDDEN : "*".repeat(v.length() - 4) + v.substring(v.length() - 4);
    }
}
