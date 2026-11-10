package in.gov.fci.payroll.general.business;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * SRS-EH-12: a refused cancel "states the Invoice Sent to APPS Date". The date as the screens show
 * it (DD-MM-YYYY, India's business day of the instant); a sent invoice with no recorded date shows
 * "-" rather than a guessed day.
 */
public final class AppsDates {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private AppsDates() {}

    public static String display(Instant sentAt) {
        return sentAt == null ? "-" : DAY.format(sentAt.atZone(INDIA));
    }
}
