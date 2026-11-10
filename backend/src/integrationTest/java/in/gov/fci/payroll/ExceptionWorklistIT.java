package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * X-05 Exceptions &amp; Holds Worklist end to end (SRS-G-9; DDL payroll_exception). Each test
 * raises its own conditions (unique exception codes) about member 105220 at Delhi HQ (DH001).
 */
class ExceptionWorklistIT extends ApiTestSupport {

    @Autowired ExceptionWorklist worklist;

    private Long member;
    private String code;

    @BeforeEach
    void member() {
        member =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE emp_num = '105220'", Long.class);
        code = "IT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Long raise(String severity, String gate, String source, String detail) {
        return worklist.raise(
                "it.producer",
                new ExceptionWorklist.NewEntry(
                        null,
                        member,
                        "C-02",
                        code,
                        severity,
                        gate,
                        null,
                        "employee",
                        "bank_account",
                        source,
                        "M-01",
                        detail));
    }

    private JsonNode row(Session session, Long id) throws Exception {
        JsonNode list =
                body(
                        // The test's own condition (other tests raise many items in the scope).
                        mvc.perform(userGet(session, "/v1/exceptions?size=100&code=" + code))
                                .andExpect(status().isOk())
                                .andReturn());
        for (JsonNode item : list.get("items")) {
            if (item.get("id").asLong() == id) {
                return item;
            }
        }
        return null;
    }

    @Test
    void SRS_G_9_aRaisedExceptionIsListedWithItsFieldSourceAndFixLocationAndIsNotDuplicated()
            throws Exception {
        Long id = raise("WARNING", "G2", "ENT", "Bank account missing");
        assertThat(raise("WARNING", "G2", "ENT", "again"))
                .as("the same condition is not raised twice")
                .isEqualTo(id);

        JsonNode item = row(login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001"), id);
        assertThat(item).isNotNull();
        assertThat(item.get("eis").asString()).isEqualTo("105220");
        assertThat(item.get("correctionScreen").asString()).isEqualTo("M-01");
        assertThat(item.get("fieldSource").asString()).isEqualTo("ENT");
        assertThat(item.get("status").asString()).isEqualTo("OPEN");
        // The tiles of another role in the same scope count it (no demo Finance user works at
        // DH001).
        JsonNode other =
                body(
                        mvc.perform(
                                        userGet(
                                                login(
                                                        "ddo.delhi@fci-demo.test",
                                                        "PAYROLL_CHECKER",
                                                        "DH001"),
                                                "/v1/exceptions"))
                                .andReturn());
        assertThat(other.get("tiles").get("open").asLong()).isPositive();
        assertThat(other.get("tiles").get("blockingG2").asLong()).isPositive();
    }

    @Test
    void SRS_EH_4_anHrmsValueIsShownAsUpstreamAndNotResolvableInPayroll() throws Exception {
        Long id = raise("BLOCKING", "G2", "HRMS", "PAN mismatch with HRMS");
        JsonNode item = row(login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001"), id);
        assertThat(item.get("status").asString()).isEqualTo("ROUTED_UPSTREAM");
        assertThat(item.get("resolvableInPayroll").asBoolean()).isFalse();
        assertThat(item.get("ownerRoleCode").asString()).isEqualTo("HRMS");
        // Not even a non-blocking upstream item is accepted: it stays open until the corrected
        // value arrives (SRS X-05 alternate flow; gate G2 may still be accepted on C-01).
        Long warning = raise("WARNING", "G2", "HRMS", "Leave balance differs from HRMS");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + warning + "/accept",
                                Map.of("reason", "HRMS will correct it")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("EXC-422-ACCEPT"));
        assertThat(row(checker, warning).get("status").asString()).isEqualTo("ROUTED_UPSTREAM");
    }

    @Test
    void SRS_G_9_theCheckerAssignsAndAcceptsANonBlockingG2ItemWithAReason() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        Long warning = raise("WARNING", "G2", "ENT", "Incentive basis missing");

        mvc.perform(
                        userPost(
                                maker,
                                "/v1/exceptions/" + warning + "/assign",
                                Map.of("owner", "PAYROLL_MAKER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + warning + "/assign",
                                Map.of("owner", "NOBODY")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("EXC-422-OWNER"));
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + warning + "/assign",
                                Map.of("owner", "PAYROLL_MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.ownerRoleCode").value("PAYROLL_MAKER"));

        mvc.perform(userPost(maker, "/v1/exceptions/" + warning + "/accept", Map.of("reason", "x")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + warning + "/accept",
                                Map.of("reason", "Incentive withheld this month")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedBy").value("ddo.delhi@fci-demo.test"));
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + warning + "/accept",
                                Map.of("reason", "again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EXC-409-STATUS"));
        // The acceptance is audited with its reason, and the item stays visible.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'payroll_exception' AND entity_key = ? AND reason ="
                                        + " 'Incentive withheld this month'",
                                Integer.class,
                                String.valueOf(warning)))
                .isEqualTo(1);
        assertThat(row(maker, warning).get("status").asString()).isEqualTo("ACCEPTED");
    }

