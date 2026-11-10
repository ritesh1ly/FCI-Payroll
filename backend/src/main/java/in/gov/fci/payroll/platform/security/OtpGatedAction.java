package in.gov.fci.payroll.platform.security;

import java.util.List;

/**
 * {@code otp_challenge.gated_action} (target DDL v4.0 S05): exactly the eighteen OTP-gated actions
 * of FRS V0.5 §6.13.9 / SRS-G-21. An approval action may require an OTP only with one of these
 * codes ({@code approval_workflow_action.otp_action_code}). Each carries the screens on which the
 * action is performed and its OTP is entered, as the X-08 list of Wireframes v6.1 draws them (a
 * frozen requirement, OD-121): the list is fixed by SRS-G-21, so its screens are too.
 */
public enum OtpGatedAction {
    AUTH_LOGIN("X-08"),
    /** Own password change; also the self-service reset and unlock (no separate code exists). */
    AUTH_OWN_PASSWORD_CHANGE("X-08", "E-01"),
    CYCLE_FREEZE_LOCK("C-01", "D-01"),
    CYCLE_UNFREEZE_STUCK_RELEASE("C-01"),
    INCREMENT_DATE_BASIC_CORRECTION("I-05", "I-21"),
    DISBURSEMENT_RELEASE("O-02"),
    APPS_TRANSMISSION("O-11"),
    YEAR_END_REMITTANCE("O-11"),
    CPF_SANCTION_FINAL_PAYMENT("I-04"),
    CPF_OPENING_BALANCE_CORRECTION("I-22"),
    WAGE_REVISION_HQ("I-25"),
    UNDO_TRANSACTION("C-01", "I-08"),
    FNF_APPROVAL("O-05"),
    SUPPLEMENTARY_POST_LOCK("I-23"),
    LOAN_FORECLOSURE("I-04"),
    PLI_ADJUSTMENT("C-02"),
    /** X-01: role, access group and access assignment changes (A-03, A-04). */
    ROLE_ACCESS_GROUP_CHANGE("X-01"),
    HRMS_FALLBACK_UPLOAD_APPROVAL("X-07");

    private final List<String> screens;

    OtpGatedAction(String... screens) {
        this.screens = List.of(screens);
    }

    /** The screens on which the action is performed and its OTP entered (X-08, as drawn). */
    public List<String> screens() {
        return screens;
    }
}
