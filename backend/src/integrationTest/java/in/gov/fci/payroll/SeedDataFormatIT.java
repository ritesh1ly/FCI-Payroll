package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * R-PII with realistic demo data (owner, 2026-10-24): every identity and financial number the demo
 * seeds store is FAKE but in its real format and passes its real check, so validators, masks and
 * screens see what production will hold. Read from the migrated database, so a later seed that
 * corrects a value (V036_8) counts.
 */
class SeedDataFormatIT extends ApiTestSupport {

    /** Fourth letter the holder type (P = individual), fifth the surname's initial. */
    private static final Pattern PAN = Pattern.compile("^[A-Z]{3}P[A-Z][0-9]{4}[A-Z]$");

    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern TWELVE_DIGITS = Pattern.compile("^[0-9]{12}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{9,18}$");

    private static final int[][] D = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
        {2, 3, 4, 0, 1, 7, 8, 9, 5, 6}, {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
        {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
        {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
        {8, 7, 6, 5, 9, 3, 2, 1, 0, 4}, {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };
    private static final int[][] P = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
        {5, 8, 0, 3, 7, 9, 6, 1, 4, 2}, {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
        {9, 4, 5, 3, 1, 2, 7, 8, 6, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
        {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    /** Aadhaar: twelve digits, the first 2-9, the last a Verhoeff check digit. */
    static boolean validAadhaar(String number) {
        if (number == null || !number.matches("^[2-9][0-9]{11}$")) {
            return false;
        }
        int c = 0;
        for (int i = 0; i < number.length(); i++) {
            c = D[c][P[i % 8][number.charAt(number.length() - 1 - i) - '0']];
        }
        return c == 0;
    }

    @Test
    void R_PII_theVerhoeffCheckAcceptsAValidAadhaarAndRefusesAnyOneDigitChange() {
        assertThat(validAadhaar("999900104886")).isTrue();
        assertThat(validAadhaar("999900104882")).isFalse();
        assertThat(validAadhaar("199900104886")).as("first digit 2-9").isFalse();
        assertThat(validAadhaar("99990010488")).isFalse();
        assertThat(validAadhaar(null)).isFalse();
    }

    @Test
    void R_PII_everySeededIdentityNumberHasItsRealFormat() {
        List<String> wrong = new ArrayList<>();
        for (Map<String, Object> e :
                jdbc.queryForList(
                        "SELECT emp_num, last_name, pan_number, mobile_number, bank_account_number,"
                                + " aadhaar_number, uan_number, pran_number, email_address FROM"
                                + " employee")) {
            String who = String.valueOf(e.get("EMP_NUM"));
            String pan = (String) e.get("PAN_NUMBER");
            String last = (String) e.get("LAST_NAME");
            if (pan != null
                    && (!PAN.matcher(pan).matches()
                            || (last != null
                                    && pan.charAt(4) != Character.toUpperCase(last.charAt(0))))) {
                wrong.add(who + " PAN " + pan + " (" + last + ")");
            }
            check(wrong, who, "mobile", (String) e.get("MOBILE_NUMBER"), MOBILE);
            check(wrong, who, "account", (String) e.get("BANK_ACCOUNT_NUMBER"), ACCOUNT);
            check(wrong, who, "UAN", (String) e.get("UAN_NUMBER"), TWELVE_DIGITS);
            check(wrong, who, "PRAN", (String) e.get("PRAN_NUMBER"), TWELVE_DIGITS);
            String aadhaar = (String) e.get("AADHAAR_NUMBER");
            if (aadhaar != null && !validAadhaar(aadhaar)) {
                wrong.add(who + " Aadhaar " + aadhaar);
            }
            String email = (String) e.get("EMAIL_ADDRESS");
            if (email != null && !email.endsWith("@fci-demo.test")) {
                wrong.add(who + " e-mail " + email);
            }
        }
        for (Map<String, Object> u :
                jdbc.queryForList("SELECT login_id, registered_mobile FROM app_user")) {
            check(
                    wrong,
                    String.valueOf(u.get("LOGIN_ID")),
                    "mobile",
                    (String) u.get("REGISTERED_MOBILE"),
                    MOBILE);
        }
        for (String ifsc : jdbc.queryForList("SELECT ifsc_code FROM bank_branch", String.class)) {
            check(wrong, "branch", "IFSC", ifsc, IFSC);
        }
        assertThat(wrong).isEmpty();
    }

    private static void check(
            List<String> wrong, String who, String what, String value, Pattern format) {
        if (value != null && !format.matcher(value).matches()) {
            wrong.add(who + " " + what + " " + value);
        }
    }
}
