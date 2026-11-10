package in.gov.fci.payroll.integration.api;

/**
 * The HRMS APIs of the Postman collection FCI shared
 * (docs/integration/HRMS_POSTMAN_API_SELECTION.md) that Payroll publishes for HRMS, chosen against
 * SRS-INT-4, SRS-INT-23 and SRS-INT-35. Each has one URL setting {@code
 * fci.integration.hrms.api.<code>.path} (SRS-INT-1, SRS-INT-16): the collection's path is only the
 * name HRMS uses today and is not a default; an API without a configured URL is not configured and
 * is refused.
 */
public enum HrmsApi {
    GET_ADVANCE_LOAN_HDR_STATUS(
            "get_advance_loan_hdr_status",
            Selection.REQUIRED,
            "GET",
            "/get-advance-loan-hdr-status"),
    GET_LEAVE_ENCASHMENT_STATUS(
            "get_leave_encashment_status",
            Selection.REQUIRED,
            "GET",
            "/get-leave-encashment-status"),
    GET_LOAN_STATUS("get_loan_status", Selection.REQUIRED, "GET", "/get-loan-status"),
    GET_LTC_ENCASH_STATUS(
            "get_ltc_encash_status", Selection.REQUIRED, "GET", "/get-ltc-encash-status"),
    GET_MEDICAL_CLAIM_STATUS(
            "get_medical_claim_status", Selection.REQUIRED, "GET", "/get-medical-claim-status"),
    GET_OTA_STATUS("get_ota_status", Selection.REQUIRED, "GET", "/get-ota-status"),
    GET_REQUEST_BRIEF_CASE_REIMBURSEMENT(
            "get_request_brief_case_reimbursement",
            Selection.REQUIRED,
            "GET",
            "/get-request-brief-case-reimbursement"),
    GET_REQUEST_LAPTOP_REIMBURSEMENT(
            "get_request_laptop_reimbursement",
            Selection.REQUIRED,
            "GET",
            "/get-request-laptop-reimbursement"),
    GET_REQUEST_LOCAL_TRAVEL_ALLOWANCE(
            "get_request_local_travel_allowance",
            Selection.REQUIRED,
            "GET",
            "/get-request-local-travel-allowance"),
    GET_REQUEST_MOBILE_REIMBURSEMENT(
            "get_request_mobile_reimbursement",
            Selection.REQUIRED,
            "GET",
            "/get-request-mobile-reimbursement"),
    GET_REQUEST_NEWSPAPER_ALLOWANCE(
            "get_request_newspaper_allowance",
            Selection.REQUIRED,
            "GET",
            "/get-request-newspaper-allowance"),
    GET_REQUEST_PAY_LTC_HOMETOWN(
            "get_request_pay_ltc_hometown",
            Selection.REQUIRED,
            "GET",
            "/get-request-pay-ltc-hometown"),
    GET_REQUEST_PAY_TRAVEL_ALLOWANCE(
            "get_request_pay_travel_allowance",
            Selection.REQUIRED,
            "GET",
            "/get-request-pay-travel-allowance"),
    GET_REQUEST_TELE_DATA_REIMBURSEMENT(
            "get_request_tele_data_reimbursement",
            Selection.REQUIRED,
            "GET",
            "/get-request-tele-data-reimbursement"),
    REQUEST_ADVANCE_LOAN_HDR(
            "request_advance_loan_hdr", Selection.REQUIRED, "POST", "/request-advance-loan-hdr"),
    REQUEST_BRIEF_CASE_REIMBURSEMENT(
            "request_brief_case_reimbursement",
            Selection.REQUIRED,
            "POST",
            "/request-brief-case-reimbursement"),
    REQUEST_LAPTOP_REIMBURSEMENT(
            "request_laptop_reimbursement",
            Selection.REQUIRED,
            "POST",
            "/request-laptop-reimbursement"),
    REQUEST_LEAVE_ENCASHMENT(
            "request_leave_encashment", Selection.REQUIRED, "POST", "/request-leave-encashment"),
    REQUEST_LOAN_FORE_CLOSURE(
            "request_loan_fore_closure", Selection.REQUIRED, "POST", "/request-loan-fore-closure"),
    REQUEST_LOAN_RESCHEDULE(
            "request_loan_reschedule", Selection.REQUIRED, "POST", "/request-loan-reschedule"),
    REQUEST_LOCAL_TRAVEL_ALLOWANCE(
            "request_local_travel_allowance",
            Selection.REQUIRED,
            "POST",
            "/request-local-travel-allowance"),
    REQUEST_LTC_ENCASH("request_ltc_encash", Selection.REQUIRED, "POST", "/request-ltc-encash"),
    REQUEST_MEDICAL_CLAIM(
            "request_medical_claim", Selection.REQUIRED, "POST", "/request-medical-claim"),
    REQUEST_MOBILE_REIMBURSEMENT(
            "request_mobile_reimbursement",
            Selection.REQUIRED,
            "POST",
            "/request-mobile-reimbursement"),
    REQUEST_NEWSPAPER_ALLOWANCE(
            "request_newspaper_allowance",
            Selection.REQUIRED,
            "POST",
            "/request-newspaper-allowance"),
    REQUEST_OTA("request_ota", Selection.REQUIRED, "POST", "/request-ota"),
    REQUEST_PAY_LTC_HOMETOWN(
            "request_pay_ltc_hometown", Selection.REQUIRED, "POST", "/request-pay-ltc-hometown"),
    REQUEST_PAY_TRAVEL_ALLOWANCE(
            "request_pay_travel_allowance",
            Selection.REQUIRED,
            "POST",
            "/request-pay-travel-allowance"),
    REQUEST_TELE_DATA_REIMBURSEMENT(
            "request_tele_data_reimbursement",
            Selection.REQUIRED,
            "POST",
            "/request-tele-data-reimbursement"),
    TOKEN("token", Selection.REQUIRED, "POST", "/token"),
    UPDATING_HRA_DA("updating_hra_da", Selection.REQUIRED, "POST", "/updating-hra-da"),
    CANCEL_ADVANCE_LOAN(
            "cancel_advance_loan", Selection.RULE_PENDING, "PUT", "/cancel-advance-loan"),
    CANCEL_BRIEF_CASE_REIMBURSEMENT(
            "cancel_brief_case_reimbursement",
            Selection.RULE_PENDING,
            "PUT",
            "/cancel-brief-case-reimbursement"),
    CANCEL_LAPTOP_REIMBURSEMENT(
            "cancel_laptop_reimbursement",
            Selection.RULE_PENDING,
            "PUT",
            "/cancel-laptop-reimbursement"),
    CANCEL_LEAVE_DETAIL(
            "cancel_leave_detail", Selection.RULE_PENDING, "PUT", "/cancel-leave-detail"),
    CANCEL_LEAVE_ENCASH(
            "cancel_leave_encash", Selection.RULE_PENDING, "PUT", "/cancel-leave-encash"),
    CANCEL_LOCAL_TRAVEL_ALLOWANCE(
            "cancel_local_travel_allowance",
            Selection.RULE_PENDING,
            "PUT",
            "/cancel-local-travel-allowance"),
    CANCEL_LTC_ENCASH("cancel_ltc_encash", Selection.RULE_PENDING, "PUT", "/cancel-ltc-encash"),
    CANCEL_MEDICAL_CLAIM(
            "cancel_medical_claim", Selection.RULE_PENDING, "PUT", "/cancel-medical-claim"),
    CANCEL_MOBILE_REIMBURSEMENT(
            "cancel_mobile_reimbursement",
            Selection.RULE_PENDING,
            "PUT",
            "/cancel-mobile-reimbursement"),
    CANCEL_NEWSPAPER_ALLOWANCE(
            "cancel_newspaper_allowance",
            Selection.RULE_PENDING,
            "PUT",
            "/cancel-newspaper-allowance"),
    CANCEL_OTA("cancel_ota", Selection.RULE_PENDING, "PUT", "/cancel-ota"),
    CANCEL_PAY_LTC_HOMETOWN(
            "cancel_pay_ltc_hometown", Selection.RULE_PENDING, "PUT", "/cancel-pay-ltc-hometown"),
    CANCEL_PAY_TRAVEL_ALLOWANCE(
            "cancel_pay_travel_allowance",
            Selection.RULE_PENDING,
            "PUT",
            "/cancel-pay-travel-allowance"),
    CANCEL_TELE_DATA_REIMBURSEMENT(
            "cancel_tele_data_reimbursement",
            Selection.RULE_PENDING,
            "PUT",
            "/cancel-tele-data-reimbursement"),
    FORECAST_REPORT("forecast_report", Selection.RULE_PENDING, "GET", "/forecast-report"),
    GET_REQUEST_LAPTOP_BUYBACK(
            "get_request_laptop_buyback",
            Selection.RULE_PENDING,
            "GET",
            "/get-request-laptop-buyback"),
    GET_REQUEST_PAY_LIVERIES_REIMBURSEMENT(
            "get_request_pay_liveries_reimbursement",
            Selection.RULE_PENDING,
            "GET",
            "/get-request-pay-liveries-reimbursement"),
    GETCPFSLIP("getcpfslip", Selection.RULE_PENDING, "GET", "/getCpfSlip"),
    GETPAYSLIP("getpayslip", Selection.RULE_PENDING, "GET", "/getPayslip"),
    GETSALARYCONTROLREGISTER(
            "getsalarycontrolregister", Selection.RULE_PENDING, "GET", "/getSalaryControlRegister"),
    /**
     * The HRMS read of an employee's basic pay (SRS-1-70, I-21 HRMS Update Request; M-01 record).
     * In the collection, no source describes its answer (PENDING API-16-7): read defensively.
     */
    GET_BASIC_SYNCUP_STATUS(
            "get_basic_syncup_status", Selection.RULE_PENDING, "GET", "/get-basic-syncup-status"),
    /** The HRMS read of an employee's status (M-01 record; PENDING API-16-7). */
    GET_PAYROLL_STATUST(
            "get_payroll_statust", Selection.RULE_PENDING, "GET", "/get-payroll-statust"),
    POST_CAFETERIA_ALLOWANCE(
            "post_cafeteria_allowance",
            Selection.RULE_PENDING,
            "POST",
            "/post-cafeteria-allowance"),
    REQUEST_LAPTOP_BUYBACK(
            "request_laptop_buyback", Selection.RULE_PENDING, "POST", "/request-laptop-buyback"),
    REQUEST_LEASE_ACCOMODATION(
            "request_lease_accomodation",
            Selection.RULE_PENDING,
            "POST",
            "/request-lease-accomodation"),
    REQUEST_PAY_LIVERIES_REIMBURSEMENT(
            "request_pay_liveries_reimbursement",
            Selection.RULE_PENDING,
            "POST",
            "/request-pay-liveries-reimbursement");

    /** Why the API is listed. */
    public enum Selection {
        /** Required by a named SRS / FRS requirement. */
        REQUIRED,
        /**
         * The sources do not state its rule or interface: the URL is configurable, nothing is
         * applied until the owner decides.
         */
        RULE_PENDING
    }

    private final String code;
    private final Selection selection;
    private final String method;
    private final String collectionPath;

    HrmsApi(String code, Selection selection, String method, String collectionPath) {
        this.code = code;
        this.selection = selection;
        this.method = method;
        this.collectionPath = collectionPath;
    }

    /** The setting name: {@code fci.integration.hrms.api.<code>.path}. */
    public String code() {
        return code;
    }

    /** Whether a requirement names it or the owner must still decide. */
    public Selection selection() {
        return selection;
    }

    /** HTTP method in the collection. */
    public String method() {
        return method;
    }

    /** The path in the collection, for the contract screen only. */
    public String collectionPath() {
        return collectionPath;
    }
}
