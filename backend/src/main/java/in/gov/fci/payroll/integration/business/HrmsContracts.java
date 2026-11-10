package in.gov.fci.payroll.integration.business;

import in.gov.fci.payroll.integration.api.HrmsApi;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * What each HRMS API of the Postman collection carries (SRS-INT-1, SRS-INT-5, SRS-INT-12; docs/
 * integration/HRMS_POSTMAN_API_SELECTION.md): the staging interface and transaction type of every
 * {@code request-*} payload, its fields exactly as the collection lists them, and for the status
 * ({@code get-*}) and cancel ({@code PUT cancel-*}) calls the identifier they carry and the
 * transaction types they refer to. The collection is the only contract FCI supplied: a field it
 * does not list is refused as an unknown shape. Pure data, no I/O.
 */
public final class HrmsContracts {

    /** The attribute1 ... attribute20 columns of the legacy payloads (free text, mostly null). */
    private static final List<String> ATTRIBUTES =
            IntStream.rangeClosed(1, 20).mapToObj(i -> "attribute" + i).toList();

    /**
     * One inbound request API.
     *
     * @param interfaceCode {@code interface_registry.interface_code}
     * @param txnType {@code INTF_TXN_TYPE} value (the staging transaction type)
     * @param fields the fields the collection lists (plus attributes)
     * @param arrayBody the body is a JSON array of records (HRA / DA, cafeteria)
     * @param employeeInQuery the employee number comes from the query string, not the body
     * @param amountField the field that holds the payment amount, or null
     */
    public record Request(
            String interfaceCode,
            String txnType,
            Set<String> fields,
            boolean arrayBody,
            boolean employeeInQuery,
            String amountField) {}

    /**
     * One status or cancel API: the id parameter besides request_id and the types it refers to.
     *
     * @param idParam the HRMS identifier parameter (reim_allow_id, transmast_id ...)
     * @param keyedByEmployee the call carries emp_num + the id and no request_id (get-loan-status,
     *     get-medical-claim-status)
     * @param txnTypes staged transaction types it may refer to, in order
     */
    public record Lookup(String idParam, boolean keyedByEmployee, List<String> txnTypes) {}

    /** What kind of call an API is. */
    public enum Kind {
        TOKEN,
        INTAKE,
        STATUS,
        CANCEL,
        REPORT
    }

    private static final Map<HrmsApi, Request> REQUESTS = new EnumMap<>(HrmsApi.class);
    private static final Map<HrmsApi, Lookup> LOOKUPS = new EnumMap<>(HrmsApi.class);

    private HrmsContracts() {}

    private static Set<String> fields(String... names) {
        List<String> all = new ArrayList<>(List.of(names));
        all.addAll(ATTRIBUTES);
        return Set.copyOf(all);
    }

    private static void request(
            HrmsApi api,
            String interfaceCode,
            String txnType,
            String amountField,
            String... names) {
        REQUESTS.put(
                api, new Request(interfaceCode, txnType, fields(names), false, false, amountField));
    }

    /** The reimbursement family shares one payload (request_id ... sanction_amount + extras). */
    private static void sanction(
            HrmsApi api, String interfaceCode, String txnType, String... more) {
        List<String> names =
                new ArrayList<>(
                        List.of(
                                "request_id",
                                "emp_num",
                                "sanction_date",
                                "sanction_number",
                                "sanction_amount"));
        names.addAll(List.of(more));
        request(api, interfaceCode, txnType, "sanction_amount", names.toArray(String[]::new));
    }

    private static void lookup(HrmsApi api, String idParam, boolean byEmployee, String... types) {
        LOOKUPS.put(api, new Lookup(idParam, byEmployee, List.of(types)));
    }

