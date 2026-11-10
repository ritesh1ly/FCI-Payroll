package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The PENDING UI-G4 builds of group g4 through their routes: M-03 lists every version of a scale,
 * the superseded ones end-dated (SRS l.3952); O-04 narrows the summary to a member range and sorts
 * it by amount, the posted totals reading the same members (SRS-18-12, US-GAP-265).
 */
class UiG4BuildsIT extends ApiTestSupport {

    @Test
    void SRS_l3952_everyVersionOfAScaleIsListedTheSupersededOnesEndDated() throws Exception {
        Session hr = login("hr.delhi@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        String code =
                jdbc.queryForObject(
                        "SELECT MIN(b.band_code) FROM pay_scale_band b JOIN pay_scale_band_version"
                                + " v ON v.band_id = b.band_id",
                        String.class);
        JsonNode versions =
                body(mvc.perform(userGet(hr, "/v1/pay-scales/" + code + "/versions")).andReturn());
        assertThat(versions.size()).isPositive();
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i).path("effectiveFrom").asString())
                    .as("newest first")
                    .isLessThanOrEqualTo(versions.get(i - 1).path("effectiveFrom").asString());
            assertThat(versions.get(i).path("effectiveTo").isNull())
                    .as("a superseded version is end-dated")
                    .isFalse();
        }
        assertThat(
                        mvc.perform(userGet(hr, "/v1/pay-scales/no%20such/versions"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    @Test
    void SRS_18_12_aMemberRangeNarrowsTheSummaryAndItStillReconciles() throws Exception {
        Session maker = login("pm.ropb@fci-demo.test", "PAYROLL_MAKER", "RO-PB");
        String yymm =
                body(mvc.perform(userGet(maker, "/v1/reports/component-summary/latest-locked"))
                                .andReturn())
                        .path("yymm")
                        .asString();
        String base = "/v1/reports/component-summary?yymm=" + yymm + "&level=UNIT";
        JsonNode all = body(mvc.perform(userGet(maker, base + "&sort=AMOUNT")).andReturn());
        JsonNode one =
                body(
                        mvc.perform(userGet(maker, base + "&fromEis=119901&toEis=119901"))
                                .andReturn());
        JsonNode none = body(mvc.perform(userGet(maker, base + "&fromEis=999990")).andReturn());
        assertThat(one.path("earnings").decimalValue())
                .isLessThan(all.path("earnings").decimalValue());
        assertThat(one.path("controlTotalsMatch").asBoolean()).isTrue();
        assertThat(none.path("lines").size()).isZero();
        // SRS-18-12: sort=AMOUNT orders the lines by amount within each office, largest first.
        JsonNode lines = all.path("lines");
        assertThat(lines.size()).isPositive();
        for (int i = 1; i < lines.size(); i++) {
            JsonNode prev = lines.get(i - 1);
            JsonNode cur = lines.get(i);
            int office =
                    prev.path("levelCode").asString().compareTo(cur.path("levelCode").asString());
            assertThat(office).as("offices in order").isLessThanOrEqualTo(0);
            if (office == 0) {
                assertThat(cur.path("amount").decimalValue())
                        .as("largest first within %s", cur.path("levelCode").asString())
                        .isLessThanOrEqualTo(prev.path("amount").decimalValue());
            }
        }
        assertThat(
                        mvc.perform(userGet(maker, base + "&fromEis=a%20b"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(
                        mvc.perform(userGet(maker, base + "&sort=NAME"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }
}
