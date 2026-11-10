package in.gov.fci.payroll.employee.business;

import in.gov.fci.payroll.employee.api.HrmsNewEmployees.NewEmployee;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The checks of a new staff record received from HRMS and of its onboarding submission (plan 16
 * D16-34 … D16-36; SRS-1-11, SRS-1-13, SRS-1-16, SRS-1-17, SRS-1-52, SRS-1-53). Pure functions.
 */
public final class OnboardingRules {

    private static final Pattern EIS = Pattern.compile("^[A-Z0-9/-]{1,20}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z .'-]{0,99}$");
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern AADHAAR = Pattern.compile("^[2-9][0-9]{11}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{6,20}$");
    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,85}$");
    private static final Pattern CPF = Pattern.compile("^[A-Z0-9/-]{1,20}$");
    private static final Pattern CODE = Pattern.compile("^[\\x21-\\x7E]{1,20}$");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]+$");

    /** The onboarding reason, entered in a text area: line breaks kept (R-ASCII). */
    private static final Pattern PRINTABLE_TEXT = Pattern.compile("^[\\x20-\\x7E\\t\\r\\n]+$");

    private OnboardingRules() {}

    /**
     * What is wrong with the received record, by field (EIS, NAME, BIRTH_DATE, JOINING_DATE,
     * GENDER, DESIGNATION, PAY_SCALE, POSTING, PAN, AADHAAR, BANK_ACCOUNT, IFSC, CPF_CODE, MOBILE,
     * EMAIL), or empty. Every HRMS-sourced field M-01 needs is required (SRS-1-52).
     */
    public static Optional<String> defect(NewEmployee e) {
        if (e == null || !matches(EIS, e.eis())) {
            return Optional.of("EIS");
        }
        if (!matches(NAME, e.firstName())
                || (present(e.lastName()) && !matches(NAME, e.lastName()))
                || (present(e.fatherName()) && !matches(NAME, e.fatherName()))) {
            return Optional.of("NAME");
        }
        if (e.birthDate() == null) {
            return Optional.of("BIRTH_DATE");
        }
        if (e.joiningDate() == null || !e.birthDate().isBefore(e.joiningDate())) {
            return Optional.of("JOINING_DATE");
        }
        if (present(e.gender()) && !e.gender().strip().matches("^[MFO]$")) {
            return Optional.of("GENDER");
        }
        if (!matches(CODE, e.designationCode())) {
            return Optional.of("DESIGNATION");
        }
        if (!matches(CODE, e.payScaleCode())) {
            return Optional.of("PAY_SCALE");
        }
        if (!matches(CODE, e.postingCode())) {
            return Optional.of("POSTING");
        }
        return identity(e);
    }

    private static Optional<String> identity(NewEmployee e) {
        if (!matches(PAN, upper(e.panNumber()))) {
            return Optional.of("PAN");
        }
        if (!matches(AADHAAR, e.aadhaar())) {
            return Optional.of("AADHAAR");
        }
        if (!matches(ACCOUNT, e.bankAccountNumber())) {
            return Optional.of("BANK_ACCOUNT");
        }
        if (!matches(IFSC, upper(e.ifsc()))) {
            return Optional.of("IFSC");
        }
        if (present(e.cpfCode()) && !matches(CPF, e.cpfCode())) {
            return Optional.of("CPF_CODE");
        }
        if (present(e.mobileNumber()) && !matches(MOBILE, e.mobileNumber())) {
            return Optional.of("MOBILE");
        }
        if (present(e.emailAddress()) && !matches(EMAIL, e.emailAddress())) {
            return Optional.of("EMAIL");
        }
        return Optional.empty();
    }

    /**
     * SRS-1-17: the provident fund type of the pay pattern: IDA is a CPF member; a CDA or FNB
     * member is GPF or NPS as HRMS holds it (the statutory provision in force), else empty.
     */
    public static Optional<String> providentFund(String payPattern, String fromHrms) {
        if ("IDA".equals(payPattern)) {
            return Optional.of("CPF");
        }
        String pf = fromHrms == null ? "" : fromHrms.strip().toUpperCase(Locale.ROOT);
        return ("CDA".equals(payPattern) || "FNB".equals(payPattern))
                        && ("GPF".equals(pf) || "NPS".equals(pf))
                ? Optional.of(pf)
                : Optional.empty();
    }

    /**
     * SRS-1-11: what is wrong with the submission: CDA_STAFF_CODE (required for a CDA member, not
     * given for another), REASON (1 to 500 printable characters), or empty.
     */
    public static Optional<String> submissionDefect(
            String payPattern, String cdaStaffCode, String reason) {
        boolean cda = "CDA".equals(payPattern);
        if (cda != present(cdaStaffCode)
                || (present(cdaStaffCode) && !matches(CODE, cdaStaffCode))) {
            return Optional.of("CDA_STAFF_CODE");
        }
        if (!present(reason) || reason.strip().length() > 500 || !matches(PRINTABLE_TEXT, reason)) {
            return Optional.of("REASON");
        }
        return Optional.empty();
    }

    private static boolean matches(Pattern p, String value) {
        return value != null && p.matcher(value.strip()).matches();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
