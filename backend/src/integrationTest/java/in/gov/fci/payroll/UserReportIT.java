package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Module 02 F-3 Active User Report and F-7 readiness check (SRS-G-50, SRS-22-11, SRS-22-13), and
 * the A-07 settings they read, which the sources give no value for: shown as not configured until
 * FCI approves a first value (CLAUDE.md: never a seeded guess).
 */
class UserReportIT extends ApiTestSupport {

    private Session admin() throws Exception {
        return login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001");
    }

    private Session hqChecker() throws Exception {
        return login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
    }

    private JsonNode get(Session s, String path) throws Exception {
        MvcResult result = mvc.perform(userGet(s, path)).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return body(result);
    }

    private JsonNode parameter(Session s, String code) throws Exception {
        for (JsonNode p : all(s, "/v1/admin/parameters")) {
            if (p.get("code").asString().equals(code)) {
                return p;
            }
        }
        throw new AssertionError("no parameter " + code);
    }

    private long newUser(Session admin, int daysOld) throws Exception {
        String login = "report." + UUID.randomUUID().toString().substring(0, 8) + "@fci-demo.test";
        long id =
                body(mvc.perform(
                                        userPost(
                                                admin,
                                                "/v1/admin/users",
                                                Map.of(
                                                        "loginId",
                                                        login,
                                                        "displayName",
                                                        "Report Test",
                                                        "category",
                                                        "OPERATIONS",
                                                        "temporaryPassword",
                                                        "Temp#Report-2027")))
                                .andReturn())
                        .get("id")
                        .asLong();
        jdbc.update(
                "UPDATE app_user SET created_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(daysOld, ChronoUnit.DAYS)),
                id);
        return id;
    }

    private static JsonNode row(JsonNode report, long userId) {
        for (JsonNode r : report.get("items")) {
            if (r.get("userId").asLong() == userId) {
                return r;
            }
        }
        return null;
    }

