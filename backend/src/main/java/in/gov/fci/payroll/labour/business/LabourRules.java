package in.gov.fci.payroll.labour.business;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Labour Master and gang rules (plan 20 phase 20a; SRS-2-1, SRS-2-2, SRS-2-30, SRS-2-46, SRS-3-11;
 * BR-LABM-001, BR-LABM-002, BR-LABM-004; DDL Section 4). Pure functions: no state, no I/O.
 */
public final class LabourRules {

    /** DDL {@code labour_profile.payroll_type}. */
    public static final List<String> PAYROLL_TYPES =
            List.of("DEPT_LABOUR", "DPS_LABOUR", "NWNP_LABOUR");

    /** D20-1: the labour code prefix of each payroll type. */
    private static final Map<String, String> PREFIX =
            Map.of("DEPT_LABOUR", "DPT", "DPS_LABOUR", "DPS", "NWNP_LABOUR", "NWN");

    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{6,20}$");
    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z .'-]{0,149}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9/-]{1,20}$");

    /** M-02 / M-09 roles a gang member holds (DDL {@code labour_gang_membership.gang_role}). */
    public enum GangRole {
        SARDAR,
        MANDAL,
        HANDLING,
        ANCILLARY
    }

    private LabourRules() {}

    /** D20-1: the labour code of a payroll type and sequence, e.g. DPT-000001. */
    public static String labourCode(String payrollType, long sequence) {
        String prefix = PREFIX.get(payrollType);
        if (prefix == null || sequence < 1 || sequence > 999_999) {
            throw new IllegalArgumentException("labour code of " + payrollType + " #" + sequence);
        }
        return String.format("%s-%06d", prefix, sequence);
    }

    /** The fields a Labour Master entry needs. */
    public record Entry(
            String payrollType,
            String firstName,
            String lastName,
            LocalDate birthDate,
            LocalDate joiningDate,
            String panNumber,
            String ifsc,
            String bankAccount,
            String mobile,
            boolean pfApplicable,
            String cpfCode) {}

    /**
     * SRS-2-30 / SRS-3-11 / LC-10 / LC-11: what is wrong with the entry: PAYROLL_TYPE, NAME,
     * BIRTH_DATE (missing or after joining), JOINING_DATE, PAN (format), IFSC, BANK_ACCOUNT,
     * MOBILE, CPF_CODE (mandatory where PF applies, DDL {@code chk_labour_profile_cpf}).
     */
    public static Optional<String> defect(Entry e, LocalDate today) {
        if (e == null || !PAYROLL_TYPES.contains(e.payrollType())) {
            return Optional.of("PAYROLL_TYPE");
        }
        if (!name(e.firstName())
                || (e.lastName() != null && !e.lastName().isBlank() && !name(e.lastName()))
                || !fullNameFits(e.firstName(), e.lastName())) {
            return Optional.of("NAME");
        }
        if (e.joiningDate() == null || e.joiningDate().isAfter(today)) {
            return Optional.of("JOINING_DATE");
        }
        if (e.birthDate() == null || e.birthDate().isAfter(e.joiningDate())) {
            return Optional.of("BIRTH_DATE");
        }
        if (e.panNumber() != null
                && !e.panNumber().isBlank()
                && !PAN.matcher(e.panNumber()).matches()) {
            return Optional.of("PAN");
        }
        if (e.ifsc() == null || !IFSC.matcher(e.ifsc()).matches()) {
            return Optional.of("IFSC");
        }
        if (e.bankAccount() == null || !ACCOUNT.matcher(e.bankAccount()).matches()) {
            return Optional.of("BANK_ACCOUNT");
        }
        if (e.mobile() != null && !e.mobile().isBlank() && !MOBILE.matcher(e.mobile()).matches()) {
            return Optional.of("MOBILE");
        }
        if (e.pfApplicable() && (e.cpfCode() == null || !CODE.matcher(e.cpfCode()).matches())) {
            return Optional.of("CPF_CODE");
        }
        return Optional.empty();
    }

    /** SRS-2-30: a name of letters, spaces, dots, apostrophes and hyphens, at most 150. */
    public static boolean name(String value) {
        return value != null && NAME.matcher(value.strip()).matches();
    }

    /** M-02 l.4449 Labour Name, Alphanumeric (150): the name and surname, as shown, fit 150. */
    public static final int FULL_NAME_MAX = 150;

    /**
     * M-02 l.4449: the Labour Name as shown (first name, a space and the surname when there is one)
     * is at most 150 characters; the DDL keeps it in two columns of 100 (SRS wins over the split).
     */
    public static boolean fullNameFits(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.strip();
        String last = lastName == null ? "" : lastName.strip();
        return first.length() + (last.isEmpty() ? 0 : 1 + last.length()) <= FULL_NAME_MAX;
    }

    /**
     * SRS-2-59 / SRS-1-22: the EPS cut-off of a corrected date of birth: the day the member attains
     * the A-07 EPS_CUTOFF_AGE (58), as for employees.
     */
    public static LocalDate epsCutoff(LocalDate birthDate, int age) {
        return birthDate.plusYears(age);
    }

    /**
     * LC-11 / SRS-1-22 convention (D16-7): the retirement date from the date of birth and the A-07
     * RETIREMENT_AGE: the last day of the month in which the age is attained; born on the first,
     * the last day of the month before.
     */
    public static LocalDate retirementDate(LocalDate birthDate, int age) {
        LocalDate attains = birthDate.plusYears(age);
        YearMonth month = YearMonth.from(attains);
        if (birthDate.getDayOfMonth() == 1) {
            month = month.minusMonths(1);
        }
        return month.atEndOfMonth();
    }

    /** LC-11: a labour past the retirement date takes no attendance and no gang assignment. */
    public static boolean retired(LocalDate retirementDate, LocalDate day) {
        return retirementDate != null && day.isAfter(retirementDate);
    }

    /**
     * SRS-2-1 / BR-LABM-001: a handling gang has exactly one Sardar and one Mandal; the Ancillary
     * Pool has neither (SRS-2-46). Answers what is wrong, or empty.
     */
    public static Optional<String> gangRoles(List<GangRole> roles, boolean pool) {
        long sardars = roles.stream().filter(r -> r == GangRole.SARDAR).count();
        long mandals = roles.stream().filter(r -> r == GangRole.MANDAL).count();
        if (pool) {
            return sardars + mandals == 0 ? Optional.empty() : Optional.of("POOL_ROLES");
        }
        if (sardars != 1) {
            return Optional.of("SARDAR");
        }
        if (mandals != 1) {
            return Optional.of("MANDAL");
        }
        return Optional.empty();
    }

    /** SRS-2-31: a gang name: printable, 1 to 150 characters. */
    public static boolean gangName(String name) {
        return name != null
                && !name.isBlank()
                && name.strip().length() <= 150
                && name.strip().matches("^[\\x20-\\x7E]+$");
    }
}