    static {
        request(
                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                "HRMS_LEAVE_ENCASH",
                "LEAVE_ENCASH",
                "sanctioned_amount",
                "request_id",
                "emp_num",
                "application_date",
                "sanction_date",
                "sanctioned_el",
                "sanctioned_amount");
        request(
                HrmsApi.REQUEST_OTA,
                "HRMS_EMP_OTA",
                "EMP_OTA",
                "net_amt",
                "request_id",
                "emp_num",
                "gross_amount",
                "it_ded",
                "othr_ded",
                "net_amt",
                "pay_mode",
                "remarks",
                "ciel_flag",
                "ota_yymm",
                "otahrs",
                "otarate");
        request(
                HrmsApi.REQUEST_ADVANCE_LOAN_HDR,
                "HRMS_LOAN_ADVANCE_HDR",
                "LOAN_ADVANCE_HDR",
                "loan_amount",
                "request_id",
                "emp_num",
                "loan_type",
                "sanction_date",
                "sanction_number",
                "loan_amount",
                "recovery_start_yymm",
                "installment_amount",
                "first_installment_amount",
                "principal_installment",
                "interest_installment",
                "interest_installment_amount",
                "total_installment");
        request(
                HrmsApi.REQUEST_LOAN_FORE_CLOSURE,
                "HRMS_LOAN_FORECLOSURE",
                "LOAN_FORECLOSURE",
                "recipt_amt",
                "request_id",
                "emp_num",
                "loan_type",
                "principle_os",
                "interst_os",
                "receipt_no",
                "trans_id",
                "loan_flag",
                "interst_amt",
                "recipt_amt");
        request(
                HrmsApi.REQUEST_LOAN_RESCHEDULE,
                "HRMS_LOAN_RESCHEDULE",
                "LOAN_RESCHEDULE",
                "new_prn_amount",
                "request_id",
                "emp_num",
                "trans_id",
                "loan_type",
                "principle_os",
                "interst_os",
                "bal_prn_instl",
                "bal_int_instl",
                "new_prn_instlno",
                "new_int_instlno",
                "new_prn_amount",
                "new_int_amount",
                "advance_type");
        request(
                HrmsApi.REQUEST_MEDICAL_CLAIM,
                "HRMS_EMP_MEDICAL_CLAIM",
                "EMP_MEDICAL_CLAIM",
                "netamt",
                "request_id",
                "emp_num",
                "bill_agnst",
                "paycode",
                "bill_amount",
                "bill_dt",
                "file_dt",
                "remark",
                "it_ded",
                "netamt",
                "from_dt",
                "to_dt",
                "qtr",
                "finyear");
        request(
                HrmsApi.REQUEST_LTC_ENCASH,
                "HRMS_LTC_ENCASH",
                "LTC_ENCASH",
                "net_amt",
                "request_id",
                "emp_num",
                "ltc_type",
                "block_yr",
                "encsh_amt",
                "it_ded",
                "othr_ded",
                "net_amt",
                "pay_mode",
                "rmrks",
                "application_dt",
                "sanction_dt");
        sanction(
                HrmsApi.REQUEST_MOBILE_REIMBURSEMENT,
                "HRMS_MOBILE_REIMB",
                "MOBILE_REIMB",
                "bill_number",
                "bill_date",
                "sanc_req_for");
        sanction(
                HrmsApi.REQUEST_BRIEF_CASE_REIMBURSEMENT,
                "HRMS_BRIEFCASE_REIM",
                "BRIEFCASE_REIM",
                "bill_number",
                "bill_date");
        sanction(HrmsApi.REQUEST_PAY_LTC_HOMETOWN, "HRMS_LTC_HOMETOWN", "LTC_HOMETOWN");
        sanction(
                HrmsApi.REQUEST_TELE_DATA_REIMBURSEMENT,
                "HRMS_TEL_DATA_REIMB",
                "TEL_DATA_REIMB",
                "bill_number",
                "bill_date",
                "from_date",
                "to_date");
        sanction(
                HrmsApi.REQUEST_LAPTOP_REIMBURSEMENT,
                "HRMS_LAPTOP_REIMB",
                "LAPTOP_REIMB",
                "bill_number",
                "bill_date",
                "sanc_req_for");
        sanction(
                HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                "HRMS_NEWSPAPER_ALLOW",
                "NEWSPAPER_ALLOW",
                "from_yymm",
                "to_yymm",
                "newspaper_name");
        sanction(
                HrmsApi.REQUEST_LOCAL_TRAVEL_ALLOWANCE,
                "HRMS_LOCAL_TRAVEL_ALLOW",
                "LOCAL_TRAVEL_ALLOW",
                "travel_mode",
                "travel_date",
                "travel_from",
                "travel_to",
                "distance");
        sanction(
                HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                "HRMS_TRAVEL_REIMB",
                "TRAVEL_REIMB",
                "from_date",
                "to_date",
                "reim_reason");
        // Class B: registered and routed, nothing is applied (PENDING API-23-8).
        sanction(
                HrmsApi.REQUEST_LAPTOP_BUYBACK,
                "HRMS_LAPTOP_BUYBACK",
                "LAPTOP_BUYBACK",
                "bill_number",
                "bill_date",
                "sanc_req_for");
        sanction(
                HrmsApi.REQUEST_PAY_LIVERIES_REIMBURSEMENT,
                "HRMS_PAY_LIVERIES",
                "PAY_LIVERIES",
                "bill_number",
                "bill_date",
                "sanc_req_for");
        request(
                HrmsApi.REQUEST_LEASE_ACCOMODATION,
                "HRMS_LEASE_ACCOMMODATION",
                "LEASE_ACCOMMODATION",
                "lease_amt",
                "request_id",
                "emp_num",
                "accom_type",
                "lic_fee_dem",
                "lease_amt",
                "lease_perc",
                "eff_strt_date",
                "eff_end_date");
        // Array bodies.
        REQUESTS.put(
                HrmsApi.UPDATING_HRA_DA,
                new Request(
                        "HRMS_EMP_HRA_DTL",
                        "EMP_HRA_DTL",
                        Set.of(
                                "request_id",
                                "request_for",
                                "pay_revision_flag",
                                "effective_date",
                                "pay_scale_type",
                                "min_basic",
                                "max_basic",
                                "hra_city_type",
                                "hra_rate"),
                        true,
                        true,
                        null));
        REQUESTS.put(
                HrmsApi.POST_CAFETERIA_ALLOWANCE,
                new Request(
                        "HRMS_CAFETERIA",
                        "CAFETERIA_SELECTION",
                        Set.of(
                                "request_id",
                                "srl_num",
                                "emp_num",
                                "pay_code",
                                "percent",
                                "adj_pay_code",
                                "adj_pay_perc",
                                "adj_perc",
                                "fin_year",
                                "paymode_flag",
                                "effective_dt",
                                "vehical_flag",
                                "attribute1"),
                        true,
                        false,
                        null));

        lookup(HrmsApi.GET_ADVANCE_LOAN_HDR_STATUS, "transmast_id", false, "LOAN_ADVANCE_HDR");
        lookup(HrmsApi.GET_LEAVE_ENCASHMENT_STATUS, "leave_encash_id", false, "LEAVE_ENCASH");
        lookup(
                HrmsApi.GET_LOAN_STATUS,
                "transmast_id",
                true,
                "LOAN_ADVANCE_HDR",
                "LOAN_FORECLOSURE",
                "LOAN_RESCHEDULE");
        lookup(HrmsApi.GET_LTC_ENCASH_STATUS, "p_ltc_encash_id", false, "LTC_ENCASH");
        lookup(HrmsApi.GET_MEDICAL_CLAIM_STATUS, "payment_id", true, "EMP_MEDICAL_CLAIM");
        lookup(HrmsApi.GET_OTA_STATUS, "emp_ota_id", false, "EMP_OTA");
        lookup(
                HrmsApi.GET_REQUEST_BRIEF_CASE_REIMBURSEMENT,
                "reim_allow_id",
                false,
                "BRIEFCASE_REIM");
        lookup(HrmsApi.GET_REQUEST_LAPTOP_REIMBURSEMENT, "reim_allow_id", false, "LAPTOP_REIMB");
        lookup(
                HrmsApi.GET_REQUEST_LOCAL_TRAVEL_ALLOWANCE,
                "reim_allow_id",
                false,
                "LOCAL_TRAVEL_ALLOW");
        lookup(HrmsApi.GET_REQUEST_MOBILE_REIMBURSEMENT, "reim_allow_id", false, "MOBILE_REIMB");
        lookup(HrmsApi.GET_REQUEST_NEWSPAPER_ALLOWANCE, "reim_allow_id", false, "NEWSPAPER_ALLOW");
        lookup(HrmsApi.GET_REQUEST_PAY_LTC_HOMETOWN, "reim_allow_id", false, "LTC_HOMETOWN");
        lookup(HrmsApi.GET_REQUEST_PAY_TRAVEL_ALLOWANCE, "reim_allow_id", false, "TRAVEL_REIMB");
        lookup(
                HrmsApi.GET_REQUEST_TELE_DATA_REIMBURSEMENT,
                "reim_allow_id",
                false,
                "TEL_DATA_REIMB");
        lookup(HrmsApi.GET_REQUEST_LAPTOP_BUYBACK, "reim_allow_id", false, "LAPTOP_BUYBACK");
        lookup(
                HrmsApi.GET_REQUEST_PAY_LIVERIES_REIMBURSEMENT,
                "reim_allow_id",
                false,
                "PAY_LIVERIES");

        lookup(HrmsApi.CANCEL_ADVANCE_LOAN, "transmast_id", false, "LOAN_ADVANCE_HDR");
        lookup(HrmsApi.CANCEL_BRIEF_CASE_REIMBURSEMENT, "reim_allow_id", false, "BRIEFCASE_REIM");
        lookup(HrmsApi.CANCEL_LAPTOP_REIMBURSEMENT, "reim_allow_id", false, "LAPTOP_REIMB");
        lookup(HrmsApi.CANCEL_LEAVE_DETAIL, "emp_leave_id", false, "LEAVE_INFO");
        lookup(HrmsApi.CANCEL_LEAVE_ENCASH, "leave_encash_id", false, "LEAVE_ENCASH");
        lookup(HrmsApi.CANCEL_LOCAL_TRAVEL_ALLOWANCE, "reim_allow_id", false, "LOCAL_TRAVEL_ALLOW");
        lookup(HrmsApi.CANCEL_LTC_ENCASH, "p_ltc_encash_id", false, "LTC_ENCASH");
        lookup(HrmsApi.CANCEL_MEDICAL_CLAIM, "payment_id", false, "EMP_MEDICAL_CLAIM");
        lookup(HrmsApi.CANCEL_MOBILE_REIMBURSEMENT, "reim_allow_id", false, "MOBILE_REIMB");
        lookup(HrmsApi.CANCEL_NEWSPAPER_ALLOWANCE, "reim_allow_id", false, "NEWSPAPER_ALLOW");
        lookup(HrmsApi.CANCEL_OTA, "emp_ota_id", false, "EMP_OTA");
        lookup(HrmsApi.CANCEL_PAY_LTC_HOMETOWN, "reim_allow_id", false, "LTC_HOMETOWN");
        lookup(HrmsApi.CANCEL_PAY_TRAVEL_ALLOWANCE, "reim_allow_id", false, "TRAVEL_REIMB");
        lookup(HrmsApi.CANCEL_TELE_DATA_REIMBURSEMENT, "reim_allow_id", false, "TEL_DATA_REIMB");
    }

    /** The payload contract of a {@code request-*} / {@code updating-hra-da} / cafeteria API. */
    public static Optional<Request> request(HrmsApi api) {
        return Optional.ofNullable(REQUESTS.get(api));
    }

    /** The payload contract of a staging transaction type (to read an amount back). */
    public static Optional<Request> byTxnType(String txnType) {
        return REQUESTS.values().stream().filter(r -> r.txnType().equals(txnType)).findFirst();
    }

    /** The status or cancel contract of a {@code get-*} / {@code cancel-*} API. */
    public static Optional<Lookup> lookup(HrmsApi api) {
        return Optional.ofNullable(LOOKUPS.get(api));
    }

    /** The kind of call (the reports are routed and refused: owner decision pending). */
    public static Kind kind(HrmsApi api) {
        if (api == HrmsApi.TOKEN) {
            return Kind.TOKEN;
        }
        if (REQUESTS.containsKey(api)) {
            return Kind.INTAKE;
        }
        if (LOOKUPS.containsKey(api)) {
            return "PUT".equals(api.method()) ? Kind.CANCEL : Kind.STATUS;
        }
        return Kind.REPORT;
    }
}
