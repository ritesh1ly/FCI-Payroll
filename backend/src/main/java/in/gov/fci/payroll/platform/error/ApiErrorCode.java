package in.gov.fci.payroll.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Backend errors (docs/api/errors.md). The user interface shows {@code messageKey} = {@code
 * error.<code>} from its own bundle; the English texts here are for logs and API clients. {@code
 * {0}} arguments are developer-controlled values (times, counts, codes), never request input.
 */
public enum ApiErrorCode implements ErrorCode {
    // ---------------------------------------------------------------- sign-in
    INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "AUTH-401-CREDENTIALS",
            "auth/invalid-credentials",
            "Sign-in failed",
            "Invalid user ID or password."),
    TEMPORARILY_LOCKED(
            HttpStatus.LOCKED,
            "AUTH-423-LOCKED",
            "auth/temporarily-locked",
            "Account locked",
            "Too many failed sign-ins. Try again after {0}."),
    ACCOUNT_LOCKED(
            HttpStatus.LOCKED,
            "AUTH-423-ACCOUNT-LOCKED",
            "auth/account-locked",
            "Account locked",
            "This account is locked. Contact your System Administrator."),
    ACCOUNT_SUSPENDED(
            HttpStatus.FORBIDDEN,
            "AUTH-403-SUSPENDED",
            "auth/account-suspended",
            "Account suspended",
            "This account is suspended. Contact your System Administrator."),
    ACCOUNT_DEACTIVATED(
            HttpStatus.FORBIDDEN,
            "AUTH-403-DEACTIVATED",
            "auth/account-deactivated",
            "Account deactivated",
            "This account has been deactivated."),
    PERSONA_NOT_PERMITTED(
            HttpStatus.FORBIDDEN,
            "AUTH-403-PERSONA",
            "auth/persona-not-permitted",
            "Not permitted",
            "You are not permitted to sign in as {0}."),
    LOCATION_NOT_IN_SCOPE(
            HttpStatus.FORBIDDEN,
            "AUTH-403-LOCATION",
            "auth/location-not-in-scope",
            "Location not in scope",
            "The selected location is not in your access scope."),
    NO_ACTIVE_SCOPE(
            HttpStatus.FORBIDDEN,
            "AUTH-403-NO-SCOPE",
            "auth/no-active-scope",
            "No active access",
            "You have no active access assignment for {0}."),
    LOCATION_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "AUTH-400-LOCATION-REQUIRED",
            "auth/location-required",
            "Location required",
            "Select a login location."),
    UNKNOWN_PERSONA(
            HttpStatus.BAD_REQUEST,
            "AUTH-400-PERSONA",
            "auth/unknown-persona",
            "Unknown persona",
            "Select how you want to sign in."),
    UNKNOWN_LOCATION(
            HttpStatus.FORBIDDEN,
            "AUTH-403-LOCATION",
            "auth/location-not-in-scope",
            "Location not permitted",
            "You are not permitted to sign in at this location."),
    SESSION_EXPIRED(
            HttpStatus.UNAUTHORIZED,
            "AUTH-401-SESSION-EXPIRED",
            "auth/session-expired",
            "Session expired",
            "Your session has expired. Please sign in again."),
    /** OD-130: the session was ended because an unauthorized access attempt was recorded. */
    SESSION_ENDED_OFFENCE(
            HttpStatus.UNAUTHORIZED,
            "AUTH-401-OFFENCE",
            "auth/session-ended-offence",
            "Session ended",
            "Your session was ended: an unauthorized access attempt was recorded."),
    INVALID_GRANT(
            HttpStatus.UNAUTHORIZED,
            "AUTH-401-GRANT",
            "auth/invalid-grant",
            "Session expired",
            "Your session has expired. Please sign in again."),
    PASSWORD_CHANGE_REQUIRED(
            HttpStatus.FORBIDDEN,
            "AUTH-403-PASSWORD-CHANGE",
            "auth/password-change-required",
            "Password change required",
            "You must change your password before continuing."),
    /** SRS-G-48 / Module 02 D-2: the acting role is sensitive and the OTP is not yet confirmed. */
    STEP_UP_REQUIRED(
            HttpStatus.FORBIDDEN,
            "AUTH-403-STEP-UP",
            "auth/step-up-required",
            "OTP confirmation required",
            "Your role needs an OTP before you can continue."),
    // ---------------------------------------------------------------- passwords and OTP
    CURRENT_PASSWORD_WRONG(
            HttpStatus.BAD_REQUEST,
            "AUTH-400-CURRENT-PASSWORD",
            "auth/current-password",
            "Password not changed",
            "The current password is not correct."),
    PASSWORD_POLICY(
            HttpStatus.BAD_REQUEST,
            "AUTH-400-PASSWORD-POLICY",
            "auth/password-policy",
            "Password not accepted",
            "The new password does not meet the password policy."),
    PASSWORD_REUSED(
            HttpStatus.BAD_REQUEST,
            "AUTH-400-PASSWORD-REUSED",
            "auth/password-reused",
            "Password not accepted",
            "The new password must differ from your last {0} passwords."),
    INVALID_OTP(
            HttpStatus.BAD_REQUEST,
            "AUTH-400-OTP",
            "auth/invalid-otp",
            "Verification failed",
            "The one-time password is not valid or has expired."),
    OTP_REQUIRED(
            HttpStatus.PRECONDITION_REQUIRED,
            "AUTH-428-OTP",
            "auth/otp-required",
            "Verification required",
            "Enter the one-time password sent to your registered contact."),
    /**
     * UC-07 precondition (SRS l.576, US-GAP-118): an OTP goes only to the acting user's own
     * registered contact (SRS-G-3); without one no code is issued and there is no fallback.
     */
    OTP_NO_CONTACT(
            HttpStatus.CONFLICT,
            "AUTH-409-NO-CONTACT",
            "auth/no-registered-contact",
            "No registered contact",
            "No registered mobile number or e-mail is recorded for your account, so no one-time"
                    + " password can be sent. Ask your administrator to record your contact."),
    /**
     * SRS-G-22, SRS-EH-13: the OTP retry limit of the action was exceeded, so the action is blocked
     * for the A-07 cooling-off period; {0} = when it ends (India's time).
     */
    OTP_COOLING_OFF(
            HttpStatus.TOO_MANY_REQUESTS,
            "AUTH-429-COOLING-OFF",
            "auth/otp-cooling-off",
            "Action blocked for now",
            "Too many wrong one-time passwords: this action is blocked until {0}. Start again"
                    + " then."),
    // ---------------------------------------------------------------- authorisation
    PERMISSION_DENIED(
            HttpStatus.FORBIDDEN,
            "AUTHZ-403-PERMISSION",
            "authz/permission-denied",
            "Not permitted",
            "Your active role does not permit {1} on {0}."),
    OUT_OF_SCOPE(
            HttpStatus.FORBIDDEN,
            "AUTHZ-403-SCOPE",
            "authz/out-of-scope",
            "Outside your scope",
            "This record is outside the location scope of your active access group."),
    READ_ONLY_CONSOLIDATED(
            HttpStatus.FORBIDDEN,
            "AUTHZ-403-READ-ONLY",
            "authz/read-only",
            "Read-only view",
            "The Headquarters consolidated view is read-only: it confers no approval or lock"
                    + " rights."),
    HQ_ONLY(
            HttpStatus.FORBIDDEN,
            "AUTHZ-403-HQ-ONLY",
            "authz/hq-only",
            "Headquarters only",
            "This action is reserved to Headquarters."),
    HQ_MAY_NOT_LOCK(
            HttpStatus.FORBIDDEN,
            "AUTHZ-403-HQ-LOCK",
            "authz/hq-may-not-lock",
            "Not permitted",
            "Headquarters may not lock on behalf of a Region."),
    ROLE_NOT_HELD(
            HttpStatus.FORBIDDEN,
            "AUTHZ-403-ROLE",
            "authz/role-not-held",
            "Not permitted",
            "This step needs the {0} role in your active access group."),
    SCOPE_SWITCH_DENIED(
            HttpStatus.FORBIDDEN,
            "AUTHZ-403-SWITCH",
            "authz/switch-denied",
            "Not permitted",
            "You can switch only to your own active access groups."),
    // ---------------------------------------------------------------- workflow
    MAKER_CHECKER(
            HttpStatus.FORBIDDEN,
            "WF-403-SOD",
            "workflow/maker-checker",
            "Separation of duties",
            "The maker of a submission cannot approve it."),
    SELF_APPROVAL(
            HttpStatus.FORBIDDEN,
            "WF-403-SELF",
            "workflow/self-approval",
            "Separation of duties",
            "You cannot approve a change to your own access."),
    NOT_PENDING(
            HttpStatus.CONFLICT,
            "WF-409-STATE",
            "workflow/not-pending",
            "Already decided",
            "This item is no longer pending approval."),
    OVERRIDE_PROTECTED_SCREEN(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RBAC-422-PROTECTED-SCREEN",
            "rbac/protected-screen",
            "Screen cannot be granted per user",
            "Administration screens are granted only through roles and access groups."),
    OVERRIDE_NO_ASSIGNMENT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RBAC-422-NO-ASSIGNMENT",
            "rbac/no-assignment-at-location",
            "No access at this location",
            "The user holds no approved access group at this location; assign one first (A-04)."),
    OVERRIDE_REDUNDANT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RBAC-422-REDUNDANT",
            "rbac/redundant-override",
            "Nothing would change",
            "The user's role already gives (or does not give) this access at this location."),
    PARAMETER_OUT_OF_RANGE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SYS-422-OUT-OF-RANGE",
            "system/parameter-out-of-range",
            "Value outside the allowed range",
            "Enter a value between {0} and {1}."),
    PARAMETER_UNCHANGED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SYS-422-UNCHANGED",
            "system/parameter-unchanged",
            "Value unchanged",
            "The new value is the value already in force."),
    // ---------------------------------------------------------------- masters (M-04, M-22, M-23,
    // M-24)
    MASTER_NOT_IN_CATALOGUE(
            HttpStatus.NOT_FOUND,
            "MST-404-CATALOGUE",
            "masters/not-in-catalogue",
            "Unknown master",
            "This master is not maintained on this screen."),
    MASTER_HRMS_OWNED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-HRMS-OWNED",
            "masters/hrms-owned",
            "Received from HRMS",
            "This value is received from HRMS and is not held in Payroll."),
    MASTER_DATE_NOT_AFTER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-DATE",
            "masters/date-not-after",
            "Effective date too early",
            "A new version must start after the version in force (from {0})."),
    MASTER_DATE_TAKEN(
            HttpStatus.CONFLICT,
            "MST-409-DATE",
            "masters/date-taken",
            "Effective date taken",
            "A version of this master already starts on {0}; choose another date."),
    REFERENCE_SYSTEM_LIST(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-SYSTEM-LIST",
            "masters/system-list",
            "System list",
            "The values of {0} are fixed by the system: relabel, reorder or end-date one, but no"
                    + " new code."),
    REFERENCE_CODE_TAKEN(
            HttpStatus.CONFLICT,
            "MST-409-CODE",
            "masters/code-taken",
            "Code taken",
            "{0} already has the value {1}."),
    REFERENCE_LABEL_TAKEN(
            HttpStatus.CONFLICT,
            "MST-409-REF-TEXT",
            "masters/label-taken",
            "Label taken",
            "{0} already shows the text {1}."),
    REFERENCE_CHANGE_PENDING(
            HttpStatus.CONFLICT,
            "MST-409-PENDING",
            "masters/change-pending",
            "Change pending",
            "A change of {0} is already awaiting the checker (request {1})."),
    MASTER_INVALID_LINES(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-LINES",
            "masters/invalid-lines",
            "Invalid rate lines",
            "Check the rate lines: each needs a rate code and a value of zero or more, percentages"
                    + " at most 100, a slab end after its start and no line twice."),
    // ---------------------------------------------------------------- masters (M-14, M-15, M-25,
    // M-26, M-27; Module 04)
    MASTER_INVALID_FLOOR(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-FLOOR",
            "masters/invalid-floor",
            "Invalid net-pay floor",
            "Choose a floor basis; an amount or percentage of zero or more (a percentage at most"
                    + " 100) unless the basis is None; a deduction ceiling between 0 and 100."),
    MASTER_INVALID_CEILING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-CEILING",
            "masters/invalid-ceiling",
            "Invalid ceiling norm",
            "Enter a ceiling value above zero and choose a periodicity."),
    MASTER_UNKNOWN_VALUE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-VALUE",
            "masters/unknown-value",
            "Unknown value",
            "{1} is not an active value of the list {0} (M-16)."),
    MASTER_COMPONENT_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-COMPONENT",
            "masters/component-invalid",
            "Not in the component catalogue",
            "Pay code {0} is not an active entry of the component catalogue (M-07)."),
    /** M-07 (SRS M-07 field table): the component definition. */
    COMPONENT_DEFINITION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-COMPONENT-DEF",
            "masters/component-definition",
            "Component not valid",
            "The component is not valid: {0}."),
    /** M-07: a pay code is unique and never reused once retired (SRS-G-42, FAP-14). */
    COMPONENT_CODE_TAKEN(
            HttpStatus.CONFLICT,
            "MST-409-COMPONENT-CODE",
            "masters/component-code-taken",
            "Pay code in use",
            "Pay code {0} exists or was used before: a pay code is never reused."),
    /** M-07: one open proposal per pay code. */
    COMPONENT_PENDING(
            HttpStatus.CONFLICT,
            "MST-409-COMPONENT-PENDING",
            "masters/component-pending",
            "Change pending",
            "Pay code {0} already has a change waiting for the Rule Approver."),
    MASTER_INVALID_TEXT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-TEXT",
            "masters/invalid-text",
            "Invalid text",
            "Fill in every required text field, each within its length (here at most {1}"
                    + " characters), in plain English letters, digits and punctuation."),
    CIRCULAR_NUMBER_TAKEN(
            HttpStatus.CONFLICT,
            "MST-409-CIRCULAR",
            "masters/circular-number-taken",
            "Circular number taken",
            "Circular {0} is already recorded."),
    CIRCULAR_DOCUMENT_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-CIRCULAR-DOC",
            "masters/circular-document-required",
            "Order copy required",
            "Attach the copy of the order (PDF) before submitting the circular."),
    CIRCULAR_NOT_ACTIVE(
            HttpStatus.CONFLICT,
            "MST-409-CIRCULAR-STATE",
            "masters/circular-not-active",
            "Circular not active",
            "Circular {0} is {1}: only an Active circular can be linked, superseded or"
                    + " withdrawn."),
    /**
     * Wave D review 6 (SRS-G-30, SRS-G-38): a change that must name its order (M-15, M-22, M-04)
     * was sent without a circular.
     */
    CIRCULAR_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-CIRCULAR-REQUIRED",
            "masters/circular-required",
            "Circular required",
            "Choose the Active circular (M-25) that orders this change."),
    /**
     * A transaction that needs an order of one category was linked to a circular of another (I-25
     * Revision Order: "an active circular of category Wage Revision", SRS l.3164).
     */
    CIRCULAR_WRONG_CATEGORY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-CIRCULAR-CATEGORY",
            "masters/circular-category",
            "Circular of another category",
            "Circular {0} is of category {1}: this needs an Active circular of category {2}."),
    CIRCULAR_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-CIRCULAR",
            "masters/circular-invalid",
            "Invalid circular",
            "Enter the circular date, an effective-from date (the effective-to date, if any, not"
                    + " before it), at least one payroll type and either all offices or the offices"
                    + " it applies to."),
    OPERATION_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-OPERATION",
            "masters/operation-invalid",
            "Invalid operation",
            "Choose at least one labour payroll and a rate or norm link; a bag-based operation"
                    + " links a norm slab; Dusting names its allowance pay code and is not Present"
                    + " But No Work."),
    OPERATION_NAME_FIXED(
            HttpStatus.CONFLICT,
            "MST-409-OPERATION-NAME",
            "masters/operation-name-fixed",
            "Standard name is fixed",
            "Operation {0} is {1}: the standard name never changes; give a local name on the Label"
                    + " Master (M-26)."),
    LABEL_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-LABEL",
            "masters/label-invalid",
            "Invalid label",
            "Enter a label key (letters, digits, dot, dash or underscore), a type, a language and"
                    + " a text of at most 500 characters; a master-value label names its master and"
                    + " code."),
    LABEL_SCOPE_NOT_ALLOWED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MST-422-LABEL-SCOPE",
            "masters/label-scope-not-allowed",
            "Local name not allowed",
            "A Region or Office label is only for a master value marked localisable, at your own"
                    + " region or office; screen text stays Global."),
    LABEL_TAKEN(
            HttpStatus.CONFLICT,
            "MST-409-LABEL",
            "masters/label-taken",
            "Label exists",
            "{0} already has a {1} label for this scope from {2}; propose a later date."),
    // ---------------------------------------------------------------- rule engine (M-07, M-08;
    // Module 06)
    RULE_FORMULA_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RULE-422-FORMULA",
            "rules/formula-invalid",
            "Formula not permitted",
            "The formula is not permitted: {0}. Use only the operators, functions and variables of"
                    + " the rule grammar."),
    RULE_FORMULA_EVALUATION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RULE-422-FORMULA-EVAL",
            "rules/formula-evaluation",
            "Formula cannot be evaluated",
            "The formula cannot be evaluated: {0}."),
    RULE_SLAB_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RULE-422-SLAB",
            "rules/slab-invalid",
            "Slab bands not contiguous",
            "Slab bands must be contiguous and must not overlap: {0}."),
    /** OD-149: the most periods of one member's bulk download is a setting not yet made. */
    PDF_BULK_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PDF-422-NOT-CONFIGURED",
            "pdf/bulk-not-configured",
            "Bulk download not configured",
            "The most months one bulk download may carry is not configured yet: download the"
                    + " months one at a time."),
    /** OD-149: more periods asked than one bulk download carries. */
    PDF_BULK_TOO_MANY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PDF-422-TOO-MANY",
            "pdf/bulk-too-many",
            "Too many months",
            "{0} months were asked; one download carries at most {1}. Choose fewer months."),
    /** OD-149: the bulk file of a job is not ready (still running, failed or stopped). */
    PDF_BULK_NOT_READY(
            HttpStatus.CONFLICT,
            "PDF-409-NOT-READY",
            "pdf/bulk-not-ready",
            "File not ready",
            "The file is not ready yet ({0}). You will be notified when it is."),
    RULE_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RULE-422-NOT-CONFIGURED",
            "rules/not-configured",
            "Rule not configured",
            "No approved version of rule {0} is in force on {1}; nothing is computed with a"
                    + " default."),
    RULE_VERSION_CONFLICT(
            HttpStatus.CONFLICT,
            "RULE-409-OVERLAP",
            "rules/version-overlap",
            "Effective date conflicts",
            "The effective date conflicts with version {0} of this rule (from {1}); a new version"
                    + " must start after it."),
    RULE_VALUE_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RULE-422-VALUE",
            "rules/value-invalid",
            "Rule value invalid",
            "The rule value is not valid: {0}."),
    RULE_END_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RULE-422-END",
            "rules/end-invalid",
            "End date invalid",
            "The end date must not be earlier than {0}, the start of the version in force."),
    RULE_READ_ONLY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "RULE-422-READ-ONLY",
            "rules/read-only",
            "Not maintained here",
            "Rule {0} is not maintained on M-08."),
    /** M-08 (SRS-19-4): a simulation tests the pending version of the rule. */
    RULE_NO_PROPOSAL(
            HttpStatus.CONFLICT,
            "RULE-409-NO-PROPOSAL",
            "rules/no-proposal",
            "Nothing to simulate",
            "Rule {0} has no version awaiting approval to simulate."),
    // ---------------------------------------------------------------- employee events (I-17, M-11)
    EMPLOYEE_EVENT_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "EMP-409-EVENT",
            "employee/event-not-allowed",
            "Event not allowed",
            "This event does not fit the employee's current status ({0})."),
    /** SRS-1-63, plan 16 D16-33: an HRMS life-cycle event is never keyed in Payroll. */
    EMPLOYEE_EVENT_FROM_HRMS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-HRMS-EVENT",
            "employee/event-from-hrms",
            "Event from HRMS",
            "{0} arrives from HRMS through I-21 and is not keyed in Payroll (SRS-1-63)."),
    /** I-17 field table (SRS l.3877, US-GAP-277): an event never precedes the joining date. */
    EMPLOYEE_EVENT_BEFORE_JOINING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-BEFORE-JOINING",
            "employee/event-before-joining",
            "Before the joining date",
            "The effective date {0} precedes the joining date {1}."),
    EMPLOYEE_FUTURE_DATE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-FUTURE",
            "employee/future-date",
            "Date in the future",
            "Record this once it has taken effect: future-dated events and transfers arrive from"
                    + " HRMS (I-21)."),
    EMPLOYEE_NUMBER_TAKEN(
            HttpStatus.CONFLICT,
            "EMP-409-NUMBER",
            "employee/number-taken",
            "Staff number in use",
            "This staff number already belongs to another employee."),
    // ---------------------------------------------------------------- payroll cycle (C-02)
    /** SRS-15-28 / BR-CYC-015: another action holds the cycle instance's lease. */
    CYCLE_LEASE_HELD(
            HttpStatus.CONFLICT,
            "CYC-409-LEASE",
            "cycle/lease-held",
            "Cycle in use",
            "{0} on this payroll cycle by {1} since {2}: wait until it ends, or request a"
                    + " release."),
    CYCLE_STATE(
            HttpStatus.CONFLICT,
            "CYC-409-STATE",
            "cycle/state",
            "Not possible in this cycle state",
            "The payroll cycle is {0}; this step is not possible now (the cycle only moves"
                    + " forward)."),
    /**
     * SRS-15-30 / SRS-15-31: a lock or un-freeze request of the cycle waits for approval; the
     * return, the un-freeze or the lock request must wait until it is decided.
     */
    CYCLE_REQUEST_PENDING(
            HttpStatus.CONFLICT,
            "CYC-409-PENDING",
            "cycle/request-pending",
            "A request is waiting",
            "A {0} request for this payroll cycle waits for approval: it must be decided first."),
    /**
     * SRS-15-28 / SRS l.1734: an ACTUAL computation of the cycle is still running; its results
     * would supersede the ones verified, so Verify waits until it ends.
     */
    CYCLE_RUN_RUNNING(
            HttpStatus.CONFLICT,
            "CYC-409-RUNNING",
            "cycle/run-running",
            "Computation still running",
            "Computation run {0} of this payroll cycle is still running: verify its results once"
                    + " it ends."),
    /** SRS-15-30: the return's scope does not name what it must (an office beneath, members). */
    CYCLE_RETURN_SCOPE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-SCOPE",
            "cycle/return-scope",
            "Return scope not valid",
            "The return scope is not valid: {0}."),
    /** SRS-15-30: the return can no longer be withdrawn. */
    CYCLE_RETURN_CLOSED(
            HttpStatus.CONFLICT,
            "CYC-409-RETURN",
            "cycle/return-closed",
            "Return cannot be withdrawn",
            "The return can no longer be withdrawn: {0}."),
    /** SRS-15-3 (plan 05 D-10): no module has registered an undo for this kind of transaction. */
    CYCLE_UNDO_TYPE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-UNDO-TYPE",
            "cycle/undo-type",
            "Not undoable",
            "This kind of transaction cannot be undone: no controlled undo is defined for it."),
    /** SRS-15-3 / SRS-15-10, BR-CYC-006: posted to accounts, never undone. */
    CYCLE_POSTED(
            HttpStatus.CONFLICT,
            "CYC-409-POSTED",
            "cycle/posted",
            "Posted to accounts",
            "The payroll is posted to accounts (EBS): correct it by an arrear or recovery in the"
                    + " next open month, or by a supplementary payment (I-23), never by an undo."),
    /** SRS M-10 field table and alternate flow: a period of a calendar proposal is not valid. */
    CALENDAR_PERIOD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CAL-422-PERIOD",
            "calendar/period",
            "Period not valid",
            "Period {0}: {1}."),
    /** Plan 05 D-12: a cycle was initiated for the period; its dates are fixed. */
    CALENDAR_IN_USE(
            HttpStatus.CONFLICT,
            "CAL-409-IN-USE",
            "calendar/in-use",
            "Period in use",
            "Period {0}: a payroll cycle was initiated for it, so its dates can no longer change."),
    /** SRS-15-1 (plan 05 D-13): the proposed run order is not a valid order of the steps. */
    RUN_ORDER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CAL-422-ORDER",
            "calendar/run-order",
            "Run order not valid",
            "Run order {0}: step {1} {2}."),
    /** SRS-15-19 / BR-CYC-018: the head is not one of the deduction heads I-19 permits. */
    MDP_HEAD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MDP-422-HEAD",
            "mdp/head",
            "Head not permitted",
            "Only the permitted deduction heads (Professional Tax, Labour Welfare Fund,"
                    + " Contributory Scheme, Benevolent Fund) are corrected on I-19; payments go"
                    + " through Supplementary Payment I-23."),
    /** SRS-15-19: no permitted head is configured (list MDP_PERMITTED_COMPONENT on M-16). */
    MDP_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MDP-422-NOT-CONFIGURED",
            "mdp/not-configured",
            "Not configured",
            "No deduction head is configured for I-19 yet (M-16 list MDP_PERMITTED_COMPONENT)."),
    /** SRS-15-2: the period is not locked at the unit, or its MDP window is not open today. */
    MDP_WINDOW(
            HttpStatus.CONFLICT,
            "MDP-409-WINDOW",
            "mdp/window",
            "Window not open",
            "Period {0}: entries are taken only after the month-end lock, while its MDP window is"
                    + " open (M-10)."),
    /** SRS I-19 field table: the member has no locked result in the period's run. */
    MDP_MEMBER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MDP-422-MEMBER",
            "mdp/member",
            "Member not in the locked run",
            "The member is not in the locked payroll run of the period."),
    /** SRS I-19 field table: the amount is a deduction only, Numeric (12,2). */
    MDP_AMOUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "MDP-422-AMOUNT",
            "mdp/amount",
            "Amount not valid",
            "The amount is not valid: {0}."),
    /** SRS-G-1 / SRS-G-37: the entry cannot be amended or cancelled now. */
    MDP_STATE(
            HttpStatus.CONFLICT,
            "MDP-409-STATE",
            "mdp/state",
            "Not possible now",
            "The entry is {0}: it cannot be changed now."),
    /** SRS l.3389: payslips are generated only for a locked period. */
    PAYSLIP_NOT_LOCKED(
            HttpStatus.CONFLICT,
            "PSL-409-NOT-LOCKED",
            "payslip/not-locked",
            "Period not locked",
            "No payslip can be generated for {0}: the period is not locked."),
    /**
     * SRS-15-35 / FRS BR-SEC-007 (SRS label BR-SEC-006, CLR-43): a suspension covers the release.
     */
    RELEASE_SUSPENDED(
            HttpStatus.CONFLICT,
            "SUS-409-SUSPENDED",
            "release/suspended",
            "Release suspended",
            "Release ({0}) is suspended (suspension {1}): nothing is released until its resumption"
                    + " is approved."),
    /** SRS-15-35: the suspension is already resumed, or its resumption already waits. */
    SUSPENSION_STATE(
            HttpStatus.CONFLICT,
            "SUS-409-STATE",
            "release/suspension-state",
            "Not possible now",
            "The suspension is {0}: it cannot be resumed now."),
    /** SRS-15-35: a UNIT suspension names a payroll cycle unit; an ALL_UNITS one names none. */
    SUSPENSION_SCOPE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SUS-422-SCOPE",
            "release/suspension-scope",
            "Scope not valid",
            "The scope is not valid: {0}."),
    /** SRS-15-7: the C-09 population selection is not valid (or above the configured maximum). */
    SIMULATION_SELECTION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SIM-422-SELECTION",
            "simulation/selection",
            "Selection not valid",
            "The population selection is not valid: {0}."),
    /** SRS C-09 / plan D-17: the what-if change is not valid. */
    SIMULATION_WHAT_IF(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SIM-422-WHAT-IF",
            "simulation/what-if",
            "What-if not valid",
            "The what-if change is not valid: {0}."),
    /** SRS-15-7: an employee number is not a member of the office. */
    SIMULATION_MEMBER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SIM-422-MEMBER",
            "simulation/member",
            "Not a member of the office",
            "Not an active member of this office: {0}."),
    /** SRS-15-7 / OQ-17: the maximum selection or the interactive limit is not set on A-07. */
    SIMULATION_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SIM-422-NOT-CONFIGURED",
            "simulation/not-configured",
            "Not configured",
            "Simulation runs are not configured yet: the setting {0} has no value on A-07."),
    /** SRS-1-28: the task cannot be submitted or decided in its present state. */
    I21_STATE(
            HttpStatus.CONFLICT,
            "HRC-409-STATE",
            "hrms-change/state",
            "Not possible now",
            "The HRMS change task is {0}: it cannot be submitted now."),
    /**
     * SRS-9-23, SRS-1-28 (Wave C review): a newer HRMS record superseded the task while it waited;
     * it is never approved.
     */
    I21_SUPERSEDED(
            HttpStatus.CONFLICT,
            "HRC-409-SUPERSEDED",
            "hrms-change/superseded",
            "Superseded",
            "The HRMS change task {0} was superseded by a newer record from HRMS: it cannot be"
                    + " approved. Reject or return it."),
    /** Plan 16 D16-4: the field has no Payroll column mapped yet. */
    I21_NOT_APPLICABLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HRC-422-NOT-APPLICABLE",
            "hrms-change/not-applicable",
            "Not applicable yet",
            "A change of {0} cannot be applied in Payroll yet: no column is configured for it."),
    /** SRS-1-22 / plan 16 D16-7: a setting the recalculation needs has no value on A-07. */
    I21_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HRC-422-NOT-CONFIGURED",
            "hrms-change/not-configured",
            "Not configured",
            "This change cannot be approved yet: the setting {0} has no value on A-07."),
    /** SRS-G-31: the order details are incomplete or not valid. */
    I21_ORDER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HRC-422-ORDER",
            "hrms-change/order",
            "Order details not valid",
            "The order details are not valid: {0}."),
    /** SRS-16-18 / SRS-G-31 (M-11): the transfer order is incomplete. */
    M11_ORDER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-TRANSFER-ORDER",
            "employee/transfer-order",
            "Transfer order incomplete",
            "The transfer order is incomplete: {0}."),

    /** M-11 field table: the destination must be an active organisational unit. */
    M11_DESTINATION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-TRANSFER-DEST",
            "employee/transfer-destination",
            "Destination not active",
            "The destination is not an active organisational unit."),

    /**
     * UC M-11 main flow (SRS l.1321): the origin already holds a computation or work-slip after the
     * transfer date.
     */
    M11_ORIGIN_LATER(
            HttpStatus.CONFLICT,
            "EMP-409-TRANSFER-ORIGIN",
            "employee/transfer-origin",
            "Origin has later records",
            "The origin already holds a computation or work-slip dated {0}, after the transfer date"
                    + " {1}."),

    /** SRS-1-7 / FRS §7.8.1 Scenario 4: a Hold / Stop-Payment change that is not valid. */
    M01_CONTROL_FLAG(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-CONTROL",
            "employee/control-flag",
            "Control flag change not valid",
            "The Hold / Stop-Payment change is not valid: {0}."),

    M01_CONTROL_PENDING(
            HttpStatus.CONFLICT,
            "EMP-409-CONTROL-PENDING",
            "employee/control-flag-pending",
            "Control flag change pending",
            "A change of the {0} flag already waits for the Payroll Checker."),

    /** SRS-1-11 / BR-EMPM-005 / 006: an M-01 allowance flag proposal is not valid. */
    M01_FLAG(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-FLAG",
            "employee/flag",
            "Allowance flag change not valid",
            "The allowance flag change is not valid: {0}."),
    /** SRS-1-11: a change of the same flag already waits for the Payroll Checker. */
    M01_FLAG_PENDING(
            HttpStatus.CONFLICT,
            "EMP-409-FLAG-PENDING",
            "employee/flag-pending",
            "Allowance flag change pending",
            "A change of the allowance flag {0} already waits for the Payroll Checker."),
    /** SRS-1-11: an onboarding submission of a new record is not valid. */
    M01_ONBOARD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EMP-422-ONBOARD",
            "employee/onboard",
            "Onboarding submission not valid",
            "The onboarding submission is not valid: {0}."),
    /** SRS-1-11 / SRS-1-12: the record is not waiting for its Payroll setup. */
    M01_ONBOARD_STATE(
            HttpStatus.CONFLICT,
            "EMP-409-ONBOARD",
            "employee/onboard-state",
            "Record not in Payroll Setup Pending",
            "The record is not waiting for its Payroll setup (it is {0})."),
    /** SRS-1-70: the HRMS Update Request is not valid (field, date, reference or employee). */
    I21_REQUEST(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HRC-422-REQUEST",
            "hrms-change/request",
            "HRMS Update Request not valid",
            "The HRMS Update Request is not valid: {0}."),
    /** SRS-1-28: the HRMS field is not in the pay-effect field registry. */
    I21_FIELD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HRC-422-FIELD",
            "hrms-change/field",
            "Unknown HRMS field",
            "{0} is not a field in the HRMS pay-effect registry."),
    /** SRS-1-28: the HRMS value does not fit the field. */
    I21_VALUE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HRC-422-VALUE",
            "hrms-change/value",
            "Value not valid",
            "The HRMS value of {0} is not valid for the field."),
    /** SRS-1-9, BR-EMPM-003: PAN is unique across the employee population. */
    I21_PAN_HELD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HRC-422-PAN-HELD",
            "hrms-change/pan-held",
            "PAN already held",
            "This PAN is already held by another employee: a PAN belongs to one employee only."),
    /** SRS-9-1 / SRS-9-2: an NPS record received from HRMS is not valid. */
    NPS_RECEIPT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "NPS-422-RECEIPT",
            "nps/receipt",
            "NPS record not valid",
            "The NPS record received from HRMS is not valid: {0}."),
    /** SRS-12-1 / SRS-12-6 / SRS-12-18: the loan sanction is not valid (terms, order, member). */
    LOAN_SANCTION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LOAN-422-SANCTION",
            "loan/sanction",
            "Loan sanction not valid",
            "The loan sanction is not valid: {0}."),
    /** R-RULE: a ceiling, limit or rate the loan needs has no value on M-18 (Q-024, Q-083). */
    LOAN_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LOAN-422-NOT-CONFIGURED",
            "loan/not-configured",
            "Not configured",
            "The loan cannot be set up yet: {0} has no value."),
    /** SRS-12-11 / SRS-12-12 / SRS-12-14: an eligibility ceiling is not met. */
    LOAN_CEILING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LOAN-422-CEILING",
            "loan/ceiling",
            "Eligibility criterion not met",
            "The loan is not within the limit: {0}."),
    /** SRS-12-8: an open request of the same type waits; the Maker must review it first. */
    LOAN_OPEN_REQUEST(
            HttpStatus.CONFLICT,
            "LOAN-409-OPEN-REQUEST",
            "loan/open-request",
            "Open request of the same type",
            "The member already has an open {0} request ({1}): review it before setting up"
                    + " another."),
    /** Plan 18 D18-2: the month is locked; a change waits for the arrears module (SRS-9-23). */
    NPS_LOCKED(
            HttpStatus.CONFLICT,
            "NPS-409-LOCKED",
            "nps/locked",
            "Month locked",
            "The payroll of {0} is locked: a back-dated NPS change is applied as an arrear or"
                    + " recovery."),
    /**
     * SRS-9-23 (Wave C review): the month's NPS record changed after the I-21 task was opened, so
     * the task no longer matches what HRMS sent last.
     */
    NPS_STALE(
            HttpStatus.CONFLICT,
            "NPS-409-STALE",
            "nps/stale",
            "Record changed",
            "The NPS record of {0} changed after this task was opened: the task no longer matches"
                    + " HRMS. Reject it; the newer record has its own task or applied at once."),
    /** SRS-15-23: Initialise stays refused until every pre-run check P1-P5 is clear. */
    CYCLE_PRE_RUN(
            HttpStatus.CONFLICT,
            "CYC-409-PRE-RUN",
            "cycle/pre-run",
            "Pre-run checks not clear",
            "The cycle cannot be initiated: pre-run checks {0} are not clear (C-02)."),
    CYCLE_PRIOR_NOT_LOCKED(
            HttpStatus.CONFLICT,
            "CYC-409-PRIOR",
            "cycle/prior-not-locked",
            "Prior period not locked",
            "The cycle cannot be initiated: the prior period {0} of this cycle unit is not locked"
                    + " (SRS-15-33)."),
    CYCLE_NO_CALENDAR(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-CALENDAR",
            "cycle/no-calendar",
            "Period not in the calendar",
            "{0} is not in the payroll calendar (M-10)."),
    CYCLE_NO_UNIT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-UNIT",
            "cycle/no-unit",
            "No payroll cycle unit",
            "Your office has no payroll cycle unit above it."),
    CYCLE_GATES_UNMET(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-GATES",
            "cycle/gates-unmet",
            "Gates not met",
            "The cycle cannot be locked: {0}."),
    CYCLE_GATE_HARD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-GATE-HARD",
            "cycle/gate-hard",
            "Gate cannot be accepted",
            "Gate {0} cannot be accepted by any role; only G2, G5 and G6 may be accepted with a"
                    + " reason."),
    /**
     * SRS-15-16 / SRS l.1525 (BMAD TR-007): G6 is overridable, but per member - the Checker accepts
     * each quarantined member on its own review, never the gate as a whole.
     */
    CYCLE_GATE_PER_MEMBER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-GATE-PER-MEMBER",
            "cycle/gate-per-member",
            "Gate accepted per member",
            "Gate {0} is accepted member by member: review each quarantined member (accept or"
                    + " correct) in the quarantine list."),
    /**
     * SRS l.1524 / SRS-15-16 (BMAD TR-010): G5 is accepted only for named members legitimately
     * excluded (members of the cycle's offices, as many as remain uncomputed).
     */
    CYCLE_GATE_MEMBERS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-GATE-MEMBERS",
            "cycle/gate-members",
            "Name the excluded members",
            "Gate {0} is accepted only for named members legitimately excluded: {1}."),
    /**
     * SRS-15-31 (BMAD TR-008): an un-freeze is applied only after the Payroll Checker checked it at
     * its own level, before the configured authority approves.
     */
    CYCLE_UNFREEZE_UNCHECKED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-UNFREEZE-CHECK",
            "cycle/unfreeze-unchecked",
            "Un-freeze not checked",
            "The un-freeze has not been checked by the Payroll Checker: its route (A-08) must have"
                    + " the Checker's level before the approving authority."),
    CYCLE_GATE_MET(
            HttpStatus.CONFLICT,
            "CYC-409-GATE-MET",
            "cycle/gate-met",
            "Gate already met",
            "Gate {0} is met or already accepted; there is nothing to accept."),
    CYCLE_CHECK_NOT_ACKNOWLEDGEABLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CYC-422-CHECK",
            "cycle/check-not-acknowledgeable",
            "Check cannot be acknowledged",
            "Only a failed Warn check can be acknowledged; a Block check must be fixed."),
    JOB_ALREADY_QUEUED(
            HttpStatus.CONFLICT,
            "JOB-409-QUEUED",
            "jobs/already-open",
            "Job already queued or running",
            "The same job with the same parameters is already pending, running or paused."),
    JOB_INVALID_STATE(
            HttpStatus.CONFLICT,
            "JOB-409-STATE",
            "jobs/invalid-state",
            "Not possible in this state",
            "{0} is not possible while the job is {1}."),
    JOB_RESTART_EXPIRED(
            HttpStatus.CONFLICT,
            "JOB-409-RESTART-EXPIRED",
            "jobs/restart-window-expired",
            "Too late to resume or restart",
            "The run ended more than {0} hours ago; submit a new job instead."),
    // SRS-15-26 / BR-CYC-013: only a full restart (a new job) after a change since the run began.
    JOB_RESUME_RULES_CHANGED(
            HttpStatus.CONFLICT,
            "JOB-409-RESUME-RULES",
            "jobs/resume-rules-changed",
            "Rates or rules changed since the run started",
            "A rate, slab or rule version in force for this period was activated after the run"
                    + " started, so it cannot continue: submit a new job, which computes every"
                    + " member from the start."),
    // Plan 05j (owner decision D-5): the per-job abandon and the global halt.
    JOB_ABANDON_PENDING(
            HttpStatus.CONFLICT,
            "JOB-409-ABANDON-PENDING",
            "jobs/abandon-pending",
            "Abandon awaiting approval",
            "This job is to be abandoned once the checker approves; nothing else is done with it"
                    + " until then."),
    JOB_HALTED(
            HttpStatus.CONFLICT,
            "JOB-409-HALTED",
            "jobs/halted",
            "Batch processing is halted",
            "Batch processing is halted: no job is resumed or restarted until the halt is"
                    + " released."),
    JOB_ALREADY_HALTED(
            HttpStatus.CONFLICT,
            "JOB-409-ALREADY-HALTED",
            "jobs/already-halted",
            "Already halted",
            "Batch processing is already halted."),
    JOB_NOT_HALTED(
            HttpStatus.CONFLICT,
            "JOB-409-NOT-HALTED",
            "jobs/not-halted",
            "Not halted",
            "Batch processing is not halted: there is nothing to release."),
    JOB_RESUME_PARAMETERS_CHANGED(
            HttpStatus.CONFLICT,
            "JOB-409-RESUME-PARAMS",
            "jobs/resume-parameters-changed",
            "Run parameters changed",
            "The parameters of this job differ from those the run started with, so it cannot"
                    + " continue: submit a new job, which computes every member from the start."),
    JOB_CONTROL_PENDING(
            HttpStatus.CONFLICT,
            "JOB-409-CONTROL-PENDING",
            "jobs/control-pending",
            "A command is already pending",
            "Wait until the previous command on this job has been applied."),
    JOB_TYPE_NOT_SUBMITTABLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "JOB-422-TYPE",
            "jobs/type-not-submittable",
            "Job cannot be submitted",
            "This job runs on its schedule only."),
    JOB_PARAMETER_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "JOB-422-PARAM",
            "jobs/parameter-invalid",
            "Invalid job parameter",
            "The parameter {0} is missing or invalid."),
    ALREADY_PENDING(
            HttpStatus.CONFLICT,
            "WF-409-PENDING",
            "workflow/already-pending",
            "Change pending",
            "A change to this record is already waiting for approval."),
    /** SRS-15-29 / SRS-NF-26: a change to this record by {0} at {1} is waiting for approval. */
    ALREADY_PENDING_BY(
            HttpStatus.CONFLICT,
            "WF-409-PENDING-BY",
            "workflow/already-pending",
            "Change pending",
            "A change to this record by {0} at {1} is already waiting for approval."),
    /** SRS-15-29 / SRS-NF-26: a save made against a version another user has since changed. */
    RECORD_CHANGED(
            HttpStatus.PRECONDITION_FAILED,
            "COM-412-CHANGED",
            "resource/stale",
            "Record changed",
            "The record was changed by {0} at {1} (now version {2}); reload it and apply your"
                    + " change again."),
    /** SRS-22-16 "mandatory attachment (Y/N)" of the approval action: none attached. */
    ATTACHMENT_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "WF-422-ATTACHMENT",
            "workflow/attachment-required",
            "Attachment required",
            "This action needs a document attached before it is submitted."),
    NO_APPROVAL_RULE(
            HttpStatus.FORBIDDEN,
            "WF-403-NO-RULE",
            "workflow/no-rule",
            "Not configured",
            "No approval authority is configured for this action."),
    /** A-08: a proposed route breaks one or more rules (each a violation: field and rule). */
    ROUTE_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "WF-422-ROUTE",
            "workflow/route-invalid",
            "Route not valid",
            "The approval route breaks one or more rules."),
    /** A-08: a version of this action already waits for the checker (BR-APPR-005). */
    ROUTE_PENDING(
            HttpStatus.CONFLICT,
            "WF-409-ROUTE-PENDING",
            "workflow/route-pending",
            "Version pending",
            "A version of this approval action already waits for approval."),
    REASON_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "WF-400-REASON",
            "workflow/reason-required",
            "Reason required",
            "Give a reason of at least 10 characters."),
    // ---------------------------------------------------------------- administration
    LOGIN_TAKEN(
            HttpStatus.CONFLICT,
            "ADM-409-LOGIN",
            "admin/login-taken",
            "Login ID in use",
            "This login ID is already registered."),
    GROUP_NOT_APPROVED(
            HttpStatus.CONFLICT,
            "ADM-409-GROUP",
            "admin/group-not-approved",
            "Not assignable",
            "This access group is not approved or not active."),
    SELF_ASSIGNMENT(
            HttpStatus.FORBIDDEN,
            "ADM-403-SELF",
            "admin/self-assignment",
            "Separation of duties",
            "You cannot change your own access or status."),
    INVALID_SCOPE(
            HttpStatus.BAD_REQUEST,
            "ADM-400-SCOPE",
            "admin/invalid-scope",
            "Invalid scope",
            "The scope does not match the access group's scope type."),
    /** SRS-22-3: a consolidated-view group's role must permit read-only MIS access. */
    CONSOLIDATED_NEEDS_MIS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ADM-422-CONSOLIDATED-MIS",
            "admin/consolidated-needs-mis",
            "Consolidated view needs MIS access",
            "A Headquarters consolidated-view group can be assigned only if its role permits"
                    + " read-only MIS access."),
    /** SRS-22-19 / 22-23: roles, permissions and access groups are maintained at Headquarters. */
    ADMIN_HQ_ONLY(
            HttpStatus.FORBIDDEN,
            "ADM-403-HQ-ONLY",
            "admin/headquarters-only",
            "Headquarters only",
            "Only a Headquarters administrator may change roles, permissions or access groups."),
    PROTECTED_ROLE(
            HttpStatus.FORBIDDEN,
            "ADM-403-PROTECTED",
            "admin/protected-role",
            "Protected role",
            "The permissions of the System Administrator role cannot be changed here."),
    INVALID_STATUS_CHANGE(
            HttpStatus.CONFLICT,
            "ADM-409-STATUS",
            "admin/invalid-status",
            "Not allowed",
            "This status change is not allowed."),
    /** A-05: no From / To, or a To before the From (the range is empty). */
    AUDIT_RANGE(
            HttpStatus.BAD_REQUEST,
            "AUD-400-RANGE",
            "audit/range",
            "Date range not valid",
            "Give a From date and a To date on or after it."),
    AUDIT_WINDOW(
            HttpStatus.BAD_REQUEST,
            "AUD-400-WINDOW",
            "audit/window",
            "Date range too large",
            "Narrow the date range to at most {0} days."),
    /** X-04 Record Key: must resolve to an existing master or rule record the reader may see. */
    AUDIT_RECORD_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "AUD-404-RECORD",
            "audit/record-not-found",
            "Record not found",
            "No master or rule record with this key is open to you."),
    /** SRS-20-6: more than one version claims the date - a data error, never resolved by a pick. */
    AUDIT_VERSIONS_OVERLAP(
            HttpStatus.CONFLICT,
            "AUD-409-VERSIONS",
            "audit/versions-overlap",
            "Versions overlap",
            "Versions {0} are all in force on this date; only one may be. Report it to the"
                    + " administrator."),
    /** SRS-20-19 / SRS-20-21: history is never changed through the application. */
    AUDIT_IMMUTABLE(
            HttpStatus.METHOD_NOT_ALLOWED,
            "AUD-405-IMMUTABLE",
            "audit/immutable",
            "History cannot be changed",
            "Audit and Legacy Audit records cannot be created, changed or deleted. The attempt has"
                    + " been recorded."),
    /** SRS-20-19 / SRS-20-21: the database refused a change to a history row. */
    AUDIT_TAMPER_REFUSED(
            HttpStatus.CONFLICT,
            "AUD-409-IMMUTABLE",
            "audit/tamper-refused",
            "History cannot be changed",
            "The change was refused: history rows are never changed or deleted. The attempt has"
                    + " been recorded."),
    /** SRS-20-5: a retention period proposal that is not a valid setting. */
    RETENTION_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "AUD-422-RETENTION",
            "audit/retention-invalid",
            "Retention period not valid",
            "Give a number of months between 1 and {0}, or mark the period indefinite, with the"
                    + " instrument that sets it."),
    /** SRS-20-5: one pending change per retention class at a time. */
    RETENTION_PENDING(
            HttpStatus.CONFLICT,
            "AUD-409-RETENTION-PENDING",
            "audit/retention-pending",
            "Change already pending",
            "A change of this retention period is waiting for approval.");

    /**
     * SRS-EH-5 (US-GAP-116): the codes that are Security conditions whatever their HTTP status.
     * SRS-EH-5 names an attempted edit of a history row a Security condition: refused by the
     * application (AUD-405-IMMUTABLE) or by the database's immutability trigger
     * (AUD-409-IMMUTABLE). Its other examples (access outside the Access Group scope, an OTP or
     * account lockout) answer 403 / 423 and are Security by their status already. Read by {@code
     * scripts/error_catalogue.py} for docs/api/errors.md.
     */
    private static final java.util.Set<String> SECURITY_CONDITIONS =
            java.util.Set.of("AUD-405-IMMUTABLE", "AUD-409-IMMUTABLE");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    ApiErrorCode(
            HttpStatus status,
            String code,
            String type,
            String defaultTitle,
            String defaultDetail) {
        this.status = status;
        this.code = code;
        this.type = type;
        this.defaultTitle = defaultTitle;
        this.defaultDetail = defaultDetail;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String defaultTitle() {
        return defaultTitle;
    }

    @Override
    public String defaultDetail() {
        return defaultDetail;
    }

    /** SRS-EH-1, SRS-EH-5: by the HTTP status, except the named Security conditions. */
    @Override
    public MessageSeverity severity() {
        return SECURITY_CONDITIONS.contains(code)
                ? MessageSeverity.SECURITY
                : ErrorCode.super.severity();
    }
}
