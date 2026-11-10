package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.HistoryTamperAttempt;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Module 03 - X-04 Audit Trail &amp; Change History on A-05 and the History button (SRS-20-2 … 29):
 * record history with outcome, maker and checker; as-of reconstruction; the Legacy Audit view
 * beside the native trail; enquiry and export logging; history never changed; retention as a
 * setting. Its own context (property below), so the settings it approves never reach other suites.
 */
@TestPropertySource(properties = "fci.it.isolated=audit-trail")
class AuditTrailIT extends ApiTestSupport {

    @Autowired private ApplicationEventPublisher events;

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session hqChecker() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    private Session zoneAdmin() throws Exception {
        return login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
    }

    private MvcResult get(Session s, String path) throws Exception {
        return mvc.perform(userGet(s, path)).andReturn();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static List<String> outcomes(JsonNode items, String field) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        items.forEach(
                i -> {
                    if (field.equals(i.path("field").asString())) {
                        out.add(i.path("outcome").asString());
                    }
                });
        return out;
    }

    private long securityEvents(String type) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM security_event WHERE event_type = ?",
                        Long.class,
                        type);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- record history and as-of

    @Test
    void SRS_20_29_anApprovedSettingShowsItsHistoryWithOutcomeMakerCheckerAndItsVersionAsOf()
            throws Exception {
        Session admin = admin();
        MvcResult proposed =
                mvc.perform(
                                userPut(
                                        admin,
                                        "/v1/admin/parameters/DO_MINIMUM_STAFF",
                                        Map.of("value", 3, "reason", "IT: unit minimum")))
                        .andReturn();
        assertThat(proposed.getResponse().getStatus()).isEqualTo(202);
        long approval = body(proposed).path("pending").path("approvalId").asLong();
        assertThat(decide(hqChecker(), approval, "APPROVE", "IT: agreed", null)).isEqualTo(200);

        MvcResult history =
                get(admin, "/v1/audit/history?entity=rule_version&key=DO_MINIMUM_STAFF");
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = body(history);
        assertThat(view.path("versioned").asBoolean()).isTrue();
        assertThat(view.path("screen").asString()).isEqualTo("A-07");
        assertThat(view.path("versions")).hasSize(1);
        assertThat(view.path("versions").get(0).path("values").path("scalar_value").asString())
                .isEqualTo("3");
        JsonNode items = view.path("items");
        // FAP-13 outcome of each row: the submission, the decision, the applied value.
        assertThat(outcomes(items, "EDIT")).containsExactly("SUBMITTED");
        assertThat(outcomes(items, "DECISION")).containsExactly("APPROVED");
        assertThat(outcomes(items, "scalar_value")).containsExactly("POSTED");
        JsonNode applied = null;
        for (JsonNode i : items) {
            if ("scalar_value".equals(i.path("field").asString())) {
                applied = i;
            }
        }
        assertThat(applied).isNotNull();
        assertThat(applied.path("oldValue").isNull()).isTrue();
        assertThat(applied.path("newValue").asString()).isEqualTo("3");
        assertThat(applied.path("changeType").asString()).isEqualTo("INITIAL_VALUE");
        assertThat(applied.path("approvalId").asLong()).isEqualTo(approval);
        assertThat(applied.path("approvalStatus").asString()).isEqualTo("APPROVED");
        assertThat(applied.path("maker").path("loginId").asString())
                .isEqualTo("sysadmin@fci-demo.test");
        assertThat(applied.path("checker").path("loginId").asString())
                .isEqualTo("checker.hq@fci-demo.test");
        assertThat(applied.path("maker").path("name").asString()).isNotBlank();
        assertThat(applied.path("origin").asString()).isEqualTo("NATIVE");
        assertThat(applied.path("transactionId").asLong()).isPositive();

        LocalDate from =
                LocalDate.parse(view.path("versions").get(0).path("effectiveFrom").asString());
        JsonNode asOf =
                body(
                        get(
                                admin,
                                "/v1/audit/history/as-of?entity=rule_version&key=DO_MINIMUM_STAFF"
                                        + "&date="
                                        + from));
        assertThat(asOf.path("status").asString()).isEqualTo("FOUND");
        assertThat(asOf.path("versionNo").asInt()).isEqualTo(1);
        assertThat(asOf.path("values").path("scalar_value").asString()).isEqualTo("3");
        JsonNode before =
                body(
                        get(
                                admin,
                                "/v1/audit/history/as-of?entity=rule_version&key=DO_MINIMUM_STAFF"
                                        + "&date="
                                        + from.minusDays(1)));
        assertThat(before.path("status").asString()).isEqualTo("NOT_IN_FORCE");

        // Change History Report and Point-in-Time Extract, each logged (SRS-20-24).
        String report =
                csv(admin, "/v1/audit/history/export?entity=rule_version&key=DO_MINIMUM_STAFF");
        assertThat(report).startsWith("\"origin\",\"transaction_id\"");
        assertThat(report).contains("\"NATIVE\"", "\"scalar_value\"", "\"POSTED\"");
        String extract =
                csv(
                        admin,
                        "/v1/audit/history/as-of/export?entity=rule_version&key=DO_MINIMUM_STAFF"
                                + "&date="
                                + from);
        assertThat(extract).contains("\"FOUND\"", "\"scalar_value\"", "\"3\"");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name = 'export'"
                                        + " AND field_name = 'EXPORT' AND entity_key IN"
                                        + " ('change-history', 'point-in-time') AND changed_by = ?",
                                Long.class,
                                "sysadmin@fci-demo.test"))
                .isGreaterThanOrEqualTo(2);
    }

    /** One record of every type: its history opens and its version is found as of a date. */
    // SC-03-0010: the audit process covers every record type of every screen.
    @Test
    void SRS_20_3_everyRecordTypeOpensItsHistoryAndTheVersionInForceOnADate() throws Exception {
        Session admin = admin();
        String route =
                jdbc.queryForObject(
                        "SELECT action_code || '@' || office_level FROM approval_workflow_action"
                            + " WHERE action_status = 'ACTIVE' AND effective_to IS NULL ORDER BY"
                            + " action_code FETCH FIRST 1 ROWS ONLY",
                        String.class);
        String group =
                jdbc.queryForObject(
                        "SELECT MIN(group_code) FROM access_group WHERE effective_to IS NULL",
                        String.class);
        String value =
                jdbc.queryForObject(
                        "SELECT l.list_code || '.' || v.value_code FROM reference_value v JOIN"
                                + " reference_list l ON l.list_id = v.list_id ORDER BY v.value_id"
                                + " FETCH FIRST 1 ROWS ONLY",
                        String.class);
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        // type, key, a day it is in force, a field of its values
        String[][] records = {
            {"rate_set", "DA_IDA", "2026-05-01", "rate_set_name"},
            {"other_parameter", "NPA_PCT", "2026-05-01", "percent_value"},
            {"gl_account_mapping", "101", "2026-05-01", "natural_account"},
            {"approval_workflow_action", route, today, "level_count"},
            {"access_group", group, today, "group_name"},
            {"reference_value", value, today, "value_label_en"},
        };
        for (String[] r : records) {
            String q = "entity=" + r[0] + "&key=" + r[1];
            MvcResult history = get(admin, "/v1/audit/history?" + q);
            assertThat(history.getResponse().getStatus())
                    .as(r[0] + " " + r[1] + " " + history.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(body(history).path("key").asString()).as(r[0]).isEqualTo(r[1]);
            assertThat(body(history).path("screen").asString()).as(r[0]).isNotBlank();
            JsonNode asOf = body(get(admin, "/v1/audit/history/as-of?" + q + "&date=" + r[2]));
            assertThat(asOf.path("status").asString()).as(r[0]).isEqualTo("FOUND");
            assertThat(asOf.path("values").has(r[3])).as(r[0] + " " + r[3]).isTrue();
        }
        // Versioned records were not in force before their first version.
        JsonNode before =
                body(
                        get(
                                admin,
                                "/v1/audit/history/as-of?entity=rate_set&key=DA_IDA&date=2000-01-01"));
        assertThat(before.path("status").asString()).isEqualTo("NOT_IN_FORCE");
        assertThat(
                        body(get(
                                        admin,
                                        "/v1/audit/history/as-of?entity=gl_account_mapping&key=101&date=2000-01-01"))
                                .path("status")
                                .asString())
                .isEqualTo("NOT_IN_FORCE");
    }

    /** Each record type refuses a key it cannot resolve (malformed or unknown): 404, never 500. */
    @Test
    void SC_03_0016_everyRecordTypeRefusesAKeyItCannotResolve() throws Exception {
        Session admin = admin();
        String[][] keys = {
            {"rate_set", "NO_SUCH_SET"},
            {"other_parameter", "NO_SUCH_PARAM"},
            {"gl_account_mapping", "not-a-code"},
            {"gl_account_mapping", "999999"},
            {"approval_workflow_action", "NO_AT_SIGN"},
            {"approval_workflow_action", "A07_EDIT@MARS"},
            {"approval_workflow_action", "NO_SUCH_ACTION@HQ"},
            {"access_group", "AG-NO-SUCH"},
            {"reference_value", "NODOT"},
            {"reference_value", ".CODE"},
            {"reference_value", "LIST."},
            {"reference_value", "NO_SUCH_LIST.X"},
            {"reference_value", "PAY_PATTERN.NO_SUCH_CODE"},
            {"rule_version", "NO_SUCH_SETTING"},
            {"employee", "000000"},
            {"net_pay_floor_rule", "NO_SUCH_TYPE"},
            {"allowance_ceiling_norm", "NO_SUCH_CLAIM"},
            {"operation_master", "NOOP"},
            {"circular_order", "not-an-id"},
            {"circular_order", "999999999"},
            {"label_master", "not-an-id"},
            {"label_master", "999999999"},
        };
        for (String[] k : keys) {
            MvcResult r = get(admin, "/v1/audit/history?entity=" + k[0] + "&key=" + k[1]);
            assertThat(r.getResponse().getStatus()).as(k[0] + " " + k[1]).isEqualTo(404);
            assertThat(errorCode(r)).as(k[0] + " " + k[1]).isEqualTo("AUD-404-RECORD");
        }
    }

    @Test
    void SC_03_0016_theRecordKeyMustResolveToAMasterOrRuleRecord() throws Exception {
        Session admin = admin();
        JsonNode types = body(get(admin, "/v1/audit/record-types"));
        List<String> names = new java.util.ArrayList<>();
        types.forEach(t -> names.add(t.asString()));
        assertThat(names)
                .contains(
                        "access_group",
                        "approval_workflow_action",
                        "employee",
                        "gl_account_mapping",
                        "other_parameter",
                        "rate_set",
                        "reference_value",
                        "rule_version");
        MvcResult unknownType = get(admin, "/v1/audit/history?entity=no_such_table&key=1");
        assertThat(unknownType.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(unknownType)).isEqualTo("AUD-404-RECORD");
        MvcResult unknownKey = get(admin, "/v1/audit/history?entity=employee&key=NO-SUCH-EIS");
        assertThat(unknownKey.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(unknownKey)).isEqualTo("AUD-404-RECORD");
        MvcResult malformed = get(admin, "/v1/audit/history?entity=Bad-Type&key=1");
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        MvcResult noDate =
                get(admin, "/v1/audit/history/as-of?entity=rule_version&key=OTP_VALIDITY");
        assertThat(noDate.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void SRS_20_29_theHistoryButtonOpensWithTheRecordsOwnScreenAndOnlyInScope() throws Exception {
        // The Payroll Maker holds M-01 VIEW, not A-05: the employee's History button opens...
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(
                        get(maker, "/v1/audit/history?entity=employee&key=104882")
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        // ...but not X-04's record list, nor a record of a screen the role does not hold.
        // OD-125 (Wireframes v6.1 role access, V078_4): X-04 Audit Trail (built A-05) is drawn for
        // the Payroll Maker; WF-071 for FCI.
        assertThat(get(maker, "/v1/audit/record-types").getResponse().getStatus()).isEqualTo(200);
        // The audit viewer reads the history of any record of its scope, a rule version included
        // (X-04 drawn for the Maker, OD-125); the record key must still resolve (below).
        MvcResult setting = get(maker, "/v1/audit/history?entity=rule_version&key=OTP_VALIDITY");
        assertThat(setting.getResponse().getStatus()).isEqualTo(200);
        // A zone administrator: an employee outside its zone reads exactly as an unknown record
        // (404, the same code), so its existence is not disclosed (OWASP A01).
        String elsewhere =
                jdbc.queryForObject(
                        "SELECT MIN(e.emp_num) FROM employee e JOIN org_location l ON l.location_id"
                                + " = e.location_id JOIN org_zone z ON z.zone_id = l.zone_id WHERE"
                                + " z.zone_code <> 'NA11'",
                        String.class);
        assertThat(elsewhere).isNotNull();
        MvcResult outside = get(zoneAdmin(), "/v1/audit/history?entity=employee&key=" + elsewhere);
        assertThat(outside.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(outside)).isEqualTo("AUD-404-RECORD");
        MvcResult unknown = get(zoneAdmin(), "/v1/audit/history?entity=employee&key=999999");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(unknown)).isEqualTo(errorCode(outside));
    }

    @Test
    void SRS_20_3_aRecordKeptAsOneRowIsRebuiltAsOfADateFromItsFieldRows() throws Exception {
        String current =
                jdbc.queryForObject(
                        "SELECT mobile_number FROM employee WHERE emp_num = '109603'",
                        String.class);
        // A change effective 2026-01-01 (as an approved M-01 change writes it).
        jdbc.update(
                "INSERT INTO audit_event (entity_name, entity_key, field_name, old_value,"
                        + " new_value, changed_by, changed_at, effective_date, reason, change_type)"
                        + " VALUES ('employee', '109603', 'mobileNumber', '9000099999', ?,"
                        + " 'checker.hq@fci-demo.test', TIMESTAMP '2026-01-01 10:00:00', DATE"
                        + " '2026-01-01', 'IT: new number', 'CHANGE')",
                current);
        Session admin = admin();
        JsonNode before =
                body(
                        get(
                                admin,
                                "/v1/audit/history/as-of?entity=employee&key=109603"
                                        + "&date=2025-12-31"));
        assertThat(before.path("status").asString()).isEqualTo("FOUND");
        assertThat(before.path("versioned").asBoolean()).isFalse();
        assertThat(before.path("values").path("mobileNumber").asString()).isEqualTo("9000099999");
        JsonNode after =
                body(
                        get(
                                admin,
                                "/v1/audit/history/as-of?entity=employee&key=109603"
                                        + "&date=2026-01-01"));
        assertThat(after.path("values").path("mobileNumber").asString()).isEqualTo(current);
        // The bank account is masked in the rebuilt record too (R-PII).
        assertThat(after.path("values").path("bankAccount").asString()).startsWith("*");
    }

    // ---------------------------------------------------------------- Legacy Audit view

    @Test
    void
            SRS_20_23_SRS_20_5a_BR_AUDIT_008_legacyRowsAreListedApartMarkedLegacyScopedAndEveryEnquiryIsLogged()
                    throws Exception {
        Session admin = admin();
        JsonNode national = body(get(admin, "/v1/audit/legacy?entity=employee&key=104882"));
        assertThat(national.path("total").asLong()).isEqualTo(4);
        national.path("items")
                .forEach(r -> assertThat(r.path("origin").asString()).isEqualTo("LEGACY"));
        assertThat(texts(national.path("items"), "sourceObject"))
                .contains("AUDIT_PAY_EMP_MAST", "PAY_EMP_BASIC_HIST", "DDL_AUDIT");
        JsonNode snapshot = null;
        for (JsonNode r : national.path("items")) {
            if ("PAY_EMP_BASIC_HIST".equals(r.path("sourceObject").asString())) {
                snapshot = r;
            }
        }
        assertThat(snapshot).isNotNull();
        // A *_HIST row is a row snapshot, not split into field-level before / after rows.
        assertThat(snapshot.path("grain").asString()).isEqualTo("ROW_IMAGE");
        assertThat(snapshot.path("rowImage").asString()).contains("\"BASIC\":49500");
        assertThat(snapshot.path("fieldName").isNull()).isTrue();

        // Within the zone: the member's rows, never the national (unresolved) DDL_AUDIT row.
        JsonNode zone = body(get(zoneAdmin(), "/v1/audit/legacy?entity=employee&key=104882"));
        assertThat(zone.path("total").asLong()).isEqualTo(3);
        assertThat(texts(zone.path("items"), "sourceObject")).doesNotContain("DDL_AUDIT");

        // SRS-20-24: each enquiry logged with the requester, the scope and the time.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'legacy_audit' AND field_name = 'ENQUIRY' AND"
                                        + " entity_key = 'employee/104882' AND new_value LIKE"
                                        + " 'office=%;groups=%'",
                                Long.class))
                .isGreaterThanOrEqualTo(2);

        // The export: every row marked LEGACY (never merged with native rows), and logged.
        String file = csv(admin, "/v1/audit/legacy/export?entity=employee&key=104882");
        assertThat(file).startsWith("\"origin\",\"legacy_source_table\"");
        assertThat(file.lines().skip(1).filter(l -> !l.isBlank()))
                .hasSize(4)
                .allMatch(l -> l.startsWith("\"LEGACY\""));

        // Read only through A-05: a Payroll Maker cannot open the Legacy Audit view.
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        // OD-125 (Wireframes v6.1 role access, V078_4): X-04 Audit Trail (built A-05) is drawn for
        // the Payroll Maker; WF-071 for FCI.
        assertThat(
                        get(maker, "/v1/audit/legacy?entity=employee&key=104882")
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- history is never changed

    // SC-03-0003: locked results and their history are never edited or deleted.
    @Test
    void SRS_20_19_anyRequestToChangeHistoryIsRefusedForEveryRoleAndLogged() throws Exception {
        Session admin = admin();
        long before = securityEvents("HISTORY_EDIT_ATTEMPT");
        MvcResult post =
                mvc.perform(userPost(admin, "/v1/audit/history", Map.of("reason", "x")))
                        .andReturn();
        assertThat(post.getResponse().getStatus()).isEqualTo(405);
        assertThat(errorCode(post)).isEqualTo("AUD-405-IMMUTABLE");
        // SRS-EH-5 (US-GAP-116): an attempted history-row edit is a Security condition.
        assertThat(body(post).get("severity").asString()).isEqualTo("SECURITY");
        MvcResult put =
                mvc.perform(userPut(admin, "/v1/audit/legacy", Map.of("reason", "x"))).andReturn();
        assertThat(put.getResponse().getStatus()).isEqualTo(405);
        MvcResult delete =
                mvc.perform(userRequest(admin, MockMvcRequestBuilders.delete("/v1/admin/audit")))
                        .andReturn();
        assertThat(delete.getResponse().getStatus()).isEqualTo(405);
        assertThat(securityEvents("HISTORY_EDIT_ATTEMPT")).isEqualTo(before + 3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM security_event WHERE event_type ="
                                        + " 'HISTORY_EDIT_ATTEMPT' AND target_entity ="
                                        + " 'legacy_audit' AND acting_user = ?",
                                Long.class,
                                "sysadmin@fci-demo.test"))
                .isPositive();
    }

    // BR-AUDIT-004: no role, the administrator included, edits or deletes a history row.
    /**
     * NEW-G1-10 (US-03-03 AC2): BR-AUDIT-006 "a saved work-slip may never be deleted"; the database
     * refuses the delete of a saved slip (trg_work_slip_no_delete) and the slip stays; nothing in
     * the API deletes one (no DELETE endpoint, R-DEL).
     */
    @Test
    void BR_AUDIT_006_theDatabaseRefusesTheDeleteOfASavedWorkSlip() {
        Map<String, Object> gang =
                jdbc.queryForMap(
                        "SELECT gang_id, depot_location_id FROM labour_gang ORDER BY gang_id FETCH"
                                + " FIRST 1 ROWS ONLY");
        String number = "WS-AUD-" + (System.nanoTime() % 1_000_000);
        jdbc.update(
                "INSERT INTO work_slip (work_slip_number, engine_code, gang_id, depot_location_id,"
                        + " work_slip_date, slip_status, created_by) VALUES (?, 'DEPT_LABOUR', ?,"
                        + " ?, ?, 'DRAFT', 'it')",
                number,
                gang.get("GANG_ID"),
                gang.get("DEPOT_LOCATION_ID"),
                java.sql.Date.valueOf(LocalDate.of(1999, 1, 4)));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM work_slip WHERE work_slip_number = ?", number))
                .hasStackTraceContaining("FCIPAY-WSL-001");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM work_slip WHERE work_slip_number = ?",
                                Integer.class,
                                number))
                .isEqualTo(1);
    }

    @Test
    void
            SRS_20_21_SRS_DM_8_SRS_DM_11_theDatabaseRefusesEveryChangeToTheAuditTrailAndTheLegacyAuditTable() {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET reason = 'tampered'"))
                .hasStackTraceContaining("FCIPAY-AUD-001");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event"))
                .hasStackTraceContaining("FCIPAY-AUD-001");
        assertThatThrownBy(() -> jdbc.update("UPDATE legacy_audit SET legacy_reason = 'x'"))
                .hasStackTraceContaining("FCIPAY-AUD-002");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM legacy_audit"))
                .hasStackTraceContaining("FCIPAY-AUD-002");
        // The refusal reaching a request is logged as a security event (AUDIT_TAMPER).
        long before = securityEvents("AUDIT_TAMPER");
        events.publishEvent(new HistoryTamperAttempt("FCIPAY-AUD-002", "PUT", "/v1/x"));
        assertThat(securityEvents("AUDIT_TAMPER")).isEqualTo(before + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT target_entity FROM security_event WHERE event_type ="
                                        + " 'AUDIT_TAMPER' ORDER BY security_event_id DESC FETCH"
                                        + " FIRST 1 ROWS ONLY",
                                String.class))
                .isEqualTo("legacy_audit");
    }

    // SRS-EH-10: a controlled change whose audit row fails is rolled back and refused.
    @Test
    void SRS_20_18_aChangeWhoseAuditRowCannotBeWrittenIsRolledBackWithItsApproval()
            throws Exception {
        Session admin = admin();
        long current =
                jdbc.queryForObject(
                        "SELECT v.scalar_value FROM rule_version v JOIN rule_definition d ON"
                                + " d.rule_id = v.rule_id WHERE d.rule_key = 'PASSWORD_HISTORY' AND"
                                + " v.approval_state = 'APPROVED' AND v.effective_to IS NULL",
                        Long.class);
        MvcResult proposed =
                mvc.perform(
                                userPut(
                                        admin,
                                        "/v1/admin/parameters/PASSWORD_HISTORY",
                                        Map.of(
                                                "value",
                                                current == 24 ? 23 : current + 1,
                                                "reason",
                                                "IT: audit failure")))
                        .andReturn();
        assertThat(proposed.getResponse().getStatus()).isEqualTo(202);
        long approval = body(proposed).path("pending").path("approvalId").asLong();
        // The audit store refuses the applied value's row (failure injected for this test only).
        jdbc.execute(
                "ALTER TABLE audit_event ADD CONSTRAINT it_no_scalar_rows CHECK (NOT (field_name ="
                        + " 'scalar_value' AND entity_key = 'PASSWORD_HISTORY'))");
        try {
            assertThat(decide(hqChecker(), approval, "APPROVE", "IT", null))
                    .isGreaterThanOrEqualTo(400);
        } finally {
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT it_no_scalar_rows");
        }
        // Nothing persisted: the value in force, the proposal and its approval are as before.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT v.scalar_value FROM rule_version v JOIN rule_definition d"
                                        + " ON d.rule_id = v.rule_id WHERE d.rule_key ="
                                        + " 'PASSWORD_HISTORY' AND v.approval_state = 'APPROVED'"
                                        + " AND v.effective_to IS NULL",
                                Long.class))
                .isEqualTo(current);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .isEqualTo("PENDING_APPROVAL");
        // Once the audit store accepts rows again, the same approval goes through.
        assertThat(decide(hqChecker(), approval, "REJECT", "IT: not needed", null)).isEqualTo(200);
    }

    // ---------------------------------------------------------------- retention as a setting

    @Test
    void SRS_20_5_SRS_NF_29_retentionPeriodsAreSettingsNotGuessesAndChangeOnlyByApproval()
            throws Exception {
        Session admin = admin();
        JsonNode classes = all(admin, "/v1/admin/retention-classes");
        assertThat(texts(classes, "code")).containsExactly("LEGACY_AUDIT", "NATIVE_AUDIT");
        classes.forEach(
                c -> {
                    assertThat(c.path("months").isNull()).as("no period seeded").isTrue();
                    assertThat(c.path("confirmed").asBoolean()).isFalse();
                });

        for (Map<String, Object> bad :
                List.<Map<String, Object>>of(
                        retention(120, true, "Order"),
                        retention(null, false, "Order"),
                        retention(0, false, "Order"),
                        retention(100000, false, "Order"))) {
            MvcResult refused =
                    mvc.perform(userPut(admin, "/v1/admin/retention-classes/NATIVE_AUDIT", bad))
                            .andReturn();
            assertThat(refused.getResponse().getStatus()).as(bad.toString()).isEqualTo(422);
            assertThat(errorCode(refused)).isEqualTo("AUD-422-RETENTION");
        }
        // R-ASCII: a basis the US7ASCII database cannot hold is refused at the boundary.
        MvcResult hindi =
                mvc.perform(
                                userPut(
                                        admin,
                                        "/v1/admin/retention-classes/NATIVE_AUDIT",
                                        retention(120, false, "Order न")))
                        .andReturn();
        assertThat(hindi.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(hindi)).isEqualTo("COM-400-VALIDATION");
        MvcResult notHq =
                mvc.perform(
                                userPut(
                                        zoneAdmin(),
                                        "/v1/admin/retention-classes/NATIVE_AUDIT",
                                        retention(120, false, "Order")))
                        .andReturn();
        assertThat(notHq.getResponse().getStatus()).isEqualTo(403);

        // A-07 changes are not among the OTP-gated actions (R-OTP): no step-up here.
        MvcResult proposed =
                mvc.perform(
                                userPut(
                                        admin,
                                        "/v1/admin/retention-classes/NATIVE_AUDIT",
                                        retention(120, false, "FCI records policy (IT)")))
                        .andReturn();
        assertThat(proposed.getResponse().getStatus()).isEqualTo(202);
        long approval = body(proposed).path("pendingApprovalId").asLong();
        assertThat(approval).isPositive();
        // Nothing changes before approval; a second change waits.
        assertThat(body(proposed).path("months").isNull()).isTrue();
        MvcResult twice =
                mvc.perform(
                                userPut(
                                        admin,
                                        "/v1/admin/retention-classes/NATIVE_AUDIT",
                                        retention(240, false, "Other")))
                        .andReturn();
        assertThat(errorCode(twice)).isEqualTo("AUD-409-RETENTION-PENDING");
        // The maker cannot approve the own proposal; the HQ checker does.
        assertThat(decide(admin, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(hqChecker(), approval, "APPROVE", "IT: policy", null)).isEqualTo(200);

        JsonNode nativeClass = null;
        for (JsonNode c : all(admin, "/v1/admin/retention-classes")) {
            if ("NATIVE_AUDIT".equals(c.path("code").asString())) {
                nativeClass = c;
            }
        }
        assertThat(nativeClass).isNotNull();
        assertThat(nativeClass.path("months").asInt()).isEqualTo(120);
        assertThat(nativeClass.path("confirmed").asBoolean()).isTrue();
        assertThat(nativeClass.path("basis").asString()).isEqualTo("FCI records policy (IT)");
        // The applied change, field by field, with its approval (SRS-20-2).
        assertThat(
                        jdbc.queryForList(
                                "SELECT field_name FROM audit_event WHERE entity_name ="
                                        + " 'retention_class' AND entity_key = 'NATIVE_AUDIT' AND"
                                        + " approval_id = ? AND field_name <> 'DECISION'",
                                String.class,
                                approval))
                .contains("retention_months", "is_confirmed", "retention_basis");
    }

    private static Map<String, Object> retention(Integer months, boolean indefinite, String basis) {
        Map<String, Object> body = new HashMap<>();
        body.put("months", months);
        body.put("indefinite", indefinite);
        body.put("basis", basis);
        body.put("reason", "IT: retention");
        return body;
    }

    // ---------------------------------------------------------------- applied master changes

    @Test
    void SRS_20_2_anApprovedReferenceValueChangeWritesItsFieldsBeforeAndAfter() throws Exception {
        Session admin = admin();
        String code = "IT_AUD_" + (System.nanoTime() % 100000);
        Map<String, Object> value = new HashMap<>();
        value.put("code", code);
        value.put("labelEn", "IT Audit Office");
        value.put("sortOrder", 900);
        // A new value starts on a future day (a past day is refused, MST-422).
        value.put("effectiveFrom", "2090-01-01");
        value.put("reason", "IT: audit trail");
        MvcResult created =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/masters/reference-lists/ISSUING_AUTHORITY/values",
                                        value))
                        .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        assertThat(
                        decide(
                                hqChecker(),
                                body(created).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        Map<String, Object> relabel = new HashMap<>();
        relabel.put("labelEn", "IT Audit Office (North)");
        relabel.put("sortOrder", 900);
        relabel.put("reason", "IT: relabel");
        MvcResult edited =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/masters/reference-lists/ISSUING_AUTHORITY/values/"
                                                + code,
                                        relabel))
                        .andReturn();
        assertThat(edited.getResponse().getStatus()).isEqualTo(202);
        assertThat(
                        decide(
                                hqChecker(),
                                body(edited).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);

        JsonNode view =
                body(
                        get(
                                admin,
                                "/v1/audit/history?entity=reference_value&key=ISSUING_AUTHORITY."
                                        + code));
        assertThat(view.path("versioned").asBoolean()).isFalse();
        List<String> labels = new java.util.ArrayList<>();
        view.path("items")
                .forEach(
                        i -> {
                            if ("value_label_en".equals(i.path("field").asString())) {
                                labels.add(
                                        i.path("oldValue").asString("-")
                                                + " > "
                                                + i.path("newValue").asString());
                            }
                        });
        assertThat(labels)
                .containsExactly(
                        "- > IT Audit Office", "IT Audit Office > IT Audit Office (North)");
        // The relabel was recorded with the reason entered with it.
        assertThat(texts(view.path("items"), "reason")).contains("IT: relabel");
    }

    /**
     * SRS-20-2 (l.1207; X-04 field table l.1253-1259, US-GAP-129): every audit row names its Maker
     * and its Checker by login, name and Employee No. The HQ Checker (007910) carries the employee
     * number of its employee record; the System Administrator, a user without one, is named with no
     * employee number rather than a guessed one.
     */
    @Test
    void SRS_20_2_X04RowNamesTheMakerAndCheckerWithEmployeeNumber() throws Exception {
        Session admin = admin();
        String code = "IT_AUDE_" + (System.nanoTime() % 100000);
        Map<String, Object> value = new HashMap<>();
        value.put("code", code);
        value.put("labelEn", "IT Audit Employee No");
        value.put("sortOrder", 901);
        value.put("effectiveFrom", "2090-01-01");
        value.put("reason", "IT: maker and checker named");
        MvcResult created =
                mvc.perform(
                                userPost(
                                        admin,
                                        "/v1/masters/reference-lists/ISSUING_AUTHORITY/values",
                                        value))
                        .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        long approval = body(created).get("approvalId").asLong();
        assertThat(decide(hqChecker(), approval, "APPROVE", null, null)).isEqualTo(200);

        JsonNode view =
                body(
                        get(
                                admin,
                                "/v1/audit/history?entity=reference_value&key=ISSUING_AUTHORITY."
                                        + code));
        JsonNode applied = null;
        for (JsonNode i : view.path("items")) {
            if ("value_label_en".equals(i.path("field").asString())) {
                applied = i;
            }
        }
        assertThat(applied).as("the applied label row").isNotNull();
        assertThat(applied.path("approvalId").asLong()).isEqualTo(approval);
        JsonNode maker = applied.path("maker");
        JsonNode checker = applied.path("checker");
        assertThat(maker.path("loginId").asString()).isEqualTo("sysadmin@fci-demo.test");
        assertThat(maker.path("name").asString()).isEqualTo("System Administrator");
        assertThat(
                        maker.path("employeeNumber").isNull()
                                || maker.path("employeeNumber").isMissingNode())
                .as("a user without an employee record has no employee number")
                .isTrue();
        assertThat(checker.path("loginId").asString()).isEqualTo("checker.hq@fci-demo.test");
        assertThat(checker.path("name").asString()).isNotBlank();
        assertThat(checker.path("employeeNumber").asString())
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT e.emp_num FROM app_user u JOIN employee e ON"
                                        + " e.employee_id = u.employee_id WHERE u.login_id = ?",
                                String.class,
                                "checker.hq@fci-demo.test"))
                .isEqualTo("007910");
    }
}
