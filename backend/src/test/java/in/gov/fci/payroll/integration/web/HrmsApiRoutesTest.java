package in.gov.fci.payroll.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * SRS-INT-1, SRS-INT-16: a path exists only when its URL setting is set; wrong settings stop
 * start-up.
 */
class HrmsApiRoutesTest {

    private static HrmsApiRoutes routes(Map<String, ApiUrl> apis) {
        return new HrmsApiRoutes(new HrmsApiProperties(null, apis));
    }

    @Test
    void SRS_INT_16_noSettingMeansNoRoute() {
        HrmsApiRoutes r = routes(Map.of());
        assertThat(r.published()).isEmpty();
        assertThat(r.isHrmsPath(new MockHttpServletRequest("POST", "/hrms/request-ota"))).isFalse();
        assertThat(r.match(new MockHttpServletRequest("POST", "/hrms/request-ota"))).isEmpty();
    }

    @Test
    void SRS_INT_1_aConfiguredApiIsMatchedByMethodAndPath() {
        HrmsApiRoutes r =
                routes(
                        Map.of(
                                "request_ota", new ApiUrl("hrms/request-ota"),
                                "token", new ApiUrl("/hrms/token/")));
        assertThat(r.published()).containsKeys(HrmsApi.REQUEST_OTA, HrmsApi.TOKEN);
        assertThat(r.isHrmsPath(new MockHttpServletRequest("GET", "/hrms/request-ota"))).isTrue();
        assertThat(r.match(new MockHttpServletRequest("POST", "/hrms/request-ota")))
                .contains(HrmsApi.REQUEST_OTA);
        assertThat(r.match(new MockHttpServletRequest("GET", "/hrms/request-ota"))).isEmpty();
        assertThat(r.isToken(new MockHttpServletRequest("POST", "/hrms/token"))).isTrue();
        assertThat(r.isToken(new MockHttpServletRequest("POST", "/hrms/request-ota"))).isFalse();
        assertThat(r.isHrmsPath(new MockHttpServletRequest("POST", "/hrms/other"))).isFalse();
    }

    @Test
    void SRS_INT_1_aPathUnderTheBrowserOrActuatorPrefixStopsStartUp() {
        for (String bad :
                new String[] {
                    "v1/x", "/auth/login", "actuator/health", "menu", "i18n/en", "/", "error"
                }) {
            assertThatThrownBy(() -> routes(Map.of("request_ota", new ApiUrl(bad))))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void SRS_INT_1_twoApisOnOneMethodAndPathStopStartUp() {
        assertThatThrownBy(
                        () ->
                                routes(
                                        Map.of(
                                                "request_ota", new ApiUrl("hrms/x"),
                                                "request_medical_claim", new ApiUrl("hrms/x"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("share a method and path");
        HrmsApiRoutes ok =
                routes(
                        Map.of(
                                "request_ota",
                                new ApiUrl("hrms/x"),
                                "cancel_ota",
                                new ApiUrl("hrms/x")));
        assertThat(ok.published()).hasSize(2);
    }
}
