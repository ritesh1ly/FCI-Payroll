package in.gov.fci.payroll.integration.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.HrmsPayloads.StageOnly;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SRS-INT-12, SRS-INT-23, SRS-1-19, R-ASCII, R-MONEY: the branches of the payload checks the main
 * suite leaves out: blank bodies, one end of a range, YYMM-like names, claim periods with one end.
 */
class HrmsPayloadsBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private static JsonNode json(String text) {
        return HrmsPayloads.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<String> defect(String body) {
        return HrmsPayloads.defect(
                HrmsContracts.request(HrmsApi.REQUEST_OTA).orElseThrow(), json(body), null);
    }

    private static String base(String extra) {
        return "{\"request_id\":\"R-"
                + TODAY.getDayOfYear()
                + "\",\"emp_num\":160661"
                + extra
                + "}";
    }

    @Test
    void SRS_INT_12_aBodyOfOnlyWhiteSpaceIsEmpty() {
        assertThatThrownBy(() -> HrmsPayloads.parse("   \n ".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_INT_23_aRangeIsCheckedOnlyWhenBothEndsAreThereAndRunForward() {
        String[][] pairs = {
            {"from_date", "to_date"},
            {"from_dt", "to_dt"},
            {"eff_strt_date", "eff_end_date"},
            {"application_date", "sanction_date"},
            {"application_dt", "sanction_dt"}
        };
        String early = TODAY.minusDays(2).toString();
        String late = TODAY.toString();
        for (String[] p : pairs) {
            assertThat(defect(base(",\"" + p[0] + "\":\"" + early + "\""))).as(p[0]).isEmpty();
            assertThat(defect(base(",\"" + p[1] + "\":\"" + late + "\""))).as(p[1]).isEmpty();
            assertThat(
                            defect(
                                    base(
                                            ",\"" + p[0] + "\":\"" + early + "\",\"" + p[1]
                                                    + "\":\"" + late + "\"")))
                    .as(p[0] + " forward")
                    .isEmpty();
            assertThat(
                            defect(
                                    base(
                                            ",\"" + p[0] + "\":\"" + late + "\",\"" + p[1] + "\":\""
                                                    + late + "\"")))
                    .as(p[0] + " same day")
                    .isEmpty();
            assertThat(
                            defect(
                                    base(
                                            ",\"" + p[0] + "\":\"" + late + "\",\"" + p[1] + "\":\""
                                                    + early + "\"")))
                    .as(p[0] + " backwards")
                    .hasValueSatisfying(m -> assertThat(m).contains(p[0]).contains(p[1]));
        }
    }

    @Test
    void SRS_INT_23_aMonthRangeIsCheckedOnlyWhenBothEndsAreThereAndRunForward() {
        assertThat(defect(base(",\"from_yymm\":2604"))).isEmpty();
        assertThat(defect(base(",\"to_yymm\":2604"))).isEmpty();
        assertThat(defect(base(",\"from_yymm\":2604,\"to_yymm\":2604"))).isEmpty();
        assertThat(defect(base(",\"from_yymm\":2604,\"to_yymm\":2703"))).isEmpty();
        assertThat(defect(base(",\"from_yymm\":2604,\"to_yymm\":2603")))
                .contains("from_yymm is after to_yymm");
    }

    @Test
    void SRS_INT_23_aNameEndingInYmmIsAMonthAndAYymmOfNullIsNotOne() {
        assertThat(defect(base(",\"gross_ymm\":2604"))).isEmpty();
        assertThat(defect(base(",\"gross_ymm\":2613"))).contains("gross_ymm must be YYMM");
        assertThat(defect(base(",\"gross_ymm\":\"26-4\""))).contains("gross_ymm must be YYMM");
        assertThat(HrmsPayloads.yymm(null)).isEmpty();
        assertThat(HrmsPayloads.yymm("0000")).isEmpty();
        assertThat(HrmsPayloads.yymm("2600")).isEmpty();
        assertThat(HrmsPayloads.yymm("2601")).isPresent();
        assertThat(HrmsPayloads.yymm("2612")).isPresent();
        assertThat(HrmsPayloads.yymm("260")).isEmpty();
        assertThat(HrmsPayloads.yymm("26012")).isEmpty();
    }

    @Test
    void SRS_INT_23_aJsonNullFieldIsAcceptedAndAnObjectOrArrayFieldIsRefusedByName() {
        assertThat(defect(base(",\"remark\":null"))).isEmpty();
        assertThat(defect(base(",\"remark\":{\"a\":1}")))
                .contains("remark must be a number or text");
        assertThat(defect(base(",\"remark\":[1]"))).contains("remark must be a number or text");
        assertThat(defect(base(",\"remark\":true"))).contains("remark must be a number or text");
    }

    @Test
    void RMONEY_theAmountOfAContractIsAbsentWhenTheRecordHasNoSuchField() {
        var ota = HrmsContracts.request(HrmsApi.REQUEST_OTA).orElseThrow();
        assertThat(HrmsPayloads.amountOf(ota, json("{\"request_id\":1}"))).isNull();
        assertThat(HrmsPayloads.amountOf(ota, json("{\"net_amt\":null}"))).isNull();
        assertThat(HrmsPayloads.amountOf(ota, json("{\"net_amt\":\"abc\"}"))).isEqualTo("abc");
        assertThat(HrmsPayloads.amountOf(ota, json("{\"net_amt\":\"0010.2500\"}")))
                .isEqualTo("10.25");
    }

    @Test
    void SRS_1_19_aClaimPeriodWithOnlyOneEndOrBackwardsIsKeptAsReceived() {
        var types = HrmsPayloads.claimTypes(Map.of("request_tele_data_reimbursement", "TELEDATA"));
        var contract = HrmsContracts.request(HrmsApi.REQUEST_TELE_DATA_REIMBURSEMENT).orElseThrow();
        String head = "{\"request_id\":1,\"emp_num\":2,\"sanction_amount\":10";
        for (String tail :
                new String[] {
                    ",\"from_date\":\"" + TODAY + "\"}",
                    ",\"to_date\":\"" + TODAY + "\"}",
                    ",\"from_date\":\"" + TODAY + "\",\"to_date\":\"" + TODAY.minusDays(1) + "\"}"
                }) {
            var plan =
                    HrmsPayloads.plan(
                            HrmsApi.REQUEST_TELE_DATA_REIMBURSEMENT,
                            contract,
                            json(head + tail),
                            types);
            assertThat(plan).isInstanceOf(StageOnly.class);
            assertThat(((StageOnly) plan).reason()).contains("period");
        }
    }

    @Test
    void SRS_1_19_aNewspaperClaimWithOneMonthMissingOrBackwardsIsKeptAsReceived() {
        var types = HrmsPayloads.claimTypes(Map.of("request_newspaper_allowance", "NEWSPAPER"));
        var contract = HrmsContracts.request(HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE).orElseThrow();
        String head = "{\"request_id\":1,\"emp_num\":2,\"sanction_amount\":300";
        for (String tail :
                new String[] {
                    ",\"from_yymm\":2604}",
                    ",\"to_yymm\":2604}",
                    ",\"from_yymm\":2604,\"to_yymm\":2603}",
                    ",\"from_yymm\":2604,\"to_yymm\":2613}"
                }) {
            var plan =
                    HrmsPayloads.plan(
                            HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                            contract,
                            json(head + tail),
                            types);
            assertThat(plan).as(tail).isInstanceOf(StageOnly.class);
        }
        var single =
                HrmsPayloads.plan(
                        HrmsApi.REQUEST_NEWSPAPER_ALLOWANCE,
                        contract,
                        json(head + ",\"from_yymm\":2604,\"to_yymm\":2604}"),
                        types);
        assertThat(single).isInstanceOf(HrmsPayloads.Change.class);
    }
}
