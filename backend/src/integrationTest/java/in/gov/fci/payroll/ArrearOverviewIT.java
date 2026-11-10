package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * I-08 as Wireframes v6.1 draws it (OD-121; SRS-8-8, SRS-8-6, SRS-8-15, SRS-8-21): the tiles count
 * the scope's open events, arrears and recoveries with their members; the event types are the
 * database list; the retro controls read the Headquarters settings; a Maker raises a retro event
 * for the office and span (RAISED, audited), a span overlapping an open event of the type is
 * refused, a viewer may not raise one, a bad span is refused, and the period-wise differential of
 * an event sums every month of its details.
 */
class ArrearOverviewIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void SRS_8_8_theMakerRaisesARetroEventForTheOfficeAndSpanAndTheTilesCountIt() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long before =
                body(mvc.perform(userGet(maker, "/v1/arrear-events/summary"))
                                .andExpect(status().isOk())
                                .andReturn())
                        .get("openEvents")
                        .asLong();
        JsonNode types =
                body(
                        mvc.perform(userGet(maker, "/v1/arrear-events/types"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(types.isArray()).isTrue();
        // Only the types M-16 lists for a manual event (demo: RULE_CHANGE, OTHER), never a sourced
        // one.
        assertThat(types.findValuesAsString("code"))
                .contains("RULE_CHANGE", "OTHER")
                .doesNotContain("HRA_REVISION", "LEAVE", "DA_REVISION");
        YearMonth last = YearMonth.now(IST).minusMonths(1);
        Map<String, Object> body =
                Map.of(
                        "eventCode", "RULE_CHANGE",
                        "reference", "Rule change arrear (IT)",
                        "payrollType", "STAFF",
                        "effectiveFrom", last.atDay(1).toString(),
                        "effectiveTo", last.atEndOfMonth().toString());
        JsonNode created =
                body(
                        mvc.perform(userPost(maker, "/v1/arrear-events", body))
                                .andExpect(status().isCreated())
                                .andReturn());
        long id = created.get("id").asLong();
        assertThat(id).isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT event_status FROM arrear_event WHERE arrear_event_id = ?",
                                String.class,
                                id))
                .isEqualTo("RAISED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scope_location_id FROM arrear_event WHERE arrear_event_id ="
                                        + " ?",
                                Long.class,
                                id))
                .isEqualTo(locationId("DH001"));
        // Audited (R-AUD).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'arrear_event' AND entity_key = ?",
                                Long.class,
                                String.valueOf(id)))
                .isPositive();
        JsonNode after = body(mvc.perform(userGet(maker, "/v1/arrear-events/summary")).andReturn());
        assertThat(after.get("openEvents").asLong()).isEqualTo(before + 1);
        assertThat(after.get("statutory").isNull()).isTrue();
        // SRS-8-15: the same type and office over an overlapping span is refused.
        mvc.perform(userPost(maker, "/v1/arrear-events", body)).andExpect(status().isConflict());
        // An event without details has no period rows yet, and a stranger's id is not found.
        JsonNode periods =
                body(
                        mvc.perform(userGet(maker, "/v1/arrear-events/" + id + "/periods"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(periods.isArray()).isTrue();
        assertThat(periods).isEmpty();
        mvc.perform(userGet(maker, "/v1/arrear-events/999999999/periods"))
                .andExpect(status().isNotFound());
    }

    @Test
    void SRS_8_8_aSpanEndingBeforeItStartsAndAnUnknownTypeAreRefused() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        LocalDate today = LocalDate.now(IST);
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/arrear-events",
                                Map.of(
                                        "eventCode", "RULE_CHANGE",
                                        "reference", "Bad span",
                                        "payrollType", "STAFF",
                                        "effectiveFrom", today.minusMonths(2).toString(),
                                        "effectiveTo", today.minusMonths(3).toString())))
                .andExpect(status().isConflict());
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/arrear-events",
                                Map.of(
                                        "eventCode", "NO_SUCH_TYPE",
                                        "reference", "Unknown type",
                                        "payrollType", "STAFF",
                                        "effectiveFrom", today.minusMonths(2).toString())))
                .andExpect(status().isConflict());
        // SRS-8-11 / BR-LEAVE-003: a type with a source of its own (HRA from HRMS, leave through
        // I-21) is
        // not a manual type: refused, nothing created.
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM arrear_event", Long.class);
        for (String sourced : new String[] {"HRA_REVISION", "LEAVE", "DA_REVISION"}) {
            mvc.perform(
                            userPost(
                                    maker,
                                    "/v1/arrear-events",
                                    Map.of(
                                            "eventCode",
                                            sourced,
                                            "reference",
                                            "Keyed " + sourced,
                                            "payrollType",
                                            "STAFF",
                                            "effectiveFrom",
                                            today.minusMonths(2).toString())))
                    .andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM arrear_event", Long.class))
                .isEqualTo(before);
        // Validation (400): a future start, a wrong payroll type.
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/arrear-events",
                                Map.of(
                                        "eventCode", "RULE_CHANGE",
                                        "reference", "Future",
                                        "payrollType", "STAFF",
                                        "effectiveFrom", today.plusMonths(1).toString())))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        userPost(
                                maker,
                                "/v1/arrear-events",
                                Map.of(
                                        "eventCode", "RULE_CHANGE",
                                        "reference", "Wrong type",
                                        "payrollType", "CONTRACT",
                                        "effectiveFrom", today.minusMonths(1).toString())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void SRS_G_29_aViewerReadsTheTilesAndControlsButMayNotRaiseAnEvent() throws Exception {
        Session finance = login("finance.mumbai@fci-demo.test", "FINANCE", "MR001");
        JsonNode summary =
                body(
                        mvc.perform(userGet(finance, "/v1/arrear-events/summary"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(summary.get("openEvents").asLong()).isGreaterThanOrEqualTo(0);
        JsonNode controls =
                body(
                        mvc.perform(userGet(finance, "/v1/arrear-events/controls"))
                                .andExpect(status().isOk())
                                .andReturn());
        // MAX_RETRO_PERIOD is unseeded in the baseline (V077_41): null = not configured, never a
        // guess.
        assertThat(controls.has("maxRetroMonths")).isTrue();
        assertThat(controls.get("postRetirementMonths").isNull()).isTrue();
        mvc.perform(
                        userPost(
                                finance,
                                "/v1/arrear-events",
                                Map.of(
                                        "eventCode", "RULE_CHANGE",
                                        "reference", "Not allowed",
                                        "payrollType", "STAFF",
                                        "effectiveFrom",
                                                LocalDate.now(IST).minusMonths(1).toString())))
                .andExpect(status().isForbidden());
    }

    /** The Maker's order upload of the drawn "Order copy - attach & store" card. */
    private MockMultipartHttpServletRequestBuilder order(long id, String orderDate) {
        return multipart("/v1/arrear-events/" + id + "/documents")
                .file(
                        new MockMultipartFile(
                                "file",
                                "da_order.pdf",
                                "application/pdf",
                                "%PDF-1.7 arrear order".getBytes(StandardCharsets.US_ASCII)));
    }

    private long raise(Session maker, String reference, YearMonth month) throws Exception {
        Map<String, Object> body =
                Map.of(
                        // "Other", a demo manual type (V078_8_1); a sourced type is never keyed.
                        "eventCode",
                        "OTHER",
                        "reference",
                        reference,
                        "payrollType",
                        "STAFF",
                        "effectiveFrom",
                        month.atDay(1).toString(),
                        "effectiveTo",
                        month.atEndOfMonth().toString());
        return body(mvc.perform(userPost(maker, "/v1/arrear-events", body))
                        .andExpect(status().isCreated())
                        .andReturn())
                .get("id")
                .asLong();
    }

    @Test
    void SRS_G_30_theMakerAttachesTheEventsOrderCopyWhichIsListedAndServedAsAnAttachment()
            throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long id = raise(maker, "DA order copy (IT)", YearMonth.now(IST).minusMonths(5));
        String today = LocalDate.now(IST).toString();
        JsonNode stored =
                body(
                        mvc.perform(
                                        userRequest(
                                                        maker,
                                                        order(id, today)
                                                                .param("documentType", "CIRCULAR")
                                                                .param(
                                                                        "reference",
                                                                        "FCI/EST/2026/118")
                                                                .param("orderDate", today)
                                                                .param("issuingOffice", "RO Delhi"))
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString()))
                                .andExpect(status().isCreated())
                                .andReturn());
        long fileId = stored.get("id").asLong();
        assertThat(stored.get("entityName").asString()).isEqualTo("arrear_event");
        assertThat(stored.get("entityKey").asString()).isEqualTo(String.valueOf(id));
        JsonNode list =
                body(
                        mvc.perform(userGet(maker, "/v1/arrear-events/" + id + "/documents"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("referenceNumber").asString()).isEqualTo("FCI/EST/2026/118");
        mvc.perform(userGet(maker, "/v1/arrear-events/" + id + "/documents/" + fileId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        // An order dated in the future is refused (validation, 400), nothing stored.
        mvc.perform(
                        userRequest(
                                        maker,
                                        order(id, today)
                                                .param("documentType", "CIRCULAR")
                                                .param(
                                                        "orderDate",
                                                        LocalDate.now(IST).plusDays(1).toString()))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        assertThat(
                        body(
                                mvc.perform(
                                                userGet(
                                                        maker,
                                                        "/v1/arrear-events/" + id + "/documents"))
                                        .andReturn()))
                .hasSize(1);
    }

    @Test
    void SRS_G_29_anOrderIsNeitherAttachedByAViewerNorReadOutsideTheOffice() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        long id = raise(maker, "DA order scope (IT)", YearMonth.now(IST).minusMonths(7));
        Session finance = login("finance.mumbai@fci-demo.test", "FINANCE", "MR001");
        String today = LocalDate.now(IST).toString();
        // Finance may view I-08 but not submit: the upload is forbidden.
        mvc.perform(
                        userRequest(
                                        finance,
                                        order(id, today)
                                                .param("documentType", "CIRCULAR")
                                                .param("orderDate", today))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        // A Delhi event is not found from Mumbai (no existence leak), its files neither.
        mvc.perform(userGet(finance, "/v1/arrear-events/" + id + "/documents"))
                .andExpect(status().isNotFound());
        mvc.perform(userGet(finance, "/v1/arrear-events/" + id + "/documents/1"))
                .andExpect(status().isNotFound());
        mvc.perform(userGet(maker, "/v1/arrear-events/999999999/documents"))
                .andExpect(status().isNotFound());
    }
}
