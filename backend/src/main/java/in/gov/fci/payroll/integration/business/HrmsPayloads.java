package in.gov.fci.payroll.integration.business;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsContracts.Request;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and checks the body of an HRMS {@code request-*} call and decides what may be applied
 * (SRS-INT-5, SRS-INT-12, SRS-INT-23, SRS-INT-26; R-ASCII, R-MONEY). Mandatory: {@code request_id}
 * and {@code emp_num}; every other field is optional but, when present, typed by its name as the
 * collection uses it (dates ISO {@code YYYY-MM-DD}, months {@code YYMM}, amounts non-negative
 * numbers, text printable ASCII). Messages name the field, never its value (R-PII). Pure: no I/O.
 */
public final class HrmsPayloads {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._-]{1,30}$");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");
    private static final Pattern FIN_YEAR = Pattern.compile("^(\\d{4})(\\d{2})$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");
    private static final int MAX_TEXT = 500;
    private static final int MAX_RECORDS = 200;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.9999");

    /** Amount-like fields of the collection (numbers, never negative). */
    private static final Set<String> AMOUNTS =
            Set.of(
                    "sanctioned_amount",
                    "sanction_amount",
                    "gross_amount",
                    "it_ded",
                    "othr_ded",
                    "net_amt",
                    "netamt",
                    "bill_amount",
                    "loan_amount",
                    "installment_amount",
                    "first_installment_amount",
                    "interest_installment_amount",
                    "principal_installment",
                    "interest_installment",
                    "total_installment",
                    "principle_os",
                    "interst_os",
                    "interst_amt",
                    "recipt_amt",
                    "encsh_amt",
                    "lease_amt",
                    "lease_perc",
                    "otarate",
                    "otahrs",
                    "sanctioned_el",
                    "min_basic",
                    "max_basic",
                    "hra_rate",
                    "distance",
                    "percent",
                    "new_prn_amount",
                    "new_int_amount");

    private static final JsonMapper MAPPER =
            JsonMapper.builder(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(4)
                                                    .maxNameLength(64)
                                                    .build())
                                    .build())
                    .build();

    private HrmsPayloads() {}

    /** What may be done with a validated record. */
    public sealed interface ApplyPlan permits Change, StageOnly {}

    /**
     * A pay-effect value for {@code HrmsChanges.receive} (an I-21 task; SRS-1-19, SRS-G-45).
     *
     * @param value the HRMS record as the owning module's {@code HrmsTaskEffects} reads it
     */
    public record Change(String fieldCode, String value, LocalDate effectiveDate)
            implements ApplyPlan {}

    /** Kept as received, nothing applied, for this exact reason (PENDING API-23-8). */
    public record StageOnly(String reason) implements ApplyPlan {}

    /** The parsed body, or the reason it is not one JSON document (duplicate key, bad syntax). */
    public static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("the body is empty");
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("the body is empty");
            }
            return node;
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("not valid JSON or a key is repeated");
        }
    }

    /** The records of the body: one object, or for an array API the objects of the array. */
    public static List<JsonNode> records(Request contract, JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (contract.arrayBody()) {
            if (!body.isArray() || body.isEmpty() || body.size() > MAX_RECORDS) {
                throw new IllegalArgumentException(
                        "the body must be an array of 1 to " + MAX_RECORDS + " records");
            }
            body.forEach(out::add);
        } else {
            if (!body.isObject()) {
                throw new IllegalArgumentException("the body must be one JSON object");
            }
            out.add(body);
        }
        for (JsonNode record : out) {
            if (!record.isObject()) {
                throw new IllegalArgumentException("every record must be a JSON object");
            }
        }
        return out;
    }

    /**
     * The first defect of a record (field name only), or empty when it is fit to stage.
     *
     * @param queryEmployee the {@code emp_num} query parameter, for the APIs that carry it there
     */
    public static Optional<String> defect(Request contract, JsonNode record, String queryEmployee) {
        for (String name : record.propertyNames()) {
            // A field the collection does not list is kept as received and never applied; only
            // its name and value shape are checked.
            if (!FIELD_NAME.matcher(name).matches()) {
                return Optional.of("field name " + safe(name) + " is not allowed");
            }
        }
        if (!id(text(record.get("request_id")))) {
            return Optional.of("request_id is required (letters, digits, . _ -, up to 30)");
        }
        String employee = contract.employeeInQuery() ? queryEmployee : text(record.get("emp_num"));
        if (!id(employee)) {
            return Optional.of("emp_num is required (letters, digits, . _ -, up to 30)");
        }
        for (String name : record.propertyNames()) {
            Optional<String> bad = fieldDefect(name, record.get(name));
            if (bad.isPresent()) {
                return bad;
            }
        }
        return pairDefect(record);
    }

    /** From-to ranges and the application / sanction order, when both ends are present. */
    private static Optional<String> pairDefect(JsonNode record) {
        String[][] ranges = {
            {"from_date", "to_date"},
            {"from_dt", "to_dt"},
            {"eff_strt_date", "eff_end_date"},
            {"application_date", "sanction_date"},
            {"application_dt", "sanction_dt"}
        };
        for (String[] r : ranges) {
            Optional<LocalDate> first = date(String.valueOf(text(record.get(r[0]))));
            Optional<LocalDate> second = date(String.valueOf(text(record.get(r[1]))));
            if (first.isPresent() && second.isPresent() && second.get().isBefore(first.get())) {
                return Optional.of(
                        r[1].startsWith("sanction")
                                ? r[1] + " is before " + r[0]
                                : r[0] + " is after " + r[1]);
            }
        }
        Optional<YearMonth> from = yymm(String.valueOf(text(record.get("from_yymm"))));
        Optional<YearMonth> to = yymm(String.valueOf(text(record.get("to_yymm"))));
        if (from.isPresent() && to.isPresent() && to.get().isBefore(from.get())) {
            return Optional.of("from_yymm is after to_yymm");
        }
        return Optional.empty();
    }

    private static Optional<String> fieldDefect(String name, JsonNode value) {
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isNumber() && !value.isString()) {
            return Optional.of(name + " must be a number or text");
        }
        String text = text(value);
        if (!PRINTABLE.matcher(text).matches() || text.length() > MAX_TEXT) {
            return Optional.of(name + " must be printable ASCII, up to " + MAX_TEXT);
        }
        if (name.endsWith("_date") || name.endsWith("_dt")) {
            return date(text).isPresent()
                    ? Optional.empty()
                    : Optional.of(name + " must be YYYY-MM-DD");
        }
        if (name.endsWith("yymm") || name.endsWith("_ymm")) {
            return yymm(text).isPresent() ? Optional.empty() : Optional.of(name + " must be YYMM");
        }
        if (name.equals("finyear") || name.equals("fin_year")) {
            return finYear(text)
                    ? Optional.empty()
                    : Optional.of(name + " must be YYYYYY (202324)");
        }
        if (AMOUNTS.contains(name)) {
            return amount(text).isPresent()
                    ? Optional.empty()
                    : Optional.of(name + " must be a number of 0 or more");
        }
        return Optional.empty();
    }

    /** ISO date, strictly (no time, no other layout). */
    public static Optional<LocalDate> date(String text) {
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    /** {@code YYMM} with a month 01 to 12: the first day of that month (20YY). */
    public static Optional<YearMonth> yymm(String text) {
        if (text == null || !text.matches("^\\d{4}$")) {
            return Optional.empty();
        }
        int month = Integer.parseInt(text.substring(2));
        return month < 1 || month > 12
                ? Optional.empty()
                : Optional.of(YearMonth.of(2000 + Integer.parseInt(text.substring(0, 2)), month));
    }

    /** A financial year as 202324: the year and the last two digits of the next. */
    public static boolean finYear(String text) {
        java.util.regex.Matcher m = text == null ? null : FIN_YEAR.matcher(text);
        return m != null
                && m.matches()
                && Integer.parseInt(m.group(2)) == (Integer.parseInt(m.group(1)) + 1) % 100;
    }

    /** A plain non-negative decimal within range and at most four decimals (R-MONEY). */
    public static Optional<BigDecimal> amount(String text) {
        try {
            BigDecimal v = new BigDecimal(text.strip());
            return v.signum() < 0
                            || v.compareTo(MAX_AMOUNT) > 0
                            || v.stripTrailingZeros().scale() > 4
                    ? Optional.empty()
                    : Optional.of(v);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** A scalar's text; null for null / missing / not a scalar. */
    public static String text(JsonNode value) {
        if (value == null || value.isNull() || !(value.isNumber() || value.isString())) {
            return null;
        }
        return value.isNumber()
                ? value.decimalValue().stripTrailingZeros().toPlainString()
                : value.asString();
    }

    /** Whether the text is a usable identifier (an HRMS number, a request key). */
    public static boolean id(String text) {
        return text != null && ID.matcher(text.strip()).matches();
    }

    private static String safe(String name) {
        String cut = name.length() > 40 ? name.substring(0, 40) : name;
        return PRINTABLE.matcher(cut).matches() ? cut : "?";
    }

    /** The amount the record names as its payment amount, as received, or null. */
    public static String amountOf(Request contract, JsonNode record) {
        if (contract.amountField() == null) {
            return null;
        }
        String text = text(record.get(contract.amountField()));
        return text == null
                ? null
                : amount(text).map(v -> v.stripTrailingZeros().toPlainString()).orElse(text);
    }

    /**
     * What may be applied (SRS-1-19: pay-effect values only through I-21). Only the employee claim
     * family whose payload carries its own period and amount is mapped, and only when the claim
     * type of the API is set ({@code fci.integration.hrms.claim-type.<code>}, M-15); every other
     * type is kept as received for the reason given.
     *
     * @param claimTypes the configured M-15 claim type per API
     */
    public static ApplyPlan plan(
            HrmsApi api,
            Request contract,
            JsonNode record,
            Function<HrmsApi, Optional<String>> claimTypes) {
        return switch (contract.txnType()) {
            case "TEL_DATA_REIMB", "TRAVEL_REIMB" ->
                    claim(api, record, claimTypes, period(record, "from_date", "to_date"));
            case "NEWSPAPER_ALLOW" ->
                    claim(api, record, claimTypes, monthsPeriod(record, "from_yymm", "to_yymm"));
            case "MOBILE_REIMB",
                    "BRIEFCASE_REIM",
                    "LAPTOP_REIMB",
                    "LTC_HOMETOWN",
                    "LOCAL_TRAVEL_ALLOW" ->
                    new StageOnly(
                            "claim period not stated in the payload (a single sanction date only):"
                                    + " PENDING API-23-9");
            case "EMP_MEDICAL_CLAIM" ->
                    new StageOnly(
                            "claim amount not stated (bill_amount or netamt) and claim type not"
                                    + " mapped: PENDING API-23-9");
            case "LOAN_ADVANCE_HDR", "LOAN_FORECLOSURE", "LOAN_RESCHEDULE" ->
                    new StageOnly(
                            "no mapping of the HRMS loan payload to the LOAN_SANCTION /"
                                    + " LOAN_FORECLOSURE / LOAN_RESCHEDULE value (loan type code,"
                                    + " schedule): PENDING API-23-10");
            case "EMP_OTA", "EMP_HRA_DTL", "LTC_ENCASH", "LEAVE_ENCASH" ->
                    new StageOnly(
                            "no pay-effect field code or rule for this type in the sources:"
                                    + " PENDING API-23-11");
            default ->
                    new StageOnly(
                            "accepted for review, no apply rule (owner decision pending):"
                                    + " PENDING API-23-8");
        };
    }

    private record Period(LocalDate from, LocalDate to) {}

    private static Optional<Period> period(JsonNode record, String from, String to) {
        Optional<LocalDate> f = date(String.valueOf(text(record.get(from))));
        Optional<LocalDate> t = date(String.valueOf(text(record.get(to))));
        return f.isPresent() && t.isPresent() && !t.get().isBefore(f.get())
                ? Optional.of(new Period(f.get(), t.get()))
                : Optional.empty();
    }

    private static Optional<Period> monthsPeriod(JsonNode record, String from, String to) {
        Optional<YearMonth> f = yymm(String.valueOf(text(record.get(from))));
        Optional<YearMonth> t = yymm(String.valueOf(text(record.get(to))));
        return f.isPresent() && t.isPresent() && !t.get().isBefore(f.get())
                ? Optional.of(new Period(f.get().atDay(1), t.get().atEndOfMonth()))
                : Optional.empty();
    }

    private static ApplyPlan claim(
            HrmsApi api,
            JsonNode record,
            Function<HrmsApi, Optional<String>> claimTypes,
            Optional<Period> period) {
        Optional<String> type = claimTypes.apply(api).filter(t -> CODE.matcher(t).matches());
        if (type.isEmpty()) {
            return new StageOnly(
                    "claim type of "
                            + api.code()
                            + " is not mapped (fci.integration.hrms.claim-type): PENDING API-23-9");
        }
        if (period.isEmpty()) {
            return new StageOnly("claim period missing or not a valid range: PENDING API-23-9");
        }
        Optional<BigDecimal> amount = amount(String.valueOf(text(record.get("sanction_amount"))));
        if (amount.isEmpty() || amount.get().signum() == 0) {
            return new StageOnly("claim amount missing or zero: PENDING API-23-9");
        }
        String value =
                "{\"claimType\":\""
                        + type.get()
                        + "\",\"periodFrom\":\""
                        + period.get().from()
                        + "\",\"periodTo\":\""
                        + period.get().to()
                        + "\",\"amount\":\""
                        + amount.get().toPlainString()
                        + "\"}";
        return new Change("REIMBURSEMENT_CLAIM", value, period.get().from());
    }

    /** The configured M-15 claim types, keyed by API code, as a lookup. */
    public static Function<HrmsApi, Optional<String>> claimTypes(Map<String, String> byApiCode) {
        return api ->
                byApiCode == null
                        ? Optional.empty()
                        : Optional.ofNullable(byApiCode.get(api.code()));
    }
}