    @Test
    void SRS_G_50_inactivityIsNotFlaggedUntilFciConfiguresTheThresholdThenItIs() throws Exception {
        Session admin = admin();
        // Defined, not seeded: no value, no version.
        JsonNode setting = parameter(admin, "INACTIVE_USER_DAYS");
        assertThat(setting.get("value").isNull()).isTrue();
        assertThat(setting.get("version").isNull()).isTrue();
        assertThat(setting.get("minValue").asLong()).isEqualTo(1);

        long stale = newUser(admin, 100);
        long fresh = newUser(admin, 0);
        JsonNode before = get(admin, "/v1/admin/active-users?q=report.&size=100");
        assertThat(before.get("inactiveAfterDays").isNull()).isTrue();
        assertThat(row(before, stale).get("inactive").isNull()).isTrue();

        // First value through A-07 maker-checker (no OTP: A-07 is not a gated action).
        MvcResult proposed =
                mvc.perform(
                                userPut(
                                        admin,
                                        "/v1/admin/parameters/INACTIVE_USER_DAYS",
                                        Map.of("value", 30, "reason", "FCI IT policy: 30 days")))
                        .andReturn();
        assertThat(proposed.getResponse().getStatus()).isEqualTo(202);
        JsonNode pending = body(proposed);
        assertThat(pending.get("value").isNull()).isTrue();
        assertThat(pending.path("pending").path("value").asLong()).isEqualTo(30);
        assertThat(
                        mvc.perform(
                                        userPut(
                                                admin,
                                                "/v1/admin/parameters/INACTIVE_USER_DAYS",
                                                Map.of("value", 0, "reason", "below")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
        long approval = pending.path("pending").path("approvalId").asLong();
        assertThat(decide(hqChecker(), approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(parameter(admin, "INACTIVE_USER_DAYS").get("value").asLong()).isEqualTo(30);

        JsonNode after = get(admin, "/v1/admin/active-users?q=report.&size=100");
        assertThat(after.get("inactiveAfterDays").asLong()).isEqualTo(30);
        assertThat(row(after, stale).get("inactive").asBoolean()).isTrue();
        assertThat(row(after, fresh).get("inactive").asBoolean()).isFalse();
        assertThat(row(after, stale).get("daysSinceLogin").isNull()).isTrue();
        JsonNode inactive =
                get(admin, "/v1/admin/active-users?q=report.&inactiveOnly=true&size=100");
        assertThat(row(inactive, stale)).isNotNull();
        assertThat(row(inactive, fresh)).isNull();
    }

    @Test
    void SRS_G_50_theReportShowsRoleUnitAndGroupOfEachUserPagedAndAsCsv() throws Exception {
        Session admin = admin();
        // Signs in, so the last sign-in is set.
        login("rbac.maker.ludhiana@fci-demo.test", null, null);
        JsonNode page = get(admin, "/v1/admin/active-users?q=rbac.maker.ludhiana&size=10");
        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode maker = page.get("items").get(0);
        assertThat(maker.get("lastLoginAt").asString()).isNotBlank();
        assertThat(maker.get("daysSinceLogin").asLong()).isZero();
        List<String> roles = new ArrayList<>();
        maker.get("access").forEach(a -> roles.add(a.get("roleCode").asString()));
        assertThat(roles).contains("PAYROLL_MAKER");
        assertThat(maker.get("access").get(0).get("unit").asString()).isNotBlank();

        JsonNode first = get(admin, "/v1/admin/active-users?page=0&size=10");
        JsonNode second = get(admin, "/v1/admin/active-users?page=1&size=10");
        assertThat(first.get("items").size()).isEqualTo(10);
        assertThat(first.get("total").asLong()).isGreaterThan(10);
        assertThat(first.get("items").get(0).get("loginId").asString())
                .isLessThan(second.get("items").get(0).get("loginId").asString());

        MvcResult csv = download(admin, "/v1/admin/active-users/export?q=rbac.maker.ludhiana");
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        String text = csv(admin, "/v1/admin/active-users/export?q=rbac.maker.ludhiana");
        assertThat(text).startsWith("\"Login ID\",").contains("rbac.maker.ludhiana@fci-demo.test");
        // SRS-G-13: the same list as Excel.
        MvcResult xlsx =
                download(admin, "/v1/admin/active-users/export?q=rbac.maker.ludhiana&format=XLSX");
        assertThat(xlsx.getResponse().getStatus()).isEqualTo(200);
        assertThat(xlsx.getResponse().getHeader("Content-Disposition"))
                .contains("active-users.xlsx");
        assertThat(xlsx.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
    }

    @Test
    void SRS_22_13_aZoneAdministratorReportsOnItsOwnZoneOnly() throws Exception {
        Session north = login("rbac.admin.north@fci-demo.test", "SYSTEM_ADMINISTRATOR", "NA11");
        JsonNode users = get(north, "/v1/admin/active-users?size=100");
        List<String> logins = new ArrayList<>();
        users.get("items").forEach(u -> logins.add(u.get("loginId").asString()));
        assertThat(logins).contains("rbac.maker.ludhiana@fci-demo.test");
        assertThat(logins).doesNotContain("finance.mumbai@fci-demo.test");

        List<String> units = new ArrayList<>();
        get(north, "/v1/admin/readiness")
                .get("units")
                .forEach(u -> units.add(u.get("code").asString()));
        assertThat(units).contains("NA11").doesNotContain("ZO-W", "RO-MH");
    }

    @Test
    void SRS_G_50_everyPayrollUnitIsCheckedForAMakerAndASeparateChecker() throws Exception {
        JsonNode report = get(admin(), "/v1/admin/readiness");
        // DO_MINIMUM_STAFF is defined and not configured: no minimum is applied.
        assertThat(report.get("doMinimumStaff").isNull()).isTrue();
        List<String> codes = new ArrayList<>();
        for (JsonNode unit : report.get("units")) {
            codes.add(unit.get("code").asString());
            assertThat(unit.get("readiness").asString())
                    .isIn("READY", "COVERED_FROM_ABOVE", "NOT_READY");
            if (unit.get("readiness").asString().equals("READY")) {
                assertThat(unit.get("makers").size()).isPositive();
                assertThat(unit.get("checkers").size()).isPositive();
            }
            if (!unit.get("type").asString().equals("DO")) {
                assertThat(unit.get("meetsMinimum").isNull()).isTrue();
            }
        }
        assertThat(codes).contains("HQ001", "NA11", "ZO-W");
        // Delhi Region has its own maker and checker in the demo data.
        for (JsonNode unit : report.get("units")) {
            if (unit.get("code").asString().equals("RO-DL")) {
                assertThat(unit.get("readiness").asString()).isNotEqualTo("NOT_READY");
            }
        }
    }

    @Test
    void SRS_G_50_payrollStaffCannotReadTheReports() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", null, null);
        for (String path :
                List.of(
                        "/v1/admin/active-users",
                        "/v1/admin/active-users/export",
                        "/v1/admin/readiness")) {
            assertThat(mvc.perform(userGet(maker, path)).andReturn().getResponse().getStatus())
                    .as(path)
                    .isEqualTo(403);
        }
    }
}
