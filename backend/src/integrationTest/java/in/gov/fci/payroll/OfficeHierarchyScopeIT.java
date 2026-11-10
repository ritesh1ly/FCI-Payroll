package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The office hierarchy for every role at every office it may work at (SRS-22-2, SRS-22-11,
 * SRS-G-29, SRS-G-35; demo seed V037_9, docs/rbac/ROLE_COVERAGE.md). Each coverage user signs in at
 * its office; a session sees the office it is signed in at and its descendants (the whole zone for
 * a zonal group), never a sibling, a parent or another zone, and acts on nothing outside it.
 *
 * <p>The expectation is computed here from org_location, login_persona_role and
 * app_role_permission, independently of ScopeResolver and AccessPolicy, so a change to either is
 * caught.
 */
class OfficeHierarchyScopeIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final java.util.regex.Pattern OFFICE_CODE =
            java.util.regex.Pattern.compile("\\(([A-Z0-9-]+)\\)$");

    private record Coverage(String login, String role, String office, Long zoneId) {}

    private List<Coverage> coverage() {
        List<Coverage> out = new ArrayList<>();
        for (Map<String, Object> r :
                jdbc.queryForList(
                        "SELECT u.login_id, ro.role_code, COALESCE(l.location_code,"
                            + " zl.location_code) office, s.zone_id FROM app_user u JOIN"
                            + " app_user_access_group ug ON ug.user_id = u.user_id JOIN"
                            + " access_group g ON g.access_group_id = ug.access_group_id JOIN"
                            + " app_role ro ON ro.role_id = g.role_id JOIN access_group_scope s ON"
                            + " s.access_group_id = g.access_group_id LEFT JOIN org_location l ON"
                            + " l.location_id = s.location_id LEFT JOIN org_location zl ON"
                            + " zl.zone_id = s.zone_id AND zl.location_type = 'ZO' WHERE"
                            + " g.group_code LIKE 'AG-C-%' ORDER BY u.login_id")) {
            out.add(
                    new Coverage(
                            (String) r.get("LOGIN_ID"),
                            (String) r.get("ROLE_CODE"),
                            (String) r.get("OFFICE"),
                            r.get("ZONE_ID") == null
                                    ? null
                                    : ((Number) r.get("ZONE_ID")).longValue()));
        }
        return out;
    }

    /** The office and its descendants (org_location.parent_location_id). */
    private Set<String> subtree(String office) {
        return new HashSet<>(
                jdbc.queryForList(
                        "WITH RECURSIVE tree (location_id, location_code) AS (SELECT location_id,"
                            + " location_code FROM org_location WHERE location_code = ? UNION ALL"
                            + " SELECT l.location_id, l.location_code FROM org_location l JOIN tree"
                            + " t ON l.parent_location_id = t.location_id) SELECT location_code"
                            + " FROM tree",
                        String.class,
                        office));
    }

    /** What the user's group covers: the zone, or the office's subtree. */
    private Set<String> coverageOf(Coverage c) {
        return c.zoneId() == null
                ? subtree(c.office())
                : new HashSet<>(
                        jdbc.queryForList(
                                "SELECT location_code FROM org_location WHERE zone_id = ?",
                                String.class,
                                c.zoneId()));
    }

    private Set<String> admittedTypes(String role) {
        Set<String> types = new HashSet<>();
        for (String list :
                jdbc.queryForList(
                        "SELECT p.allowed_location_types FROM login_persona_role p JOIN app_role r"
                                + " ON r.role_id = p.role_id WHERE r.role_code = ?",
                        String.class,
                        role)) {
            types.addAll(List.of(list.split(",")));
        }
        return types;
    }

    private String typeOf(String office) {
        return jdbc.queryForObject(
                "SELECT location_type FROM org_location WHERE location_code = ?",
                String.class,
                office);
    }

    private boolean grants(String role, String screen, String action) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_role_permission rp JOIN app_role r ON r.role_id ="
                            + " rp.role_id JOIN app_permission p ON p.permission_id ="
                            + " rp.permission_id WHERE r.role_code = ? AND p.screen_code = ? AND"
                            + " p.action_code = ? AND rp.effective_from <= CURRENT_DATE AND"
                            + " (rp.effective_to IS NULL OR rp.effective_to >= CURRENT_DATE)",
                        Integer.class,
                        role,
                        screen,
                        action);
        return n != null && n > 0;
    }

    /** An active staff member of the office, or null. */
    private String employeeAt(String office) {
        List<String> eis =
                jdbc.queryForList(
                        "SELECT MIN(e.emp_num) FROM employee e JOIN org_location l ON l.location_id"
                                + " = e.location_id WHERE l.location_code = ? AND e.employee_type ="
                                + " 'STAFF' AND e.record_status = 'ACTIVE'",
                        String.class,
                        office);
        return eis.isEmpty() ? null : eis.get(0);
    }

    /**
     * An office of a type the role admits that the group does not cover, or null (HQ covers all).
     */
    private String admittedOfficeOutside(Set<String> covered, Set<String> types) {
        return jdbc
                .queryForList(
                        "SELECT location_code, location_type FROM org_location ORDER BY"
                                + " location_code")
                .stream()
                .filter(r -> types.contains((String) r.get("LOCATION_TYPE")))
                .map(r -> (String) r.get("LOCATION_CODE"))
                .filter(code -> !covered.contains(code))
                .findFirst()
                .orElse(null);
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    /**
     * Refused for the data scope: 404, exactly as an unknown record, never 403 - a 403 would tell
     * that the record exists in another office (project owner, 2026-10-24; OWASP A01).
     */
    private boolean refusedForScope(MvcResult r) {
        return status(r) == 404;
    }

    /**
     * The offices of every M-01 row the session lists (the label ends in the code: "... (DH001)").
     */
    private Set<String> listedOffices(Session s) throws Exception {
        Set<String> out = new HashSet<>();
        for (String label : all(s, "/v1/employees").findValuesAsString("location")) {
            java.util.regex.Matcher m = OFFICE_CODE.matcher(label);
            out.add(m.find() ? m.group(1) : "?" + label);
        }
        return out;
    }

    @Test
    void SRS_22_11_everyRoleAtEveryAdmittedOfficeSeesAndActsOnlyWithinItsSubtree()
            throws Exception {
        List<Coverage> users = coverage();
        // 19 roles at the office types their personas admit (gen_role_coverage.py), and the 29
        // tax desks of the hierarchy seed V039_11_9 in the same groups (gen_tax_year_demo.py).
        assertThat(users).hasSize(153 + 29);
        List<String> wrong = new ArrayList<>();
        int lists = 0;
        int refusedLogins = 0;
        int childSessions = 0;
        for (Coverage c : users) {
            Set<String> covered = coverageOf(c);
            Set<String> types = admittedTypes(c.role());

            // Negative: signing in at an admitted office the group does not cover is refused.
            String outside = admittedOfficeOutside(covered, types);
            if (outside != null) {
                MvcResult refused = loginRaw(c.login(), DEMO_PASSWORD, c.role(), outside);
                if (status(refused) != 403
                        || !body(refused)
                                .path("errorCode")
                                .asString()
                                .equals("AUTH-403-LOCATION")) {
                    wrong.add(c.login() + ": sign-in at " + outside + " gave " + status(refused));
                }
                refusedLogins++;
            }

            // Positive: the own office; edge: every office offered is covered and admitted.
            Session s = login(c.login(), c.role(), c.office());
            JsonNode scopes = body(mvc.perform(userGet(s, "/auth/scopes")).andReturn());
            Set<String> offered = new HashSet<>(scopes.findValuesAsString("location"));
            if (!offered.contains(c.office())) {
                wrong.add(c.login() + ": own office not offered " + offered);
            }
            for (String o : offered) {
                if (!covered.contains(o) || !types.contains(typeOf(o))) {
                    wrong.add(c.login() + ": offered " + o + " outside " + covered + " / " + types);
                }
            }

            if (!grants(c.role(), "M-01", "VIEW")) {
                // Negative: no permission, no list - whatever the office.
                int st = status(mvc.perform(userGet(s, "/v1/employees?size=10")).andReturn());
                if (st != 403) {
                    wrong.add(c.login() + ": M-01 without the permission answered " + st);
                }
                continue;
            }
            Set<String> seen = new HashSet<>(listedOffices(s));
            lists++;
            Set<String> beyond = new HashSet<>(seen);
            beyond.removeAll(covered);
            if (!beyond.isEmpty()) {
                wrong.add(c.login() + ": sees " + beyond + " outside " + covered);
            }
            String own = employeeAt(c.office());
            if (own != null) {
                if (!seen.contains(c.office())) {
                    wrong.add(c.login() + ": does not list its own office " + c.office());
                }
                // Positive: an employee inside opens.
                int st = status(mvc.perform(userGet(s, "/v1/employees/" + own)).andReturn());
                if (st != 200) {
                    wrong.add(c.login() + ": own office employee " + own + " answered " + st);
                }
            }
            // Negative: the nearest employee outside (a sibling, the parent or another zone) is
            // 404, exactly as an employee that does not exist: its existence is not disclosed.
            String foreignOffice =
                    jdbc
                            .queryForList(
                                    "SELECT location_code FROM org_location ORDER BY location_code",
                                    String.class)
                            .stream()
                            .filter(code -> !covered.contains(code) && employeeAt(code) != null)
                            .findFirst()
                            .orElse(null);
            if (foreignOffice != null) {
                String foreign = employeeAt(foreignOffice);
                MvcResult read = mvc.perform(userGet(s, "/v1/employees/" + foreign)).andReturn();
                int st = status(read);
                if (!refusedForScope(read)) {
                    wrong.add(
                            c.login()
                                    + ": "
                                    + foreign
                                    + " of "
                                    + foreignOffice
                                    + " answered "
                                    + st);
                }
                if (grants(c.role(), "M-01", "FLAG")) {
                    // Negative: a forged flag proposal for that employee creates nothing.
                    int before = pendingApprovals();
                    MvcResult change =
                            mvc.perform(
                                            flagPost(
                                                    s,
                                                    foreign,
                                                    Map.of(
                                                            "flagCode",
                                                            "NPA",
                                                            "value",
                                                            "Y",
                                                            "effectiveFrom",
                                                            LocalDate.now(IST).toString(),
                                                            "reason",
                                                            "Forged proposal outside"
                                                                    + " the scope"),
                                                    true))
                                    .andReturn();
                    int sc = status(change);
                    if (!refusedForScope(change) || pendingApprovals() != before) {
                        wrong.add(c.login() + ": forged change of " + foreign + " answered " + sc);
                    }
                }
            }

            // Edge: signed in at a child office, the session sees the child's subtree only - a
            // zone group too (project owner, 2026-10-24); only at its zonal office the whole zone.
            String child =
                    offered.stream()
                            .filter(o -> !o.equals(c.office()))
                            .sorted()
                            .findFirst()
                            .orElse(null);
            if (child != null) {
                Session at = login(c.login(), c.role(), child);
                Set<String> allowed = subtree(child);
                Set<String> got = new HashSet<>(listedOffices(at));
                got.removeAll(allowed);
                if (!got.isEmpty()) {
                    wrong.add(c.login() + " at " + child + ": sees " + got + " beyond " + allowed);
                }
                childSessions++;
            }
        }
        assertThat(wrong).as("scope breaches or gaps").isEmpty();
        assertThat(lists).as("roles with M-01 checked").isPositive();
        assertThat(refusedLogins).as("out-of-scope sign-ins tried").isPositive();
        assertThat(childSessions).as("child-office sessions tried").isPositive();
    }

    private int pendingApprovals() {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_queue WHERE status = 'PENDING_APPROVAL'",
                        Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void SRS_22_11_aDepotRoleSeesItsDepotOnlyAndAZonalRoleNeverHeadquartersOrAnotherZone()
            throws Exception {
        // Edge: the lowest level. The depot is a leaf of the tree.
        assertThat(subtree("DP-PB01")).containsExactly("DP-PB01");
        assertThat(
                        loginRaw(
                                        "ldh.dppb01@fci-demo.test",
                                        DEMO_PASSWORD,
                                        "LABOUR_DEALING_HAND",
                                        "LD001")
                                .getResponse()
                                .getStatus())
                .as("a depot role at its parent division")
                .isEqualTo(403);
        // Edge: a zonal checker covers its zone's offices, depots included, and nothing above.
        Session zone = login("pc.zoe@fci-demo.test", "PAYROLL_CHECKER", "ZO-E");
        Set<String> seen = new HashSet<>(listedOffices(zone));
        assertThat(seen).contains("ZO-E").doesNotContain("HQ001", "NA11", "ZO-S", "ZO-W", "DH001");
        assertThat(seen)
                .allMatch(code -> List.of("ZO-E", "RO-WB", "KL001", "DP-WB01").contains(code));
        // Edge: Headquarters sees every zone (consolidated reading is SRS-22-3's, tested
        // elsewhere).
        Session hq = login("pc.hq001@fci-demo.test", "PAYROLL_CHECKER", "HQ001");
        assertThat(new HashSet<>(listedOffices(hq)))
                .contains("HQ001", "DH001", "KL001", "CH001", "MR001");
    }

    /**
     * SRS-22-9, SRS-22-22, SRS-G-29: an item reaches the approvers of its own office upwards
     * (M01_FLAG is decided at the PAYING_UNIT, V040_7): every Payroll Checker whose subtree holds
     * the item's office sees it, no checker of a sibling office or another zone does, and a forged
     * decision from outside is refused. The DH001 checker then rejects it, so nothing changes.
     */
    @Test
    void SRS_22_22_anItemReachesTheCheckersAboveItsOfficeAndNoSiblingOrOtherZone()
            throws Exception {
        String eis = employeeAt("DH001");
        Session hr = login("hr.dh001@fci-demo.test", "ESTABLISHMENT_HR", "DH001");
        MvcResult raised = proposeFlag(hr, eis, "Hierarchy routing check (IT)");
        assertThat(status(raised)).as(raised.getResponse().getContentAsString()).isEqualTo(202);
        long approval = body(raised).path("approvalId").asLong();
        List<String> wrong = new ArrayList<>();
        for (Coverage c : coverage()) {
            if (!"PAYROLL_CHECKER".equals(c.role())) {
                continue;
            }
            boolean above = coverageOf(c).contains("DH001");
            Session s = login(c.login(), c.role(), c.office());
            boolean sees =
                    all(s, "/v1/inbox").findValues("approvalId").stream()
                            .anyMatch(id -> id.asLong() == approval);
            if (sees != above) {
                wrong.add(c.login() + (above ? ": does not see" : ": sees") + " the DH001 item");
            }
            if (!above) {
                // Negative: a forged decision from outside the office's subtree.
                int st =
                        status(
                                mvc.perform(
                                                userPost(
                                                        s,
                                                        "/v1/approvals/" + approval + "/decision",
                                                        Map.of(
                                                                "decision",
                                                                "REJECT",
                                                                "reason",
                                                                "Forged from outside the"
                                                                        + " office")))
                                        .andReturn());
                if (st < 400 || st >= 500) {
                    wrong.add(c.login() + ": forged decision answered " + st);
                }
            }
        }
        assertThat(wrong).as("routing breaches").isEmpty();
        // Still pending after every forged attempt; the office's own checker ends it.
        Session own = login("pc.dh001@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(
                        status(
                                mvc.perform(
                                                userPost(
                                                        own,
                                                        "/v1/approvals/" + approval + "/decision",
                                                        Map.of(
                                                                "decision",
                                                                "REJECT",
                                                                "reason",
                                                                "Hierarchy routing check"
                                                                        + " done (IT)")))
                                        .andReturn()))
                .isEqualTo(200);
    }

    @Test
    void SRS_G_35_everyCoverageEsevaMemberSeesOnlyTheOwnRecord() throws Exception {
        List<String> members =
                jdbc.queryForList(
                        "SELECT login_id FROM app_user WHERE login_id LIKE 'eseva.%@fci-demo.test'",
                        String.class);
        assertThat(members).hasSize(5);
        for (String login : members) {
            Session s = login(login, null, null);
            JsonNode me = body(mvc.perform(userGet(s, "/v1/eseva/me")).andReturn());
            String own =
                    jdbc.queryForObject(
                            "SELECT e.emp_num FROM app_user u JOIN employee e ON e.employee_id ="
                                    + " u.employee_id WHERE u.login_id = ?",
                            String.class,
                            login);
            assertThat(me.findValuesAsString("eis")).as(login).contains(own);
            // Negative: no staff screen, not even the own office's list or another member's record.
            assertThat(status(mvc.perform(userGet(s, "/v1/employees")).andReturn()))
                    .as(login + " list")
                    .isEqualTo(403);
            assertThat(status(mvc.perform(userGet(s, "/v1/employees/105220")).andReturn()))
                    .as(login + " another member")
                    .isEqualTo(403);
        }
    }
}
