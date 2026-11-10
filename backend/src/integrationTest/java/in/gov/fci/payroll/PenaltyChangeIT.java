package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Penalty Revoke and cancellation end to end (plan 12 phase 12e; SRS-14-16, SRS-14-17, SRS-14-20;
 * D12-9, D12-10): the Payroll Maker of Chennai submits with the order copy, the Payroll Checker
 * approves; the months still due are waived (a partial amount ends the recovery earlier), what was
 * already recovered within the waiver becomes the reversing arrear on I-08 (plan 13 phase 13g,
 * SRS-21-10: a COMPUTED PENALTY event, one refund line per recovered month under the recovery
 * head), a revoked increment stop is listed for its restoration; the refusals. Orders are fixtures
 * of Chennai members.
 */
class PenaltyChangeIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final byte[] PDF = "%PDF-1.7 revoke".getBytes(StandardCharsets.US_ASCII);

    private Session maker() throws Exception {
        return login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
    }

    private Session checker() throws Exception {
        return login("ddo.chennai@fci-demo.test", "PAYROLL_CHECKER", "CH001");
    }

    private long member(int fromEnd) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND record_status ="
                        + " 'ACTIVE' ORDER BY emp_num DESC OFFSET "
                        + fromEnd
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("CH001"));
    }

    /** An ACTIVE order; {@code months} = amount and status per month ("1000:RECOVERED"). */
    private long order(long employee, String nature, String... months) {
        String ref = ("PEN-IT-" + System.nanoTime()).substring(0, 20);
        BigDecimal total = BigDecimal.ZERO;
        for (String m : months) {
            total = total.add(new BigDecimal(m.split(":")[0]));
        }
        YearMonth first = YearMonth.now(IST).plusMonths(100);
        jdbc.update(
                "INSERT INTO penalty_order (penalty_ref, employee_id, order_reference,"
                        + " penalty_nature, total_recovery_amount, instalment_count, period_from,"
                        + " imposed_date, order_status) VALUES (?, ?, 'IT/ORDER', ?, ?, ?, ?, ?,"
                        + " 'ACTIVE')",
                ref,
                employee,
                nature,
                months.length == 0 ? null : total,
                months.length == 0 ? null : months.length,
                Date.valueOf(first.atDay(1)),
                Date.valueOf(LocalDate.now(IST)));
        long id =
                jdbc.queryForObject(
                        "SELECT order_id FROM penalty_order WHERE penalty_ref = ?",
                        Long.class,
                        ref);
        YearMonth m = first;
        for (String line : months) {
            String[] p = line.split(":");
            BigDecimal amount = new BigDecimal(p[0]);
            jdbc.update(
                    "INSERT INTO penalty_recovery_schedule (order_id, payroll_yymm,"
                            + " scheduled_amount, recovered_amount, schedule_status) VALUES (?, ?,"
                            + " ?, ?, ?)",
                    id,
                    String.format("%04d%02d", m.getYear(), m.getMonthValue()),
                    amount,
                    "RECOVERED".equals(p[1]) ? amount : BigDecimal.ZERO,
                    p[1]);
            m = m.plusMonths(1);
        }
        return id;
    }

    private MvcResult send(
            Session s, long order, String what, String part, Object body, byte[] file)
            throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/v1/penalties/" + order + "/" + what);
        r.file(
                new MockMultipartFile(
                        part, "", MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(body)));
        r.file(new MockMultipartFile("file", "revoke.pdf", "application/pdf", file));
        r.param("documentType", "OTHER")
                .param("orderDate", LocalDate.now(IST).toString())
                .param("issuingAuthority", "Disciplinary Authority Chennai");
        return mvc.perform(
                        userRequest(s, r).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
    }

    private static Map<String, Object> revoke(String type, String amount, String from, String to) {
        Map<String, Object> b = new HashMap<>();
        b.put("type", type);
        if (amount != null) {
            b.put("amount", new BigDecimal(amount));
        }
        b.put("fromYymm", from);
        b.put("toYymm", to);
        b.put("orderReference", "REV/IT/" + System.nanoTime() % 100000);
        b.put("orderDate", LocalDate.now(IST).toString());
        b.put("reason", "IT: revoked on appeal");
        return b;
    }

    private long approved(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(202);
        long approval = body(r).path("id").asLong();
        assertThat(decide(checker(), approval, "APPROVE", null, null)).isEqualTo(200);
        return approval;
    }

    private List<Map<String, Object>> lines(long order) {
        return jdbc.queryForList(
                "SELECT scheduled_amount, schedule_status FROM penalty_recovery_schedule WHERE"
                        + " order_id = ? ORDER BY payroll_yymm",
                order);
    }

    private String status(long order) {
        return jdbc.queryForObject(
                "SELECT order_status FROM penalty_order WHERE order_id = ?", String.class, order);
    }

    /** The reversing arrear on I-08: the employee's COMPUTED PENALTY events, their lines' sum. */
    private BigDecimal reversing(long employee) {
        BigDecimal sum =
                jdbc.queryForObject(
                        "SELECT SUM(d.arrear_amount) FROM arrear_detail d JOIN arrear_event e ON"
                                + " e.arrear_event_id = d.arrear_event_id WHERE d.employee_id = ?"
                                + " AND e.source_module = 'PENALTY' AND e.event_status ="
                                + " 'COMPUTED' AND d.arrear_status = 'CALCULATED'",
                        BigDecimal.class,
                        employee);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private int pending(long employee, String code) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND exception_code ="
                        + " ?",
                Integer.class,
                employee,
                code);
    }

    private String error(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    // SC-12-0044: a partial or full waiver against an approved Penalty ID.
    @Test
    void SRS_14_16_aPartRevokeByAmountEndsTheRecoveryEarlier() throws Exception {
        long employee = member(30);
        long order =
                order(
                        employee,
                        "RECOVERY",
                        "1000:RECOVERED",
                        "1000:PENDING",
                        "1000:PENDING",
                        "1500:PENDING");
        approved(
                send(
                        maker(),
                        order,
                        "revokes",
                        "revoke",
                        revoke("PARTIAL", "2000", null, null),
                        PDF));
        List<Map<String, Object>> l = lines(order);
        assertThat(l)
                .extracting(m -> m.get("SCHEDULE_STATUS"))
                .containsExactly("RECOVERED", "PENDING", "PENDING", "WAIVED");
        assertThat((BigDecimal) l.get(2).get("SCHEDULED_AMOUNT")).isEqualByComparingTo("500");
        assertThat(status(order)).isEqualTo("PARTLY_REVOKED");
        assertThat(reversing(employee)).as("nothing recovered is waived").isZero();
        JsonNode detail =
                body(mvc.perform(userGet(checker(), "/v1/penalties/" + order)).andReturn());
        assertThat(detail.path("revokes").get(0).path("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void SRS_14_16_aFullRevokeWaivesWhatIsDueAndListsTheReversingArrear() throws Exception {
        long employee = member(31);
        long order = order(employee, "RECOVERY", "1000:RECOVERED", "1000:PENDING");
        approved(send(maker(), order, "revokes", "revoke", revoke("FULL", null, null, null), PDF));
        assertThat(lines(order))
                .extracting(m -> m.get("SCHEDULE_STATUS"))
                .containsExactly("RECOVERED", "WAIVED");
        assertThat(status(order)).isEqualTo("REVOKED");
        assertThat(reversing(employee)).isEqualByComparingTo("1000");
    }

    // SC-17-0044: a penalty that stopped an increment, waived, restores the stopped increment.
    @Test
    void SRS_14_17_revokingAnIncrementStopListsItsRestorationAndAPeriodRevokeWaivesItsMonths()
            throws Exception {
        long employee = member(32);
        long stop = order(employee, "INCREMENT_STOP");
        approved(send(maker(), stop, "revokes", "revoke", revoke("FULL", null, null, null), PDF));
        assertThat(status(stop)).isEqualTo("REVOKED");
        assertThat(pending(employee, "PENALTY_INCREMENT_RESTORE")).isOne();

        long order = order(employee, "RECOVERY", "1000:PENDING", "1000:PENDING", "1000:PENDING");
        YearMonth second = YearMonth.now(IST).plusMonths(101);
        String m2 = String.format("%04d%02d", second.getYear(), second.getMonthValue());
        approved(send(maker(), order, "revokes", "revoke", revoke("PARTIAL", null, m2, m2), PDF));
        assertThat(lines(order))
                .extracting(m -> m.get("SCHEDULE_STATUS"))
                .containsExactly("PENDING", "WAIVED", "PENDING");
    }

    @Test
    void SRS_14_20_aCancellationEndsTheOrderAndWhatIsRefused() throws Exception {
        long employee = member(33);
        long order = order(employee, "RECOVERY", "1000:RECOVERED", "1000:PENDING");
        Session maker = maker();
        assertThat(
                        error(
                                send(
                                        maker,
                                        order,
                                        "revokes",
                                        "revoke",
                                        revoke("PARTIAL", null, null, null),
                                        PDF)))
                .isEqualTo("PEN-422-ORDER");
        assertThat(
                        error(
                                send(
                                        maker,
                                        order,
                                        "revokes",
                                        "revoke",
                                        revoke("FULL", "100", null, null),
                                        PDF)))
                .isEqualTo("PEN-422-ORDER");
        assertThat(
                        error(
                                send(
                                        maker,
                                        order,
                                        "revokes",
                                        "revoke",
                                        revoke("PARTIAL", "99999", null, null),
                                        PDF)))
                .isEqualTo("PEN-422-ORDER");
        assertThat(
                        error(
                                send(
                                        maker,
                                        order,
                                        "revokes",
                                        "revoke",
                                        revoke("FULL", null, null, null),
                                        new byte[0])))
                .isEqualTo("PEN-422-ORDER-COPY");
        assertThat(
                        send(
                                        checker(),
                                        order,
                                        "revokes",
                                        "revoke",
                                        revoke("FULL", null, null, null),
                                        PDF)
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        MvcResult returned =
                send(maker, order, "revokes", "revoke", revoke("FULL", null, null, null), PDF);
        long r = body(returned).path("id").asLong();
        assertThat(decide(checker(), r, "RETURN", "IT: wrong order", null)).isEqualTo(200);
        assertThat(status(order)).isEqualTo("ACTIVE");

        approved(
                send(
                        maker,
                        order,
                        "cancellations",
                        "cancellation",
                        Map.of("reason", "IT: imposed in error"),
                        PDF));
        assertThat(status(order)).isEqualTo("CANCELLED");
        assertThat(lines(order))
                .extracting(m -> m.get("SCHEDULE_STATUS"))
                .containsExactly("RECOVERED", "WAIVED");
        assertThat(reversing(employee)).isEqualByComparingTo("1000");
        assertThat(
                        error(
                                send(
                                        maker,
                                        order,
                                        "revokes",
                                        "revoke",
                                        revoke("FULL", null, null, null),
                                        PDF)))
                .as("a cancelled order changes no more")
                .isEqualTo("PEN-422-ORDER");
    }

    /**
     * SC-12-0027 (UC I-06 verify; SRS-14-20, SRS-21-10, R-DEL, R-AUD): a cancelled order keeps its
     * row and its schedule, now CANCELLED with the months still due WAIVED; what was recovered
     * comes back as one reversing arrear on I-08 under the order's source, a refund line per
     * recovered month of that month's amount; the change is audited with the Checker's approval.
     * Edge: an order with nothing recovered is cancelled with no reversing arrear. SC-13-0087: UC
     * Impose, Revise or Cancel a Back-Dated Penalty Event (I-08), main flow - the cancellation's
     * reversing arrear raised on I-08 under the order's source.
     */
    @Test
    void SC_12_0027_aCancelledPenaltyCreatesAReversingArrearAndKeepsItsAudit() throws Exception {
        long employee = member(34);
        long order = order(employee, "RECOVERY", "700:RECOVERED", "300:RECOVERED", "1000:PENDING");
        String ref =
                jdbc.queryForObject(
                        "SELECT penalty_ref FROM penalty_order WHERE order_id = ?",
                        String.class,
                        order);
        BigDecimal before = reversing(employee);
        long approval =
                approved(
                        send(
                                maker(),
                                order,
                                "cancellations",
                                "cancellation",
                                Map.of("reason", "IT: imposed in error"),
                                PDF));
        assertThat(status(order)).isEqualTo("CANCELLED");
        assertThat(lines(order))
                .extracting(m -> m.get("SCHEDULE_STATUS"))
                .containsExactly("RECOVERED", "RECOVERED", "WAIVED");
        assertThat(reversing(employee).subtract(before)).isEqualByComparingTo("1000");
        List<Map<String, Object>> refund =
                jdbc.queryForList(
                        "SELECT d.differential_yymm, d.arrear_amount FROM arrear_detail d JOIN"
                                + " arrear_event e ON e.arrear_event_id = d.arrear_event_id WHERE"
                                + " e.source_module = 'PENALTY' AND e.source_key = ? ORDER BY"
                                + " d.differential_yymm",
                        ref + "/CANCEL");
        YearMonth first = YearMonth.now(IST).plusMonths(100);
        assertThat(refund)
                .extracting(m -> m.get("DIFFERENTIAL_YYMM"))
                .containsExactly(
                        String.format("%04d%02d", first.getYear(), first.getMonthValue()),
                        String.format(
                                "%04d%02d",
                                first.plusMonths(1).getYear(),
                                first.plusMonths(1).getMonthValue()));
        assertThat(refund)
                .extracting(m -> ((BigDecimal) m.get("ARREAR_AMOUNT")).abs())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("700"), new BigDecimal("300"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'penalty_order' AND entity_key = ? AND field_name ="
                                        + " 'order_status' AND new_value = 'CANCELLED' AND"
                                        + " approval_id = ?",
                                Integer.class,
                                ref,
                                approval))
                .isEqualTo(1);

        // Nothing recovered: cancelled, every month waived, no reversing arrear.
        long untouched = member(35);
        long fresh = order(untouched, "RECOVERY", "500:PENDING", "500:PENDING");
        BigDecimal none = reversing(untouched);
        approved(
                send(
                        maker(),
                        fresh,
                        "cancellations",
                        "cancellation",
                        Map.of("reason", "IT: imposed in error"),
                        PDF));
        assertThat(status(fresh)).isEqualTo("CANCELLED");
        assertThat(lines(fresh))
                .extracting(m -> m.get("SCHEDULE_STATUS"))
                .containsExactly("WAIVED", "WAIVED");
        assertThat(reversing(untouched)).isEqualByComparingTo(none);
    }
}
