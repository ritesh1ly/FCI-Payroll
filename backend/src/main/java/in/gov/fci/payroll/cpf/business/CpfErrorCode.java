package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the CPF module (Module 07; docs/api/errors.md). The screen shows {@code error.<code>}
 * from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum CpfErrorCode implements ErrorCode {
    // ---------------------------------------------------------------- M-19 PF interest rates
    /** M-19 Financial Year: the current-system format, e.g. 2026-27. */
    FIN_YEAR(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-FIN-YEAR",
            "cpf/fin-year",
            "Financial year not valid",
            "Enter the financial year as YYYY-YY, the second year following the first (e.g."
                    + " 2026-27)."),
    /** M-19 Rate %: a percentage with at most two decimals. */
    RATE_VALUE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-RATE",
            "cpf/rate",
            "Rate not valid",
            "A rate is a percentage from 0 to 100 with at most two decimals."),
    /** M-19 Applies to period: inside the financial year, the end not before the start. */
    RATE_PERIOD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PERIOD",
            "cpf/period",
            "Period not valid",
            "The rate applies within its financial year, and its end is not before its start."),
    /** M-19 Bonus interest: over the FCI-declared (Trust) rate only. */
    BONUS_SOURCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-BONUS",
            "cpf/bonus-source",
            "Bonus interest not allowed here",
            "Bonus interest is declared over the FCI-declared (Trust) rate only."),
    /** M-19 versions: a new rate starts after the approved one it replaces. */
    RATE_ORDER(
            HttpStatus.CONFLICT,
            "CPF-409-RATE-ORDER",
            "cpf/rate-order",
            "Earlier than the rate in force",
            "A new rate for this year, fund and source starts after the approved rate it replaces"
                    + " ({0})."),
    /** M-19 declaration: only an approved rate not yet declared is declared. */
    RATE_DECLARED(
            HttpStatus.CONFLICT,
            "CPF-409-DECLARED",
            "cpf/declared",
            "Rate already declared",
            "Only an approved rate that is not yet declared can be declared."),

    // ---------------------------------------------------------------- member ledger (07c)
    /** SRS-6-57, BR-CPF-025: no part of a CPF / PF balance may go below zero. */
    NEGATIVE_BALANCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-NEGATIVE",
            "cpf/negative-balance",
            "Balance would go negative",
            "The {0} part of the CPF balance would go below zero; the transaction is refused."),
    /** SRS-6-59: a CPF code, received from HRMS, before CPF is deducted or posted. */
    CPF_CODE_MISSING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-CPF-CODE",
            "cpf/cpf-code",
            "CPF code missing",
            "The member has no CPF code from HRMS; it is listed on the Exceptions & Holds"
                    + " worklist."),
    /** SRS-6-59: only a CPF member has a CPF ledger. */
    NOT_A_CPF_MEMBER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-NOT-MEMBER",
            "cpf/not-a-member",
            "Not a CPF member",
            "The member does not belong to the CPF / PF fund."),
    /** A member's ledger is closed for the change (finally settled or closed by a zone change). */
    LEDGER_CLOSED(
            HttpStatus.CONFLICT,
            "CPF-409-LEDGER-CLOSED",
            "cpf/ledger-closed",
            "Ledger closed",
            "The member's CPF ledger is {0}; nothing more is posted to it."),

    // ---------------------------------------------------------------- I-22 (07d)
    /** SRS-6-44: a correction changes at least one part. */
    NO_CHANGE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-NO-CHANGE",
            "cpf/no-change",
            "Nothing corrected",
            "Enter a corrected amount for at least one part that differs from the current one."),
    /** SRS-6-43: the member must exist on the CPF ledger for the year corrected. */
    NO_LEDGER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-NO-LEDGER",
            "cpf/no-ledger",
            "No CPF ledger",
            "The member has no CPF ledger for that financial year; there is no opening balance to"
                    + " correct."),
    /** SRS-6-44: no correction once the member's CPF final settlement is sanctioned. */
    FINALLY_SETTLED(
            HttpStatus.CONFLICT,
            "CPF-409-SETTLED",
            "cpf/settled",
            "Final settlement sanctioned",
            "The member's CPF final settlement is sanctioned; the opening balance is no longer"
                    + " corrected."),
    /** One correction at a time per member and year (the pending one decides first). */
    CORRECTION_PENDING(
            HttpStatus.CONFLICT,
            "CPF-409-CORRECTION-PENDING",
            "cpf/correction-pending",
            "Correction already open",
            "A correction of this member's opening balance for the year is already awaiting a"
                    + " decision or the CPF Division's review."),
    /** SRS-6-43: one of the permitted reasons (M-16 list OB_CORRECTION_REASON). */
    REASON_CODE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-REASON-CODE",
            "cpf/reason-code",
            "Reason not permitted",
            "Choose one of the permitted reasons for an opening-balance correction."),
    /** SRS-6-43: evidence through the standard order-copy control. */
    EVIDENCE_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-EVIDENCE",
            "cpf/evidence",
            "Evidence required",
            "Attach the supporting evidence of the correction."),

    // ---------------------------------------------------------------- C-12 (07e)
    /** DDL pf_interest_rate: no run without the approved rates of the year its members need. */
    RATE_MISSING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-RATE-MISSING",
            "cpf/rate-missing",
            "Interest rate not configured",
            "No approved {1} interest rate for {0} on M-19; the interest run does not start."),
    /** DDL pf_interest_rate: refuse an undeclared rate rather than fall back to last year's. */
    RATE_UNDECLARED(
            HttpStatus.CONFLICT,
            "CPF-409-RATE-UNDECLARED",
            "cpf/rate-undeclared",
            "Interest rate not declared",
            "The {1} interest rate for {0} is not yet declared on M-19; the interest run does not"
                    + " start."),
    /** SRS-6-46: after the freeze no interest run or adjustment for that financial year. */
    INTEREST_FROZEN(
            HttpStatus.CONFLICT,
            "CPF-409-INTEREST-FROZEN",
            "cpf/interest-frozen",
            "Interest frozen",
            "CPF interest for this financial year is frozen; no further run or adjustment."),
    /** SRS-6-45: all members, or one member named by EIS. */
    RUN_SCOPE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-RUN-SCOPE",
            "cpf/run-scope",
            "Run scope",
            "An individual run names one member by EIS; an all-members run names none."),

    // ---------------------------------------------------------------- purposes (07f)
    /** A purpose code: upper-case letters, digits and underscores, starting with a letter. */
    PURPOSE_CODE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PURPOSE-CODE",
            "cpf/purpose-code",
            "Purpose code",
            "A purpose code has 2 to 30 upper-case letters, digits or underscores and starts with a"
                    + " letter."),
    /** BR-CPF-028: Covid-19 is not offered as a withdrawal purpose. */
    PURPOSE_WITHDRAWN(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PURPOSE-WITHDRAWN",
            "cpf/purpose-withdrawn",
            "Purpose withdrawn",
            "Covid-19 is no longer offered as a CPF withdrawal purpose."),
    /** A limit key is one whose basis the SRS states; a final payment carries none. */
    PURPOSE_LIMIT_KEY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PURPOSE-LIMIT",
            "cpf/purpose-limit",
            "Limit rule",
            "Choose one of the CPF sanction-limit rules, or none; a final payment carries no limit"
                    + " rule."),
    /** DDL max_occurrences NUMBER(3). */
    PURPOSE_QUOTA(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PURPOSE-QUOTA",
            "cpf/purpose-quota",
            "Quota",
            "A purpose quota is a whole number from 1 to 999, or none."),
    /** SRS-6-14: each document of the checklist is a type of the master, once. */
    PURPOSE_DOCUMENT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PURPOSE-DOCUMENT",
            "cpf/purpose-document",
            "Checklist document",
            "Each checklist document is a document type of the master, listed once."),
    /** A new version starts after the version in force. */
    PURPOSE_DATE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PURPOSE-DATE",
            "cpf/purpose-date",
            "Effective date",
            "A new version of the purpose starts after the version in force."),

    // ---------------------------------------------------------------- sanction chain (07f-2)
    /** SRS I-04 / E-01: the requested amount. */
    REQUEST_AMOUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-AMOUNT",
            "cpf/amount",
            "Requested amount",
            "The requested amount must be more than zero, with at most two decimals."),
    /** SRS I-04 field table "Supporting Documents": up to 5 files per request. */
    DOCUMENT_COUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-DOCUMENT-COUNT",
            "cpf/document-count",
            "Too many documents",
            "A request carries at most {0} documents."),
    /** BR-CPF-039: property details for house, construction, renovation and plot purposes. */
    PROPERTY_DETAILS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PROPERTY",
            "cpf/property",
            "Property details",
            "Enter the address and description of the property for this purpose."),
    /** SRS-6-14: submission is blocked naming the missing checklist documents. */
    DOCUMENTS_MISSING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-DOCUMENTS",
            "cpf/documents",
            "Documents missing",
            "Attach the documents the purpose requires: {0}."),
    /** SRS-6-39: a purpose in force on the request date. */
    PURPOSE_NOT_OFFERED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-PURPOSE",
            "cpf/purpose",
            "Purpose not offered",
            "The purpose is not offered on the request date."),
    /** SRS-6-5 / BR-CPF-009: minimum membership for a part-final withdrawal. */
    MEMBERSHIP_YEARS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-MEMBERSHIP",
            "cpf/membership",
            "CPF membership too short",
            "A part-final withdrawal needs at least {0} years of CPF membership."),
    /** SRS-6-5 / SRS-6-55 / BR-CPF-010: the withdrawal register count. */
    WITHDRAWAL_COUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-WITHDRAWAL-COUNT",
            "cpf/withdrawal-count",
            "Withdrawals used",
            "The member has used the {0} withdrawals permitted."),
    /** SRS-6-5 / BR-CPF-011: the 90 % window. */
    RETIREMENT_WINDOW(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-RETIREMENT-WINDOW",
            "cpf/retirement-window",
            "Outside the retirement window",
            "The 90 % withdrawal is allowed only within {0} year(s) before retirement."),
    /** SRS-6-37 / BR-CPF-030: one active advance (Q-07-4). */
    ACTIVE_ADVANCE(
            HttpStatus.CONFLICT,
            "CPF-409-ACTIVE-ADVANCE",
            "cpf/active-advance",
            "Advance already active",
            "Another CPF advance of the member is still in the chain or not yet paid."),
    /** SRS-6-36 / SRS-6-59: eligibility data received from HRMS. */
    ELIGIBILITY_DATA(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-ELIGIBILITY-DATA",
            "cpf/eligibility-data",
            "HRMS data missing",
            "The member's {0} has not been received from HRMS; eligibility cannot be checked."),
    /** SRS-6-23: a limit whose rule or basis is not configured is never guessed. */
    LIMIT_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-LIMIT-NOT-CONFIGURED",
            "cpf/limit-not-configured",
            "Limit not configured",
            "The sanction limit of this purpose cannot be computed: {0} is not configured."),
    /** SRS-6-38 / BR-CPF-031: the layout of the sanction number (Q-07-1). */
    SANCTION_NUMBER_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-SANCTION-NUMBER",
            "cpf/sanction-number",
            "Sanction number not configured",
            "The sanction-number layout (separator and sequence width) is not configured on M-16."),
    /** SRS-6-48 / BR-CPF-014: interest to the sanction date (Q-072, D-4). */
    FINAL_INTEREST_NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-FINAL-INTEREST",
            "cpf/final-interest",
            "Interest method not configured",
            "A final payment needs the interest to the sanction date, and the interest method is"
                    + " not configured (Q-072)."),
    /** SRS-6-4: each tier acts only on a request waiting at it. */
    SANCTION_STAGE(
            HttpStatus.CONFLICT,
            "CPF-409-SANCTION-STAGE",
            "cpf/sanction-stage",
            "Not at this tier",
            "The request is not waiting at this tier."),
    /**
     * FRS V0.5 §13.4 Steps 2-3 (Wave C review): the unit returns a request it cannot forward; the
     * rejection is the Zonal / HQ CPF Division's at tier 2.
     */
    TIER1_REJECT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-TIER1-REJECT",
            "cpf/tier1-reject",
            "Return instead",
            "The unit does not reject a CPF request: return it to the member with the reason. A"
                    + " rejection is the Zonal / HQ CPF Division's, at tier 2."),
    /** SRS-6-4 / SRS-G-1: one user acts once per pass (uk_cpf_tier_actor). */
    TIER_SAME_USER(
            HttpStatus.CONFLICT,
            "CPF-409-TIER-USER",
            "cpf/tier-user",
            "Already acted",
            "You have already acted on this request in this pass; another user must act at this"
                    + " tier."),
    /** SRS-6-4 tier 1: UAN, KYC and the documents verified. */
    TIER_CHECKS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-TIER-CHECKS",
            "cpf/tier-checks",
            "Verification incomplete",
            "Forward only after the UAN, KYC and documents are verified."),
    /** SRS-6-27: I-09 records only for a member on deputation. */
    NOT_DEPUTED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-NOT-DEPUTED",
            "cpf/not-deputed",
            "Not on deputation",
            "The member has no deputation received from HRMS: I-09 records remittances of deputed"
                    + " members only."),
    /** SRS-6-27 … 6-30: the entry's amounts or month. */
    DEPUTATION_ENTRY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-DEPUTATION-ENTRY",
            "cpf/deputation-entry",
            "Entry not valid",
            "The entry is not valid: {0}."),
    /** BR-CPF-021: an instalment repays a sanctioned advance of the member. */
    DEPUTATION_ADVANCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-DEPUTATION-ADVANCE",
            "cpf/deputation-advance",
            "No such advance",
            "Choose a sanctioned CPF advance of the member for the instalment."),
    /** SRS-6-29, BR-CPF-022: an instalment never takes the advance balance below zero. */
    OVER_REMITTANCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-OVER-REMITTANCE",
            "cpf/over-remittance",
            "Over-remittance",
            "The instalment exceeds the outstanding advance of {0}: record the excess separately"
                    + " as a contribution receipt or a refund item."),
    /** SRS-6-27: the remittance evidence is mandatory. */
    REMITTANCE_EVIDENCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-REMITTANCE-EVIDENCE",
            "cpf/remittance-evidence",
            "Evidence required",
            "Attach the remittance evidence."),
    /** SRS I-09 Remittance Evidence: PDF / JPG / PNG, up to 5 files per entry. */
    EVIDENCE_FILES(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-EVIDENCE-FILES",
            "cpf/evidence-files",
            "Too many evidence files",
            "An entry carries at most {0} remittance evidence files; cancel a wrong file first."),
    /** DDL uk_cpf_deputation_recovery: one entry per month, organisation, type and advance. */
    DEPUTATION_DUPLICATE(
            HttpStatus.CONFLICT,
            "CPF-409-DEPUTATION-DUPLICATE",
            "cpf/deputation-duplicate",
            "Already recorded",
            "An entry of this type for this month and organisation already exists; correct it if"
                    + " it was returned."),
    /** A returned entry is corrected by the Maker; nothing else changes an entry. */
    DEPUTATION_STATUS(
            HttpStatus.CONFLICT,
            "CPF-409-DEPUTATION-STATUS",
            "cpf/deputation-status",
            "Not with the Maker",
            "Only an entry returned to the Maker can be corrected."),
    /** DDL uk_borrowing_org: a borrowing organisation code once. */
    BORROWING_ORG(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-BORROWING-ORG",
            "cpf/borrowing-org",
            "Borrowing organisation",
            "{0}"),
    /** BR-ESEVA-006 (plan 14 phase 14c): the VPF change is not valid. */
    VPF_CHANGE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CPF-422-VPF-CHANGE",
            "cpf/vpf-change",
            "VPF change not valid",
            "The VPF change is not valid: {0}."),
    /** BR-ESEVA-006: one VPF change of a member at a time. */
    VPF_PENDING(
            HttpStatus.CONFLICT,
            "CPF-409-VPF-PENDING",
            "cpf/vpf-pending",
            "VPF change pending",
            "A VPF change of the member is already waiting ({0})."),
    /** BR-ESEVA-006: the action does not fit the request's status. */
    VPF_STATE(
            HttpStatus.CONFLICT,
            "CPF-409-VPF-STATE",
            "cpf/vpf-state",
            "Not at this status",
            "The VPF change is {0}; this action needs {1}.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    CpfErrorCode(
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
}