    /**
     * US-01-07 AC7 (US-GAP-100): SRS-G-9 rights on X-05 - Finance sees the items in its scope but
     * neither assigns nor accepts one; the item is unchanged.
     */
    @Test
    void SRS_G_9_financeSeesTheWorklistReadOnly() throws Exception {
        Session finance = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        Long warning = raise("WARNING", "G2", "ENT", "Finance read-only check");
        assertThat(row(finance, warning)).as("listed for Finance").isNotNull();
        mvc.perform(
                        userPost(
                                finance,
                                "/v1/exceptions/" + warning + "/assign",
                                Map.of("owner", "PAYROLL_MAKER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        userPost(
                                finance,
                                "/v1/exceptions/" + warning + "/accept",
                                Map.of("reason", "Finance cannot accept")))
                .andExpect(status().isForbidden());
        assertThat(row(finance, warning).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void SRS_G_9_aBlockingExceptionMustBeClearedNotAccepted() throws Exception {
        Long blocking = raise("BLOCKING", "G2", "ENT", "No basic pay");
        mvc.perform(
                        userPost(
                                login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                                "/v1/exceptions/" + blocking + "/accept",
                                Map.of("reason", "please")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("EXC-422-ACCEPT"));
    }

    @Test
    void SRS_G_9_theSystemClosesAnExceptionWhenTheConditionClearsAndRecordsTheCause()
            throws Exception {
        Long id = raise("BLOCKING", "G2", "ENT", "No bank account");
        assertThatThrownBy(
                        () ->
                                worklist.clear(
                                        "it.producer", code, null, member, "bank_account", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        worklist.clear(
                                "it.producer",
                                code,
                                null,
                                member,
                                "bank_account",
                                "Bank account entered on M-01"))
                .isEqualTo(1);
        JsonNode item = row(login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001"), id);
        assertThat(item.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(item.get("resolvedAt").isNull()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT changed_by FROM audit_event WHERE entity_name ="
                                    + " 'payroll_exception' AND entity_key = ? AND reason = 'Bank"
                                    + " account entered on M-01'",
                                String.class,
                                String.valueOf(id)))
                .isEqualTo("it.producer");
    }

    @Test
    void anotherOfficeNeitherSeesNorActsOnTheItem() throws Exception {
        Long id = raise("WARNING", "G2", "ENT", "x");
        assertThat(row(login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001"), id))
                .isNull();
        mvc.perform(
                        userPost(
                                login("ddo.kolkata@fci-demo.test", "PAYROLL_CHECKER", "KL001"),
                                "/v1/exceptions/" + id + "/accept",
                                Map.of("reason", "not mine")))
                .andExpect(status().isNotFound());
    }

    @Test
    void SRS_G_13_theExportIsCsvAndNeutralisesFormulae() throws Exception {
        raise("WARNING", "G2", "ENT", "=HYPERLINK(\"http://x\")");
        String csv =
                csv(
                        login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001"),
                        "/v1/exceptions/export");
        assertThat(csv).startsWith("\"Id\",\"Module\"");
        assertThat(csv)
                .contains("\"'=HYPERLINK(\"\"http://x\"\")\"")
                .doesNotContain(",\"=HYPERLINK");
    }

    @Test
    void SRS_G_9_aCycleLevelExceptionHasNoMemberAndIsActedOnInTheCyclesUnit() throws Exception {
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = '202403'"
                                + " AND org_unit_id = ?",
                        Integer.class,
                        unit)
                == 0) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                            + " fin_year, cycle_status, trigger_type, initiated_by) VALUES"
                            + " ('202403', ?, 'DO', '2023-24', 'INITIATED', 'ON_DEMAND',"
                            + " 'it.fixture')",
                    unit);
        }
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " '202403' AND org_unit_id = ?",
                        Long.class,
                        unit);
        Long id =
                worklist.raise(
                        "it.producer",
                        new ExceptionWorklist.NewEntry(
                                cycle,
                                null,
                                "C-02",
                                code,
                                "WARNING",
                                "G2",
                                null,
                                "payroll_cycle_instance",
                                "input_cutoff",
                                null,
                                "C-02",
                                "Attendance not received for the cycle"));
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        JsonNode item = row(checker, id);
        assertThat(item).isNotNull();
        assertThat(item.get("eis").isNull()).isTrue();
        assertThat(item.get("fieldSource").isNull()).isTrue();

        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + id + "/assign",
                                Map.of("owner", "PAYROLL_MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        // Assigning again keeps the status; only the owner changes.
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + id + "/assign",
                                Map.of("owner", "ESTABLISHMENT_HR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(
                        userPost(
                                checker,
                                "/v1/exceptions/" + id + "/accept",
                                Map.of("reason", "  Cycle runs on last month's attendance  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        // Another office does not see the cycle of DH001.
        assertThat(row(login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001"), id))
                .isNull();
    }

    @Test
    void SRS_G_9_noExceptionIsClosedWithoutACause() {
        assertThatThrownBy(() -> worklist.clear("it.producer", code, null, member, "x", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- by condition and filters

    private JsonNode groupOf(Session session, String query) throws Exception {
        JsonNode page =
                body(
                        mvc.perform(userGet(session, "/v1/exceptions/groups?size=100" + query))
                                .andExpect(status().isOk())
                                .andReturn());
        for (JsonNode g : page.get("items")) {
            if (code.equals(g.get("exceptionCode").asString())) {
                return g;
            }
        }
        return null;
    }

    /**
     * SRS-G-9, SRS l.634 "Count - Members affected": a condition about 25 members is one row with
     * count 25, whatever the page size; its items are a paged list of their own.
     */
    @Test
    void SRS_G_9_aConditionLargerThanOnePageIsOneRowCountingEveryMember() throws Exception {
        java.util.List<Long> members =
                jdbc.queryForList(
                        "SELECT e.employee_id FROM employee e JOIN org_location l ON l.location_id"
                                + " = e.location_id WHERE l.location_code = 'DH001' ORDER BY"
                                + " e.employee_id FETCH FIRST 25 ROWS ONLY",
                        Long.class);
        assertThat(members).hasSize(25);
        for (Long m : members) {
            worklist.raise(
                    "it.producer",
                    new ExceptionWorklist.NewEntry(
                            null,
                            m,
                            "C-02",
                            code,
                            "WARNING",
                            "G2",
                            null,
                            "employee",
                            "bank_account",
                            "ENT",
                            "M-01",
                            "Bank account missing"));
        }
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        JsonNode group = groupOf(maker, "");
        assertThat(group).isNotNull();
        assertThat(group.get("items").asLong()).isEqualTo(25);
        assertThat(group.get("members").asLong()).isEqualTo(25);
        assertThat(group.get("blocksG2").asBoolean()).as("a warning never blocks").isFalse();
        assertThat(group.get("actionable").asBoolean()).isTrue();
        assertThat(group.get("correctionScreen").asString()).isEqualTo("M-01");

        // The condition appears once over the pages of conditions, however small the page.
        int seen = 0;
        long total = Long.MAX_VALUE;
        for (int page = 0; page * 10L < total; page++) {
            JsonNode p =
                    body(
                            mvc.perform(
                                            userGet(
                                                    maker,
                                                    "/v1/exceptions/groups?size=10&page=" + page))
                                    .andExpect(status().isOk())
                                    .andReturn());
            total = p.get("total").asLong();
            for (JsonNode g : p.get("items")) {
                if (code.equals(g.get("exceptionCode").asString())) {
                    seen++;
                }
            }
        }
        assertThat(seen).isEqualTo(1);

        // Its items: 25 over two pages of 20.
        JsonNode items =
                body(
                        mvc.perform(userGet(maker, "/v1/exceptions?size=20&code=" + code))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(items.get("total").asLong()).isEqualTo(25);
        assertThat(items.get("items").size()).isEqualTo(20);
        assertThat(items.get("tiles").get("open").asLong()).isEqualTo(25);
        // The class filter: another module hides it, its own shows it; the classes are listed.
        assertThat(groupOf(maker, "&module=I-01")).isNull();
        assertThat(groupOf(maker, "&module=C-02")).isNotNull();
        JsonNode all = body(mvc.perform(userGet(maker, "/v1/exceptions/groups")).andReturn());
        assertThat(all.get("modules").toString()).contains("\"C-02\"");
    }

    /**
     * SRS l.604: filter by period (the cycle's month) and by an office of the session's scope; an
     * office outside it is not found; a malformed month or code is refused; no X-05 right, no list.
     */
    @Test
    void SRS_G_9_thePeriodAndOfficeFiltersNarrowTheWorklistWithinScope() throws Exception {
        Long unit =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'DH001'",
                        Long.class);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payroll_cycle_instance WHERE payroll_yymm = '202403'"
                                + " AND org_unit_id = ?",
                        Integer.class,
                        unit)
                == 0) {
            jdbc.update(
                    "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                            + " fin_year, cycle_status, trigger_type, initiated_by) VALUES"
                            + " ('202403', ?, 'DO', '2023-24', 'INITIATED', 'ON_DEMAND',"
                            + " 'it.fixture')",
                    unit);
        }
        Long cycle =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm ="
                                + " '202403' AND org_unit_id = ?",
                        Long.class,
                        unit);
        worklist.raise(
                "it.producer",
                new ExceptionWorklist.NewEntry(
                        cycle,
                        null,
                        "C-02",
                        code,
                        "WARNING",
                        "G2",
                        null,
                        "payroll_cycle_instance",
                        "input_cutoff",
                        null,
                        "C-02",
                        "Attendance not received for the cycle"));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(groupOf(maker, "&period=2024-03")).isNotNull();
        assertThat(groupOf(maker, "&period=2024-04")).as("one month off").isNull();
        assertThat(groupOf(maker, "&office=" + unit)).isNotNull();
        JsonNode offices =
                body(
                        mvc.perform(userGet(maker, "/v1/exceptions/offices"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(offices.findValuesAsString("code")).contains("DH001");

        Long kolkata =
                jdbc.queryForObject(
                        "SELECT location_id FROM org_location WHERE location_code = 'KL001'",
                        Long.class);
        mvc.perform(userGet(maker, "/v1/exceptions/groups?office=" + kolkata))
                .andExpect(status().isNotFound());
        mvc.perform(userGet(maker, "/v1/exceptions?office=" + kolkata))
                .andExpect(status().isNotFound());
        // Kolkata's own maker does not see the Delhi condition, with or without its office.
        Session other = login("maker.kolkata@fci-demo.test", "PAYROLL_MAKER", "KL001");
        assertThat(groupOf(other, "")).isNull();
        assertThat(groupOf(other, "&office=" + kolkata)).isNull();
        for (String bad : new String[] {"period=2024-13", "period=202403", "office=0"}) {
            mvc.perform(userGet(maker, "/v1/exceptions/groups?" + bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(userGet(maker, "/v1/exceptions?code=A%20B")).andExpect(status().isBadRequest());
        mvc.perform(
                        userGet(
                                login("tax.old@fci-demo.test", "EMPLOYEE_ESEVA", null),
                                "/v1/exceptions/groups"))
                .andExpect(status().isForbidden());
    }
}
