package in.gov.fci.payroll.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SRS-INT-1, SRS-INT-16, SRS-INT-23: one URL setting per HRMS API; unset means not configured. */
class HrmsApiPropertiesTest {

    @Test
    void SRS_INT_16_noSettingMeansNotConfigured() {
        assertThat(new HrmsApiProperties(null, null).url(HrmsApi.REQUEST_OTA)).isEmpty();
        assertThat(new HrmsApiProperties("https://h.example/", Map.of()).url(HrmsApi.REQUEST_OTA))
                .isEmpty();
    }

    @Test
    void SRS_INT_16_blankOrNullPathIsNotConfigured() {
        var props =
                new HrmsApiProperties(
                        "https://h.example/",
                        Map.of(
                                "request_ota",
                                new ApiUrl("  "),
                                "get_ota_status",
                                new ApiUrl(null)));
        assertThat(props.url(HrmsApi.REQUEST_OTA)).isEmpty();
        assertThat(props.url(HrmsApi.GET_OTA_STATUS)).isEmpty();
    }

    @Test
    void SRS_INT_1_relativePathIsJoinedToTheBaseWithOneSlash() {
        var props =
                new HrmsApiProperties(
                        "https://h.example/hrms//",
                        Map.of("request_ota", new ApiUrl("/request-ota ")));
        assertThat(props.url(HrmsApi.REQUEST_OTA)).contains("https://h.example/hrms/request-ota");
    }

    @Test
    void SRS_INT_1_relativePathWithoutBaseIsNotConfigured() {
        assertThat(
                        new HrmsApiProperties(
                                        null, Map.of("request_ota", new ApiUrl("request-ota")))
                                .url(HrmsApi.REQUEST_OTA))
                .isEmpty();
        assertThat(
                        new HrmsApiProperties(" ", Map.of("request_ota", new ApiUrl("request-ota")))
                                .url(HrmsApi.REQUEST_OTA))
                .isEmpty();
    }

    @Test
    void SRS_INT_1_absoluteUrlIsUsedAsItStands() {
        var props =
                new HrmsApiProperties(
                        null,
                        Map.of(
                                "request_ota",
                                new ApiUrl("http://a.example/x"),
                                "get_ota_status",
                                new ApiUrl("https://b.example/y")));
        assertThat(props.url(HrmsApi.REQUEST_OTA)).contains("http://a.example/x");
        assertThat(props.url(HrmsApi.GET_OTA_STATUS)).contains("https://b.example/y");
    }

    @Test
    void SRS_INT_23_catalogueHasOneUniqueSettingPerApi() {
        Set<String> codes = new HashSet<>();
        for (HrmsApi api : HrmsApi.values()) {
            assertThat(codes.add(api.code())).as(api.code()).isTrue();
            assertThat(api.collectionPath()).startsWith("/");
            assertThat(api.method()).isIn("GET", "POST", "PUT");
            assertThat(api.selection()).isNotNull();
        }
        assertThat(codes).hasSize(57).contains("token", "request_ota", "cancel_ota");
    }
}
