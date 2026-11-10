package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Large list downloads (SRS-G-13), end to end on the A-05 audit trail, the longest list: the export
 * streams every row of the filter, in the screen's order, across many batches (batch size 10 in the
 * IT profile), as CSV or Excel; nothing is cut short; a refusal comes before any byte is sent.
 */
class ExportIT extends ApiTestSupport {

    private static final String MAKER = "rbac.maker.ludhiana@fci-demo.test";

    private Session auditor() throws Exception {
        return login("superuser.hq@fci-demo.test", "SUPER_USER", "HQ001");
    }

    private String window(String field) {
        Instant to = Instant.now().plus(1, ChronoUnit.MINUTES);
        Instant from = to.minus(1, ChronoUnit.DAYS);
        return "from=" + from + "&to=" + to + "&actor=" + MAKER + "&field=" + field;
    }

    /** The ids the screen lists for the filter, page after page, in its order. */
    private List<Long> screenIds(Session s, String filter) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int page = 0; ; page++) {
            JsonNode view =
                    body(
                            mvc.perform(
                                            userGet(
                                                    s,
                                                    "/v1/admin/audit?"
                                                            + filter
                                                            + "&size=10&page="
                                                            + page))
                                    .andReturn());
            view.get("items").forEach(r -> ids.add(r.get("id").asLong()));
            if ((long) (page + 1) * 7 >= view.get("total").asLong()) {
                return ids;
            }
        }
    }

    private static List<Long> csvIds(String csv) {
        List<Long> ids = new ArrayList<>();
        String[] lines = csv.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            ids.add(Long.valueOf(lines[i].substring(1, lines[i].indexOf('"', 1))));
        }
        return ids;
    }

    @Test
    void SRS_G_13_theExportHasEveryRowOfTheFilterInTheScreensOrderAcrossManyBatches()
            throws Exception {
        // 25 sign-ins: more than two batches of 10, and rows sharing a second (the id breaks ties).
        for (int i = 0; i < 25; i++) {
            login(MAKER, null, null);
        }
        Session auditor = auditor();
        String filter = window("LOGIN");
        List<Long> screen = screenIds(auditor, filter);
        assertThat(screen).hasSizeGreaterThanOrEqualTo(25).doesNotHaveDuplicates();

        List<Long> exported = csvIds(csv(auditor, "/v1/admin/audit/export?" + filter));
        assertThat(exported).containsExactlyElementsOf(screen);

        MvcResult xlsx = download(auditor, "/v1/admin/audit/export?" + filter + "&format=XLSX");
        assertThat(xlsx.getResponse().getStatus()).isEqualTo(200);
        assertThat(xlsx.getResponse().getHeader("Content-Disposition"))
                .contains("audit-trail.xlsx");
        try (XSSFWorkbook book =
                new XSSFWorkbook(
                        new ByteArrayInputStream(xlsx.getResponse().getContentAsByteArray()))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("id");
            List<Long> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() > 0) {
                    rows.add((long) row.getCell(0).getNumericCellValue());
                }
            }
            assertThat(rows).containsExactlyElementsOf(screen);
        }
    }

    @Test
    void SRS_G_13_anEmptyListExportsItsHeaderOnlyInBothFormats() throws Exception {
        Session auditor = auditor();
        String filter = window("NO_SUCH_FIELD");
        assertThat(csv(auditor, "/v1/admin/audit/export?" + filter))
                .isEqualTo(
                        "\"id\",\"changed_at\",\"changed_by\",\"entity\",\"entity_key\",\"field\","
                                + "\"old_value\",\"new_value\",\"reason\",\"approval_id\","
                                + "\"change_type\",\"module\",\"effective_date\",\"ip_address\","
                                + "\"outcome\",\"maker\",\"checker\",\"order_reference\"\r\n");
        MvcResult xlsx = download(auditor, "/v1/admin/audit/export?" + filter + "&format=XLSX");
        try (XSSFWorkbook book =
                new XSSFWorkbook(
                        new ByteArrayInputStream(xlsx.getResponse().getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    @Test
    void SRS_G_13_aRefusedExportAnswersBeforeStreamingAnything() throws Exception {
        Session auditor = auditor();
        Instant now = Instant.now();
        // A reversed window: 400, no download started.
        MvcResult reversed =
                download(
                        auditor,
                        "/v1/admin/audit/export?from=" + now + "&to=" + now.minusSeconds(60));
        assertThat(reversed.getResponse().getStatus()).isEqualTo(400);
        assertThat(reversed.getRequest().isAsyncStarted()).isFalse();
        assertThat(reversed.getResponse().getHeader("Content-Disposition")).isNull();
        // An unknown format (SRS-G-13 lists CSV, Excel and, since OD-144, PDF on every list).
        assertThat(
                        download(
                                        auditor,
                                        "/v1/admin/audit/export?"
                                                + window("LOGIN")
                                                + "&format=DOCX")
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        // A role without EXPORT.
        Session maker = login("maker.delhi@fci-demo.test", null, null);
        MvcResult refused = download(maker, "/v1/admin/audit/export?" + window("LOGIN"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getRequest().isAsyncStarted()).isFalse();
    }
}
