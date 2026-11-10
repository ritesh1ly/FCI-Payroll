package in.gov.fci.payroll.payroll.business;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The rules of four pre-lock checks of SRS §6.7.2 "Pre-payroll checks before lock" (SRS-15-39, FRS
 * V0.5 §6.11.3; DDL v4.0 {@code pre_payroll_check}, every one BLOCK on all payrolls; OD-152). Pure
 * functions over what the sources hold: a value that is missing or cannot be read fails, it is
 * never assumed good (plan 05 D-1). HRMS-owned values (bank, UAN, CPF code) are only read here.
 */
public final class PreLockRules {

    /**
     * IFSC as the Labour Master (SRS-3-11, KB LC-10: "IFSC 11 chars") and the HRMS onboarding
     * checks (OnboardingRules, LabourRules) read it: four letters, a zero, six letters or digits.
     */
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    /**
     * Bank account number: digits, 6 to 20 (the onboarding check, plan 16 D16-34; Labour Master
     * "Bank account (20)", KB LC-10).
     */
    // OPEN-QUESTION: OD-152 / PENDING PRELOCK-BANK-FMT - the SRS names no account-number
    // format; the onboarding check's 6-20 digits is reused, FCI to confirm.
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{6,20}$");

    /** UAN: twelve digits (SRS I-22 field table "UAN N12", KB 07-cpf). */
    private static final Pattern UAN = Pattern.compile("^[0-9]{12}$");

    private PreLockRules() {}

    /**
     * APPS_POSTING_UNIT_ACTIVE (SRS §6.7.2 row 1; DDL org_location "the pre-lock check blocks the
     * lock while any posting unit is inactive in APPS", FRS V0.5 §6.11.3): the member's place of
     * posting is known and its APPS state is active. A posting never synchronised from APPS (flag
     * NULL) is not known to be active and fails.
     *
     * @param postingLocationId the depot or unit of posting, or null
     * @param appsActiveFlag {@code org_location.apps_active_flag} of that location, or null
     */
    // OPEN-QUESTION: OD-152 / PENDING PRELOCK-APPS-NULL - a location with no APPS sync yet (flag
    // NULL, no APPS location feed before module 23) is read as not active; FCI to confirm.
    public static boolean postingActiveInApps(Long postingLocationId, String appsActiveFlag) {
        return postingLocationId != null && "Y".equals(appsActiveFlag);
    }

    /**
     * BANK_ACCOUNT_IFSC_VALID (SRS §6.7.2 row 9; SRS §6.7 Bank Master "read from the EBS bank
     * branch list"; DDL bank_branch "every member bank route resolves to a live branch record"):
     * only a member to be paid (net pay above zero) is checked; the account number is present and
     * well formed, the branch resolves to an active {@code bank_branch} row and its IFSC is well
     * formed.
     *
     * @param netPay the member's net pay in the run of record
     * @param account the account number (HRMS; labour: Labour Master), or null
     * @param branchFound whether {@code bank_branch_id} resolves to a branch row
     * @param branchActive {@code bank_branch.active_flag}, or null
     * @param ifsc {@code bank_branch.ifsc_code}, or null
     */
    public static boolean bankRouteValid(
            BigDecimal netPay,
            String account,
            boolean branchFound,
            String branchActive,
            String ifsc) {
        if (netPay == null || netPay.signum() <= 0) {
            return true;
        }
        return matches(ACCOUNT, account)
                && branchFound
                && "Y".equals(branchActive)
                && matches(IFSC, ifsc);
    }

    /**
     * PF_UAN_VALID (SRS §6.7.2 row 6; fixed on I-21 for staff, the Labour Master for labour): a PF
     * member holds a CPF code and a twelve-digit UAN. Staff are PF members when HRMS says so
     * ({@code provident_fund_type} CPF; NULL is not known and fails); labour when {@code
     * pf_applicable_flag} is Y (DDL chk_labour_profile_cpf). GPF, NPS and NONE staff and labour
     * without PF are not PF members.
     *
     * @param labour whether the member is labour ({@code emp_lbr_flag} Y)
     * @param providentFundType staff {@code provident_fund_type}, or null
     * @param labourPfApplicable labour {@code pf_applicable_flag}, or null
     * @param cpfCode the CPF code, or null
     * @param uan the UAN, or null
     */
    // OPEN-QUESTION: OD-152 / PENDING PRELOCK-PF-SCOPE - the SRS says "PF / UAN present and
    // valid" on all payrolls without naming who is a PF member: GPF / NPS / NONE staff are left
    // out and a staff record with no provident-fund type fails; FCI to confirm.
    public static boolean pfUanValid(
            boolean labour,
            String providentFundType,
            String labourPfApplicable,
            String cpfCode,
            String uan) {
        boolean member =
                labour
                        ? !"N".equals(labourPfApplicable)
                        : providentFundType == null || "CPF".equals(providentFundType);
        if (!member) {
            return true;
        }
        if (!labour && providentFundType == null) {
            return false;
        }
        return cpfCode != null && !cpfCode.isBlank() && matches(UAN, uan);
    }

    /**
     * One recovery line: the member, the recovery head (a DEDUCTION pay component) and the month.
     */
    public record RecoveryLine(long employeeId, long componentId, String payrollYymm) {}

    /**
     * NO_DUPLICATE_RECOVERY (SRS §6.7.2 row 5, fixed on "the screen of the recovery (Loans &
     * Advances, I-06, I-19)"): the members who are recovered the same head more than once for the
     * same month. A run line is one per result and component (DDL uk_prc_line), so a repeat comes
     * from a second current Actual result of the member for that month.
     *
     * @return the number of members with at least one repeated head and month
     */
    // OPEN-QUESTION: OD-152 / PENDING PRELOCK-DUP-SCOPE - "duplicate" is not defined in the
    // sources: read as the same deduction head in more than one current Actual result of the member
    // for the
    // month (arrear differentials excluded); the recovery records themselves (loan schedule, I-06,
    // I-19) are not compared; FCI to confirm.
    public static long membersWithDuplicateRecovery(Collection<RecoveryLine> lines) {
        Map<RecoveryLine, Integer> seen = new HashMap<>();
        for (RecoveryLine l : lines) {
            seen.merge(Objects.requireNonNull(l), 1, Integer::sum);
        }
        return seen.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(e -> e.getKey().employeeId())
                .distinct()
                .count();
    }

    private static boolean matches(Pattern p, String value) {
        return value != null && p.matcher(value).matches();
    }
}
