package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 14 phase 14c (plan docs/plan/14-eseva.md; BR-ESEVA-006, SRS-17-3; D14-4): the member asks
 * on E-01 for a new monthly VPF; the Payroll Maker of the member's office verifies it on E-02 and
 * the Payroll Checker approves it on W-01, after which the year's CPF ledger carries the new
 * subscription and the request is APPLIED; the submission log follows it. Refused: a second request
 * while one waits, an unchanged amount, the Maker of another office, a member verifying. Member
 * 104882 of Delhi (DH001); the subscription is restored at the end.
 */
class VpfChangeIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static String finYear(YearMonth month) {
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        return String.format("%d-%02d", start, (start + 1) % 100);
    }

    private long self() {
        return jdbc.queryForObject(
                "SELECT employee_id FROM app_user WHERE login_id = 'emp.rsharma@fci-demo.test'",
                Long.class);
    }

    /** A CPF ledger of the year for the member, when none exists yet. */
    private void ledger(long employee, String year) {
        jdbc.update(
                "INSERT INTO employee_pf_header (employee_id, fin_year, fund_type, zone_id,"
                    + " opening_balance_source, vcpf_subscription_amt) SELECT ?, ?, 'CPF',"
                    + " parent_zone_id, 'SYSTEM_COMPUTED', 1000 FROM employee WHERE employee_id = ?"
                    + " AND NOT EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ? AND"
                    + " fin_year = ? AND fund_type = 'CPF')",
                employee,
                year,
                employee,
                employee,
                year);
    }

    private BigDecimal subscription(long employee, String year) {
        return jdbc.queryForObject(
                "SELECT MAX(vcpf_subscription_amt) FROM employee_pf_header WHERE employee_id = ?"
                        + " AND fin_year = ? AND fund_type = 'CPF'",
                BigDecimal.class,
                employee,
                year);
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    // SC-16-0128: the VCPF subscription is the one field the employee changes, through eSeva.
    @Test
    void BR_ESEVA_006_aVpfChangeIsVerifiedApprovedAndAppliedFromTheNextOpenMonth()
            throws Exception {
        long me = self();
        YearMonth now = YearMonth.now(IST);
        List<String> years = List.of(finYear(now), finYear(now.plusMonths(1)));
        years.forEach(y -> ledger(me, y));
        Map<String, BigDecimal> before = new HashMap<>();
        years.forEach(y -> before.put(y, subscription(me, y)));
        try {
            Session member = login("emp.rsharma@fci-demo.test", null, null);
            JsonNode mine =
                    body(mvc.perform(userGet(member, "/v1/cpf/vpf-changes/mine")).andReturn());
            assertThat(mine.get("cpfMember").asBoolean()).isTrue();
            BigDecimal asked = mine.get("currentAmount").decimalValue().add(new BigDecimal("750"));
            assertThat(
                            body(mvc.perform(
                                                    userPost(
                                                            member,
                                                            "/v1/cpf/vpf-changes/mine",
                                                            Map.of(
                                                                    "amount",
                                                                    mine.get("currentAmount")
                                                                            .decimalValue())))
                                            .andReturn())
                                    .path("errorCode")
                                    .asString())
                    .as("an unchanged amount")
                    .isEqualTo("CPF-422-VPF-CHANGE");
            MvcResult made =
                    mvc.perform(
                                    userPost(
                                            member,
                                            "/v1/cpf/vpf-changes/mine",
                                            Map.of("amount", asked)))
                            .andReturn();
            assertThat(status(made)).as(made.getResponse().getContentAsString()).isEqualTo(201);
            long id = body(made).get("id").asLong();
            String from = body(made).get("effectiveFromYymm").asString();
            assertThat(
                            body(mvc.perform(
                                                    userPost(
                                                            member,
                                                            "/v1/cpf/vpf-changes/mine",
                                                            Map.of(
                                                                    "amount",
                                                                    asked.add(BigDecimal.ONE))))
                                            .andReturn())
                                    .path("errorCode")
                                    .asString())
                    .as("one change at a time")
                    .isEqualTo("CPF-409-VPF-PENDING");
            assertThat(log(id)).isEqualTo("SUBMITTED");
            assertThat(
                            status(
                                    mvc.perform(
                                                    userPost(
                                                            member,
                                                            "/v1/cpf/vpf-changes/"
                                                                    + id
                                                                    + "/verifications",
                                                            Map.of()))
                                            .andReturn()))
                    .as("a member never verifies")
                    .isEqualTo(403);
            Session chennai = login("maker.chennai@fci-demo.test", "PAYROLL_MAKER", "CH001");
            assertThat(
                            status(
                                    mvc.perform(
                                                    userPost(
                                                            chennai,
                                                            "/v1/cpf/vpf-changes/"
                                                                    + id
                                                                    + "/verifications",
                                                            Map.of()))
                                            .andReturn()))
                    .as("another office's Maker")
                    .isEqualTo(404);

            Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
            MvcResult verified =
                    mvc.perform(
                                    userPost(
                                            maker,
                                            "/v1/cpf/vpf-changes/" + id + "/verifications",
                                            Map.of()))
                            .andReturn();
            assertThat(status(verified))
                    .as(verified.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(body(verified).get("awaitingChecker").asBoolean()).isTrue();
            assertThat(log(id)).isEqualTo("ROUTED");
            long approval =
                    jdbc.queryForObject(
                            "SELECT approval_id FROM vpf_change_request WHERE vpf_change_request_id"
                                    + " = ?",
                            Long.class,
                            id);
            assertThat(
                            decide(
                                    login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                    approval,
                                    "APPROVE",
                                    null,
                                    null))
                    .isEqualTo(200);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT request_status FROM vpf_change_request WHERE"
                                            + " vpf_change_request_id = ?",
                                    String.class,
                                    id))
                    .isEqualTo("APPLIED");
            YearMonth effective =
                    YearMonth.of(
                            Integer.parseInt(from.substring(0, 4)),
                            Integer.parseInt(from.substring(4)));
            assertThat(subscription(me, finYear(effective))).isEqualByComparingTo(asked);
            assertThat(log(id)).isEqualTo("ACTIONED");
        } finally {
            before.forEach(
                    (year, amount) ->
                            jdbc.update(
                                    "UPDATE employee_pf_header SET vcpf_subscription_amt = ? WHERE"
                                            + " employee_id = ? AND fin_year = ? AND fund_type ="
                                            + " 'CPF'",
                                    amount,
                                    me,
                                    year));
        }
    }

    private String log(long id) {
        return jdbc.queryForObject(
                "SELECT submission_status FROM ess_submission WHERE reference_entity_name ="
                        + " 'vpf_change_request' AND reference_entity_id = ?",
                String.class,
                id);
    }

    private String state(long id) {
        return jdbc.queryForObject(
                "SELECT request_status FROM vpf_change_request WHERE vpf_change_request_id = ?",
                String.class,
                id);
    }

    private long ask(Session member, BigDecimal amount) throws Exception {
        MvcResult made =
                mvc.perform(userPost(member, "/v1/cpf/vpf-changes/mine", Map.of("amount", amount)))
                        .andReturn();
        assertThat(status(made)).as(made.getResponse().getContentAsString()).isEqualTo(201);
        return body(made).get("id").asLong();
    }

    /**
     * BR-ESEVA-006: the Maker lists the office's requests by status, returns one to the member with
     * a reason (a blank one refused); the Checker rejects a verified request, which ends it, or
     * returns it to the Maker, where it stays submitted.
     */
    @Test
    void BR_ESEVA_006_aRequestIsListedReturnedToTheMemberOrRejectedOrReturnedByTheChecker()
            throws Exception {
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        BigDecimal now =
                body(mvc.perform(userGet(member, "/v1/cpf/vpf-changes/mine")).andReturn())
                        .get("currentAmount")
                        .decimalValue();
        long first = ask(member, now.add(new BigDecimal("1100")));
        JsonNode listed =
                body(
                        mvc.perform(userGet(maker, "/v1/cpf/vpf-changes?status=SUBMITTED&size=50"))
                                .andReturn());
        assertThat(listed.get("items").toString()).contains("\"id\":" + first);
        assertThat(
                        body(mvc.perform(userGet(maker, "/v1/cpf/vpf-changes?size=10")).andReturn())
                                .get("total")
                                .asLong())
                .isPositive();
        String returns = "/v1/cpf/vpf-changes/" + first + "/returns";
        assertThat(status(mvc.perform(userPost(maker, returns, Map.of("reason", " "))).andReturn()))
                .isBetween(400, 422);
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        maker,
                                                        returns,
                                                        Map.of("reason", "Attach the form")))
                                        .andReturn()))
                .isEqualTo(200);
        assertThat(state(first)).isEqualTo("RETURNED");

        long second = ask(member, now.add(new BigDecimal("1200")));
        mvc.perform(userPost(maker, "/v1/cpf/vpf-changes/" + second + "/verifications", Map.of()))
                .andReturn();
        long approval =
                jdbc.queryForObject(
                        "SELECT approval_id FROM vpf_change_request WHERE vpf_change_request_id ="
                                + " ?",
                        Long.class,
                        second);
        assertThat(decide(checker, approval, "RETURN", "IT: amount to confirm", null))
                .isEqualTo(200);
        assertThat(state(second)).as("returned to the Maker").isEqualTo("SUBMITTED");
        mvc.perform(userPost(maker, "/v1/cpf/vpf-changes/" + second + "/verifications", Map.of()))
                .andReturn();
        long again =
                jdbc.queryForObject(
                        "SELECT approval_id FROM vpf_change_request WHERE vpf_change_request_id ="
                                + " ?",
                        Long.class,
                        second);
        assertThat(decide(checker, again, "REJECT", "IT: over the limit", null)).isEqualTo(200);
        assertThat(state(second)).isEqualTo("REJECTED");
    }
}
