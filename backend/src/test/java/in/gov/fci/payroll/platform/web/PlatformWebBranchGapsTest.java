package in.gov.fci.payroll.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.export.ExportDelivered;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.XlsxSink;
import in.gov.fci.payroll.platform.i18n.I18nController;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.persistence.InLists;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Edge cases of the shared web plumbing: SRS-G-13 (an export's delivery record names the
 * requester's groups only when the session has them; an Excel export never holds more rows than a
 * sheet), SRS-G-2 (a statement without text is left as it is), and the strong ETag of the label
 * bundles (a browser holding the current tag gets 304 without a body).
 */
class PlatformWebBranchGapsTest {

    @Test
    void SRS_G_13_aRequesterWithoutGroupsIsRecordedWithoutAScope() {
        AccessContext noGroups = mock(AccessContext.class);
        when(noGroups.groups()).thenReturn(null);
        when(noGroups.loginId()).thenReturn("payroll.maker@x");
        when(noGroups.locationId()).thenReturn(41L);
        ExportDelivered delivered =
                ExportStreaming.delivered("register", ExportFormat.CSV, 3, noGroups, "a=1");
        assertThat(delivered.scope()).isNull();
        assertThat(delivered.loginId()).isEqualTo("payroll.maker@x");
        assertThat(delivered.locationId()).isEqualTo(41L);
    }

    @Test
    void SRS_G_13_anExcelSheetRefusesTheRowAfterItsLastOne() throws Exception {
        try (XlsxSink sink = new XlsxSink(new ByteArrayOutputStream(), "Big")) {
            sink.header("A");
            // Excel holds 1,048,576 rows, the header included: the last data row still fits.
            for (int i = 1; i < 1_048_576; i++) {
                sink.row();
            }
            assertThat(sink.rows()).isEqualTo(1_048_575L);
            assertThatThrownBy(sink::row)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("more rows than an Excel sheet holds");
        }
    }

    @Test
    void SRS_G_2_aStatementWithoutTextIsLeftAsItIs() {
        InLists.ExpandedMap none = InLists.expand(null, Map.of("ids", java.util.List.of(1L)));
        assertThat(none.sql()).isNull();
        assertThat(none.params()).containsEntry("ids", java.util.List.of(1L));
    }

    @Test
    void aLabelBundleTheBrowserAlreadyHoldsIsAnswered304WithoutABody() {
        MessageBundles bundles = mock(MessageBundles.class);
        byte[] json = "{\"a\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        when(bundles.bundle("en"))
                .thenReturn(Optional.of(new MessageBundles.Bundle("en", json, "\"e1\"")));
        I18nController controller = new I18nController(bundles);
        ResponseEntity<byte[]> fresh = controller.bundle("en", "\"e1\"");
        assertThat(fresh.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(fresh.getBody()).isNull();
        assertThat(fresh.getHeaders().getETag()).isEqualTo("\"e1\"");
        ResponseEntity<byte[]> stale = controller.bundle("en", "\"old\"");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stale.getBody()).isEqualTo(json);
    }
}
