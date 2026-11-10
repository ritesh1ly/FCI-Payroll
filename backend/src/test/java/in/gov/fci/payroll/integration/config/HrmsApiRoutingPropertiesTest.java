package in.gov.fci.payroll.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SRS-INT-1, SRS-INT-16: the published path, the direction, the bulkhead limit and the pool
 * defaults.
 */
class HrmsApiRoutingPropertiesTest {

    @Test
    void SRS_INT_16_aRelativePathIsPublishedWithoutABaseAddress() {
        var p = new HrmsApiProperties(null, Map.of("request_ota", new ApiUrl("hrms/request-ota")));
        assertThat(p.routePath(HrmsApi.REQUEST_OTA)).contains("/hrms/request-ota");
        assertThat(p.published(HrmsApi.REQUEST_OTA)).isTrue();
        assertThat(p.published(HrmsApi.CANCEL_OTA)).isFalse();
    }

    @Test
    void SRS_INT_16_aFullUrlIsPublishedAtItsPathPart() {
        var p =
                new HrmsApiProperties(
                        null,
                        Map.of("token", new ApiUrl("https://payroll.example.invalid/hrms/token")));
        assertThat(p.routePath(HrmsApi.TOKEN)).contains("/hrms/token");
        var bare =
                new HrmsApiProperties(
                        null, Map.of("token", new ApiUrl("https://payroll.example.invalid")));
        assertThat(bare.routePath(HrmsApi.TOKEN)).isEmpty();
        var bad = new HrmsApiProperties(null, Map.of("token", new ApiUrl("https://bad host/x")));
        assertThat(bad.routePath(HrmsApi.TOKEN)).isEmpty();
    }

    @Test
    void SRS_INT_16_unsetBlankOrNullPathsAreNotPublished() {
        assertThat(new HrmsApiProperties(null, null).routePath(HrmsApi.TOKEN)).isEmpty();
        assertThat(new HrmsApiProperties(null, Map.of()).routePath(HrmsApi.TOKEN)).isEmpty();
        assertThat(
                        new HrmsApiProperties(null, Map.of("token", new ApiUrl(" ")))
                                .routePath(HrmsApi.TOKEN))
                .isEmpty();
        assertThat(
                        new HrmsApiProperties(null, Map.of("token", new ApiUrl(null)))
                                .routePath(HrmsApi.TOKEN))
                .isEmpty();
    }

    @Test
    void SRS_INT_16_anOutboundApiIsCalledNotPublished() {
        var p =
                new HrmsApiProperties(
                        "https://hrms.example.invalid/",
                        Map.of("get_ota_status", new ApiUrl("get-ota-status", "outbound")));
        assertThat(p.routePath(HrmsApi.GET_OTA_STATUS)).isEmpty();
        assertThat(p.outboundUrl(HrmsApi.GET_OTA_STATUS))
                .contains("https://hrms.example.invalid/get-ota-status");
        var inbound =
                new HrmsApiProperties(
                        null, Map.of("get_ota_status", new ApiUrl("get-ota-status", " ")));
        assertThat(inbound.outboundUrl(HrmsApi.GET_OTA_STATUS)).isEmpty();
        assertThat(inbound.routePath(HrmsApi.GET_OTA_STATUS)).isPresent();
        assertThat(new HrmsApiProperties(null, null).outboundUrl(HrmsApi.GET_OTA_STATUS)).isEmpty();
    }

    @Test
    void R_INT_aBulkheadLimitNeedsBothValuesAndSensibleOnes() {
        var p =
                new HrmsApiProperties(
                        null,
                        Map.of(),
                        Map.of(
                                "a", new Limit(2, 100),
                                "no_wait", new Limit(2, null),
                                "no_calls", new Limit(null, 100),
                                "zero_calls", new Limit(0, 100),
                                "neg_wait", new Limit(2, -1),
                                "zero_wait", new Limit(1, 0)),
                        null);
        assertThat(p.limit("a")).contains(new Limit(2, 100));
        assertThat(p.limit("zero_wait")).isPresent();
        for (String code :
                new String[] {"no_wait", "no_calls", "zero_calls", "neg_wait", "absent"}) {
            assertThat(p.limit(code)).as(code).isEmpty();
        }
        assertThat(new HrmsApiProperties(null, null).limit(HrmsApi.REQUEST_OTA)).isEmpty();
    }

    @Test
    void R_INT_thePoolHasWorkingDefaultsAndRefusesNonsense() {
        var d =
                new HrmsClientProperties(
                        null, null, null, null, null, null, null, null, null, null);
        assertThat(d.connections()).isEqualTo(50);
        assertThat(d.pending()).isEqualTo(100);
        assertThat(d.pendingTimeout().toSeconds()).isEqualTo(5);
        assertThat(d.maxIdle().toSeconds()).isEqualTo(30);
        assertThat(d.maxLife().toSeconds()).isEqualTo(300);
        assertThat(d.evictInterval().toSeconds()).isEqualTo(30);
        var s = new HrmsClientProperties(0, -1, 0, 0, 0, 0, null, " ", null, null);
        assertThat(s.connections()).isEqualTo(50);
        assertThat(s.key()).isEmpty();
        assertThat(s.bearer()).isEmpty();
        var set = new HrmsClientProperties(8, 9, 2, 3, 4, 5, "k", null, "b", null);
        assertThat(set.connections()).isEqualTo(8);
        assertThat(set.pending()).isEqualTo(9);
        assertThat(set.evictInterval().toSeconds()).isEqualTo(5);
        assertThat(set.key()).contains("k");
        assertThat(set.bearer()).contains("b");
    }
}
