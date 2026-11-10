package in.gov.fci.payroll.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SRS-INT-1, SRS-INT-4, SRS-INT-16, R-INT: every branch of the HRMS settings records: direction,
 * http / https paths, throttle defaults, secrets from a value or a file, empty and missing files.
 */
class HrmsPropertiesBranchGapsTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-secret";

    @TempDir Path dir;

    private Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void SRS_INT_16_theDirectionDecidesWhetherPayrollPublishesOrCalls() {
        assertThat(new ApiUrl("p", "INBOUND").inbound()).isTrue();
        assertThat(new ApiUrl("p", " inbound ").inbound()).isTrue();
        assertThat(new ApiUrl("p", "  ").inbound()).isTrue();
        assertThat(new ApiUrl("p", null).inbound()).isTrue();
        assertThat(new ApiUrl("p", "OUTBOUND").inbound()).isFalse();
        assertThat(new ApiUrl("p", "sideways").inbound()).isFalse();
    }

    @Test
    void SRS_INT_16_anOutboundApiIsNeverPublishedAndAnUnsetOneHasNoCallUrl() {
        var props =
                new HrmsApiProperties(
                        "https://hrms.example.invalid/api",
                        Map.of(
                                "request_ota", new ApiUrl("request-ota", "OUTBOUND"),
                                "cancel_ota", new ApiUrl("cancel-ota")));
        assertThat(props.routePath(HrmsApi.REQUEST_OTA)).isEmpty();
        assertThat(props.published(HrmsApi.REQUEST_OTA)).isFalse();
        assertThat(props.outboundUrl(HrmsApi.REQUEST_OTA))
                .contains("https://hrms.example.invalid/api/request-ota");
        assertThat(props.outboundUrl(HrmsApi.CANCEL_OTA)).isEmpty();
        assertThat(props.outboundUrl(HrmsApi.TOKEN)).isEmpty();
    }

    @Test
    void SRS_INT_16_anHttpOrHttpsUrlIsPublishedAtItsPathPartAndABlankPathPartIsNot() {
        var props =
                new HrmsApiProperties(
                        null,
                        Map.of(
                                "token", new ApiUrl("http://payroll.example.invalid/hrms/token"),
                                "request_ota", new ApiUrl("https://payroll.example.invalid/"),
                                "cancel_ota", new ApiUrl("   ")));
        assertThat(props.routePath(HrmsApi.TOKEN)).contains("/hrms/token");
        assertThat(props.routePath(HrmsApi.REQUEST_OTA)).contains("/");
        assertThat(props.routePath(HrmsApi.CANCEL_OTA)).isEmpty();
    }

    @Test
    void SRS_INT_4_theThrottleFallsBackToItsDefaultsWhenUnsetOrBelowOne() {
        assertThat(sec(null, null).failureLimit()).isEqualTo(10);
        assertThat(sec(0, 0).failureLimit()).isEqualTo(10);
        assertThat(sec(-3, -3).failureWindow()).isEqualTo(300);
        assertThat(sec(null, null).failureWindow()).isEqualTo(300);
        assertThat(sec(3, 60).failureLimit()).isEqualTo(3);
        assertThat(sec(3, 60).failureWindow()).isEqualTo(60);
    }

    private static HrmsSecurityProperties sec(Integer max, Integer window) {
        return new HrmsSecurityProperties(
                "k", null, "c", "h", null, SECRET, null, null, max, window);
    }

    @Test
    void SRS_INT_4_theClientNameAndLifetimeAreReadWithTheirBoundaries() {
        assertThat(client(null).client()).isEmpty();
        assertThat(client("  ").client()).isEmpty();
        assertThat(client(" hrms-client ").client()).contains("hrms-client");
        assertThat(withLifetime(null).lifetime()).isEqualTo(900);
        assertThat(withLifetime(0).lifetime()).isEqualTo(900);
        assertThat(withLifetime(1).lifetime()).isEqualTo(1);
    }

    private static HrmsSecurityProperties client(String id) {
        return new HrmsSecurityProperties("k", null, id, "h", null, SECRET, null, null);
    }

    private static HrmsSecurityProperties withLifetime(Integer seconds) {
        return new HrmsSecurityProperties("k", null, "c", "h", null, SECRET, null, seconds);
    }

    @Test
    void SRS_INT_4_aSecretComesFromTheValueFirstThenTheFileAndAShortOrEmptyOneIsNotSet()
            throws IOException {
        Path keyFile = file("key", "  file-key \n");
        Path empty = file("empty", " \n ");
        var fromValue =
                new HrmsSecurityProperties(
                        " direct ", keyFile.toString(), "c", "h", null, SECRET, null, null);
        assertThat(fromValue.key()).contains("direct");
        var fromFile =
                new HrmsSecurityProperties(
                        " ",
                        keyFile.toString(),
                        "c",
                        null,
                        keyFile.toString(),
                        null,
                        file("sig", SECRET).toString(),
                        null);
        assertThat(fromFile.key()).contains("file-key");
        assertThat(fromFile.secretHash()).contains("file-key");
        assertThat(fromFile.signingSecret()).contains(SECRET);
        assertThat(fromFile.complete()).isTrue();
        var none =
                new HrmsSecurityProperties(
                        null, empty.toString(), "c", null, " ", "short", null, null);
        assertThat(none.key()).isEmpty();
        assertThat(none.secretHash()).isEmpty();
        assertThat(none.signingSecret()).isEmpty();
        assertThat(none.complete()).isFalse();
        var missing =
                new HrmsSecurityProperties(
                        null, dir.resolve("absent").toString(), "c", "h", null, SECRET, null, null);
        assertThat(missing.key()).isEmpty();
        assertThat(missing.complete()).isFalse();
        assertThat(missing.toString()).doesNotContain(SECRET);
    }

    @Test
    void R_INT_theOutboundCredentialsComeFromTheValueOrAFileAndNeverFromAnEmptyOneOrADirectory()
            throws IOException {
        Path keyFile = file("client-key", " from-file \n");
        Path empty = file("client-empty", "   ");
        var viaFile =
                new HrmsClientProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        keyFile.toString(),
                        " ",
                        keyFile.toString());
        assertThat(viaFile.key()).contains("from-file");
        assertThat(viaFile.bearer()).contains("from-file");
        var direct =
                new HrmsClientProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        " k ",
                        keyFile.toString(),
                        " b ",
                        keyFile.toString());
        assertThat(direct.key()).contains("k");
        assertThat(direct.bearer()).contains("b");
        var emptyFile =
                new HrmsClientProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        empty.toString(),
                        null,
                        dir.resolve("absent").toString());
        assertThat(emptyFile.key()).isEmpty();
        assertThat(emptyFile.bearer()).isEmpty();
        var aDirectory =
                new HrmsClientProperties(
                        null, null, null, null, null, null, null, dir.toString(), null, null);
        assertThat(aDirectory.key()).isEmpty();
        assertThat(viaFile.toString()).doesNotContain("from-file").contains("key=true");
    }
}
