package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SRS-G-13: a list larger than one download may carry is refused with "narrow the filter" before
 * any byte is sent; it is never cut short. Its own context, with a limit of 2 rows.
 */
@TestPropertySource(properties = "fci.export.max-rows=2")
class ExportLimitIT extends ApiTestSupport {

    @Test
    void SRS_G_13_aListOverTheLimitIsRefusedWithItsSizeNeverTruncated() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("rbac.maker.ludhiana@fci-demo.test", null, null);
        }
        Session auditor = login("superuser.hq@fci-demo.test", "SUPER_USER", "HQ001");
        Instant to = Instant.now().plus(1, ChronoUnit.MINUTES);
        String path =
                "/v1/admin/audit/export?from="
                        + to.minus(1, ChronoUnit.DAYS)
                        + "&to="
                        + to
                        + "&actor=rbac.maker.ludhiana@fci-demo.test&field=LOGIN";
        MvcResult refused = download(auditor, path);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getRequest().isAsyncStarted()).isFalse();
        assertThat(refused.getResponse().getHeader("Content-Disposition")).isNull();
        assertThat(body(refused).get("errorCode").asString()).isEqualTo("COM-422-EXPORT-TOO-LARGE");
        assertThat(body(refused).get("args").get(1).asLong()).isEqualTo(2);
        assertThat(body(refused).get("args").get(0).asLong()).isGreaterThan(2);
        // A filter within the limit still downloads.
        assertThat(
                        download(auditor, path.replace("field=LOGIN", "field=NO_SUCH_FIELD"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }
}
