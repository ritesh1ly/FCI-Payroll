package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsPayloads.Change;
import in.gov.fci.payroll.integration.business.HrmsPayloads.StageOnly;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SRS-INT-5, SRS-INT-12, SRS-INT-23, SRS-INT-26, SRS-1-19; R-ASCII, R-MONEY: the body of an HRMS
 * request call is checked against the Postman payload and mapped, or kept as received.
 */
class HrmsPayloadsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);

    private static JsonNode json(String text) {
        return HrmsPayloads.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static HrmsContracts.Request contract(HrmsApi api) {
        return HrmsContracts.request(api).orElseThrow();
    }

    private static String travel(String extra) {
        return "{\"request_id\":4516,\"emp_num\":160661,\"sanction_date\":\""
                + TODAY
                + "\",\"sanction_number\":\"10\",\"sanction_amount\":1000,\"from_date\":\""
                + TODAY.minusDays(3)
                + "\",\"to_date\":\""
                + TODAY.minusDays(1)
                + "\""
                + extra
                + "}";
    }

    private static Optional<String> defect(HrmsApi api, String body) {
        return HrmsPayloads.defect(contract(api), json(body), null);
    }

    @Test
    void SRS_INT_12_aRepeatedKeyIsRefusedNotResolved() {
        assertThatThrownBy(() -> json("{\"request_id\":1,\"request_id\":2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeated");
    }

    @Test
    void SRS_INT_12_anEmptyOrUnreadableBodyIsRefused() {
        assertThatThrownBy(() -> HrmsPayloads.parse(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HrmsPayloads.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json("{\"request_id\":"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json("[[[[[[1]]]]]]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_INT_12_anObjectApiTakesOneObjectAnArrayApiTakesAnArray() {
        assertThat(HrmsPayloads.records(contract(HrmsApi.REQUEST_OTA), json("{\"request_id\":1}")))
                .hasSize(1);
        assertThatThrownBy(
                        () ->
                                HrmsPayloads.records(
                                        contract(HrmsApi.REQUEST_OTA), json("[{\"a\":1}]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                HrmsPayloads.records(
                                        contract(HrmsApi.UPDATING_HRA_DA),
                                        json("{\"request_id\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> HrmsPayloads.records(contract(HrmsApi.UPDATING_HRA_DA), json("[]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> HrmsPayloads.records(contract(HrmsApi.UPDATING_HRA_DA), json("[1]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        HrmsPayloads.records(
                                contract(HrmsApi.POST_CAFETERIA_ALLOWANCE),
                                json("[{\"request_id\":1},{\"request_id\":1}]")))
                .hasSize(2);
    }

    @Test
    void SRS_INT_12_aTooLongArrayIsRefused() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            many.append(i == 0 ? "" : ",").append("{\"request_id\":1}");
        }
        assertThatThrownBy(
                        () ->
                                HrmsPayloads.records(
                                        contract(HrmsApi.POST_CAFETERIA_ALLOWANCE),
                                        json(many.append(']').toString())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_INT_23_aValidTravelPayloadHasNoDefect() {
        assertThat(defect(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, travel(""))).isEmpty();
    }

    @Test
    void SRS_INT_23_requestIdAndEmpNumAreMandatory() {
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"emp_num\":1}"))
                .hasValueSatisfying(d -> assertThat(d).contains("request_id"));
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":1}"))
                .hasValueSatisfying(d -> assertThat(d).contains("emp_num"));
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":null,\"emp_num\":1}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":\"a b\",\"emp_num\":1}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":\"" + "9".repeat(31) + "\",\"emp_num\":1}"))
                .isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":{\"x\":1},\"emp_num\":1}"))
                .isPresent();
    }

    @Test
    void SRS_INT_23_aFieldNotInTheCollectionIsKeptAsReceivedAndNeverApplied() {
        // Unknown fields are not refused (HRMS may add some); they are stored with the record and
        // the
        // apply plan ignores them. Only their name and value shape are checked.
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":1,\"emp_num\":2,\"extra\":\"X\"}"))
                .isEmpty();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":1,\"emp_num\":2,\"bad name\":\"X\"}"))
                .hasValue("field name bad name is not allowed");
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":1,\"emp_num\":2,\"naïve\":\"X\"}"))
                .hasValue("field name ? is not allowed");
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":1,\"emp_num\":2,\"" + "k".repeat(60) + "\":1}"))
                .hasValueSatisfying(
                        d ->
                                assertThat(d)
                                        .hasSize(
                                                "field name ".length()
                                                        + 40
                                                        + " is not allowed".length()));
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":1,\"emp_num\":2,\"extra\":{\"a\":1}}"))
                .hasValue("extra must be a number or text");
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":1,\"emp_num\":2,\"extra\":\"ह\"}"))
                .isPresent();
        // an unknown field named like a date is still a date
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":1,\"emp_num\":2,\"x_date\":\"nope\"}"))
                .isPresent();
    }

    @Test
    void SRS_INT_12_markupAndSqlTextIsStoredInertNotRefusedAndNeverAnId() {
        String text = "<script>alert(1)</script>'; DROP TABLE employee; --";
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":1,\"emp_num\":2,\"remarks\":\""
                                        + text.replace("\"", "")
                                        + "\"}"))
                .isEmpty();
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":\"1' OR '1'='1\",\"emp_num\":2}"))
                .isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":1,\"emp_num\":\"2;DROP\"}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":1,\"emp_num\":2,\"remarks\":\"a\\u0000b\"}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_OTA,
                                "{\"request_id\":1,\"emp_num\":2,\"remarks\":\"line\\nbreak\"}"))
                .isPresent();
    }

    @Test
    void RMONEY_amountsHaveAtMostFourDecimalsAndStayWithinRange() {
        String base = "{\"request_id\":1,\"emp_num\":2,";
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":100}")).isEmpty();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":100.5000}")).isEmpty();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":0}")).isEmpty();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":\"100.12345\"}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":10000000000.01}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":\"1e3\"}")).isEmpty();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":\"\"}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":\"NaN\"}")).isPresent();
    }

    @Test
    void SRS_INT_23_rangesRunForwardAndSanctionComesAfterApplication() {
        String base = "{\"request_id\":1,\"emp_num\":2,";
        assertThat(
                        defect(
                                HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                base
                                        + "\"from_date\":\""
                                        + TODAY
                                        + "\",\"to_date\":\""
                                        + TODAY.minusDays(1)
                                        + "\"}"))
                .hasValue("from_date is after to_date");
        assertThat(
                        defect(
                                HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                                base
                                        + "\"from_date\":\""
                                        + TODAY
                                        + "\",\"to_date\":\""
                                        + TODAY
                                        + "\"}"))
                .isEmpty();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_MEDICAL_CLAIM,
                                base
                                        + "\"from_dt\":\""
                                        + TODAY
                                        + "\",\"to_dt\":\""
                                        + TODAY.minusDays(2)
                                        + "\"}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEASE_ACCOMODATION,
                                base
                                        + "\"eff_strt_date\":\""
                                        + TODAY
                                        + "\",\"eff_end_date\":\""
                                        + TODAY.minusDays(2)
                                        + "\"}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                                base + "\"from_yymm\":2312,\"to_yymm\":2311}"))
                .hasValue("from_yymm is after to_yymm");
        assertThat(
                        defect(
                                HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                                base + "\"from_yymm\":2311,\"to_yymm\":2311}"))
                .isEmpty();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                                base + "\"from_yymm\":\"0000\",\"to_yymm\":2311}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base
                                        + "\"application_date\":\""
                                        + TODAY
                                        + "\",\"sanction_date\":\""
                                        + TODAY.minusDays(1)
                                        + "\"}"))
                .hasValue("sanction_date is before application_date");
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LTC_ENCASH,
                                base
                                        + "\"application_dt\":\""
                                        + TODAY
                                        + "\",\"sanction_dt\":\""
                                        + TODAY.minusDays(1)
                                        + "\"}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base
                                        + "\"application_date\":\""
                                        + TODAY
                                        + "\",\"sanction_date\":\""
                                        + TODAY
                                        + "\"}"))
                .isEmpty();
    }

    @Test
    void SRS_INT_23_aFinancialYearIsTheYearAndTheNextTwoDigits() {
        String base = "{\"request_id\":1,\"emp_num\":2,";
        assertThat(defect(HrmsApi.REQUEST_MEDICAL_CLAIM, base + "\"finyear\":202324}")).isEmpty();
        assertThat(defect(HrmsApi.REQUEST_MEDICAL_CLAIM, base + "\"finyear\":199900}")).isEmpty();
        assertThat(defect(HrmsApi.REQUEST_MEDICAL_CLAIM, base + "\"finyear\":202325}"))
                .hasValue("finyear must be YYYYYY (202324)");
        assertThat(defect(HrmsApi.REQUEST_MEDICAL_CLAIM, base + "\"finyear\":2023}")).isPresent();
        assertThat(defect(HrmsApi.POST_CAFETERIA_ALLOWANCE, base + "\"fin_year\":\"2024-25\"}"))
                .isPresent();
        assertThat(defect(HrmsApi.POST_CAFETERIA_ALLOWANCE, base + "\"fin_year\":\"202425\"}"))
                .isEmpty();
        assertThat(HrmsPayloads.finYear(null)).isFalse();
    }

    @Test
    void SRS_INT_23_datesAreIsoMonthsAreYymmAmountsAreNotNegative() {
        String base = "{\"request_id\":1,\"emp_num\":2,";
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base + "\"sanction_date\":\"13/09/2023\"}"))
                .hasValue("sanction_date must be YYYY-MM-DD");
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base + "\"sanction_date\":\"2023-02-30\"}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base + "\"sanction_date\":\"" + TODAY + "\"}"))
                .isEmpty();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"ota_yymm\":2313}"))
                .hasValue("ota_yymm must be YYMM");
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"ota_yymm\":2300}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"ota_yymm\":\"23-3\"}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"ota_yymm\":2303}")).isEmpty();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":-1}"))
                .hasValue("net_amt must be a number of 0 or more");
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":\"abc\"}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":99999999999}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"net_amt\":\"100.50\",\"otahrs\":12}"))
                .isEmpty();
    }

    @Test
    void RASCII_textIsPrintableAsciiAndBounded() {
        String base = "{\"request_id\":1,\"emp_num\":2,";
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"remarks\":\"हिंदी\"}"))
                .hasValue("remarks must be printable ASCII, up to 500");
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"remarks\":\"" + "a".repeat(501) + "\"}"))
                .isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"remarks\":\"ok\\u0007\"}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"remarks\":{\"a\":1}}"))
                .hasValue("remarks must be a number or text");
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"remarks\":true}")).isPresent();
        assertThat(defect(HrmsApi.REQUEST_OTA, base + "\"attribute1\":null,\"remarks\":null}"))
                .isEmpty();
    }

    @Test
    void SRS_INT_23_hraAndDaTakeTheEmployeeFromTheQuery() {
        HrmsContracts.Request hra = contract(HrmsApi.UPDATING_HRA_DA);
        JsonNode row =
                json(
                        "{\"request_id\":37198,\"request_for\":\"HRA\",\"effective_date\":\""
                                + TODAY
                                + "\",\"min_basic\":81.34}");
        assertThat(HrmsPayloads.defect(hra, row, "100752")).isEmpty();
        assertThat(HrmsPayloads.defect(hra, row, null))
                .hasValueSatisfying(d -> assertThat(d).contains("emp_num"));
        assertThat(HrmsPayloads.defect(hra, row, "bad value")).isPresent();
    }

    @Test
    void textOfNumbersIsPlain() {
        assertThat(HrmsPayloads.text(json("1.50"))).isEqualTo("1.5");
        assertThat(HrmsPayloads.text(json("1E+3"))).isEqualTo("1000");
        assertThat(HrmsPayloads.text(json("\"x\""))).isEqualTo("x");
        assertThat(HrmsPayloads.text(json("null"))).isNull();
        assertThat(HrmsPayloads.text(json("{}"))).isNull();
        assertThat(HrmsPayloads.text(null)).isNull();
        assertThat(HrmsPayloads.id(null)).isFalse();
        assertThat(HrmsPayloads.id(" 37180 ")).isTrue();
    }

    @Test
    void SRS_1_19_aTravelClaimWithAMappedTypeBecomesAnI21Value() {
        var plan =
                HrmsPayloads.plan(
                        HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                        contract(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE),
                        json(travel("")),
                        HrmsPayloads.claimTypes(Map.of("request_pay_travel_allowance", "TRAVEL")));
        assertThat(plan).isInstanceOf(Change.class);
        Change change = (Change) plan;
        assertThat(change.fieldCode()).isEqualTo("REIMBURSEMENT_CLAIM");
        assertThat(change.effectiveDate()).isEqualTo(TODAY.minusDays(3));
        JsonNode value = json(change.value());
        assertThat(value.get("claimType").asString()).isEqualTo("TRAVEL");
        assertThat(value.get("periodFrom").asString()).isEqualTo(TODAY.minusDays(3).toString());
        assertThat(value.get("periodTo").asString()).isEqualTo(TODAY.minusDays(1).toString());
        assertThat(value.get("amount").asString()).isEqualTo("1000");
    }

    @Test
    void SRS_1_19_aClaimWithoutAMappedTypeIsKeptAsReceived() {
        for (Map<String, String> types :
                java.util.List.of(
                        Map.<String, String>of(),
                        Map.of("request_pay_travel_allowance", "lower case"))) {
            var plan =
                    HrmsPayloads.plan(
                            HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE,
                            contract(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE),
                            json(travel("")),
                            HrmsPayloads.claimTypes(types));
            assertThat(plan).isInstanceOf(StageOnly.class);
            assertThat(((StageOnly) plan).reason()).contains("not mapped").contains("API-23-9");
        }
        assertThat(HrmsPayloads.claimTypes(null).apply(HrmsApi.REQUEST_OTA)).isEmpty();
    }

    @Test
    void SRS_1_19_aClaimWithoutAValidPeriodOrAmountIsKeptAsReceived() {
        var types = HrmsPayloads.claimTypes(Map.of("request_pay_travel_allowance", "TRAVEL"));
        HrmsContracts.Request c = contract(HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE);
        String noPeriod = "{\"request_id\":1,\"emp_num\":2,\"sanction_amount\":10}";
        String backwards =
                "{\"request_id\":1,\"emp_num\":2,\"sanction_amount\":10,\"from_date\":\""
                        + TODAY
                        + "\",\"to_date\":\""
                        + TODAY.minusDays(1)
                        + "\"}";
        String noAmount =
                "{\"request_id\":1,\"emp_num\":2,\"from_date\":\""
                        + TODAY
                        + "\",\"to_date\":\""
                        + TODAY
                        + "\"}";
        String zero =
                "{\"request_id\":1,\"emp_num\":2,\"sanction_amount\":0,\"from_date\":\""
                        + TODAY
                        + "\",\"to_date\":\""
                        + TODAY
                        + "\"}";
        for (String body : new String[] {noPeriod, backwards, noAmount, zero}) {
            assertThat(
                            HrmsPayloads.plan(
                                    HrmsApi.REQUEST_PAY_TRAVEL_ALLOWANCE, c, json(body), types))
                    .isInstanceOf(StageOnly.class);
        }
    }

    @Test
    void SRS_1_19_aNewspaperClaimUsesTheWholeMonths() {
        YearMonth from = YearMonth.from(TODAY).minusMonths(2);
        YearMonth to = YearMonth.from(TODAY).minusMonths(1);
        String body =
                "{\"request_id\":1,\"emp_num\":2,\"sanction_amount\":300,\"from_yymm\":"
                        + yymm(from)
                        + ",\"to_yymm\":"
                        + yymm(to)
                        + "}";
        var plan =
                (Change)
                        HrmsPayloads.plan(
                                HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                                contract(HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE),
                                json(body),
                                HrmsPayloads.claimTypes(
                                        Map.of("request_newspaper_allowance", "NEWSPAPER")));
        assertThat(plan.effectiveDate()).isEqualTo(from.atDay(1));
        assertThat(json(plan.value()).get("periodTo").asString())
                .isEqualTo(to.atEndOfMonth().toString());
    }

    private static String yymm(YearMonth month) {
        return String.format("%02d%02d", month.getYear() % 100, month.getMonthValue());
    }

    @Test
    void SRS_INT_26_everyOtherTypeIsKeptAsReceivedForItsExactReason() {
        var types = HrmsPayloads.claimTypes(Map.of());
        Map<HrmsApi, String> expected =
                Map.ofEntries(
                        Map.entry(HrmsApi.REQUEST_MOBILE_REIMBURSEMENT, "API-23-9"),
                        Map.entry(HrmsApi.REQUEST_BRIEF_CASE_REIMBURSEMENT, "API-23-9"),
                        Map.entry(HrmsApi.REQUEST_LAPTOP_REIMBURSEMENT, "API-23-9"),
                        Map.entry(HrmsApi.REQUEST_PAY_LTC_HOMETOWN, "API-23-9"),
                        Map.entry(HrmsApi.REQUEST_LOCAL_TRAVEL_ALLOWANCE, "API-23-9"),
                        Map.entry(HrmsApi.REQUEST_MEDICAL_CLAIM, "API-23-9"),
                        Map.entry(HrmsApi.REQUEST_ADVANCE_LOAN_HDR, "API-23-10"),
                        Map.entry(HrmsApi.REQUEST_LOAN_FORE_CLOSURE, "API-23-10"),
                        Map.entry(HrmsApi.REQUEST_LOAN_RESCHEDULE, "API-23-10"),
                        Map.entry(HrmsApi.REQUEST_OTA, "API-23-11"),
                        Map.entry(HrmsApi.REQUEST_LTC_ENCASH, "API-23-11"),
                        Map.entry(HrmsApi.REQUEST_LEAVE_ENCASHMENT, "API-23-11"),
                        Map.entry(HrmsApi.UPDATING_HRA_DA, "API-23-11"),
                        Map.entry(HrmsApi.REQUEST_LEASE_ACCOMODATION, "API-23-8"),
                        Map.entry(HrmsApi.REQUEST_LAPTOP_BUYBACK, "API-23-8"),
                        Map.entry(HrmsApi.REQUEST_PAY_LIVERIES_REIMBURSEMENT, "API-23-8"),
                        Map.entry(HrmsApi.POST_CAFETERIA_ALLOWANCE, "API-23-8"));
        expected.forEach(
                (api, pending) -> {
                    var plan =
                            HrmsPayloads.plan(
                                    api, contract(api), json("{\"request_id\":1}"), types);
                    assertThat(plan).as(api.code()).isInstanceOf(StageOnly.class);
                    assertThat(((StageOnly) plan).reason()).as(api.code()).contains(pending);
                });
    }

    @Test
    void amountOfReadsTheContractsAmountField() {
        assertThat(
                        HrmsPayloads.amountOf(
                                contract(HrmsApi.REQUEST_OTA), json("{\"net_amt\":100.50}")))
                .isEqualTo("100.5");
        assertThat(
                        HrmsPayloads.amountOf(
                                contract(HrmsApi.UPDATING_HRA_DA), json("{\"net_amt\":1}")))
                .isNull();
    }

    @Test
    void SRS_INT_23_theSourcesStateNoPastOrFutureLimitOnADateSoNoneIsInvented() {
        String base = "{\"request_id\":1,\"emp_num\":2,";
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base + "\"sanction_date\":\"1900-01-01\"}"))
                .isEmpty();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base + "\"sanction_date\":\"2999-12-31\"}"))
                .isEmpty();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base + "\"sanction_date\":\"2023-9-13\"}"))
                .isPresent();
        assertThat(
                        defect(
                                HrmsApi.REQUEST_LEAVE_ENCASHMENT,
                                base + "\"sanction_date\":\"2023-09-13T10:00:00\"}"))
                .isPresent();
        assertThat(defect(HrmsApi.REQUEST_LEAVE_ENCASHMENT, base + "\"sanction_date\":20230913}"))
                .isPresent();
    }

    @Test
    void SRS_INT_12_aNumberAndItsTextAreTheSameValueButRequestIdsStayExact() {
        assertThat(HrmsPayloads.text(json("37180"))).isEqualTo("37180");
        assertThat(HrmsPayloads.text(json("\"37180\""))).isEqualTo("37180");
        assertThat(HrmsPayloads.text(json("37180.0")))
                .as("a decimal id is the same number")
                .isEqualTo("37180");
        assertThat(defect(HrmsApi.REQUEST_OTA, "{\"request_id\":\"007\",\"emp_num\":\"0042\"}"))
                .isEmpty();
    }
}
