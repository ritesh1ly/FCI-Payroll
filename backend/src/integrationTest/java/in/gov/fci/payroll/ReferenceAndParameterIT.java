package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * M-16 reference lists and A-07 system parameters under maker-checker (R-MC, R-RULE): the System
 * Administrator at Headquarters proposes, the HQ Payroll Checker decides; a system list's codes are
 * fixed by the platform; a value is never deleted; one change per value awaits the checker at a
 * time. A-07 values stay within their bounds and an unchanged value is refused. System parameters
 * are only proposed and rejected here, so the settings other tests rely on never change.
 */
class ReferenceAndParameterIT extends ApiTestSupport {

    private static final String LIST = "ISSUING_AUTHORITY";

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session hqChecker() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    private MvcResult send(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPost(s, path, body)).andReturn();
    }

    private MvcResult put(Session s, String path, Object body) throws Exception {
        return mvc.perform(userPut(s, path, body)).andReturn();
    }

    private static int code(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).path("errorCode").asString();
    }

    private static Map<String, Object> newValue(String code, String label, String from) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("labelEn", label);
        m.put("sortOrder", 900);
        m.put("effectiveFrom", from);
        m.put("reason", "IT: new issuing authority");
        return m;
    }

    private JsonNode list(Session s, String code) throws Exception {
        JsonNode all =
                body(
                        mvc.perform(userGet(s, "/v1/masters/reference-lists"))
                                .andExpect(status().isOk())
                                .andReturn());
        for (JsonNode l : all) {
            if (l.get("code").asString().equals(code)) {
                return l;
            }
        }
        throw new AssertionError("no list " + code);
    }

    private static JsonNode value(JsonNode list, String code) {
        for (JsonNode v : list.get("values")) {
            if (v.get("code").asString().equals(code)) {
                return v;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- M-16

    @Test
    void M_16_aNewValueIsAddedOnlyOnceTheCheckerApproves() throws Exception {
        Session admin = admin();
        String code = "IT_AUTH_" + (System.nanoTime() % 100000);
        MvcResult proposed =
                send(
                        admin,
                        "/v1/masters/reference-lists/" + LIST + "/values",
                        newValue(code, "IT Zonal Office", "2090-01-01"));
        assertThat(code(proposed)).isEqualTo(202);
        long approval = body(proposed).get("approvalId").asLong();
        JsonNode before = list(admin, LIST);
        assertThat(value(before, code)).isNull();
        assertThat(texts(before.get("pendingNew"), "code")).contains(code);
        // The same code cannot be proposed twice while the first awaits the checker.
        MvcResult twice =
                send(
                        admin,
                        "/v1/masters/reference-lists/" + LIST + "/values",
                        newValue(code, "IT Zonal Office 2", "2090-01-01"));
        assertThat(errorCode(twice)).isEqualTo("MST-409-PENDING");
        // The maker cannot decide the own proposal.
        assertThat(decide(admin, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(hqChecker(), approval, "APPROVE", null, null)).isEqualTo(200);

        JsonNode added = value(list(admin, LIST), code);
        assertThat(added).isNotNull();
        assertThat(added.get("labelEn").asString()).isEqualTo("IT Zonal Office");
        assertThat(added.get("active").asBoolean()).as("starts in 2090").isFalse();
        // The code is now taken.
        MvcResult taken =
                send(
                        admin,
                        "/v1/masters/reference-lists/" + LIST + "/values",
                        newValue(code, "Again", "2090-01-01"));
        assertThat(code(taken)).isEqualTo(409);
        assertThat(errorCode(taken)).isEqualTo("MST-409-CODE");

        // Relabel then end-date: each an approved EDIT; the value stays on record.
        Map<String, Object> relabel = new HashMap<>();
        relabel.put("labelEn", "IT Zonal Office (North)");
        relabel.put("sortOrder", 900);
        relabel.put("reason", "IT: relabel");
        MvcResult edit =
                send(admin, "/v1/masters/reference-lists/" + LIST + "/values/" + code, relabel);
        assertThat(code(edit)).isEqualTo(202);
        assertThat(value(list(admin, LIST), code).path("pending").path("labelEn").asString())
                .isEqualTo("IT Zonal Office (North)");
        assertThat(
                        decide(
                                hqChecker(),
                                body(edit).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        JsonNode relabelled = value(list(admin, LIST), code);
        assertThat(relabelled.get("labelEn").asString()).isEqualTo("IT Zonal Office (North)");
        assertThat(relabelled.get("versionNo").asInt())
                .isGreaterThan(added.get("versionNo").asInt());

        // An identical change is refused; an end before the first day of use is refused.
        assertThat(
                        errorCode(
                                send(
                                        admin,
                                        "/v1/masters/reference-lists/" + LIST + "/values/" + code,
                                        relabel)))
                .isEqualTo("COM-400-VALIDATION");
        Map<String, Object> badEnd = new HashMap<>(relabel);
        badEnd.put("effectiveTo", "2089-12-31");
        assertThat(
                        errorCode(
                                send(
                                        admin,
                                        "/v1/masters/reference-lists/" + LIST + "/values/" + code,
                                        badEnd)))
                .isEqualTo("COM-400-VALIDATION");
        Map<String, Object> end = new HashMap<>(relabel);
        end.put("effectiveTo", "2095-12-31");
        MvcResult ended =
                send(admin, "/v1/masters/reference-lists/" + LIST + "/values/" + code, end);
        assertThat(
                        decide(
                                hqChecker(),
                                body(ended).get("approvalId").asLong(),
                                "REJECT",
                                "Keep it",
                                null))
                .isEqualTo(200);
        assertThat(value(list(admin, LIST), code).get("effectiveTo").isNull()).isTrue();
    }

    @Test
    void M_16_aSystemListUnknownListOrBadValueIsRefused() throws Exception {
        Session admin = admin();
        MvcResult system =
                send(
                        admin,
                        "/v1/masters/reference-lists/PAY_PATTERN/values",
                        newValue("XYZ", "New pattern", "2090-01-01"));
        assertThat(code(system)).isEqualTo(422);
        assertThat(errorCode(system)).isEqualTo("MST-422-SYSTEM-LIST");
        assertThat(
                        errorCode(
                                send(
                                        admin,
                                        "/v1/masters/reference-lists/NO_SUCH_LIST/values",
                                        newValue("A1", "x", "2090-01-01"))))
                .isEqualTo("MST-404-CATALOGUE");
        // A value that starts in the past, or ends before it starts.
        assertThat(
                        errorCode(
                                send(
                                        admin,
                                        "/v1/masters/reference-lists/" + LIST + "/values",
                                        newValue("IT_PAST", "Past", "2020-01-01"))))
                .isEqualTo("COM-400-VALIDATION");
        Map<String, Object> inverted = newValue("IT_INV", "Inverted", "2090-06-01");
        inverted.put("effectiveTo", "2090-01-01");
        assertThat(
                        errorCode(
                                send(
                                        admin,
                                        "/v1/masters/reference-lists/" + LIST + "/values",
                                        inverted)))
                .isEqualTo("COM-400-VALIDATION");
        // Bean validation: lower-case code, no reason, order out of range.
        assertThat(
                        code(
                                send(
                                        admin,
                                        "/v1/masters/reference-lists/" + LIST + "/values",
                                        newValue("lower", "x", "2090-01-01"))))
                .isEqualTo(400);
        Map<String, Object> noReason = newValue("IT_NR", "x", "2090-01-01");
        noReason.remove("reason");
        assertThat(code(send(admin, "/v1/masters/reference-lists/" + LIST + "/values", noReason)))
                .isEqualTo(400);
        Map<String, Object> order = newValue("IT_ORD", "x", "2090-01-01");
        order.put("sortOrder", 100_000);
        assertThat(code(send(admin, "/v1/masters/reference-lists/" + LIST + "/values", order)))
                .isEqualTo(400);
        // An unknown value cannot be changed.
        Map<String, Object> change = new HashMap<>();
        change.put("labelEn", "x");
        change.put("reason", "IT");
        assertThat(
                        errorCode(
                                send(
                                        admin,
                                        "/v1/masters/reference-lists/"
                                                + LIST
                                                + "/values/NO_SUCH_VALUE",
                                        change)))
                .isEqualTo("MST-404-CATALOGUE");
    }

    @Test
    void M_16_onlyTheHeadquartersAdministratorProposes() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(code(mvc.perform(userGet(maker, "/v1/masters/reference-lists")).andReturn()))
                .isEqualTo(403);
        assertThat(
                        code(
                                send(
                                        maker,
                                        "/v1/masters/reference-lists/" + LIST + "/values",
                                        newValue("IT_MK", "x", "2090-01-01"))))
                .isEqualTo(403);
        // The checker reads the lists to decide but proposes nothing.
        Session checker = hqChecker();
        assertThat(
                        code(
                                send(
                                        checker,
                                        "/v1/masters/reference-lists/" + LIST + "/values",
                                        newValue("IT_CK", "x", "2090-01-01"))))
                .isEqualTo(403);
    }

    // ---------------------------------------------------------------- A-07

    private JsonNode parameter(Session s, String code) throws Exception {
        JsonNode all = all(s, "/v1/admin/parameters");
        for (JsonNode p : all) {
            if (p.get("code").asString().equals(code)) {
                return p;
            }
        }
        throw new AssertionError("no parameter " + code);
    }

    @Test
    void A_07_aParameterChangeStaysWithinItsBoundsAndWaitsForTheChecker() throws Exception {
        Session admin = admin();
        JsonNode retro = parameter(admin, "MAX_RETRO_PERIOD");
        long value = retro.get("value").asLong();
        long min = retro.get("minValue").asLong();
        long max = retro.get("maxValue").asLong();
        String path = "/v1/admin/parameters/MAX_RETRO_PERIOD";

        MvcResult above = put(admin, path, Map.of("value", max + 1, "reason", "IT"));
        assertThat(code(above)).isEqualTo(422);
        assertThat(errorCode(above)).isEqualTo("SYS-422-OUT-OF-RANGE");
        assertThat(errorCode(put(admin, path, Map.of("value", min - 1, "reason", "IT"))))
                .isEqualTo("SYS-422-OUT-OF-RANGE");
        assertThat(errorCode(put(admin, path, Map.of("value", value, "reason", "IT"))))
                .isEqualTo("SYS-422-UNCHANGED");
        assertThat(
                        code(
                                put(
                                        admin,
                                        "/v1/admin/parameters/NO_SUCH_PARAMETER",
                                        Map.of("value", 1, "reason", "IT"))))
                .isEqualTo(404);
        assertThat(code(put(admin, path, Map.of("reason", "IT")))).isEqualTo(400);
        assertThat(
                        code(
                                put(
                                        admin,
                                        "/v1/admin/parameters/bad-code",
                                        Map.of("value", 1, "reason", "IT"))))
                .isEqualTo(400);

        long other = value > min ? value - 1 : value + 1;
        MvcResult proposed = put(admin, path, Map.of("value", other, "reason", "IT: proposal"));
        assertThat(code(proposed)).isEqualTo(202);
        JsonNode view = body(proposed);
        // Not in force until approved: the value is unchanged, the proposal pending.
        assertThat(view.get("value").asLong()).isEqualTo(value);
        assertThat(view.path("pending").path("value").asLong()).isEqualTo(other);
        long approval = view.path("pending").path("approvalId").asLong();
        assertThat(decide(admin, approval, "APPROVE", null, null)).isEqualTo(403);
        assertThat(decide(hqChecker(), approval, "REJECT", "Not now", null)).isEqualTo(200);
        JsonNode after = parameter(admin, "MAX_RETRO_PERIOD");
        assertThat(after.get("value").asLong()).isEqualTo(value);
        assertThat(after.get("pending").isNull()).isTrue();
    }

    @Test
    void A_07_payrollStaffNeitherSeeNorChangeSystemParameters() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(code(mvc.perform(userGet(maker, "/v1/admin/parameters")).andReturn()))
                .isEqualTo(403);
        assertThat(
                        code(
                                put(
                                        maker,
                                        "/v1/admin/parameters/MAX_RETRO_PERIOD",
                                        Map.of("value", 1, "reason", "IT"))))
                .isEqualTo(403);
        // The checker sees them (to decide) but proposes nothing.
        Session checker = hqChecker();
        assertThat(code(mvc.perform(userGet(checker, "/v1/admin/parameters")).andReturn()))
                .isEqualTo(200);
        assertThat(
                        code(
                                put(
                                        checker,
                                        "/v1/admin/parameters/MAX_RETRO_PERIOD",
                                        Map.of("value", 1, "reason", "IT"))))
                .isEqualTo(403);
    }

    // ---------------------------------------------------------------- M-16 Hindi labels

    private String hindiLabel(String code, String status) {
        java.util.List<String> rows =
                jdbc.queryForList(
                        "SELECT m.label_text FROM label_master m JOIN reference_value v ON"
                            + " m.master_code = CAST(v.value_id AS VARCHAR) JOIN reference_list l"
                            + " ON l.list_id = v.list_id WHERE m.label_type = 'MASTER_VALUE' AND"
                            + " m.master_entity = 'REFERENCE_VALUE' AND m.language_code = 'HI' AND"
                            + " m.label_status = ? AND l.list_code = ? AND v.value_code = ?",
                        String.class,
                        status,
                        LIST,
                        code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void SRS_G_40_aHindiLabelIsAddedChangedAndWithdrawnUnderMakerChecker() throws Exception {
        Session admin = admin();
        String code = "IT_HI_" + (System.nanoTime() % 100000);
        Map<String, Object> created =
                newValue(
                        code,
                        "IT Regional Office",
                        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString());
        created.put("labelHi", "\u0915\u094D\u0937\u0947\u0924\u094D\u0930");
        MvcResult proposed =
                send(admin, "/v1/masters/reference-lists/" + LIST + "/values", created);
        assertThat(
                        decide(
                                hqChecker(),
                                body(proposed).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(hindiLabel(code, "ACTIVE"))
                .isEqualTo("\u0915\u094D\u0937\u0947\u0924\u094D\u0930");
        JsonNode value = value(list(admin, LIST), code);
        assertThat(value.get("labelHi").asString())
                .isEqualTo("\u0915\u094D\u0937\u0947\u0924\u094D\u0930");
        assertThat(value.get("active").asBoolean()).isTrue();

        // Changed the same day: the active label is corrected in place.
        Map<String, Object> sameDay = new HashMap<>();
        sameDay.put("labelEn", "IT Regional Office");
        sameDay.put("labelHi", "\u0915\u093E\u0930\u094D\u092F\u093E\u0932\u092F");
        sameDay.put("sortOrder", 900);
        sameDay.put("reason", "IT Hindi");
        MvcResult corrected =
                send(admin, "/v1/masters/reference-lists/" + LIST + "/values/" + code, sameDay);
        assertThat(
                        decide(
                                hqChecker(),
                                body(corrected).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(hindiLabel(code, "ACTIVE"))
                .isEqualTo("\u0915\u093E\u0930\u094D\u092F\u093E\u0932\u092F");

        // A label in force since earlier days is superseded, never overwritten.
        jdbc.update(
                "UPDATE label_master SET effective_from = effective_from - 5 WHERE label_status ="
                        + " 'ACTIVE' AND label_text ="
                        + " '\u0915\u093E\u0930\u094D\u092F\u093E\u0932\u092F' AND label_type ="
                        + " 'MASTER_VALUE'");
        Map<String, Object> later = new HashMap<>(sameDay);
        later.put(
                "labelHi",
                "\u0915\u094D\u0937\u0947\u0924\u094D\u0930"
                        + " \u0915\u093E\u0930\u094D\u092F\u093E\u0932\u092F");
        MvcResult superseded =
                send(admin, "/v1/masters/reference-lists/" + LIST + "/values/" + code, later);
        assertThat(
                        decide(
                                hqChecker(),
                                body(superseded).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(hindiLabel(code, "ACTIVE"))
                .isEqualTo(
                        "\u0915\u094D\u0937\u0947\u0924\u094D\u0930"
                                + " \u0915\u093E\u0930\u094D\u092F\u093E\u0932\u092F");
        assertThat(hindiLabel(code, "SUPERSEDED"))
                .isEqualTo("\u0915\u093E\u0930\u094D\u092F\u093E\u0932\u092F");

        // Removing the Hindi label withdraws it.
        Map<String, Object> none = new HashMap<>(sameDay);
        none.remove("labelHi");
        MvcResult withdrawn =
                send(admin, "/v1/masters/reference-lists/" + LIST + "/values/" + code, none);
        assertThat(
                        decide(
                                hqChecker(),
                                body(withdrawn).get("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        assertThat(hindiLabel(code, "ACTIVE")).isNull();
        assertThat(hindiLabel(code, "WITHDRAWN"))
                .isEqualTo(
                        "\u0915\u094D\u0937\u0947\u0924\u094D\u0930"
                                + " \u0915\u093E\u0930\u094D\u092F\u093E\u0932\u092F");
    }

    // ---------------------------------------------------------------- A-07 approval

    @Test
    void A_07_anApprovedSettingIsInForceTodayASecondChangeTheSameDayFromTomorrow()
            throws Exception {
        Session admin = admin();
        // FCI_PERIOD_FOR_PAYROLL_PROCESS is read by no code path yet: approving it affects no other
        // test.
        String path = "/v1/admin/parameters/FCI_PERIOD_FOR_PAYROLL_PROCESS";
        JsonNode before = parameter(admin, "FCI_PERIOD_FOR_PAYROLL_PROCESS");
        long value = before.get("value").asLong();
        long min = before.get("minValue").asLong();
        long max = before.get("maxValue").asLong();
        long first = value < max ? value + 1 : value - 1;
        long second = first > min ? first - 1 : first + 1;
        if (second == value) {
            second = first < max ? first + 1 : first - 1;
        }
        JsonNode proposed =
                body(put(admin, path, Map.of("value", first, "reason", "IT first change")));
        assertThat(
                        decide(
                                hqChecker(),
                                proposed.path("pending").path("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        JsonNode inForce = parameter(admin, "FCI_PERIOD_FOR_PAYROLL_PROCESS");
        assertThat(inForce.get("value").asLong()).isEqualTo(first);
        assertThat(inForce.get("version").asInt()).isGreaterThan(before.get("version").asInt());
        assertThat(inForce.get("pending").isNull()).isTrue();

        JsonNode again =
                body(put(admin, path, Map.of("value", second, "reason", "IT second change")));
        assertThat(
                        decide(
                                hqChecker(),
                                again.path("pending").path("approvalId").asLong(),
                                "APPROVE",
                                null,
                                null))
                .isEqualTo(200);
        // One version per rule and day: today's stays in force, the new one starts tomorrow.
        assertThat(parameter(admin, "FCI_PERIOD_FOR_PAYROLL_PROCESS").get("value").asLong())
                .isEqualTo(first);
        java.time.LocalDate tomorrow =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1);
        Long starting =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rule_version v JOIN rule_definition d ON d.rule_id ="
                            + " v.rule_id WHERE d.rule_key = 'FCI_PERIOD_FOR_PAYROLL_PROCESS' AND"
                            + " v.effective_from = ? AND v.approval_state = 'APPROVED'",
                        Long.class,
                        java.sql.Date.valueOf(tomorrow));
        assertThat(starting).isEqualTo(1);
    }
}
