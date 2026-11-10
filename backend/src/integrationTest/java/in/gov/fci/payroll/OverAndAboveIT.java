package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Over-and-above payments on I-06 (plan 12 phase 12f; SRS-14-2, SRS-14-5, SRS-14-14, SRS-14-20,
 * BR-PEN2-002): the Payroll Maker of Chennai records the payment with its sanction, the Payroll
 * Checker approves before posting (taxable as its pay component), a cancellation until the invoice
 * is sent; the refusals.
 */
class OverAndAboveIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Session maker() throws Exception {
        return login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
    }

    private Session checker() throws Exception {
        return login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
    }

    private String eis() {
        return jdbc.queryForObject(
                "SELECT emp_num FROM employee WHERE location_id = ? AND record_status = 'ACTIVE'"
                    + " AND employment_status = 'IN_SERVICE' ORDER BY emp_num FETCH FIRST 1 ROWS"
                    + " ONLY",
                String.class,
                locationId("CH001"));
    }

    private int code(String type) {
        return jdbc.queryForObject(
                "SELECT MIN(legacy_pay_code) FROM pay_component WHERE component_type = ?",
                Integer.class,
                type);
    }

    private Map<String, Object> payment(int payCode, LocalDate applied, LocalDate sanctioned) {
        YearMonth m = YearMonth.now(IST).plusMonths(1);
        Map<String, Object> p = new HashMap<>();
        p.put("eis", eis());
        p.put("payCode", payCode);
        p.put("amount", new BigDecimal("2500.50"));
        p.put("payrollYymm", String.format("%04d%02d", m.getYear(), m.getMonthValue()));
        p.put("reason", "IT: special duty");
        p.put("applicationDate", applied == null ? null : applied.toString());
        p.put("sanctionReference", "SAN/IT/" + System.nanoTime() % 100000);
        p.put("sanctionDate", sanctioned == null ? null : sanctioned.toString());
        return p;
    }

    private MvcResult post(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private String status(long approval) {
        return jdbc.queryForObject(
                "SELECT payment_status FROM over_and_above_payment WHERE approval_id = ?",
                String.class,
                approval);
    }

    @Test
    void SRS_14_2_aPaymentIsApprovedBeforePostingAndCancelledUntilItsInvoiceIsSent()
            throws Exception {
        LocalDate today = LocalDate.now(IST);
        MvcResult sent =
                post(
                        maker(),
                        "/v1/penalties/over-and-above",
                        payment(code("EARNING"), today.minusDays(5), today));
        assertThat(sent.getResponse().getStatus())
                .as(sent.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(sent).path("id").asLong();
        assertThat(status(approval)).isEqualTo("SUBMITTED");
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(status(approval)).isEqualTo("APPROVED");
        long id =
                jdbc.queryForObject(
                        "SELECT over_above_id FROM over_and_above_payment WHERE approval_id = ?",
                        Long.class,
                        approval);
        JsonNode list =
                body(mvc.perform(userGet(checker(), "/v1/penalties/over-and-above")).andReturn());
        assertThat(list.toString()).contains("\"status\":\"APPROVED\"", "\"amount\":2500.5");

        MvcResult cancel =
                post(
                        maker(),
                        "/v1/penalties/over-and-above/" + id + "/cancellations",
                        Map.of("reason", "IT: paid twice"));
        assertThat(cancel.getResponse().getStatus()).isEqualTo(202);
        assertThat(decide(checker(), body(cancel).path("id").asLong(), "APPROVE", null, null))
                .isEqualTo(200);
        assertThat(status(approval)).isEqualTo("CANCELLED");
        MvcResult again =
                post(
                        maker(),
                        "/v1/penalties/over-and-above/" + id + "/cancellations",
                        Map.of("reason", "x"));
        assertThat(body(again).path("errorCode").asString()).isEqualTo("PEN-422-ORDER");
    }

    /**
     * Use case alternate flow 4 (SRS l.2998; US-GAP-230): a payment the Checker rejects or returns
     * goes back to the Maker with the reason, listed with it on I-06. US-GAP-231 (BR-PEN2-002): a
     * payment without a sanction date is accepted (the date is checked only where given).
     */
    @Test
    void SRS_14_2_aRejectedOrReturnedPaymentGoesBackToTheMakerWithTheReason() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Session maker = maker();
        MvcResult undated =
                post(
                        maker,
                        "/v1/penalties/over-and-above",
                        payment(code("EARNING"), today.minusDays(5), null));
        assertThat(undated.getResponse().getStatus())
                .as("no sanction date: " + undated.getResponse().getContentAsString())
                .isEqualTo(202);
        long rejected = body(undated).path("id").asLong();
        assertThat(decide(maker, rejected, "REJECT", "IT: own payment", null))
                .as("never the maker")
                .isEqualTo(403);
        assertThat(decide(checker(), rejected, "REJECT", "IT: no sanction on file", null))
                .isEqualTo(200);
        assertThat(status(rejected)).isEqualTo("REJECTED");
        long returned =
                body(post(
                                maker,
                                "/v1/penalties/over-and-above",
                                payment(code("EARNING"), today.minusDays(5), today)))
                        .path("id")
                        .asLong();
        assertThat(decide(checker(), returned, "RETURN", "IT: amount differs", null))
                .isEqualTo(200);
        assertThat(status(returned)).isEqualTo("RECORDED");
        String list =
                body(mvc.perform(userGet(maker, "/v1/penalties/over-and-above?size=100"))
                                .andReturn())
                        .toString();
        assertThat(list)
                .contains(
                        "\"checkerReason\":\"IT: no sanction on file\"",
                        "\"checkerReason\":\"IT: amount differs\"");
    }

    @Test
    void BR_PEN2_002_whatIsRefused() throws Exception {
        LocalDate today = LocalDate.now(IST);
        Session maker = maker();
        assertThat(
                        body(post(
                                        maker,
                                        "/v1/penalties/over-and-above",
                                        payment(code("EARNING"), today, today)))
                                .path("errorCode")
                                .asString())
                .as("a sanction not later than the application")
                .isEqualTo("PEN-422-ORDER");
        assertThat(
                        body(post(
                                        maker,
                                        "/v1/penalties/over-and-above",
                                        payment(code("DEDUCTION"), null, null)))
                                .path("errorCode")
                                .asString())
                .as("a deduction component")
                .isEqualTo("PEN-422-ORDER");
        assertThat(
                        post(
                                        checker(),
                                        "/v1/penalties/over-and-above",
                                        payment(code("EARNING"), null, null))
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        post(
                                        maker,
                                        "/v1/penalties/over-and-above/999999/cancellations",
                                        Map.of("reason", "x"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
    }
}
