package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The workspace menu and the session idle timeout as the original SRS v0.5 states them
 * (docs/quality/source-documents-analysis.md, findings S-1 and S-2):
 *
 * <ul>
 *   <li>the menu groups and their order as Wireframes v6.1 renders them (OD-121, OD-125: the frozen
 *       wireframe groups by module - Home, General, Employee, Departmental / DPS / NWNP Labour ...
 *       - where SRS v0.5 "Screen Designs" (line 349, SRS-NF-23) orders six groups by the payroll
 *       cycle; the conflict is WIREFRAME_GAPS WF-069 for FCI): every section is a group of
 *       app_menu_group in its order, every entry sits in the group app_menu_item files it under;
 *   <li>"idle logout after 15 minutes for payroll users and 10 minutes for eSeva members"
 *       (SRS-SEC-14), also after a switch between a staff scope and the eSeva scope.
 * </ul>
 */
class NavigationAndSessionIT extends ApiTestSupport {

    /** The groups in their order (app_menu_group, V078_1: the wireframe's rendered sidebar). */
    private List<String> groupOrder() {
        return jdbc.queryForList(
                "SELECT group_code FROM app_menu_group ORDER BY group_order", String.class);
    }

    /** The group each wireframe entry is filed under (app_menu_item). */
    private java.util.Map<String, String> groupOfEntry() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        jdbc.query(
                "SELECT menu_item_id, group_code FROM app_menu_item",
                rs -> {
                    m.put(rs.getString(1), rs.getString(2));
                });
        return m;
    }

    /** A user with a staff access group and the eSeva role (demo seed V026_9). */
    private static final String STAFF_AND_ESEVA = "rbac.staff.eseva@fci-demo.test";

    private List<String> sectionIds(JsonNode menu) {
        List<String> ids = new ArrayList<>();
        menu.path("sections").forEach(s -> ids.add(s.path("id").asString()));
        return ids;
    }

    /**
     * The menu of one persona: its sections in the wireframe's group order, each entry in its
     * group.
     */
    private List<String> checkedMenu(Session session) throws Exception {
        List<String> order = groupOrder();
        java.util.Map<String, String> filed = groupOfEntry();
        JsonNode menu = body(mvc.perform(userGet(session, "/menu")).andReturn());
        List<String> ids = sectionIds(menu);
        assertThat(ids).isNotEmpty().doesNotHaveDuplicates().isSubsetOf(order);
        assertThat(ids)
                .as(session.loginId() + ": sections in the order the wireframe renders them")
                .isSortedAccordingTo((a, b) -> Integer.compare(order.indexOf(a), order.indexOf(b)));
        for (JsonNode section : menu.path("sections")) {
            String id = section.path("id").asString();
            assertThat(section.path("labelKey").asString()).isEqualTo("menu.section." + id);
            assertThat(section.path("items")).as(id + " is never empty").isNotEmpty();
            for (JsonNode item : section.path("items")) {
                String entry = item.path("id").asString();
                assertThat(filed.get(entry)).as(session.loginId() + ": " + entry).isEqualTo(id);
            }
        }
        return ids;
    }

    @Test
    void SRS_NF_23_theMenuFollowsThePayrollCycleAndEachScreenSitsInItsGroup() throws Exception {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        seen.addAll(checkedMenu(login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001")));
        seen.addAll(checkedMenu(login("checker.hq@fci-demo.test", "PAYROLL_CHECKER", "HQ001")));
        seen.addAll(checkedMenu(login("su.hq@fci-demo.test", "RULE_ADMINISTRATOR", "HQ001")));
        seen.addAll(checkedMenu(login("sysadmin@fci-demo.test", "SYSTEM_ADMINISTRATOR", "HQ001")));
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        seen.addAll(checkedMenu(member));
        // Together the personas open the Home, General and eSeva groups of the wireframe at least.
        assertThat(seen).contains("home", "general_cross_cutting", "employee_self_service_eseva");
        // Every group has its label in both bundles (SRS-G-40).
        for (String locale : List.of("en", "hi")) {
            JsonNode bundle = body(mvc.perform(userGet(member, "/i18n/" + locale)).andReturn());
            for (String id : groupOrder()) {
                assertThat(bundle.path("menu.section." + id).asString())
                        .as(locale + " " + id)
                        .isNotBlank();
            }
        }
    }

    @Test
    void SRS_NF_23_anEsevaMemberSeesTheGeneralQueriesAndItsSelfServiceGroupAsDrawn()
            throws Exception {
        // As the wireframe renders the member's sidebar (OD-121): General (the payroll queries of
        // X-06) then the eSeva group (E-01 first); no staff group.
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        JsonNode menu = body(mvc.perform(userGet(member, "/menu")).andReturn());
        assertThat(sectionIds(menu))
                .containsExactly("general_cross_cutting", "employee_self_service_eseva");
        List<String> general = new ArrayList<>();
        menu.at("/sections/0/items").forEach(i -> general.add(i.path("code").asString()));
        assertThat(general).contains("X-06");
        assertThat(menu.at("/sections/1/items/0/code").asString()).isEqualTo("E-01");
    }

    @Test
    void SRS_G_7_theProfileNamesTheScreensProposedAtHeadquartersOnly() throws Exception {
        // M-24's route is maintained at HQ (V033_3): Finance at a division holds the permission
        // but proposes nothing there, so the screen shows the action disabled with the reason.
        Session division = login("finance.mumbai@fci-demo.test", "FINANCE", "MR001");
        mvc.perform(userGet(division, "/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atHeadquarters").value(false))
                .andExpect(jsonPath("$.hqOnlyScreens").value(hasItem("M-24")))
                .andExpect(jsonPath("$.hqOnlyScreens").value(not(hasItem("I-20"))));
        Session hq = login("finance.hq@fci-demo.test", "FINANCE", "HQ001");
        mvc.perform(userGet(hq, "/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atHeadquarters").value(true));
    }

    @Test
    void SRS_SEC_14_payrollUsersIdleOutAfter15MinutesAndEsevaMembersAfter10() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        mvc.perform(userGet(maker, "/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.idleTimeoutSeconds").value(900));
        Session member = login("emp.rsharma@fci-demo.test", null, null);
        mvc.perform(userGet(member, "/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.idleTimeoutSeconds").value(600));
    }

    @Test
    void SRS_SEC_14_theIdleTimeoutFollowsAScopeSwitchBetweenStaffAndEseva() throws Exception {
        Session session = login(STAFF_AND_ESEVA, null, null);
        JsonNode scopes = body(mvc.perform(userGet(session, "/auth/scopes")).andReturn());
        String staffRole = null;
        for (JsonNode scope : scopes) {
            if (!scope.path("location").isNull()) {
                staffRole = scope.path("role").asString();
            }
        }
        assertThat(staffRole).as("a staff scope beside eSeva").isNotNull();

        actAs(session, staffRole);
        assertThat(idleSeconds(session)).isEqualTo(900);
        actAs(session, "EMPLOYEE_ESEVA");
        assertThat(idleSeconds(session)).isEqualTo(600);
        actAs(session, staffRole);
        assertThat(idleSeconds(session)).as("back to the payroll timeout").isEqualTo(900);
    }

    private int idleSeconds(Session session) throws Exception {
        JsonNode me = body(mvc.perform(userGet(session, "/auth/me")).andReturn());
        return me.at("/session/idleTimeoutSeconds").asInt();
    }
}
