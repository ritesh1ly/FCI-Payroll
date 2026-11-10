package in.gov.fci.payroll.general.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the general module (Module 01 transactions; docs/api/errors.md). The screen shows
 * {@code error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum GeneralErrorCode implements ErrorCode {
    // ---------------------------------------------------------------- X-06 Payroll Query
    /** SRS-G-11 precondition: "A payroll record exists for the period the query concerns". */
    QUERY_PAYROLL_ID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "QRY-422-PAYROLL-ID",
            "query/payroll-id",
            "Unknown Payroll ID",
            "No payroll record of a run of record has this Payroll ID."),
    /** The lifecycle Open -> In Progress -> Resolved -> Closed admits only forward steps. */
    QUERY_STATUS(
            HttpStatus.CONFLICT,
            "QRY-409-STATUS",
            "query/status",
            "Query status changed",
            "This query is {0}; the action is not possible in that status."),
    /** SRS X-06 field table: Maker Response is required before the query can move to Resolved. */
    QUERY_RESPONSE_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "QRY-422-RESPONSE",
            "query/response-required",
            "Response required",
            "The Maker response is required before the query is resolved."),
    /**
     * DDL chk_query_checker: a computed-amount query is confirmed by someone other than the
     * responder.
     */
    QUERY_SAME_PERSON(
            HttpStatus.FORBIDDEN,
            "QRY-403-SOD",
            "query/same-person",
            "Different person needed",
            "A query about a computed amount is confirmed by a Checker who did not respond to it."),
    /** A computed-amount query is resolved only by the Payroll Checker (SRS X-06 use case). */
    QUERY_CHECKER_REQUIRED(
            HttpStatus.FORBIDDEN,
            "QRY-403-CHECKER",
            "query/checker-required",
            "Checker confirmation needed",
            "A query about a computed amount is confirmed by the Payroll Checker."),
    /** X-06: a query about a computed amount keeps that flag, so the Checker confirms it. */
    QUERY_COMPUTED_FLAG(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "QRY-422-COMPUTED",
            "query/computed-flag",
            "Computed amount kept",
            "This query concerns a computed amount and stays so: the Payroll Checker confirms its"
                    + " resolution."),
    QUERY_TEXT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "QRY-422-TEXT",
            "query/text",
            "Text not accepted",
            "Enter the text in English letters, digits and punctuation, up to 4000 characters."),

    // ---------------------------------------------------------------- X-05 Exceptions & Holds
    /** SRS-G-9: only a non-blocking item holding gate G2 can be accepted instead of corrected. */
    EXCEPTION_NOT_ACCEPTABLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EXC-422-ACCEPT",
            "exception/not-acceptable",
            "Cannot be accepted",
            "Only a non-blocking exception at gate G2 can be accepted; a blocking one must be"
                    + " cleared."),
    /** SRS-G-9: acceptance needs a recorded reason (DDL chk_exception_accept). */
    EXCEPTION_REASON(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EXC-422-REASON",
            "exception/reason-required",
            "Reason required",
            "Enter the reason for accepting the exception."),
    EXCEPTION_OWNER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "EXC-422-OWNER",
            "exception/owner",
            "Owner not recognised",
            "Choose an owner from the list."),
    EXCEPTION_STATUS(
            HttpStatus.CONFLICT,
            "EXC-409-STATUS",
            "exception/status",
            "Exception already settled",
            "This exception is {0}; it can no longer be changed."),

    // ---------------------------------------------------------------- X-07 HRMS Fallback Upload
    /** SRS-EH-11: refused without an open enablement; the message names the normal route. */
    FALLBACK_NOT_ENABLED(
            HttpStatus.CONFLICT,
            "HFB-409-NOT-ENABLED",
            "fallback/not-enabled",
            "Upload not enabled",
            "No fallback upload is enabled for this cycle and office. HRMS data reaches Payroll"
                + " through the HRMS interface; Headquarters enables an upload only when HRMS is"
                + " unavailable."),
    /** SRS-G-46: a file not in the HRMS export format is rejected as a whole. */
    FALLBACK_FORMAT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HFB-422-FORMAT",
            "fallback/format",
            "Not in the HRMS export format",
            "The file is not in the HRMS export format for this upload type."),
    /**
     * Decision D-2: no HRMS export layout is configured, so nothing is read rather than guessed.
     */
    FALLBACK_FORMAT_NOT_CONFIGURED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "HFB-503-FORMAT",
            "fallback/format-not-configured",
            "Export format not configured",
            "The HRMS export format for this upload type is not configured yet."),
    /** X-07 precondition: "The payroll cycle for the period is not yet locked." */
    FALLBACK_CYCLE_LOCKED(
            HttpStatus.CONFLICT,
            "HFB-409-LOCKED",
            "fallback/cycle-locked",
            "Cycle locked",
            "The payroll cycle of {0} is locked; a fallback upload is no longer possible."),
    FALLBACK_STATUS(
            HttpStatus.CONFLICT,
            "HFB-409-STATUS",
            "fallback/status",
            "Status changed",
            "This upload is {0}; the action is not possible in that status."),
    FALLBACK_NOTHING_ACCEPTED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HFB-422-EMPTY",
            "fallback/nothing-accepted",
            "No accepted record",
            "No record passed validation, so there is nothing to approve."),
    /** X-07 field table: the order copy is mandatory for fallback (b). */
    FALLBACK_ORDER_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HFB-422-ORDER",
            "fallback/order-required",
            "Order copy required",
            "Attach the suspension, absconding or revocation order before submitting."),
    FALLBACK_OFFICES(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HFB-422-OFFICES",
            "fallback/offices",
            "Offices not accepted",
            "Choose one or more known offices."),
    FALLBACK_PERIOD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "HFB-422-PERIOD",
            "fallback/period",
            "Period not accepted",
            "Choose a payroll month (YYYYMM)."),

    // ---------------------------------------------------------------- I-20 Off-Cycle Salary
    /**
     * SRS-G-10: disbursement is blocked unless the duplicate check passes; no role can override.
     */
    OFFCYCLE_DUPLICATE(
            HttpStatus.CONFLICT,
            "OFC-409-DUPLICATE",
            "offcycle/duplicate",
            "Duplicate payment",
            "The duplicate-payment check failed ({0}); the case cannot be paid."),
    /** SRS-G-10: a month-end-locked salary month is paid through I-23. */
    OFFCYCLE_MONTH_LOCKED(
            HttpStatus.CONFLICT,
            "OFC-409-LOCKED",
            "offcycle/month-locked",
            "Salary month locked",
            "The salary month {0} is month-end locked: pay it through I-23 Supplementary Payment."),
    /** The amount is computed, never entered; a missing rate or member field blocks it. */
    OFFCYCLE_NOT_COMPUTABLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "OFC-422-COMPUTE",
            "offcycle/not-computable",
            "Salary cannot be computed",
            "The salary for this month cannot be computed: {0}."),
    /** SRS-G-16: submission is blocked without the approval note or order. */
    OFFCYCLE_ORDER_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "OFC-422-ORDER",
            "offcycle/order-required",
            "Order required",
            "Attach the approval note or order before submitting."),
    /**
     * chk_off_cycle_cancel: after the invoice is sent, cancel only once APPS confirms; SRS-EH-12:
     * the message states the Invoice Sent to APPS Date ({0}).
     */
    OFFCYCLE_INVOICE_SENT(
            HttpStatus.CONFLICT,
            "OFC-409-INVOICE",
            "offcycle/invoice-sent",
            "Invoice already sent",
            "The invoice was sent to APPS on {0}: it can be cancelled once APPS confirms the"
                    + " invoice cancellation."),
    OFFCYCLE_STATUS(
            HttpStatus.CONFLICT,
            "OFC-409-STATUS",
            "offcycle/status",
            "Case changed",
            "This case is {0}; the action is not possible."),
    OFFCYCLE_INPUT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "OFC-422-INPUT",
            "offcycle/input",
            "Case details not accepted",
            "Check the case type, salary month and approval reference."),

    // ---------------------------------------------------------------- I-23 Supplementary Payment
    /** SRS-G-33 / trg_supp_period_locked: an unlocked period is paid in its regular run. */
    SUPPLEMENTARY_NOT_LOCKED(
            HttpStatus.CONFLICT,
            "SUP-409-NOT-LOCKED",
            "supplementary/period-not-locked",
            "Period not locked",
            "The period {0} is not month-end locked: the amount belongs in that period's regular"
                    + " run."),
    /** BR-PEN2-003 / trg_supp_component_excluded: M-14 keeps the excluded pay codes. */
    SUPPLEMENTARY_EXCLUDED_CODE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SUP-422-EXCLUDED",
            "supplementary/excluded-pay-code",
            "Pay code excluded",
            "Pay code {0} is excluded from supplementary payment."),
    /**
     * SRS l.786 / BR-CYC-018 (US-GAP-113): a head corrected in bulk on I-19 (the M-16 list
     * MDP_PERMITTED_COMPONENT: PT, LWF, Contributory Scheme, Benevolent Fund) is not paid on I-23.
     */
    SUPPLEMENTARY_USE_I19(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SUP-422-USE-I19",
            "supplementary/use-direct-payroll",
            "Use I-19",
            "Pay code {0} is corrected in bulk on I-19 Maintain Direct Payroll, not on I-23."),
    /** SRS-G-34 / chk_supp_dup_passed: no role, the Administrator included, overrides it. */
    SUPPLEMENTARY_DUPLICATE(
            HttpStatus.CONFLICT,
            "SUP-409-DUPLICATE",
            "supplementary/duplicate",
            "Duplicate payment",
            "A matching payment exists for this member, component and period; the request is"
                    + " blocked."),
    /** Decision D-5: TDS, CPF / EPS and PT come with modules 07 / 08; nothing is guessed. */
    SUPPLEMENTARY_STATUTORY(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SUP-503-STATUTORY",
            "supplementary/statutory-not-configured",
            "Statutory deductions not configured",
            "The statutory deductions (TDS, CPF / EPS, PT) for this payment are not configured"
                    + " yet, so it cannot be submitted."),
    /** The amount comes from the originating override or event (I-23 field table: CALC). */
    SUPPLEMENTARY_SOURCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SUP-422-SOURCE",
            "supplementary/raised-by-source",
            "Raised by the originating transaction",
            "A {0} payment is raised by the transaction that computes it, not entered here."),
    SUPPLEMENTARY_SANCTION_DATE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SUP-422-SANCTION",
            "supplementary/sanction-date",
            "Sanction date not accepted",
            "The sanction date must be later than the application date."),
    SUPPLEMENTARY_INVOICE_SENT(
            HttpStatus.CONFLICT,
            "SUP-409-INVOICE",
            "supplementary/invoice-sent",
            "Invoice already sent",
            "The supplementary invoice was sent to APPS on {0}: it can be cancelled once APPS"
                    + " confirms the invoice cancellation."),
    SUPPLEMENTARY_STATUS(
            HttpStatus.CONFLICT,
            "SUP-409-STATUS",
            "supplementary/status",
            "Request changed",
            "This request is {0}; the action is not possible."),
    SUPPLEMENTARY_INPUT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SUP-422-INPUT",
            "supplementary/input",
            "Request details not accepted",
            "Check the member, payment type, period, amount and order details.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    GeneralErrorCode(
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
